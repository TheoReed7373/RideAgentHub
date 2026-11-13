# Facebook Lease 令牌：当回填本身就是破坏性覆盖

> **Redis 考点**：缓存的 L2 回填是「整体替换」这一**破坏性覆盖**时，「删除」和「状态标记」都无从下手 —— 唯一的解法是给回填加一道**版本租约**（Lease），用 Lua 保证「版本校验 + 替换」的原子性。
> **来源**：`docs/02-cache-consistency-race.md` §2.3 的竞态推导；Facebook *Scaling Memcache at Facebook*（NSDI '13）第 3.2 节 "Leases"
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/memory/RedisMemory.java`、`MessageMemory.java`、`HeapMemory.java`，`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`

---

## 一、业务场景

AI 客服的对话上下文按 `chatId` 维维护，是一个三层记忆结构（详见 [19 篇](19-multi-level-cache.md)）：

| 层 | 实现 | 介质 | 生命周期 |
|---|---|---|---|
| L1 | `HeapMemory` | JVM 堆（`ConcurrentHashMap`） | 进程存活期 |
| L2 | `RedisMemory` | Redis List | 聚焦会话 24 小时 / 非聚焦 30 分钟 |
| L3 | `MysqlMemory` | MySQL | 永久 |

读路径是 **L1 → L2 → L3** 逐层回源并回填；写路径是 **先落 MySQL，再按租约追加 L2 / L1**。触发频率决定了问题的严重性：`ChatServiceImpl` 每轮对话都调 `memory.get`，Agent 每轮结束都调 `messageMemory.save`——「读回填」和「并发追加」每轮对话都可能撞上。

**为什么这是 Lease 才能解的问题**：L2 的回填语义是「用 MySQL 快照整体替换缓存」。当替换与并发追加撞上：

```
T1 读线程：L1/L2 miss → 查 MySQL 得快照 S = [消息 1..10]
T2 写线程：save → 追加消息 11（L3、L2、L1）
T1：用 S 整体替换 L2 → L2 变回 [1..10]，消息 11 从缓存里消失
```

这不是脏读而是**丢失**：脏读会自愈（TTL 到期或下次写入修正），丢失不会——L2 看起来是完整的（非空），后续命中该缓存的读不会回源 MySQL，直到数据过期或失效，消息 11 在缓存失效或被清理前持续缺失，MySQL 中的消息仍存在。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:history:{chatId}` | List（JSON 字符串） | 聚焦 24 小时 / 非聚焦 30 分钟 | 消息队列：`RPUSH` 追加、`LRANGE 0 -1` 读取 |
| `chat:history:version:{chatId}` | String（整数） | **`-1`（`PERSIST` 永久保留）** | Lease 版本号：`INCR` 递增、永不归零 |
| `chat:history:staging:{chatId}:{uuid}` | List | 1 分钟（`STAGING_TTL`） | 回填暂存区：正常路径立刻被 Lua 消费，TTL 是崩溃兜底 |

L1 与 L2 各有一份版本租约（heapLease / redisLease）；staging 是 L2 的回填暂存区，并非第三份租约——L1 与 L2 分居进程内外，不可能用同一次原子操作覆盖。

---

## 三、代码落点

### L2：`agentbase/memory/RedisMemory.java`

| 方法 | 职责 | 脚本 |
|---|---|---|
| `currentVersion(chatId)` | 取租约；Java 侧读不到结果抛 `IllegalStateException`（保守失败） | `CURRENT_HISTORY_VERSION` |
| `appendIfVersionMatch(chatId, msgs, ttl, expectedVersion)` | **写路径**：版本不符就 `DEL` 数据（让在途租约必然失效），否则仅在数据 key 已存在时 `RPUSH` + `PEXPIRE` | `APPEND_HISTORY_IF_VERSION_MATCH` |
| `append(chatId, msgs, versionTtl)` | 通用追加：`INCR` 版本 + `PERSIST` + `RPUSH` | `APPEND_WITH_VERSION_BUMP` |
| `overwriteIfLeaseValid(chatId, msgs, leaseVersion)` | 持租约回填：`MULTI` 写暂存 → 校验 + `RENAME` | `OVERWRITE_IF_VERSION_MATCH` |
| `expire(chatId, duration)` | **只给数据 key** 设 TTL，版本 key 不触碰 | — |
| `clear(chatId)` | 只删数据、递增版本，一条 Lua 原子完成 | `CLEAR_HISTORY_WITH_VERSION_BUMP` |

### Lua 脚本：`util/RedisScripts.java`（5 段）

| 脚本 | 职责 |
|---|---|
| `CURRENT_HISTORY_VERSION` | 取租约；处理「key 不存在 / 带 TTL」分支、清数据 key、`PERSIST` 版本 key |
| `APPEND_HISTORY_IF_VERSION_MATCH` | 写路径；版本不符 `DEL` 数据，否则追加 + 数据 `PEXPIRE` |
| `APPEND_WITH_VERSION_BUMP` | 通用追加；`INCR` + `PERSIST` + `RPUSH` |
| `OVERWRITE_IF_VERSION_MATCH` | 暂存 → `RENAME`；成功时 `INCR` 版本并 `PERSIST` |
| `CLEAR_HISTORY_WITH_VERSION_BUMP` | `INCR` + `PERSIST` + `DEL` 数据 |

### 编排层：`agentbase/memory/MessageMemory.java`

| 方法 | 职责 |
|---|---|
| `get(userId, chatId, lastN)` | 归属守卫 → 逐层 `getAll` → `MessageWindow.tail` 截窗口；逐层取租约回填 |
| `save(userId, chatId, msgs)` | `assertOwned` → 先 `mysqlMemory.append` → 再按租约追加 L2 / L1 |
| `getPersisted` | 恢复链路用：直接读 MySQL 权威数据 |
| `fillRedisWithLease` / `fillHeapWithLease` | 逐级回填 |

---

## 四、实现拆解

### 4.1 竞态形态：这不是脏读，是数据丢失

见一的时序。第二个后果比第一个更隐蔽：T2 的 `save` 明明写成功了（MySQL 里有 11），但用户看到的上下文里没有 11——而且是**持续地**没有。L2 非空 → L1 回填走 L2 → 缓存失效前不回源。**丢失的数据会「看起来完好」地持续服务**，这是它与脏读的本质区别。

### 4.2 为什么延迟双删和墓碑都解不了

| 机制 | 需要的前提 | 本场景 |
|---|---|---|
| **延迟双删** | 写路径存在「删除」动作 | ❌ `save` 是 `RPUSH` 追加，不删任何东西。没有第一次删除，就无从谈第二次 |
| **墓碑** | 存在可标记的「失效」动作 | ❌ 问题出在**读路径**（回填是破坏性的），写线程没做错任何事。墓碑是给写线程的信号，这里写线程不需要被拦 |

把两个机制硬套上去会得到荒谬结果：给 `save` 加「追加后删缓存」——它自己刚追加的消息立刻没了，每轮对话都退化成冷读 MySQL；给 `save` 写墓碑——墓碑让读线程「别回填」，可这里读线程**必须**把数据补上（L2 是空的），不让它回填 L2 只会永远空着。

**根因：回填这个动作本身带有破坏性。** 前两个机制在「回填结果何时能被接受」上做文章（时机 / 状态），而这里的回填结果**在任何时机都不能被接受**——除非能证明「我手里这份快照仍然是最新的」。这就是 Lease 要回答的问题。

### 4.3 Lease 令牌的对应关系

| Facebook Memcache Lease | 本项目实现 |
|---|---|
| 读 miss 时返回 lease token | `MessageMemory.get` 在任何耗时读取**之前**调 `redisMemory.currentVersion`（捕获版本号） |
| 只有持有效 lease 才能回填 | `overwriteIfLeaseValid(chatId, msgs, leaseVersion)` |
| 写路径使 lease 失效 | `save` → `appendIfVersionMatch`：`INCR` 版本与 `RPUSH` 同一次 Lua 原子完成 |
| 过期 lease 的回填被拒绝 | 版本不匹配 → 脚本 `DEL` 暂存 → 回填放弃，L2 保持并发写入的内容 |
| lease 有 TTL、过期回收 | **不采用**：版本 key `PERSIST` 永久保留（见 4.7） |

竞态重放：

```
T1  捕获 redisLease = 5
T2  save → INCR → 版本变成 6，同时 RPUSH 消息 11
T1  overwriteIfLeaseValid(chatId, S, 5)
    → Lua: current(6) ~= ARGV[1](5) → DEL 暂存 → return 0
    → 回填被拒绝，若 L2 已有完整历史，则保留 [1..11]；若原本缺失，写路径不会仅用消息 11 新建残缺缓存，后续继续回源
```

**关键点：回填被拒绝不是失败，而是正确行为。** 注释把它写成设计原则：

> 宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息

代价是「下次读 L2 仍然 miss，会再查一次 MySQL」——一次多余的 DB 查询。相比「在缓存失效前持续漏掉一条消息」，这个代价可以忽略。

**「取租约必须在任何耗时读取之前」是这套机制的全部要害。** 如果在查完 MySQL 之后才取版本号，那么「查 MySQL」这段窗口就没有被租约覆盖——恰恰是这段窗口里 T2 提交了追加，而 T1 捕获到的版本号是**更新后**的，校验反而会通过，回填照样覆盖。

### 4.4 为什么回填要绕一次暂存 key

回填数据不能直接作为 `ARGV` 传给 Lua——消息列表**可能很大**（`getAll` 读整个会话，长期对话几千条）：客户端产生巨大请求体、Redis 侧占用与数据等量的临时内存、受 `proto-max-bulk-len` 协议限制。

暂存 key 把「数据搬运」和「原子性保证」分开：

```
MULTI: RPUSH → staging（普通命令，可分批、可以很大）+ EXPIRE 1min
EVAL:  只传三个 key 名 + 一个版本号（极小）
       脚本内部做 DEL / RENAME（O(1) 的元数据操作，不搬数据）
```

`RENAME` 只改 key 的元数据指针，**不复制 value**。暂存 key 的三个出口全部消费它：

| 出口 | 对暂存 key 的操作 |
|---|---|
| 版本匹配 → 回填成功 | `RENAME` 到数据 key |
| 版本不匹配 → 拒绝 | `DEL` 暂存 |
| 暂存 key 不存在（进程曾崩溃） | 返回 -1；TTL 已自动清理 |

### 4.5 版本递增必须与数据追加在同一次原子操作内

如果把 `append` 拆成两条独立命令：

```
T1  捕获 redisLease = 5
T2  save ① RPUSH 消息 11        ← 数据已进 L2，版本还是 5
T1  overwriteIfLeaseValid(S, 5) → 版本仍是 5，校验【通过】！
    → RENAME 暂存 → L2 变成 [1..10]
T2  save ② INCR → 版本 6        ← 太晚了，消息 11 已被抹掉
```

这个窗口比 4.1 的更隐蔽：T1 的租约校验**是通过的**，日志无异常，回填返回 true。表面上一切都对，数据却丢了。所以 `APPEND_HISTORY_IF_VERSION_MATCH` / `APPEND_WITH_VERSION_BUMP` 里 `INCR` 在 `RPUSH` 之前、且整个脚本原子执行——从外部看，「版本号变了」和「消息可见了」是**同一个瞬间**。

### 4.6 L1 为什么也要租约

L1 是**最先被读到的层**（`heapAll` 非空就直接返回）。假设 L1 回填不校验租约：

```
T1  查 MySQL 得 S = [1..10]
T2  save → heapMemory.append(11)
T1  heapMemory.overwrite(chatId, S)    ← L1 变成 [1..10]
后续所有读 → L1 命中 [1..10] → 直接返回，连 L2 都不看
           → L2 里明明有正确的 [1..11]，却永远读不到
```

**L1 的数据丢失比 L2 更致命**——L1 短路了整条回源链路，L2 修得再好也救不回来。`HeapMemory` 因此也引入版本号（`record Entry(List<Message>, long version)`），「校验 + 替换」在 `ConcurrentHashMap.compute` 内对同一 `chatId` 原子。

L1 租约失效时的动作是**保留并发写入内容**（`return existing`）：写顺序是「先 MySQL 再追加 L2/L1」，L1 里的列表包含最新追加的消息，清空反而丢数据。L1 要么是完整上下文、要么包含最新追加，后续由 `MessageWindow.tail` 与 L2/MySQL 校正。

### 4.7 版本 key 必须永久保留（`PERSIST`）

如果版本 key 与数据 key 同生命周期（一起 TTL 或被 `clear` 同时删除），版本号会**归零重新计数**：

```
T1  捕获 redisLease = 1（会话早期版本号是 1），开始耗时读 MySQL
T2  clearChat() → 同时删数据 key 和版本 key → 版本归零
T3  save → INCR 新建版本 key → 值又是 1
T1  overwriteIfLeaseValid(S, 1) → current(1) == 1 → 校验【通过】！
    → 已清空的会话被旧快照"复活"
```

当前实现里五段脚本都执行 `redis.call('PERSIST', versionKey)`，`clear` 走 `CLEAR_HISTORY_WITH_VERSION_BUMP`（只 `INCR` + `DEL` 数据），在版本 key 未被外部删除或 Redis 数据丢失的前提下，版本号对同一 `chatId` **单调递增**，任何旧租约都不可能重新匹配。

**「版本号是租约的时钟——时钟可以停，但不能倒转。」** 代价：版本 key 不随数据回收，Redis 中该前缀的 key 数随历史 `chatId` 单调增长（用少量内存换单调性）。可选优化是定期清理「长期无数据且无写入」的版本 key，但清理本身必须与在途租约协调，否则又会把版本号重置到小值——这正是当前不做自动清理的原因。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| **版本号 + 拒绝回填** | 回填时合并（union） | 消息没有稳定 ID 可去重；且合并语义在「删除过消息」的场景同样错 |
| **版本号** 而非**时间戳** | `System.currentTimeMillis()` 当 lease | 多实例时钟不同步；同一毫秒两次写入无法区分。`INCR` 由 Redis 单点产生，全局单调 |
| 暂存 key + `RENAME` | 消息列表作为 `ARGV` | 消息量不可控；`RENAME` O(1) 不搬数据（4.4） |
| `INCR` 与 `RPUSH` 同脚本 | 两条独立命令 | 否则存在「数据已追加、版本未递增」的隐蔽窗口（4.5） |
| **版本 key `PERSIST` 永久保留** | 与数据 key 同 TTL | 同 TTL 会被 `clear` 或一起过期归零，旧租约可能撞上重置后的小版本号（4.7） |
| L1 也引入版本号 | 只修 L2 | L1 短路整条回源链路（4.6） |
| L1 租约失效时保留 | 清空 L1 | 写顺序已改为先 MySQL 再追加，L1 里是最新内容，清空反而丢 |
| `currentVersion` 读不到抛异常 | 按 0 处理 | 版本号是正确性依赖，读不到不应「猜一个 0」继续回填（保守失败） |

**更轻的替代方案：按长度增量补齐尾部**。既然数据只追加，可以不用版本号：`LLEN` + `LRANGE` 对比 MySQL 行数，只 `RPUSH` 缺失的尾部。简单、无版本 key、从根本上避开「覆盖」这个动作。没有采用的原因：增量补齐只适用于「只追加、不修改、不删除」的数据形态（约束脆弱）；而 **Lease 是通用原语**——它回答「我手里的快照还能不能用」，与数据形态无关，换成可修改的缓存也能直接用。

---

## 六、边界与已知问题

1. **回填被拒时没有 single-flight**：N 个并发 miss 同时被拒 → N 次 MySQL 查询。性能问题而非正确性问题。
2. **`getAll`（`LRANGE 0 -1`）成本随对话长度线性增长**：Lease 解决正确性，没有解决读取成本。
3. **L1 与 L2 的租约互不相关**：可能出现「L2 回填成功、L1 回填被拒」的组合——正确（L1 保留并发写入），但两层内容短暂不一致，下次读 L1 miss 后自愈。
4. **版本号没有上界检查**：Redis `INCR` 超出有符号 64 位整数范围时返回错误，不会回绕；Java 堆内版本的 `long + 1` 未做溢出检查。
5. **版本 key 不回收**（4.7 的代价）：会话量大时该前缀 key 单调增长。

---

## 七、如何验证

```bash
# 1. 观察三个 key 的关系
redis-cli KEYS "chat:history:*"
# 期望：chat:history:{chatId} 与 chat:history:version:{chatId}；
# staging key 正常应立即被消费（偶见则是崩溃兜底，TTL ≤ 60）

# 2. 版本号随每次 save 递增
redis-cli GET chat:history:version:{chatId}     # 例如 "5"
# （调一次对话接口触发 save）
redis-cli GET chat:history:version:{chatId}     # 期望 "6"

# 3. 版本 key 永久保留（PERSIST）
redis-cli TTL  chat:history:{chatId}            # 例如 86390（数据 key 有 TTL）
redis-cli TTL  chat:history:version:{chatId}    # 期望 -1 —— 与数据 key 无关
# 清空历史后：版本 key 仍在且值递增，数据 key 被删除

# 4. 手工构造"租约失效"：改掉版本号模拟并发 save
redis-cli SET chat:history:version:{chatId} 9999
# 持旧租约的读线程回填会被拒绝，观察应用日志：
#   "回填租约已失效，放弃覆盖: chatId=..., leaseVersion=..., result=0"

# 5. 端到端：并发「一轮对话（触发 get）+ 一次 save」，
#    缓存允许为空；完整回填成功且无并发修改时，LLEN 应等于可解析的持久化消息数
redis-cli LLEN chat:history:{chatId}
```

---

## 八、延伸阅读

- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 为什么「第二次删除」在这里无从下手
- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 为什么「状态标记」在这里也无从下手
- [`19-multi-level-cache.md`](19-multi-level-cache.md) —— 三层记忆的整体设计与读写顺序
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— `ticket:pool:version` 同样无 TTL，是该模式的第二处落地
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— 版本 key 的 `PERSIST` 与会话 key 的 `persist` 是两件不同的事
- Facebook *Scaling Memcache at Facebook*（NSDI '13），第 3.2 节 "Leases" —— Lease 令牌的原始论文
