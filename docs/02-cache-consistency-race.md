# 缓存一致性：延迟双删 / 墓碑 / Lease 令牌的竞态与落地

> 目标：盘点 RideAgentHub 中「DB 更新导致缓存脏数据」的真实竞态点，讲清延迟双删、墓碑机制、Facebook Lease 令牌三种解法各自解决什么、边界在哪，以及它们在本项目中的当前落地状态。
> 代码定位格式为「类名 + 方法名」，路径基准 `src/main/java/com/fancy/rideagenthub/`（下称 `<base>/`）。
> 深入拆解见 [07](learn/redis/07-delayed-double-delete.md) / [08](learn/redis/08-city-code-tombstone.md) / [09](learn/redis/09-lease-token.md) 三篇笔记。

---

## 一、竞态盘点总览

先把**不存在竞态**的缓存划掉，避免过度设计：

| 缓存 | 数据可变？ | 回填 / 写入语义 | 结论 |
|---|---|---|---|
| `TokenServiceImpl` | — | — | **安全**：Redis 本身是数据源，无 DB 双写；写入走 `SAVE` Lua 原子发布 |
| `EmailCodeService` | — | — | **安全**：同上，四段 Lua 维护全部状态 |
| `ToolResponseMemory` | 否（按 `(callId, chatId)` 不可变） | `set` 覆盖 | **安全**：工具结果不修改；MySQL 侧每次 `insert` 新行、取 `orderByDesc(id) LIMIT 1`，重复调用会累积重复行，属已知的存储冗余，不是缓存竞态 |
| `HeapMemory`（L1） | 是 | 租约校验覆盖 | **受保护**：由 `MessageMemory` 统一调度，租约失效时保留并发写入内容 |
| `ESMemory` / `MemoryAdvisor` | — | — | 空占位类，无业务调用方（设计中，不参与盘点） |
| `UserUsernameBloomFilterService` | 只增 | `rebuild` 全量 | 非双写竞态，是**重建期假阴性**（见 3.4） |
| `CityCodeUtil` | 是 | 版本校验回填 | **已防护**：读侧版本 + 墓碑两段 Lua；写路径未接线（见 2.2） |
| 工单统计缓存 | 高频 | 双删 + 随机 TTL | **已防护**：统一失效 + 提交后立即删除 + 500ms 二次删除（见 2.1） |
| `ChatManager` / `ChatInfoService` | 双写 | `HASH_SET_WITH_EXPIRE` Lua | 原子化已防护；`restoreChat` 的 `persist` 是有意的语义（见 3.1） |
| 聊天历史 L2/L3 | 双写 | 版本租约回填 | **已防护**：Lease 令牌（见 2.3） |

三个需要一致性防护的缓存，正好对应三种解法的适用边界：

- **2.1 延迟双删** —— 「删除被并发回填覆盖」，用**第二次删除**兜住
- **2.2 墓碑机制** —— 「TTL 太长，时间窗口猜不准」，用**状态标记**替代猜时间
- **2.3 Lease 令牌** —— 「回填本身就是破坏性覆盖」，只有**版本校验**能解

---

## 二、三处真竞态与三解法

### 2.1 延迟双删 → 工单统计缓存

**竞态模型**。工单统计（`getTicketStatistics`）缓存 20~30 秒，写工单的方法有 10 个（`submitTicket`、状态流转、分配、关闭等），每个都调用 `afterTicketMutation` 统一收尾。危险序列是：

```
写线程：DB 提交 → 删除缓存
读线程：        ← 在删除之前已把旧 DB 快照查走
读线程：此刻才把旧快照 set 回缓存     ← 删除被回填覆盖，脏数据存活一个 TTL
```

单次删除兜不住「读线程在删除前已经查走旧快照、在删除后才写回」的窗口 —— 旧快照回填的时机不可预测。

**当前实现**（`TicketServiceImpl.evictTicketStatistics` + `service/base/DelayedCacheEvictor`）：

1. **提交后失效**：删除动作挂在 `afterCommit`，事务回滚时根本不触发；若在事务内删除，并发读会拿到「尚未提交的 DB 快照」并回填，制造出比目标竞态更早、更容易触发的脏窗口。
2. **第一次删除**：立即 `delete`，包在 `try/catch(RuntimeException)` 里，失败只 `log.warn` —— 数据库已提交，缓存故障交给第二次删除兜。
3. **第二次删除**：`DelayedCacheEvictor`（自建单线程守护调度器）在 500ms 后再删一次，兜住「并发读基于旧快照回填」的窗口。延迟取值需覆盖「读线程查 DB + 回填」的耗时（统计查询 4 次 `selectCount`，实测几十毫秒，取 500ms 留足余量）；延迟过长只是多几次未命中，不会造成脏数据。
4. **保守失效**：不区分本次写入是否真的影响 4 个统计项，一律失效。B 端仪表盘多几次重建的代价远低于漏失效。

`afterTicketMutation` 的顺序是刻意安排的：**先注册索引同步**（`ticketPoolIndex.syncByTicketId` 挂 `afterCommit`），**再调 `evictTicketStatistics`** —— 统计缓存的独立故障不能阻止索引版本推进。

**边界**：延迟双删降低的是概率，不是消除竞态 —— 若回填发生在第二次删除之后（读线程卡顿超过 500ms），仍会留下脏数据，直到随机 TTL 到期自愈。它适合「短 TTL + 低价值数据」的缓存；对 7 天 TTL 的数据请看墓碑。

### 2.2 墓碑机制 → 城市编码缓存

**竞态模型**。城市编码缓存 TTL 7 天（`CityCodeUtil.CACHE_TTL_DAYS`）。直接 `DEL` 失效是危险的：

```
写线程：城市数据变更，DEL key
读线程：此刻 key 不存在 → 查 DB（拿到的是旧快照，写尚未可见）→ set 旧值 + 7 天 TTL
```

延迟双删在此救不了：无法预测该读线程何时回填，「sleep 多久」无从取值。**把『猜时间』换成『看状态』**——写线程不删 key，而是写入一个短 TTL 的墓碑（`__TOMBSTONE__`，5 秒），读线程见到墓碑就知道「此刻别回填」。

**当前实现**（`CityCodeUtil` + `RedisScripts` 两段 Lua + 版本 key）：

- 读路径 `getCityCode`：先读版本 key `amap:city_code:version:{name}` 与数据 key；命中墓碑 → 查 DB 但**不回填**（回填会把墓碑覆盖成 7 天 TTL 的正常条目，使竞态窗口重新打开）；缓存 miss → 查 DB 后走 `CACHE_CITY_CODE_IF_VERSION_MATCH`（版本匹配才 `SET NX EX` 回填，不覆盖任何已有值）。
- 失效路径 `invalidate`：`INVALIDATE_CITY_CODE` Lua 原子完成「`INCR` 版本号 + 写 5 秒墓碑」。版本号的作用是兜住墓碑到期后的迟到回填：墓碑只有 5 秒，若读线程恰在墓碑过期后才尝试回填，版本号已经递增，`CACHE_CITY_CODE_IF_VERSION_MATCH` 的版本比对会拒绝它。
- key 规范化：`trim().toLowerCase(Locale.ROOT)` 在 cache key 与 DB 查询前统一执行，两者永远使用同一个值（MySQL 8 默认 `NO PAD` 排序规则下 `北京 ` 与 `北京` 结果不同，共用一个 key 会造成缓存污染）。

**边界（重要）**：`invalidate()` 目前**没有生产调用方**——机制、Lua、回归测试齐备，但城市表的写路径（`bus_citycode.sql` 导入、运维改数）尚未接入失效调用。这是「机制已就位、写路径未接线」的部分实现状态：读路径的版本校验与哨兵防穿透是生效的；写侧主动失效需要在使用城市表写入的地方补 `invalidate` 调用。

### 2.3 Lease 令牌 → 聊天历史回填

**竞态模型**。聊天历史 L2 是 Redis List，回填的语义是「用 MySQL 快照**整体替换**缓存」。当「回填」与「并发追加」撞上：

```
T1 读线程：L1/L2 miss → 查 MySQL 得快照 S = [消息 1..10]
T2 写线程：save → 追加消息 11（L3、L2、L1）
T1：用 S 整体替换 L2 → L2 变回 [1..10]，消息 11 从缓存里消失
```

这不是脏读而是**丢失**：脏读会自愈，丢失不会 —— L2 看起来是完整的（非空），后续读在命中旧缓存时不会回源 MySQL，直到缓存失效或过期。

为什么另外两个解法在此失效：

| 机制 | 需要的前提 | 本场景 |
|---|---|---|
| 延迟双删 | 写路径存在「删除」动作 | ❌ `save` 是 `RPUSH` 追加，没有删除可谈第二次 |
| 墓碑 | 存在可标记的「失效」动作 | ❌ 问题出在读路径的破坏性回填，写线程没做错任何事 |

**当前实现**（`agentbase/memory/` 三层 + `RedisScripts` 五段历史脚本）：

1. **取租约必须在任何耗时读取之前**：`MessageMemory.get` 先捕获 `redisLease`（`CURRENT_HISTORY_VERSION` Lua 读版本号），再去查 L2 / MySQL。若查完才取版本号，「查 MySQL」这段窗口就没有被租约覆盖。
2. **写路径使所有在途租约失效**：`save` 先落 MySQL，再走 `APPEND_HISTORY_IF_VERSION_MATCH`——版本不符时 `DEL` 数据（让在途租约必然失效），版本相符且数据 key 已存在时 `RPUSH` + `PEXPIRE`，缓存缺失时不新建只含增量的残缺历史；版本递增与数据追加在同一次 Lua 内完成，不存在「消息可见但版本未变」的中间态。
3. **回填持租约**：数据先写入暂存 key（`MULTI` 原子 `RPUSH` + 1 分钟 TTL），再由 `OVERWRITE_IF_VERSION_MATCH` Lua 原子完成「校验版本 + `RENAME` 暂存为数据 key」。租约失效 → 脚本 `DEL` 暂存 → 回填放弃（**被拒绝是正确行为**，代价只是一次多余的 MySQL 查询）。回填数据走暂存 key 而非 `ARGV`，是因为消息量不可控，而 `RENAME` 是 O(1) 的元数据操作。
4. **版本 key 永久保留**：五段脚本都会 `PERSIST` 版本 key。若版本 key 与数据同生命周期（一起 TTL 或被 `clear` 同时删除），版本号会归零重新计数，某个在途租约恰好等于重置后的小值时会「复活」。版本号是租约的时钟——**可以停，不能倒转**。
5. **L1 也要租约**：`HeapMemory` 每条目带版本号，`overwriteIfVersionMatch` 在 `ConcurrentHashMap.compute` 内原子校验；租约失效时保留并发写入内容（写顺序是先 MySQL 再追加 L2/L1，L1 里是包含最新消息的列表，清空反而丢数据）。

**边界**：回填被拒时无 single-flight，N 个并发 miss 会产生 N 次 MySQL 查询（性能问题而非正确性问题）；`getAll`（`LRANGE 0 -1`）的读取成本随对话长度线性增长。

---

## 三、附带缺陷与现状

### 3.1 `restoreChat` 的 `persist`（有意为之，但语义需留意）

`ChatInfoService.restoreChat` 在恢复会话时对 `chat:info` 执行 `persist`，使该 key 永不过期。这是「恢复的会话不应在用户还在用时被 TTL 收掉」的取舍，代价是被恢复会话的 key 生命周期与用户是否活跃脱钩。与 `HASH_SET_WITH_EXPIRE`（`lockChat` 写 60 分钟 TTL）叠加后，`chat:info` 的 TTL 语义是「普通 HSET 不主动设 TTL、报价 24 小时、锁定或恢复检查 60 分钟、POST 恢复后永久」——三种状态并存，是 [12 篇](learn/redis/12-chat-lock-atomic.md) 讨论 TTL 语义矛盾的来源。

### 3.2 手写锁的释放失败

`RedisLock.unlock` 捕获 `RuntimeException` 只记 warn：**不覆盖临界区的返回值或异常**，剩余锁由固定 TTL（5~10 秒）兜底释放。这是「锁释放失败不应把业务结果一起拖垮」的取舍，代价是 TTL 窗口内其他实例无法重建。

### 3.3 令牌消费与业务执行分离

取消令牌（`OrderSearchTool.CANCEL`）与创建权限（`OrderConfirmation.CONSUME`）的**消费**已由 Lua 原子化（校验 + 时效 + `HDEL` 一步完成），但「令牌消费」与「业务执行」（真正调 Service 取消订单 / 创建订单）仍是两段：极端时序下可能出现令牌已消费、业务执行失败，用户需要重新走一次确认。属于已知的残留窗口。

### 3.4 布隆重建期假阴性

`UserUsernameBloomFilterService.rebuild` 先 `delete` 再 `tryInit` + 全量回灌，重建窗口内「一定不存在」的判定会出现假阴性（已注册的用户名被误报可用）。后果已被数据库生成列唯一索引（`uk_active_username_role`）兜底：并发注册重名会被 DB 拒绝（`DuplicateKeyException` → 409），当前 `mightContain` 无生产调用方，可用性检查直查 DB，因此重建不会改变该接口结果；若未来接入才需处理此缺失窗口。若要消除，可用双 key 切换重建。

### 3.5 工具结果 MySQL 重复行

`ToolResponseMemory.save` 每次调用都 `mapper.insert` 新行，读取按 `orderByDesc(id) LIMIT 1` 取最新。同一 `(callId, chatId)` 重复保存会累积冗余行——不影响正确性（取到的永远是最新），但表会缓慢膨胀，属于已知存储冗余。

---

## 四、三解法适用边界总结

| | 前提条件 | 解决的窗口 | 残留风险 | 本项目落点 |
|---|---|---|---|---|
| **延迟双删** | 写路径存在删除动作 | 「删除被并发回填覆盖」 | 回填晚于第二次删除 → 脏到 TTL 到期；进程崩溃丢掉第二次删除 | 工单统计（20~30s TTL + 500ms 双删） |
| **墓碑** | 存在可标记的失效动作 | 「失效后旧快照回填」+ 长 TTL 窗口 | 墓碑 TTL 内读请求全部穿透 DB；写路径必须接线失效调用 | 城市编码（5s 墓碑 + 版本 key；写路径未接线） |
| **Lease 令牌** | 回填是破坏性覆盖 | 「回填抹掉并发写入」 | 回填被拒无 single-flight；版本 key 不回收 | 聊天历史三层记忆 |

选型口诀：**先看写路径有没有删除（双删），再看有没有失效动作可标记（墓碑），最后看回填本身是不是破坏性的（Lease）**。三个机制层层递进，任何把低层机制硬套到高层问题的尝试都会得到荒谬结果——给追加型写路径加「删除」会让缓存每轮失效，给读路径问题写墓碑只会让缓存永远为空。

---

## 五、参考资料

- Facebook, *Scaling Memcache at Facebook*（NSDI '13），第 3.2 节 "Leases" —— Lease 令牌的原始论文
- Redis 官方文档：[DEL](https://redis.io/commands/del/)、[SET with options](https://redis.io/commands/set/)、[EVAL script](https://redis.io/commands/eval/)
- 本仓库对应笔记：[07 延迟双删](learn/redis/07-delayed-double-delete.md)、[08 墓碑机制](learn/redis/08-city-code-tombstone.md)、[09 Lease 令牌](learn/redis/09-lease-token.md)
