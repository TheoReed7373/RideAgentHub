# 缓存一致性竞态盘点：延迟双删 / 墓碑 / Lease

> 目标：定位 RideAgentHub（旧名 TaxiAgent）中"DB 更新导致缓存脏数据"的真实竞态点，
> 并为延迟双删、墓碑机制、Facebook Lease 令牌三种解法找到最合适的落点。
> 代码引用格式为 `文件:行号`，基于历史提交 `8d5d574`（`import TaxiAgent project`，属已被重写合并的旧分支）。
> **本文性质**：通篇「现状」「排查结论」「附带缺陷」描述的都是**改造前**快照。三解法与四个附带缺陷的当前状态（✅ 已修复 / ⚠️ 部分修复 / ❌ 仍存在 / 📐 设计中）在各节以【当前状态】框追加说明；竞态推导、时序图、方案对比属于教学主体，**原样保留**。章节编号（2.x/3.x）是 25 篇学习笔记「来源」的锚点，**保持不变**。

---

## 一、排查结论总览

先把**不存在竞态**的缓存划掉，避免过度设计。

> 【当前状态说明】下表「无失效路径」「双写非原子」「会丢数据」都是**改造前**的排查结论，是全文推导的起点，不删除；最右列追加当前代码的状态。`ESMemory` 空实现与布隆「重建期穿透」两格至今成立，保留。

| 缓存 | 数据可变？ | 有失效路径？（改造前） | 回填语义（改造前） | 结论：改造前 → 当前状态 |
|---|---|---|---|---|
| `TokenServiceImpl` | — | — | — | ✅ **安全**：Redis 本身是数据源，无 DB 双写 —— 结论不变；当前改为 `SAVE`/`REVOKE`/`REMOVE` 三段 Lua 原子发布 + 会话版本围栏 |
| `EmailCodeService` | — | — | — | ✅ **安全**：同上 —— 当前四段 Lua + 5 次错误上限 |
| `ToolResponseMemory` | 否 | — | `set` 覆盖 | ✅ **安全**：工具结果按 `(callId, chatId)` 不可变 —— 当前 key 含 chatId、Redis + MySQL 两级；`set` 覆盖与 MySQL 重复行问题见 `docs/01` 第三节 |
| `HeapMemory` | 是 | — | `overwrite` | ➖ L1 进程内，由 `MessageMemory` 统一调度 —— 当前租约失效时**保留**并发写入内容，不清空 |
| `ESMemory` | — | — | — | ➖ 空实现（规划中）—— **结论不变**：当前仍是空类、`MemoryAdvisor` 返回 `null`，均 0 调用方（📐 设计中） |
| `UserUsernameBloomFilterService` | 只增 | 仅 `rebuild` 全量 | — | ⚠️ 非双写竞态，是**重建期穿透**（见 **3.4**；原「见 3.3」是交叉引用笔误） —— ❌ 缺陷仍在，后果由 DB 唯一索引兜底 |
| `CityCodeUtil` | 是 | **无** | 全量 `set` | ⚠️ **真竞态**（TTL 7 天）→ ⚠️ **部分实现**：读侧版本 + 墓碑两段 Lua 已就位，但 `invalidate()` 写路径未接入 → 见 2.2 |
| `TicketServiceImpl` 统计缓存 | 高频 | **无** | `set` 覆盖 | ⚠️ **真竞态** → ✅ **已解决**：统一失效 + 提交后立即删除 + 500ms 二次删除 → 见 2.1 |
| `ChatManager` / `ChatInfoService` | 是 | 双写 | — | ⚠️ **真竞态**（DB+Redis 双写非原子）→ ⚠️ **部分修复**：`HSET`+`EXPIRE` 已 Lua 原子化，`restoreChat` 的 `persist` 仍在 → 见 3.1 |
| `RedisMemory` / `MessageMemory` | 是 | 双写 L2/L3 | **`clear` + 全量 `append`** | ⚠️⚠️ **真竞态且会丢数据** → ✅ **已解决**：取租约 + 版本校验原子回填；版本 key `PERSIST` 永久保留 → 见 2.3 |

三个真竞态点的差异，正好对应三种解法的适用边界：

- **2.1 延迟双删** —— "删除被回填覆盖"，用**第二次删除**兜住
- **2.2 墓碑机制** —— "TTL 太长，时间窗口猜不准"，用**状态标记**替代猜时间
- **2.3 Lease 令牌** —— "回填本身就是破坏性覆盖"，只有**版本校验**能解

### 当前状态总表（四态标注）

> 下表把「已修复 / 部分修复 / 仍存在 / 设计中」一次说清，避免读者把改造前的缺陷标注当成现状。所有 `文件:行号` 均为历史快照，当前按类名 + 方法名定位。

| 本文位置 | 改造前标注 | 当前代码事实 | 状态 | 主要证据 |
|---|---|---|---|---|
| 2.1 / 一 | 「10 个写方法没有一个碰缓存」「无失效路径」 | 10 个写方法逐个调 `afterTicketMutation()`，先注册索引同步再 `evictTicketStatistics()`，删除包 try/catch 且必定调度 500ms 二次删除 | ✅ 已修复 | `TicketServiceImpl.afterTicketMutation` / `evictTicketStatistics`、`DelayedCacheEvictor` |
| 2.2 / 一 | 「没有任何失效路径」「大小写 key 分裂」 | `normalized = trim().toLowerCase(Locale.ROOT)` 统一 key 与 DB 查询；`.and()` 已包裹 `.or()` | ✅ 已修复（key 规范化） | `CityCodeUtil.getCityCode`、`RedisKeyConstants.amapCityCodeKey` |
| 2.2 / 3.3 裁决 | 「墓碑建议已全部实现 / 已彻底修复」 | `INVALIDATE_CITY_CODE` / `CACHE_CITY_CODE_IF_VERSION_MATCH` 两段 Lua 与版本 key 齐备并通过回归测试，但 `invalidate()` 生产代码 **0 调用方** | ⚠️ 部分实现（机制已就位·写路径未接入） | `CityCodeUtil.invalidate`、`RedisCacheRegressionTest` |
| 2.3 / 一 | 「破坏性 `overwrite`，唯一一处会丢数据」 | 取租约 + 版本校验回填；`clear()` 走 `CLEAR_HISTORY_WITH_VERSION_BUMP` 原子脚本；版本 key 五处 `PERSIST` | ✅ 已修复（且设计结论反转） | `MessageMemory`、`RedisMemory`、`RedisScripts:169-235` |
| 3.1 | `lockChat` 的 `put` + `expire` 非原子 | `HASH_SET_WITH_EXPIRE` Lua 把 HSET 与 EXPIRE 合并；`//其实这里始终为真` 注释已删除 | ✅ 已修复（原子化部分） | `ChatManager.lockChat`、`RedisScripts.HASH_SET_WITH_EXPIRE` |
| 3.1 / 附注 | `restoreChat` 去掉 TTL | `ChatInfoService.restoreChat()` 仍 `persist(chatInfoKey)`，TTL 语义矛盾未梳理 | ⚠️ 部分修复（整体） | `ChatInfoService.restoreChat`、`POST /chat/v2/restore` |
| 3.2 | `finally` 无条件 `delete(lockKey)` 误删他人锁 | 手写锁统一由 `RedisLock` 管理，释放走 `RELEASE_LOCK_IF_MATCH` 校验 UUID 令牌 | ✅ 已修复 | `RedisLock.unlock`、`RedisScripts.RELEASE_LOCK_IF_MATCH` |
| 3.3 | 「校验 + 删除非原子」「3.3 未修复」 | `OrderSearchTool.CANCEL` Lua 把「校验令牌 + 绑定 orderId + 5 分钟时效 + `HDEL`」原子化；`OrderConfirmation.CONSUME` 一次性消费创建权限 | ✅ 核心竞态已修复 | `OrderSearchTool.CANCEL`、`OrderConfirmation` |
| 3.3 残留 | — | `hasKey` 预检（`:240`）、`finally` 冗余 `delete`（`:282`）、令牌消费与业务执行分离（`:269` vs `:275`）仍在 | ⚠️ 部分修复（整体） | `OrderSearchTool.cancelOrder` |
| 3.4 | 布隆 `delete()` + `tryInit()` 原地重建 | 代码未变；但 `isUsernameAvailable` 已直查 DB + 生成列唯一索引兜底，后果从「落库重名用户」变为「重建期假阴性」 | ❌ 仍存在（后果已变） | `UserUsernameBloomFilterService.rebuild`、`sql/init.sql` |
| `docs/01` 三、缺陷 1 | 「重复 tool call 会覆盖已有结果」 | Redis 仍用 `set`；MySQL 每次 `insert` 新行、靠 `orderByDesc(id) LIMIT 1` 取最新 | ❌ 仍存在（且更重） | `ToolResponseMemory.save/get` |
| 验证码 / Token | 「可重复消费 / 三步非原子」 | `EmailCodeService` 四段 Lua + 5 次上限；`TokenServiceImpl` 三段 Lua + 会话版本 / 凭据摘要三重校验 | ✅ 已修复 | `SecurityRedisIntegrationTest` |
| 锁与队列 | 固定 10s 租约、`rollbackGrab` 非原子、延迟队列无 DB 兜底、`INCR`+`EXPIRE` 非原子 | 订单锁启用 Redisson 看门狗；回滚走 `RELEASE_LOCK_IF_MATCH`；`OrderTimeoutJob` 每分钟扫 DB 并重排失败；`TICKET_SEQUENCE` Lua | ✅ 已修复 | `RideOrderServiceImpl.withOrderLock`、`OrderTimeoutJob`、`TicketServiceImpl` |
| `CHECK_DRIVER_ONLINE` | 作为第 7 个脚本讲解 | 脚本已删除，改由 `UPDATE_/REMOVE_/READ_DRIVER_LOCATION` 三脚本 + Redis `TIME` 实现 | 已废弃 | `RedisScripts:10-45`、`DriverGeoIndex` |

---

## 二、三处真竞态

### 2.1 延迟双删 → `TicketServiceImpl` 工单统计缓存

> 【当前状态 · ✅ 已修复】「没有任何一处删除」「10 个写方法没有一个碰缓存」是**改造前**结论。当前 `TicketServiceImpl.afterTicketMutation()` 被 10 个写方法逐个调用，且顺序刻意是**先注册 `afterCommit(() -> ticketPoolIndex.syncByTicketId(...))`，再 `evictTicketStatistics()`**（统计缓存删除失败不阻止索引版本推进）；第一次删除包在 `try/catch(RuntimeException)` 里，失败只 `log.warn`，无论成败都调度 `DelayedCacheEvictor` 的 500ms 二次删除。`updateTicketTime` 是辅助方法，由调用方统一覆盖失效，不算遗漏。下面 `:985/:1002/:1035` 等行号是历史快照，当前按 `getTicketStatistics` / `evictTicketStatistics` / `afterTicketMutation` 方法名定位（约 `:1135-1171`、`:1236-1251`）。TTL 20+0~10 秒未变，但已叠加延迟双删；下文「延迟双删是过度设计」的**教学判断仍然成立**，保留作设计取舍的证据。详见 [07 篇](learn/redis/07-delayed-double-delete.md)、[23 篇](learn/redis/23-ticket-pool-zset.md)。

**现状（改造前）**

`ticketStatisticsKey` **全项目只有 `getTicketStatistics` 读写，没有任何一处删除**：

```
RedisKeyConstants.java:165   — ticketStatisticsKey 定义
RedisKeyConstants.java:173   — ticketStatisticsLockKey 定义
TicketServiceImpl.java:985   — 读
TicketServiceImpl.java:1002  — 写
TicketServiceImpl.java:1035  — 写（cached 方法内）
```

所有写工单的方法**没有一个碰缓存**：

| 方法 | 行号 | 操作 |
|---|---|---|
| `submitTicket` | 121 | insert |
| `cancelTicket` | 163 | update status |
| `confirmAndRate` | 208 | update status |
| `assignTicket` | 347 | update handler + status |
| `reassignTicket` | 389 | update handler + status |
| `processTicket` | 445 / 459 / 473 | update status（四个分支） |
| `sendMessage` | 525 | update status |
| `escalateTicket` | 843 | update priority |
| `escalateTicketByAdmin` | 887 | update priority |
| `appendUserMessage` | 933 | update time |
| `updateTicketTime` | 621 | update time（辅助方法，被多处调用） |

**竞态形态**

改造加上"写时删缓存"之后，竞态才会暴露：

```
T1  getTicketStatistics:985   读缓存 miss
T1  :993                      tryLockTicketStatistics 拿锁成功
T1  :1001                     queryTicketStatisticsFromDb  // 4 次 selectCount，几十 ms
T2  submitTicket:121          insert 工单
T2  (改造后)                   delete cache                  // ← 第一次删除
T1  :1002                     cacheTicketStatistics(cacheKey, fresh)  // ← 写回"不含新工单"的旧统计
```

**关键点：`TicketServiceImpl:993-1006` 那个互斥锁防不住这个竞态**，因为写方（T2）不走锁。
第一次删除被 T1 的回填覆盖了 —— 这正是延迟双删存在的唯一理由：**回填之后再删一次**。

**改造方向**

```java
// 写方法统一加失效（建议用注解 + AOP，避免 10+ 处散落）
@CacheEvict(key = "ticket:statistics")   // 或自定义注解
public void submitTicket(...) { ... }

// 延迟双删
public TicketDataVO getTicketStatistics() {
    // ... 现有逻辑
    cacheTicketStatistics(cacheKey, fresh);
    scheduleDelayedEvict(cacheKey, 500);   // ← 延迟第二次删除
    return fresh;
}
```

**工程诚实说明**

这个 key 的 TTL 只有 **20 + 0-10 秒抖动**（`TicketServiceImpl:65-66`、`1039-1040`），
所以脏数据窗口**本来就有界**。严格按工程标准，**加个删除就够，延迟双删是过度设计**。

> 它的价值在这里是**教学**：演示"为什么加了删除还会有脏数据"。
> 也正因为 TTL 短，"延迟双删的 sleep 该睡多久"这个致命缺陷在这里不够刺眼 ——
> 这一点正好指向 2.2。

---

### 2.2 墓碑机制 → `CityCodeUtil`

> 【当前状态 · ⚠️ 部分实现：机制已就位·写路径未接入】必须分三件事看，否则很容易把「已修复」写成过头的结论：
> - ✅ **key 规范化已修复**：`CityCodeUtil` 一次做 `trim().toLowerCase(Locale.ROOT)`，cache key 与 DB 查询使用同一个 `normalized`；`.or()` 已用 `.and(w -> ...)` 包裹。下面「顺带一个现存 bug」整段是改造前记录，**结论不再成立**，但推导保留作反面教材。
> - ✅ **读侧机制已落地**：新增 `amap:city_code:version:{name}`（无 TTL）与两段 Lua —— `INVALIDATE_CITY_CODE`（`INCR` 版本 + `SET` 5 秒墓碑）、`CACHE_CITY_CODE_IF_VERSION_MATCH`（版本不符返回 0，否则 `SET … NX EX`）；命中墓碑不回填，版本号防止墓碑过期后旧快照回填。这比改造建议多了一层「版本租约」。
> - ⚠️ **写路径未接线**：`CityCodeUtil.invalidate(String)` 在**生产代码 0 调用方**，只有 `RedisCacheRegressionTest` 调用；城市表改名/纠错的写操作没有触发它。所以「写城市表 → 写墓碑」这一环仍是设计建议，整个机制不构成闭环，按术语规范标注为「机制已就位·写路径未接入」，**不能描述为已彻底修复**。
>
> 下方「为什么必须是这里」「延迟双删 vs 墓碑」对比表仍是本节最有价值的部分，原样保留。实现细节见 [08 篇](learn/redis/08-city-code-tombstone.md)、[06 篇](learn/redis/06-null-sentinel-and-prewarm.md)。

**现状（改造前）**

`CityCodeUtil:35-61` 缓存高德城市编码：

```java
private static final String NULL_SENTINEL = "__NULL__";   // :20
private static final long CACHE_TTL_DAYS = 7;             // :21

public String getCityCode(String cityName) {
    String key = RedisKeyConstants.amapCityCodeKey(cityName);   // :40
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return NULL_SENTINEL.equals(cached) ? null : cached;    // :43
    }

    CityCode cityCode = cityCodeMapper.selectOne(...);          // :46-51
    String code = cityCode == null ? null : cityCode.getCityCode();
    redisTemplate.opsForValue().set(key,                        // :54-59
            code == null ? NULL_SENTINEL : code,
            CACHE_TTL_DAYS, TimeUnit.DAYS);
    return code;
}
```

**为什么必须是这里**

1. **TTL 是 7 天**。同样的读写竞态，脏数据存活 7 天。
   延迟双删的"sleep N 毫秒"在这里**彻底失效** —— 不可能 sleep 7 天，也不可能覆盖所有并发窗口。
   它暴露了延迟双删的本质缺陷：**靠猜时间**。

2. **它已经有一个墓碑的近亲**：`NULL_SENTINEL = "__NULL__"`（`:20`）。
   空值哨兵（防穿透）和墓碑是同一套思路 —— **把状态编码进 value，而不是靠时间猜**。
   墓碑只是把 `"__NULL__"` 换成 `"__TOMBSTONE__"` + 5 秒短 TTL。

3. **现在没有任何失效路径**。城市表改名/纠错后缓存 7 天不动。

**改造方向**

```
写城市表 → SET amap:city_code:{name} "__TOMBSTONE__" EX 5    // 短 TTL 墓碑

读 → 读到 "__TOMBSTONE__"
   → 视为"此刻有写入正在进行，缓存不可用"
   → 走 DB 查询
   → 不回填（或延迟回填）
```

把延迟双删那个"第二次删除"的**不确定时间**，换成一个**确定的状态标记**：
读线程看到墓碑就知道"别回填"。

**关键区别**（值得在文档/演示中强调）：

| | 延迟双删 | 墓碑机制 |
|---|---|---|
| 拦截依据 | 时间（sleep N ms） | 状态（读到墓碑） |
| 窗口是否确定 | ❌ 靠猜，高并发下无论睡多久都有窗口 | ✅ 确定，写到墓碑清除为止 |
| 额外开销 | 一次延迟删除 | 一次墓碑写入 + 墓碑自身 TTL 管理 |
| 适用 | 短 TTL、低并发 | 长 TTL、写不频繁但要求强一致 |

> **注意**：墓碑要求写路径**必须写入墓碑**，否则退化成普通删除。
> 所以它的前提是写路径可控（本项目城市表是低频运维操作，完全可控）。

**顺带一个现存 bug**

`CityCodeUtil:40` 的 cache key 走 `RedisKeyConstants:131`：

```java
return AMAP_CITY_CODE_PREFIX + cityName.trim().toLowerCase();
```

但 `CityCodeUtil:46-51` 的 DB 查询用的是**原始 `cityName`**（未 trim、大小写敏感）：

```java
cityCodeMapper.selectOne(new LambdaQueryWrapper<CityCode>()
        .eq(CityCode::getName, cityName)
        .or()
        .eq(CityCode::getSimpleName, cityName))
```

后果：
- `"成都 "` 和 `"成都"` 是两个不同 cache key，却查同一条 DB 记录 → **key 分裂，命中率虚低**
- 由于 DB 查询大小写敏感而 cache key 不敏感，`"Beijing"` 和 `"beijing"` 会共用 cache key 但 DB 结果可能不同 → **缓存污染**

建议：DB 查询也用规范化后的值，或在应用层统一规范化入参。

> 另注：上面那个 `.or()` 没有用 `.and(w -> ...)` 包裹。这里因为外层没有其他条件所以恰好正确，
> 但一旦后续在外层追加条件（例如加个 `is_deleted = 0`），SQL 语义就会出错。属于隐患。

---

### 2.3 Facebook Lease 令牌 → `MessageMemory` 的 L2 回填

> 【当前状态 · ✅ 已修复，且一处设计结论完全反转】破坏性 `overwrite` 已不存在：三层读取都改为 `getAll` + `MessageWindow.tail`，回填走「先取 `currentVersion` 租约 → Lua 校验版本 → 原子 `RENAME` / `RPUSH`」；`MessageMemory.save` **先落 MySQL** 再按租约追加 L2/L1，L1 租约失效时**保留**并发写入内容而不是清空。
>
> 最需要修正的是本文原来的直觉：**版本 key 现在被 `PERSIST` 永久保留**，只有数据 key 带 TTL（`CURRENT_HISTORY_VERSION`、`APPEND_*`、`CLEAR_HISTORY_WITH_VERSION_BUMP` 五处脚本）。原因是如果版本 key 与数据 key 一起过期归零，「旧租约复活」就会重新获得回填资格，把新数据覆盖掉。`clear()` 也不再是两条独立 `DEL`，而是一条 `INCR` 版本 + `PERSIST` + `DEL` 数据的 Lua 脚本。
>
> 本节「为什么另外两个机制都解不了」的论证与「Lease 令牌对应关系」表是教学核心，**原样保留**；但实现细节（脚本名、`ARGV[1]` 是数据 TTL 毫秒而非版本 key 过期秒数、写路径 `APPEND_HISTORY_IF_VERSION_MATCH` 与回填路径 `APPEND_WITH_VERSION_BUMP` 的区别）以 [09 篇](learn/redis/09-lease-token.md)、[19 篇](learn/redis/19-multi-level-cache.md) 为准。

**这是全项目唯一一处"回填会丢数据"的地方（改造前）。**

**现状**

`MessageMemory.get:57-69`：

```java
List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
if (!redisLastN.isEmpty()) {
    heapMemory.overwrite(chatId, redisLastN);
    return redisLastN;
}

List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    redisMemory.overwrite(chatId, mysqlLastN);       // ← 破坏性回填
    redisMemory.expire(chatId, FOCUS_CHAT_TTL);
    heapMemory.overwrite(chatId, mysqlLastN);
    return mysqlLastN;
}
```

`MessageMemory.getUserMessage:110-127` 同构（走 `redisMemory.getAll` + `overwrite`）。

而 `RedisMemory:94-100`：

```java
public void overwrite(String chatId, List<Message> messages) {
    if (chatId == null) return;
    clear(chatId);          // ← 先全量清空
    append(chatId, messages);
}
```

**竞态形态（数据丢失，非脏读）**

```
T1  MessageMemory.get:63      读 L2 miss
T1  :63                       mysqlMemory.getLastN 拿到快照 S（消息 1..10）
T2  MessageMemory.save:91     redisMemory.append 追加消息 11
T2  :93                       mysqlMemory.append 追加消息 11
T1  :65                       redisMemory.overwrite(chatId, S)   // clear + append
                              → 消息 11 被整段抹掉
```

**触发频率**：`ChatServiceImpl.chat:73` 每轮对话都调用 `memory.get(...)`，
`MessageMemory.save` 也在每轮发生 —— **这个竞态是每轮对话都可能撞上的**。

**为什么另外两个机制都解不了**

- **延迟双删**不行 —— 这里没有"删除被回填覆盖"，而是**回填本身就是破坏性覆盖**。
  再删一次只会把刚补好的数据又抹掉。
- **墓碑**不行 —— 没有"删除"这个动作可以标记。问题出在**读**路径，不是写路径。

**Lease 令牌的对应关系**

| Facebook Lease | 本项目 |
|---|---|
| 读 miss 时返回 lease token（而非空值） | `MessageMemory.get` miss 时取 `chat:version:{chatId}` |
| 只有持有效 lease 才能回填 | `overwrite` 改为 `overwriteIfVersionMatch(chatId, msgs, expectedVersion)` |
| 写路径使 lease 失效 | `MessageMemory.save` 时 `INCR chat:version:{chatId}` |
| 过期 lease 的回填被拒绝 | T1 持有的旧版本号不匹配 → 回填被拒 |

**改造方向**

```java
// 读路径
long version = getVersion(chatId);
List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    redisMemory.overwriteIfVersionMatch(chatId, mysqlLastN, version);  // Lua 原子校验
}

// 写路径
redisMemory.append(chatId, newMessages);
incrementVersion(chatId);          // INCR chat:version:{chatId}
```

Lua 脚本保证"版本校验 + 覆盖"的原子性。

**更轻的替代方案（建议在文档中对比）**

不做版本号，直接把 `overwrite` 换成**按消息数增量补齐尾部**：

```java
// 若 Redis 中已有 n 条，MySQL 快照有 m 条（m > n），只 append 第 n+1..m 条
// 若 m <= n，说明 Redis 更新，直接返回
```

这样连版本号都不需要。**但 Lease 的版本令牌更通用** —— 增量补齐只适用于"只追加不修改"的数据形态，
换一个会被修改的缓存就没法用了。作为教学，Lease 更能体现原理。

---

## 三、附带缺陷（与竞态主题直接相关）

> 【四态标注总览】以下四节的缺陷成因推导、代码块与时序图**全部保留**，只把结论标注更新为当前状态。术语遵循 `docs/learn/redis/README.md` 的统一约定。

| 节 | 改造前缺陷 | 当前状态 | 消除了什么 / 仍留什么 |
|---|---|---|---|
| 3.1 | `put` + `expire` 两步非原子；`restoreChat` 去掉 TTL | ⚠️ **部分修复** | ✅ `HASH_SET_WITH_EXPIRE` Lua 把 HSET 与 EXPIRE 合并、返回 `EXPIRE` 结果；失效注释已删。❌ `ChatInfoService.restoreChat()` 仍 `persist(chatInfoKey)`，`-1` 与 3600 秒的 TTL 语义矛盾未梳理（由 `POST /chat/v2/restore` 触发） |
| 3.2 | `finally` 无条件 `delete(lockKey)` 误删他人锁 | ✅ **已修复** | 手写锁统一由 `service/base/RedisLock.java` 管理，`unlock` 走 `RELEASE_LOCK_IF_MATCH` Lua 校验 UUID 令牌；订单状态机另用 Redisson 看门狗锁 |
| 3.3 | `hasKey` 检查与 `delete` 之间非原子 | ⚠️ **部分修复（核心竞态已消除）** | ✅ `CANCEL` Lua 把「校验令牌 + 绑定 orderId + 5 分钟时效 + `HDEL`」合并为原子消费；`OrderConfirmation.CONSUME` 一次性消费创建权限。❌ 仍有 `hasKey` 预检、`finally` 冗余 `delete`、令牌消费与业务执行分离 |
| 3.4 | 布隆过滤器原地 `delete()` + `tryInit()` 重建 | ❌ **仍存在（后果已变）** | 代码未改；但 `isUsernameAvailable` 已直查 DB，且 `init.sql` 有生成列唯一索引兜底，后果从「落库重名用户」变成「重建期假阴性」，双 key 切换建议依然有效 |

### 3.1 `ChatManager.lockChat` 的 put + expire 非原子

> 【当前状态】`put`+`expire` 非原子这一条 ✅ 已修复：`ChatManager.lockChat` 改调 `HASH_SET_WITH_EXPIRE` Lua（一次 `HSET` + `EXPIRE`，返回 `EXPIRE` 的结果而非固定 `return 1`），`//其实这里始终为真` 注释已从代码删除。但 `restoreChat` 的 `persist` 仍在，所以**整节属于部分修复**；`POST /chat/v2/restore` 是触发 `persist` 的入口。下面代码块与 TTL 矛盾分析按「改造前对照」阅读，见 [12 篇](learn/redis/12-chat-lock-atomic.md)。

`ChatManager:24-29`：

```java
public void lockChat(String chatId){
    chatInfoService.lockChat(chatId);                                    // DB 更新
    String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
    stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "true"); // Redis 写入
    stringRedisTemplate.expire(chatInfoKey, expireLock);                 // 独立命令
}
```

问题：
- `put` 成功而 `expire` 失败（或进程崩溃）→ **key 永久留存**
- 这是典型的 **Cache-Aside 双写**，方向"先 DB 后缓存"符合常规，但**两步都不是原子的**

更麻烦的是 TTL 语义自相矛盾 —— `ChatInfoService:82-85` 的 `restoreChat` 主动去掉了 TTL：

```java
unlockChat(chatId);
stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "false");
stringRedisTemplate.persist(chatInfoKey);      // ← 去掉 TTL，永久化
```

`ChatManager:41` 的注释 `//其实这里始终为真` 也说明这块设计没想透。

**改造**：用 Lua 把 `put` + `expire` 合并成一次原子操作；重新梳理 `locked` 的 TTL 语义。

### 3.2 `TicketServiceImpl:1005` 不校验持有者就删锁

> 【当前状态 · ✅ 已修复】当前没有「无条件删除」：`service/base/RedisLock.java` 的 `unlock()` 是 `void` 且吞掉释放异常，内部用 `RELEASE_LOCK_IF_MATCH` Lua「`GET` 等于自己的令牌才 `DEL`」；释放失败由固定 5 秒 TTL 兜底。订单状态机则改用 Redisson 看门狗锁（`tryLock(3, TimeUnit.SECONDS)`，持锁到 `afterCompletion`）。下面的三类事故推导仍然是最好的教材，见 [13 篇](learn/redis/13-distributed-lock.md)、[04 篇](learn/redis/04-cache-breakdown-mutex.md)。

```java
} finally {
    stringRedisTemplate.delete(lockKey);      // ← 无条件删除
}
```

经典分布式锁 bug：

```
A 拿到锁（TTL 5s） → A 的业务执行超过 5s → 锁自动过期
B 拿到锁
A 进入 finally → delete(lockKey) → A 删掉了 B 的锁
```

**改造**：value 存唯一标识（如 `UUID` + 线程 ID），`finally` 里用 Lua 校验后再删；
或直接换项目已引入的 Redisson `RLock`（自带看门狗续期和持有者校验）。

### 3.3 `OrderSearchTool` 两阶段令牌不是原子消费

> 【当前状态 · ⚠️ 部分修复】「并发双通过」这个**核心竞态已消除**：`OrderSearchTool.CANCEL` 内联 Lua 把「校验 `readyForCancel == ARGV[1]` + 校验 `now - cancelVerifiedAt <= 300000ms` + `HDEL`」合成一次原子操作，令牌绑定具体订单号；订单创建权限另由 `OrderConfirmation` 的 `CONFIRM`/`CONSUME` 两段 Lua 管理（15 分钟报价有效期、一次性消费、`resuming` 幂等重放）。下面「用 Lua 把校验 + 删除合并」的**建议已经落地**，不再是待办。
>
> **仍留三处**：`hasKey` 预检与原子消费之间的冗余检查、`finally` 里的无条件 `delete`、以及「令牌消费」与「业务执行」仍是两步（消费成功但业务失败时不可回滚）。这三点是 [11 篇](learn/redis/11-two-phase-confirm-token.md) 六章的重点，不要因为核心竞态已修就认为整节结论不变。

`OrderSearchTool:254-256` 校验令牌存在：

```java
if(!stringRedisTemplate.opsForHash().hasKey(chatInfoKey, REDIS_READY_FOR_CANCEL)){
    return "先使用verifyCancelConditions()检查取消订单条件";
}
```

`:293-295` 消费令牌：

```java
} finally {
    // 一次性令牌：不管成功失败都清理，避免误用
    stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_READY_FOR_CANCEL);
}
```

`hasKey` 检查与 `delete` 之间**没有原子性**，理论上并发请求可能双双通过校验。
用 Lua 把"校验 + 删除"合并即可。

### 3.4 `UserUsernameBloomFilterService.rebuild:41-59` 重建期穿透

```java
bloomFilter.delete();                    // 先删
bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
for (String username : normalized) {     // 再逐条 add
    bloomFilter.add(username);
}
```

重建期间过滤器为空/半空，**所有查询都会穿透到 DB**。
建议改为**双 key 切换**（重建到新 key，完成后原子改名），而非原地删除重建。

> 【当前状态 · ❌ 仍存在，但后果已变】`UserUsernameBloomFilterService.rebuild()` 依旧是 `delete()` + `tryInit()` + 逐条 `add`，双 key 切换**未实现**；`mightContain` 也已退出可用性主链路（生产 0 调用方）。但 `AuthServiceImpl.isUsernameAvailable` 现在直查 DB，`sql/init.sql` 又加了「同角色、未删除账号」的生成列唯一索引 `uk_active_username_role`，注册前置还会校验用户名 —— 所以重建期的假阴性不再导致「落库两个同名用户」，只影响一次可用性判断。上面的双 key 切换建议作为**可选优化**依然成立，见 [03 篇](learn/redis/03-cache-penetration-bloom.md)。

---

## 四、演练顺序建议

> 【当前状态】四步中**三步闭环，第 3 步（墓碑）为部分实现**：`CityCodeUtil.invalidate(String)` 类、Lua 与回归测试齐备，但生产代码 0 调用方；因此不能写成「均已完成」。本表是**历史实施顺序**，不再是「待办建议」。今天读它只有一个目的：按认知递进理解「为什么需要 07/08/09 篇的机制」—— 每一步都让上一步的缺陷暴露出来。配套的 `docs/01` 第四节优先级表也已同步改为「已实施」。

这个顺序是**认知递进**，每一步都让上一步的缺陷暴露出来：

| 步骤 | 内容 | 暴露出的问题 |
|---|---|---|
| 1 | 给 `TicketServiceImpl` 的 10+ 个写方法加缓存失效 | "加了删除怎么还有脏数据？" |
| 2 | 上延迟双删 | "sleep 到底该睡多久？TTL 短还能糊弄，TTL 长怎么办？" |
| 3 | 在 `CityCodeUtil`（TTL 7 天）用墓碑替换第二次删除 | "把猜时间换成看状态" |
| 4 | 在 `MessageMemory` 上 Lease 令牌 | "回填本身是破坏性的，前两个都解不了" |

> 第 1 步是后面所有步骤的前提 —— 没有写路径的删除，就构造不出竞态。

**历史实施记录**：当时确实从第 1 步起手（改动最局部、能立刻观察效果）。四步落地后分别对应 [07 篇「延迟双删」](learn/redis/07-delayed-double-delete.md)、[08 篇「墓碑」](learn/redis/08-city-code-tombstone.md)（注意：机制已就位但写路径未接入）、[09 篇「Lease 令牌」](learn/redis/09-lease-token.md)。学习时建议按 `docs/learn/redis/README.md` 的阅读路径 `04 → 07 → 08 → 09` 走一遍，把「否掉某个方案的理由」也一起记住。

---

## 五、参考资料

- Facebook Memcache Lease 原始论文：*Scaling Memcache at Facebook*（NSDI '13），第 3.2 节 "Leases"
- 缓存一致性模式：Cache-Aside / Write-Through / Write-Behind 的权衡
- Redis 官方文档：`SET ... NX`、`GEO*` 命令族、`ZADD`/`ZRANGEBYSCORE` 延时队列模式
