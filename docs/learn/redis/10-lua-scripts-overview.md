# Lua 脚本总览：22 个主脚本 + 11 个内联脚本的原子性总表

> **Redis 考点**：Redis 单线程执行 Lua —— 把「多条命令 + 中间的条件判断」合并成一次不可被打断的原子执行，做 MULTI/EXEC 做不到的「带判断的临界区」。
> **来源**：`docs/01-redis-application-points.md` P0-1 / P1-1 / P1-2；`docs/02-cache-consistency-race.md` 第二、三节
> **完整路径**：`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（历史快照 234 行 / 7 个脚本；**当前 278 行 / 22 个 `DefaultRedisScript`，另有 11 个业务类内联脚本**）
> **代码版本**：当前工作区为 `RideAgentHub` / `com.fancy.rideagenthub`（基准路径 `src/main/java/com/fancy/rideagenthub/`）。本系列撰写于历史快照 `bdc40d7`（旧名 `TaxiAgent`、旧包 `com.fancy.taxiagent`，属已被重写合并的旧分支），文中的 `文件:行号` 与部分代码块是**改造前快照**，整体已漂移；学习时以「类名 + 方法名 + 章节名」定位当前源码，具体行为一律以当前代码为准。

---

## 【当前实现对照 / 本篇勘误】（2026-11-13 代码反向复核）

> 本篇 4.x 讲解、4.3 的 Lua 真值表示例与七章部分 `EVAL` 命令写于「7 个脚本」时代，**作为原理教材保留**；凡与 3.1 脚本总表及本节冲突，一律以本节与当前源码为准（`util/RedisScripts.java`）。

| # | 正文中的旧说法 | 当前代码事实 |
|---|---|---|
| 1 | 4.3 表格与代码块①以「项目里的写法，见 `GRAB_ORDER_ATOMIC:146`」讲 `status == false` | `status == false` 只出现在 **`SYNC_ORDER_STATUS:252`**；`GRAB_ORDER_ATOMIC:239,242` 用 `~= ARGV[1]` / `~= ARGV[2]`。旧代码块应标【改造前】 |
| 2 | 4.1「所有脚本都是常数条命令，没有循环、没有全量扫描」 | 22 个脚本中**至少 7 段含 `for` 循环**（`:60-62,80-83,90-92,120-122,129-131,185-187,204-206`），成本与批量成正比 |
| 3 | 4.3 以现在时描述 `RedisScripts.script(lua, Long.class)` 工厂与「7 个脚本每一个都显式返回整数」 | 工厂**已删除**（`:276-278` 只剩私有构造）；当前是 22 个静态 `DefaultRedisScript`。只保留「`Long` 型脚本须显式 `return` 整数」的原理 |
| 4 | #15 `CURRENT_HISTORY_VERSION`「清暂存」 | `DEL KEYS[2]` 清的是 `chat:history:{chatId}` **数据 key**（`:171,175`） |
| 5 | #17「版本不符就 `DEL` 数据，否则 `RPUSH` + `PEXPIRE`」 | 漏 `EXISTS KEYS[2] == 1` 条件（`:201-208`）：数据 key 不存在时即使版本匹配也不追加、不设 TTL |
| 6 | 「`APPEND_*` 系列同样用 `ARGV[1]` 毫秒 TTL」 | 只有 `APPEND_WITH_VERSION_BUMP` 在 `ARGV[1]`（`:188-189`）；`APPEND_HISTORY_IF_VERSION_MATCH`(`:207`) 与 `OVERWRITE_IF_VERSION_MATCH`(`:225`) 都在 **`ARGV[2]`** |
| 7 | #6 写成 `COUNT 200` | 脚本里是 `COUNT ARGV[4]`（`:77-78`），200 是 Java 侧 `SCAN_LIMIT` 实参 |
| 8 | 七-4 两条 `EVAL` 命令完全相同，第二条注释却在讲 `if redis.call('EXISTS', KEYS[1])`，且「0 是真值 → 永远不会返回 -1」结论写反 | 第二条应改为错误写法（去掉 `== 0`），注释改为「Lua 中 0 为真，即使 key 存在也会恒返回 -1」 |
| 9 | 已失效、删除合理的旧内容 | 「7 个脚本全部在 `RedisScripts.java`」→ 22 主 + 11 内联；「6 小时占位 TTL」→ 统一 **300 秒**（`OrderGrabService.java:30,80`）；两条旧 `EVAL`（4 ARGV、客户端传 now）→ 限流脚本已改 3 ARGV + Redis `TIME` |

### 【原文恢复】4.5.6 复杂度与 Lua 慢脚本边界

> 按 `git show HEAD:` 恢复本次修改删掉的原理推导，结论至今成立。

**`SLIDING_WINDOW_RATE_LIMIT` 的 `ZREMRANGEBYSCORE` 在成员极多时是 `O(log N + M)`**（`RedisScripts.java:266`）。按 `ChatGuardProperties` 默认值（`rateLimitMax=30`、`rateLimitWindowSeconds=60`）ZSet 成员数被封在阈值附近，不构成风险；但把阈值调到几千，这条命令的成本需要重新评估。

**由此引出 Lua 的底层边界**：Redis 单线程原子执行 Lua，脚本内任何慢命令都会**阻塞整个实例**的其他请求。因此带 `for` 循环的批量脚本必须限制批量大小 —— 本项目用 `CLAIM_DUE_ORDERS` 的 `LIMIT 0 ARGV[2]`（默认 100）、`SEARCH_ORDER_GEO_POOL` 的 `COUNT ARGV[4]`（200）、`claimBatch()` 的 `LIMIT 5` 三处共同设上限。这不是可选优化，而是把 Lua 用于生产的前提。

---

## 一、业务场景

`RedisScripts` 的类注释把它的存在理由写得很直白：

```java
凡是"先做 A 再做 B，且 A/B 之间不允许其他请求插队"的场景，都应该走这里。
```

**撰写本文时**符合这句话的地方一共 7 处。它们的共同形态是 **读-判-写**（read-check-write）：先把状态读出来，按状态决定要不要写、写什么，然后再写回去。拆成多条 Redis 命令时，三步之间就是并发窗口；而窗口一旦被并发请求踩中，症状通常不是"报错"而是"静默地少判了一次"：

下表是**改造前的 7 处**，编号沿用至今，方便与 4.5 节逐脚本讲解对照（4.5.7 的脚本已被删除，见该节的替换说明）：

| # | 拆开写会出的问题 | 本项目对应 | 本文档小节 |
|---|---|---|---|
| 1 | `HSET` 成功、`EXPIRE` 失败 → key 永久留存 | `ChatManager.lockChat`（→ [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)） | 4.5.1 |
| 2 | 锁超时后 `DEL` 误删他人的锁 | `TicketServiceImpl` 旧实现（→ [`13-distributed-lock.md`](13-distributed-lock.md)） | 4.5.2 |
| 3 | 数据已追加、版本号未递增 → 旧租约仍然有效 | `RedisMemory.append`（→ [`09-lease-token.md`](09-lease-token.md)） | 4.5.3 |
| 4 | 版本校验通过、替换之前被并发写插入 | `RedisMemory.overwriteIfLeaseValid` | 4.5.4 |
| 5 | 「订单可抢」与「司机空闲」两次独立查询双双通过 | `OrderGrabService.tryGrab`（→ [`14-grab-order-lua.md`](14-grab-order-lua.md)） | 4.5.5 |
| 6 | 都读到最后一次计数、都认为没超限 | `ChatRateLimiter.tryAcquire`（→ [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md)） | 4.5.6 |
| 7 | 心跳已删、位置还在 → 孤儿成员永不被清理 | `DriverGeoIndex.status`（→ [`15-nearby-order-geo.md`](15-nearby-order-geo.md)） | 4.5.7（脚本已删除） |

当时 7 个脚本全部定义在 `RedisScripts.java`，全部是 `public static final RedisScript<Long>`，通过 `stringRedisTemplate.execute(script, keys, args...)` 调用。

**当前代码（`a8f4e33` 之后）已经不是这个规模**：

- `util/RedisScripts.java` 有 **22** 个 `DefaultRedisScript`（278 行），其中 **18 个返回 `Long`、4 个返回 `List`**（`READ_DRIVER_LOCATION`、`SEARCH_ORDER_GEO_POOL`、`READ_TICKET_POOL`、`CLAIM_DUE_ORDERS`）；
- 另有 **11 段内联 Lua** 分散在 5 个业务类：`service/impl/TokenServiceImpl`(3)、`service/base/EmailCodeService`(4)、`service/base/OrderConfirmation`(2)、`service/impl/TicketServiceImpl`(1)、`agentbase/tool/OrderSearchTool`(1)；
- 全项目合计 **33 段 Lua**。之所以拆成「主流程集中 + 领域脚本内联」，是因为后来新增的脚本大多只服务一个业务类的私有状态机（会话版本、验证码、订单确认），放进公共类反而让 `KEYS/ARGV` 约定失去内聚性。

新增脚本按功能可分成六族，这也是当前代码里「读-判-写」的全部落点：

| 族 | 脚本 | 解决的竞态 |
|---|---|---|
| 司机位置（3） | `UPDATE_DRIVER_LOCATION` / `REMOVE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION` | 心跳、GEO 坐标与成员清理必须原子；时间源统一为 Redis `TIME` |
| 订单 GEO 池（3） | `INVALIDATE_ORDER_GEO_POOL` / `PUBLISH_ORDER_GEO_POOL` / `SEARCH_ORDER_GEO_POOL` | 整池失效换版本、过期锁持有者不能发布、读取时校验 `{version}:{count}` |
| 工单池（3） | `INVALIDATE_TICKET_POOLS` / `PUBLISH_TICKET_POOL` / `READ_TICKET_POOL` | 同上；空池也要能被区分于「数据丢失」 |
| 城市编码（2） | `INVALIDATE_CITY_CODE` / `CACHE_CITY_CODE_IF_VERSION_MATCH` | 版本租约 + 5 秒墓碑，阻止旧快照在墓碑过期后回填 |
| 聊天历史（5） | `CURRENT_HISTORY_VERSION` / `APPEND_WITH_VERSION_BUMP` / `APPEND_HISTORY_IF_VERSION_MATCH` / `OVERWRITE_IF_VERSION_MATCH` / `CLEAR_HISTORY_WITH_VERSION_BUMP` | Lease 版本租约；**5 处 `PERSIST`** 让版本 key 永不归零 |
| 队列与状态（2） | `CLAIM_DUE_ORDERS` / `SYNC_ORDER_STATUS` | 到期任务原子领取且防改期误删；订单状态单调推进，迟到旧状态不覆盖新状态 |

完整清单见 3.1 的 22 行表与 3.3 的内联脚本清单。

---

## 二、Redis 结构选型

脚本本身不选结构，但它操作的结构决定了脚本能做什么。7 个脚本触及的全部 key：

| Key | 结构 | TTL | 语义 | 涉及脚本 |
|---|---|---|---|---|
| `chat:info:{chatId}` | Hash | 60 分钟（`ChatManager.java:24`），可被 `persist` 去掉 | 会话锁 `locked` + 分类 `classification` + 订单槽位 + 各阶段令牌 | `HASH_SET_WITH_EXPIRE` |
| `order:lock:{orderId}` | String | 5-10 秒 | 订单状态机锁 / 缓存重建锁 | `RELEASE_LOCK_IF_MATCH` |
| `chat:history:version:{chatId}` | String | 与数据 key 同生命周期（`RedisMemory.java:174-176`） | Lease 版本号 | `APPEND_WITH_VERSION_BUMP`、`OVERWRITE_IF_VERSION_MATCH` |
| `chat:history:{chatId}` | List | 24 小时 / 30 分钟（`MessageMemory.java:18-19`） | 对话消息（L2） | `APPEND_WITH_VERSION_BUMP`、`OVERWRITE_IF_VERSION_MATCH` |
| `chat:history:staging:{chatId}:{token}` | List | 1 分钟（`RedisMemory.java:34`） | 回填暂存区 | `OVERWRITE_IF_VERSION_MATCH` |
| `order:status:{orderId}` | String | 2 小时（`OrderGrabService.java:63`） | 订单当前状态码 | `GRAB_ORDER_ATOMIC` |
| `driver:active:{driverId}` | String | 6 小时（`OrderGrabService.java:80`） | 司机占用的 orderId，`__IDLE__` 表示空闲 | `GRAB_ORDER_ATOMIC` |
| `chat:ratelimit:{userId}` | ZSet | 窗口长度（毫秒），每次调用刷新 | 滑动窗口内的调用时刻，score = epoch 毫秒 | `SLIDING_WINDOW_RATE_LIMIT` |
| `driver:online:beat` | Hash | **不设 TTL** | field = driverId，value = 最后心跳毫秒 | `CHECK_DRIVER_ONLINE` |
| `driver:geo:online` | GEO（底层 ZSet） | **不设 TTL** | 全体司机共享的位置池 | `CHECK_DRIVER_ONLINE` |

**最后两行是脚本存在的根本原因**：共享 key 不能设 TTL（一个人心跳会刷新所有人的过期时间），所以成员级的失效判定与清理只能下沉到脚本里，且必须原子完成 —— 见 4.5.7。

> 【上表的当前变化】这张表是改造前的 key/TTL 快照，结构选型推导仍然成立，但以下数值与语义已变，验证命令要按新值：
>
> | 项 | 改造前 | 当前实现 |
> |---|---|---|
> | `chat:history:version:{chatId}` | 与数据 key 同生命周期 | **`PERSIST` 永久保留（TTL `-1`）**，只有数据 key 带 24h / 30min TTL；避免数据过期后版本归零、旧租约「复活」覆盖新数据。见 [09 篇](09-lease-token.md) 4.8 |
> | `order:status:{orderId}` / `driver:active:{driverId}` | 2 小时 / 6 小时 | 统一 `CACHE_TTL = 5 分钟`（300 秒），写入不续期；抢单正确性由 DB 行锁 + 条件更新兜底 |
> | `chat:ratelimit:{userId}` | `now` 由应用传 `ARGV[1]` | 脚本内 `redis.replicate_commands()` + `redis.call('TIME')`，**ARGV 只剩 3 个**（window / limit / member），消除多实例时钟偏斜 |
> | `driver:online:beat` / `driver:geo:online` | 由 `CHECK_DRIVER_ONLINE` 惰性清理 | 两个 key 仍不设 key 级 TTL，但脚本已换成 `UPDATE_DRIVER_LOCATION` / `REMOVE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION` 三支；`READ_DRIVER_LOCATION` 一次读取即返回坐标或顺手 `HDEL`+`ZREM` 清掉孤儿成员，判定同样用 Redis `TIME` |
> | 新增 key | — | `amap:city_code:version:{name}`、`ticket:pool:version`、`order:geo:pool:version`、`chat:tool:v2:{chatId}:{callId}`、`auth:session-version:{userId}`、`request:rate:{摘要}`、`chat:execution:{chatId}`；其中版本 key 一律无 TTL |

---

## 三、代码落点

### 3.1 当前 22 个主脚本总表（`util/RedisScripts.java`）

> 定义位置一律按**常量名**定位；括号里的行号只是当前工作区的参考位置，仍会随提交漂移。返回 `List` 的 4 个脚本用 `DefaultRedisScript<List>` 声明，其余 18 个为 `Long`。

| # | 常量名（当前行） | 用途 / 关键命令 | 返回 | 对应笔记 |
|---|---|---|---|---|
| 1 | `UPDATE_DRIVER_LOCATION`(:10) | `TIME` 取毫秒；`refresh` 分支校验心跳与 `ZSCORE` 后才 `GEOADD`+`HSET`；返回 1/0 | Long | [16](16-driver-online-heartbeat.md) |
| 2 | `REMOVE_DRIVER_LOCATION`(:27) | `HDEL` + `ZREM` 一次完成主动下线 | Long | [16](16-driver-online-heartbeat.md) |
| 3 | `READ_DRIVER_LOCATION`(:34) | `TIME` + `HGET` + `GEOPOS`；有效返回坐标，无效则顺手 `HDEL`/`ZREM` 并返回空表 | **List** | [16](16-driver-online-heartbeat.md) |
| 4 | `INVALIDATE_ORDER_GEO_POOL`(:48) | 换版本令牌 + `DEL` ready/pool（整池失效） | Long | [15](15-nearby-order-geo.md) |
| 5 | `PUBLISH_ORDER_GEO_POOL`(:55) | 校验版本与**锁令牌**都匹配才 `DEL`、批量 `GEOADD`、`ZCARD`、`PEXPIRE`、写 `ready={version}:{count}` | Long | [15](15-nearby-order-geo.md) |
| 6 | `SEARCH_ORDER_GEO_POOL`(:70) | 校验 `ready` 后 **`GEORADIUS … km ASC COUNT 200 WITHDIST`**（兼容 Redis 5） | **List** | [15](15-nearby-order-geo.md) |
| 7 | `INVALIDATE_TICKET_POOLS`(:88) | 换 `ticket:pool:version` + 循环 `DEL` 全部状态池 | Long | [23](23-ticket-pool-zset.md) |
| 8 | `PUBLISH_TICKET_POOL`(:97) | 版本 + 锁令牌校验；`ZADD`、`ZCARD`、`SET ready={version}:{count} PX`、`PEXPIRE` | Long | [23](23-ticket-pool-zset.md) |
| 9 | `READ_TICKET_POOL`(:112) | 校验 ready；`ZREVRANGE` 返回 `{version, count, members…}` | **List** | [23](23-ticket-pool-zset.md) |
| 10 | `CLAIM_DUE_ORDERS`(:127) | `ZRANGEBYSCORE 0..now LIMIT 0 batch` + 逐条 `ZREM`，一次 EVAL 原子领取，防改期误删 | **List** | [17](17-order-timeout-delay-queue.md) |
| 11 | `HASH_SET_WITH_EXPIRE`(:136) | `HSET` 后 **`return redis.call('EXPIRE', KEYS[1], ARGV[3])`**（返回 1/0，不是恒定 1） | Long | [12](12-chat-lock-atomic.md) |
| 12 | `RELEASE_LOCK_IF_MATCH`(:142) | `GET==ARGV[1]` 才 `DEL`，令牌校验释放 | Long | [13](13-distributed-lock.md) |
| 13 | `INVALIDATE_CITY_CODE`(:150) | `INCR` 版本 + `SET` 5 秒墓碑 `EX`，返回新版本号 | Long | [08](08-city-code-tombstone.md) |
| 14 | `CACHE_CITY_CODE_IF_VERSION_MATCH`(:157) | 版本不符返回 0；否则 `SET … NX EX` 成功返回 1 | Long | [08](08-city-code-tombstone.md) |
| 15 | `CURRENT_HISTORY_VERSION`(:169) | 取租约：按 `EXISTS`/`PTTL` 分支 `INCR`、清暂存、**`PERSIST` 版本 key** 后 `GET` | Long | [09](09-lease-token.md) |
| 16 | `APPEND_WITH_VERSION_BUMP`(:182) | `INCR` + **`PERSIST`** + `RPUSH`；`ARGV[1] > 0` 时 `PEXPIRE` 数据，否则 **`PERSIST` 数据** | Long | [09](09-lease-token.md) |
| 17 | `APPEND_HISTORY_IF_VERSION_MATCH`(:197) | **写路径**：版本不符就 `DEL` 数据（使在途租约失效），否则 `RPUSH` + `PEXPIRE`；`PERSIST` 版本 | Long | [09](09-lease-token.md)、[19](19-multi-level-cache.md) |
| 18 | `OVERWRITE_IF_VERSION_MATCH`(:213) | 回填路径：暂存不存在 `-1`；版本不符 `DEL` 暂存返回 0；否则 `INCR` + `PERSIST` + `RENAME` + `PEXPIRE` | Long | [09](09-lease-token.md) |
| 19 | `CLEAR_HISTORY_WITH_VERSION_BUMP`(:230) | `INCR` 版本 + `PERSIST` + `DEL` 数据（只删数据、递增版本，一条脚本原子完成） | Long | [09](09-lease-token.md) |
| 20 | `GRAB_ORDER_ATOMIC`(:238) | 订单状态不符 `-1`；司机占位不符 `-2`；通过则 `SET … EX ARGV[4]` 一次占位 | Long | [14](14-grab-order-lua.md) |
| 21 | `SYNC_ORDER_STATUS`(:250) | 单调推进：`current==false or tonumber(current)==nil or current<=ARGV[1]` 才 `SET … EX`，否则返回 0 | Long | [14](14-grab-order-lua.md) |
| 22 | `SLIDING_WINDOW_RATE_LIMIT`(:260) | `redis.replicate_commands()` + **`TIME`**；3 个 ARGV（window/limit/member）；`ZREMRANGEBYSCORE`→`ZCARD`→`ZADD`→`PEXPIRE` | Long | [22](22-sliding-window-rate-limit.md) |

三个容易数错/看错的细节（面试常问）：

- **`PERSIST` 出现在 5 个脚本、6 处调用**（#15、#16 两处、#17、#18、#19）：版本 key 永久保留是 [09 篇](09-lease-token.md) 4.8 结论反转的代码依据；**数据 key** 才带 TTL。
- **用 Redis `TIME` 的是 #1、#3、#22 三支**：把「现在几点」交给 Redis 服务器裁定，多实例应用时钟偏斜不再影响窗口/心跳判定。
- **`false` 与 `nil` 的比较只在 `SYNC_ORDER_STATUS`（#21）里出现**；旧文档把 `GRAB_ORDER_ATOMIC` 也说成与 `false` 比较是记错了。4.3 节的 `false`/`nil` 原理讲解仍然有效，保留。

### 3.2 调用点（按「类名 + 方法名」定位）

| 脚本 / 脚本族 | 定义与调用位置 | 上层业务入口 |
|---|---|---|
| `HASH_SET_WITH_EXPIRE` | `agentbase/chatinfo/ChatManager.lockChat` | `ChatController` 的 `POST /chat/v2/lock/{id}`、`ChatStatusTool`、`OrderTool.createOrder`（创建后锁会话）；**`MessageMemory` 全文没有 `lockChat`**，旧表把它列为入口是错的 |
| `RELEASE_LOCK_IF_MATCH` | `service/base/RedisLock.unlock`；`OrderGrabService` 的回滚路径 | 工单统计、GEO 池、工单池索引重建 |
| 聊天历史五段 | `agentbase/memory/RedisMemory` 的 `currentVersion` / `appendIfVersionMatch` / `append` / `overwriteIfLeaseValid` / `clear` ← `MessageMemory.save` / `fillRedisWithLease` / `clearChat` | `ChatServiceImpl` 每轮对话 |
| `GRAB_ORDER_ATOMIC` / `SYNC_ORDER_STATUS` | `service/base/OrderGrabService.tryGrab` / `syncOrderStatus` | `RideOrderServiceImpl.driverAcceptOrder` 等 7 个状态变更 |
| `SLIDING_WINDOW_RATE_LIMIT` | `service/base/ChatRateLimiter.tryAcquire` 与 `interceptor/RequestRateLimitInterceptor` | `ChatServiceImpl.canStartChat()`（30/60）、`/auth/**` 与 `/order/estimate`（120/60） |
| `CLAIM_DUE_ORDERS` | `service/base/OrderDelayQueue.claimDue` | `OrderTimeoutJob` 轮询 + DB 补偿扫描 |
| GEO 池三支 / 工单池三支 | `service/base/OrderGeoPool` / `service/base/TicketPoolIndex` | 司机附近订单分页、管理端工单池分页 |
| 城市编码两支 | `agentbase/amap/util/citycode/CityCodeUtil`（`invalidate` / `getCityCode`） | 地址解析工具（**写路径未接入**，见 [08 篇](08-city-code-tombstone.md)） |
| 司机位置三支 | `service/base/DriverGeoIndex` | `DriverLocationServiceImpl`、`RideOrderController` 的四个司机端点 |

### 3.3 11 个内联脚本（不在 `RedisScripts.java`）

| 类（`src/main/java/com/fancy/rideagenthub/` 下） | 数量 | 脚本 / 用途 |
|---|---|---|
| `service/impl/TokenServiceImpl.java` | 3 | `SAVE`（校验 `auth:session-version` 后原子写 token + SADD 索引 + EXPIRE）、`REVOKE`（`INCR` 版本 + 删全部令牌 + 删索引）、`REMOVE`（`DEL` + `SREM`）—— 见 [01 篇](01-shared-session-token.md) |
| `service/base/EmailCodeService.java` | 4 | `ISSUE`（签发验证码与冷却）、`VERIFY`（5 次尝试上限 + reserve）、`CLEANUP`（按 `issueId` 归属清理）、`COMPLETE`（事务提交后消费 / 回滚释放）—— 见 [02 篇](02-email-code-cooldown.md) |
| `service/base/OrderConfirmation.java` | 2 | `CONFIRM`（校验 `confirmationId` + 15 分钟报价有效期 + `resuming` 幂等重放 + 签发 `ReadyForCreate`）、`CONSUME`（一次性 `HDEL` 创建权限）—— 见 [11 篇](11-two-phase-confirm-token.md)、[25 篇](25-agent-hitl-state.md) |
| `service/impl/TicketServiceImpl.java` | 1 | `TICKET_SEQUENCE`（`INCR` 且仅当 `TTL < 0` 才 `EXPIRE 172800`，避免每次重置过期）—— 见 [18 篇](18-global-unique-id-incr.md) |
| `agentbase/tool/OrderSearchTool.java` | 1 | `CANCEL`（校验 `readyForCancel` 绑 orderId + 300000ms 时效 + `HDEL` 原子消费）—— 见 [11 篇](11-two-phase-confirm-token.md) |

> **排障经验**：`TicketServiceImpl` 与 `OrderSearchTool` 是用完全限定名 `new org.springframework.data.redis.core.script.DefaultRedisScript<>(...)` 构造的，简单 grep `new DefaultRedisScript` 只会命中 4 个文件（22+3+2+4=31），很容易把 33 段误数成 31 段。数脚本时要同时搜 `DefaultRedisScript<>` 与全限定名。

---

## 四、实现拆解

### 4.1 为什么「单线程 + Lua」就等于原子

Redis 处理命令是单线程的（指命令执行阶段），而 `EVAL` 在执行时做了一件特殊的事：**它把整个脚本当成一条命令来对待**，脚本执行期间不会去处理其他客户端的命令。所以

```
GET k  → 判断 → SET k
```

写成脚本之后，这三步之间的时间对 Redis 的其他客户端来说是"不存在的" —— 没有别的命令能插进来，也就不存在「T1 判断完、T2 插入、T1 才写」这种交错。

两个必须说清的边界：

1. **原子性 ≠ 事务的回滚能力**。Lua 脚本执行到一半报错时，**已经执行的写命令不会回滚**（Redis 不是关系型数据库）。脚本能保证的是"不被插队"，不是"要么全做要么全不做"。所以脚本里要避免"先写一半再抛错"的逻辑；本项目的 7 个脚本都写成「先判断、后写入」，把可能失败的判断放在写之前。
2. **原子性 ≠ 无阻塞**。脚本执行期间其他请求全部排队。所以脚本必须短小 —— 本项目所有脚本都是常数条命令，没有循环 `ZRANGE`、没有全量扫描。`OVERWRITE_IF_VERSION_MATCH` 还特意绕了一次暂存 key，就是为了**不把可能很大的消息列表塞进脚本参数**（`RedisScripts.java:93-94`）。

### 4.2 `KEYS` 与 `ARGV` 的使用约定

```java
stringRedisTemplate.execute(RedisScripts.XXX, List.of(key), arg1, arg2);   // RedisScripts.java:14
```

- **`KEYS` 传 key 名，`ARGV` 传值** —— 这是硬约定，不是风格问题。Redis Cluster 要靠 `KEYS` 声明才能算出脚本该路由到哪个槽；把 key 混进 `ARGV` 在单机没事，一上集群就会直接报错。项目里所有调用点（`ChatManager.java:33`、`RedisMemory.java:111-112`、`OrderGrabService.java:144` …）都严格区分了两者。
- **`ARGV` 全是字符串**。`EVAL` 传来的参数在 Lua 里是 string 类型，所以要参与算术必须先 `tonumber()`：`SLIDING_WINDOW_RATE_LIMIT:178-180`、`CHECK_DRIVER_ONLINE:214`、`APPEND_WITH_VERSION_BUMP:77` 都做了转换，`OVERWRITE_IF_VERSION_MATCH:111` 则干脆按字符串比较（`current ~= ARGV[1]`）—— 版本号只用来比相等，不做运算，比字符串是安全的。
- **变长参数用 `#ARGV` 遍历**。`APPEND_WITH_VERSION_BUMP:80-82` 用 `for i = 2, #ARGV` 一次脚本追加任意条消息，避免为每条消息发一次 `RPUSH`。
- **Java 侧的 `List.of(key)` 与 `args.toArray()`** 分别对应 `KEYS` 与 `ARGV`；`RedisScripts` 类注释（`:6-16`）给的就是这个模板。

### 4.3 `redis.call` 返回值：`false` 与 `nil` 的坑

这是写 Lua 脚本时最容易静默出错的地方。Redis 与 Lua 的类型转换规则里，**nil bulk reply / nil multi bulk reply 都会被转成 Lua 的 `false`**（不是 `nil`）：

| Lua 里写 | 结果 | 说明 |
|---|---|---|
| `redis.call('GET', k)`，key 不存在 | 返回 `false` | **不是 `nil`** |
| `if v == nil then` | **永远不成立** | 拿不到"key 不存在"的判断，是最常见的静默 bug |
| `if v == false then` | 正确 | 项目里的写法，见 `GRAB_ORDER_ATOMIC:146`、`OVERWRITE_IF_VERSION_MATCH:108` |
| `if not v then` | 正确 | `false` 与 `nil` 在 Lua 里都是假值；`CHECK_DRIVER_ONLINE:212-213` 用 `if beat then` 同理 |
| `if redis.call('EXISTS', k) then` | **永远成立** | `EXISTS` 返回整数，而 **Lua 里 `0` 是真值** |
| `if redis.call('EXISTS', k) == 0 then` | 正确 | 项目里的写法，见 `OVERWRITE_IF_VERSION_MATCH:104` |
| `tonumber(beat)` 失败 | 返回 `nil` | 所以要先判 `beatNum and ...`，见 `CHECK_DRIVER_ONLINE:214-215` |

三条实证：

```lua
-- ① 显式与 false 比较，而不是与 nil 比较（GRAB_ORDER_ATOMIC:145-148）
local status = redis.call('GET', KEYS[1])
if status == false or status ~= ARGV[1] then
    return -1
end

-- ② 缺失值先归一成一个可比较的值，再走统一的相等判断（OVERWRITE_IF_VERSION_MATCH:107-111）
local current = redis.call('GET', KEYS[3])
if current == false then
    current = '0'
end
if current ~= ARGV[1] then ... end

-- ③ 非数字的字段值也要防（CHECK_DRIVER_ONLINE:211-218）
local beat = redis.call('HGET', KEYS[1], ARGV[1])
local online = false
if beat then
    local beatNum = tonumber(beat)
    if beatNum and (tonumber(ARGV[2]) - beatNum) <= tonumber(ARGV[3]) then
        online = true
    end
end
```

> Java 侧还有一个连带后果：`RedisScripts.script(lua, Long.class)`（`:227-232`）声明了返回类型是 `Long`。如果脚本返回 Lua 的 `false`，Spring Data Redis 的类型转换会失败（返回 `null` 或抛异常）。所以 7 个脚本**每一个都显式 `return` 一个整数**，没有一个靠"隐式返回空"。调用方要相应地把 `null` 当成"脚本没返回"来降级：`OrderGrabService.java:150-154`、`DriverGeoIndex.java:140-147`、`ChatRateLimiter.java:68-70` 都做了这件事。

### 4.4 为什么用 Lua 而不是 `MULTI`/`EXEC` 事务

`MULTI`/`EXEC` 也是"一次执行多条命令、中间不被打断"，但它是**盲发**的：命令在 `MULTI` 之后就被逐条发给服务端排队，客户端根本没机会读取中间结果。所以事务里做不到：

```lua
-- 事务做不到这件事：先读，再根据读到的值决定要不要写
local used = redis.call('ZCARD', KEYS[1])
if used >= limit then
    return -1          -- 条件分支：超限就不写了
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
```

| 维度 | `MULTI`/`EXEC` | Lua 脚本 |
|---|---|---|
| 不被打断 | ✅ | ✅ |
| 中间做条件判断 | ❌ 命令已全部入队 | ✅ 读的结果能参与分支 |
| 读-判-写 | ❌ 只能用 `WATCH` 做乐观重试 | ✅ 一步到位 |
| 中途失败回滚 | ❌ 一样不回滚 | ❌ 一样不回滚 |
| 网络往返 | 1 次（`EXEC`） | 1 次（`EVALSHA`） |
| 集群要求 | 同槽 | `KEYS` 声明同槽 |

项目里必须用 Lua 的典型就是 `SLIDING_WINDOW_RATE_LIMIT`：`WATCH` 版本需要「读 ZCARD → WATCH → 重试」，高并发下重试风暴本身就是问题。抢单也是 —— `GRAB_ORDER_ATOMIC` 的判断结果（`-1` / `-2` / `1`）就是业务返回值，事务给不了这个。

`WATCH` 的做法在下单等场景仍有价值，但"热点争抢"场景下 Lua 是唯一不引入重试风暴的选择。

### 4.5 逐个看：改造前的 7 个脚本 + 当前实现对照

> 下面 4.5.1~4.5.7 的 Lua 代码块与「定义行号」都是**改造前快照**，保留它们是为了讲清「为什么要原子化」。每节开头用【当前实现】框给出今天的真实脚本与参数语义；4.5.7 的脚本已整体删除，由三支新脚本替代。当前完整清单见 3.1。

#### 4.5.1 `HASH_SET_WITH_EXPIRE`（当前 `RedisScripts.java:136-139`）

> 【当前实现】脚本只有两行，`return` 的是 `EXPIRE` 的结果而**不是恒定 `1`**：
>
> ```lua
> redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
> return redis.call('EXPIRE', KEYS[1], ARGV[3])
> ```
>
> `ARGV[3]` 仍是**秒**（`ChatManager` 传 60 分钟的 `toSeconds()`）。返回值 `1` 表示 HSET 与过期都生效，`0` 表示 key 不存在、`EXPIRE` 失败 —— 这一点修正了旧文档 6.5「恒定返回 1、判断永远为真」的表述：`isLocked` 现在是两分支判断，且写 `locked` 的位置只有两处。下方代码块是改造前的 `return 1` 版本，作对照。

```lua
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
```

原实现是两条独立命令（`HSET` + `EXPIRE`），`HSET` 成功而 `EXPIRE` 失败（网络抖动、进程崩溃）时 key 永久留存。对会话锁而言这意味着**会话被永久锁死**：`isLocked` 永远返回 `true`，后续所有请求都被拦下。

注意脚本用的是 `EXPIRE`（秒）而不是 `PEXPIRE`（毫秒）：`ARGV[3]` 由调用方按秒传（`ChatManager.java:36` 传 `expireLock.toSeconds()`）。见 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)。

#### 4.5.2 `RELEASE_LOCK_IF_MATCH`（`:53-59`）

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

「读回 → 比对 → 删除」三步合一。这里的 `ARGV[1]` 是加锁时写入的 `UUID`（`RedisLock.java:53-54`），也就是**锁的持有者身份令牌**。没有这个令牌，就无法区分"锁还是我的"与"锁已经被别人重新拿到了"。

`DEL` 的返回值（实际删除的 key 数）被直接当作脚本返回值，所以 `1`/`0` 同时也是"删没删到"的答案。见 [`13-distributed-lock.md`](13-distributed-lock.md)。

#### 4.5.3 `APPEND_WITH_VERSION_BUMP`（当前 `:182-194`）

> 【当前实现】先 `INCR` 再 `RPUSH` 的顺序没变（安全方向偏差的原理见下方原文），但**过期语义完全反转**：
>
> ```lua
> local version = redis.call('INCR', KEYS[1])
> redis.call('PERSIST', KEYS[1])                 -- 版本 key 永久保留
> for i = 2, #ARGV do redis.call('RPUSH', KEYS[2], ARGV[i]) end
> if tonumber(ARGV[1]) > 0 then redis.call('PEXPIRE', KEYS[2], ARGV[1])
> else redis.call('PERSIST', KEYS[2]) end
> return version
> ```
>
> **`ARGV[1]` 现在是「数据 key 的 TTL 毫秒」，不是「版本 key 的过期秒数」**：版本 key 由 `PERSIST` 永久保留，`ARGV[1] <= 0` 时数据 key 也 `PERSIST`（测试/暂存路径），正常路径用 `PEXPIRE` 给数据设 24h/30min。原因见 [09 篇](09-lease-token.md) 4.8：版本 key 若随数据过期归零，旧租约会「复活」。
>
> 还要区分**写路径与回填路径**：生产 `MessageMemory.save` 走的是 `RedisMemory.appendIfVersionMatch` → `APPEND_HISTORY_IF_VERSION_MATCH`（版本不符就 `DEL` 数据，使在途租约失效），本脚本主要在回填/测试路径使用。旧文档把两者混讲，读代码时容易找不到调用方。

```lua
local version = redis.call('INCR', KEYS[1])
if tonumber(ARGV[1]) > 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
for i = 2, #ARGV do
    redis.call('RPUSH', KEYS[2], ARGV[i])
end
return version
```

**顺序很关键：先 `INCR` 再 `RPUSH`。** 如果反过来（先追加数据、后递增版本），在两者之间就存在一个窗口：数据已经多了一条，而版本号还是旧的 —— 一个手持旧租约的读线程此时仍能通过版本校验，用它的旧快照把刚追加的消息整段覆盖掉。先递增则相反：版本号只会"提前"变化，导致在途租约被**过早**判为失效（结果是放弃回填、下次再查一次 DB），这是安全方向的偏差。

`ARGV[1] <= 0` 表示版本 key 不设过期。之所以要能关掉：版本号与数据 key 必须同生命周期（`RedisMemory.java:174-176`），版本 key 若先过期会归零，在途租约就会被误判为有效。见 [`09-lease-token.md`](09-lease-token.md)。

#### 4.5.4 `OVERWRITE_IF_VERSION_MATCH`（当前 `:213-227`）

> 【当前实现】三态返回值（`-1` 暂存不存在 / `0` 租约失效 / `1` 成功）保持不变，但**成功分支会 `INCR` 版本并 `PERSIST` 版本 key**，且有 **2 个 ARGV**（租约版本号 + 数据 TTL 毫秒）：
>
> - 回填成功后 `INCR` 版本，是为了让此前所有在途租约立即失效 —— 否则「刚回填完又被另一个旧快照覆盖」；
> - 暂存 key 不再靠两条独立命令写：Java 侧用 `SessionCallback` / `MULTI` 把「写暂存 + 版本读取 + 执行脚本」收进一次交互，减少中间状态暴露；
> - `RENAME` 之后对数据 key `PEXPIRE`，版本 key 一律 `PERSIST`。
>
> 下方旧 Lua 的 `ARGV[1]` 只有租约版本号，属于改造前版本；`false` / `nil` 的判定陷阱（4.3 节）依然成立。细节见 [09 篇](09-lease-token.md) 4.4/4.5。

```lua
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
local current = redis.call('GET', KEYS[3])
if current == false then
    current = '0'
end
if current ~= ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 0
end
redis.call('DEL', KEYS[2])
redis.call('RENAME', KEYS[1], KEYS[2])
return 1
```

「校验 + 替换」合一。之所以绕一次暂存 key（`KEYS[1]`）而不是把数据当参数传进来：回填的数据可能很大（整段对话历史），不适合整体作为 `ARGV` 传输（`RedisScripts.java:93-94`）。

三个返回值都有明确语义，且都对应调用方的不同处置：`-1`（暂存没了）与 `0`（租约失效）在 `RedisMemory.java:151-156` 走同一条"放弃回填"分支并打 debug 日志。租约失效时**主动删除暂存 key**，避免残留数据自然过期前的垃圾占用。

#### 4.5.5 `GRAB_ORDER_ATOMIC`（`:144-155`）

```lua
local status = redis.call('GET', KEYS[1])
if status == false or status ~= ARGV[1] then
    return -1
end
local active = redis.call('GET', KEYS[2])
if active == false or active ~= ARGV[2] then
    return -2
end
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
return 1
```

两处要点：

1. `active ~= ARGV[2]` 而不是 `active == false` —— 只有**明确等于空闲哨兵**才放行。key 缺失（状态未知）走 `-2` 拒绝，而不是被当成空闲放过去。
2. 判断通过后**立刻 `SET` 占位** —— 「检查空闲」与「占住司机」是同一步，否则两个请求仍可同时通过检查、再先后占位。

脚本内直接用 `SET ... EX` 而不是 `SET` + `EXPIRE`，与 4.5.1 是同一个理由。完整业务链路见 [`14-grab-order-lua.md`](14-grab-order-lua.md)。

#### 4.5.6 `SLIDING_WINDOW_RATE_LIMIT`（当前 `:260-274`）

> 【当前实现】时间源从「应用传 `now`」改为 **Redis 服务器 `TIME`**，因此 `ARGV` 由 4 个减为 **3 个**：
>
> ```lua
> redis.replicate_commands()
> local t = redis.call('TIME')
> local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
> -- ARGV[1]=window(ms)  ARGV[2]=limit  ARGV[3]=唯一 member
> redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - tonumber(ARGV[1]))
> ...
> ```
>
> `redis.replicate_commands()` 是让脚本内使用 `TIME` 等**非确定性命令**在 Redis 5+ 的复制/持久化里合法的关键；去掉它，主从复制可能因为「主库与从库各自取时间」产生不一致。这是旧文档「客户端时钟偏斜」坑的**最终修法** —— 原 6.1 的原理讲解（多实例应用时钟不同步会让窗口边界抖动）仍值得读，但结论应改为 ✅ 已修复。下方 Lua 与「4 个 ARGV」是改造前版本；另一套按来源地址摘要的 120/60 限流复用同一脚本，见 [22 篇](22-sliding-window-rate-limit.md) 4.7。

```lua
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local used = redis.call('ZCARD', KEYS[1])
if used >= limit then
    return -1
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return limit - used - 1
```

「剔除过期记录 → 计数 → 判断 → 写入 → 续期」五步合一。

- **为什么不用 `INCR` + `EXPIRE` 的固定窗口**：固定窗口的两端是硬边界，跨边界时上一窗口尾部与下一窗口头部可以叠加出 **2 倍阈值**的瞬时流量（`RedisScripts.java:163-164`）。
- **`ARGV[4]` 的 member 必须唯一**（调用方传 `UUID`，`ChatRateLimiter.java:67`，注释在 `:65-66`）：同一毫秒内的两次调用如果用了同一个 member，`ZADD` 会去重成一条，限流直接失效。这是个很容易被忽略的坑。
- **`PEXPIRE` 每次调用都重设**，所以 key 的存活期是"最后一次调用 + 一个窗口"。这里不构成"共享 key 无法整体过期"的问题（对比 `driver:geo:online`），因为窗口内的陈旧记录由 `ZREMRANGEBYSCORE` 逐成员清掉了，TTL 只是兜底。
- 返回值可以是 `0`（放行，但正好用满），所以调用方判的是 `remaining >= 0` 而不是 `> 0`（`ChatRateLimiter.java:71`）。见 [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md)。

#### 4.5.7 从 `CHECK_DRIVER_ONLINE` 到三支司机位置脚本（当前 `RedisScripts.java:10-45`）

> 【当前实现】`CHECK_DRIVER_ONLINE` **已删除**（全库 0 命中，旧 4.5.7 整节与「7 个脚本」表述一并作废）。旧脚本把「判定在线 + 清理孤儿成员」塞在一个读接口里，且时间由应用用 `ARGV[2]` 传入；现在职责拆成三支脚本，时间源统一为 Redis `TIME`：
>
> | 脚本 | 做什么 | 为什么必须原子 |
> |---|---|---|
> | `UPDATE_DRIVER_LOCATION` | `refresh` 分支先校验心跳 Hash 与 `ZSCORE`（**只刷新仍在线的司机**，不会把已下线司机复活），再 `GEOADD` 写坐标 + `HSET` 写心跳 | 否则「写坐标」与「写心跳」之间失败会留下无心跳的坐标 |
> | `REMOVE_DRIVER_LOCATION` | `HDEL` + `ZREM` 一次完成主动下线 | 防止「心跳删了、位置还在」的孤儿成员 |
> | `READ_DRIVER_LOCATION` | 用 `TIME` 判定 60 秒心跳；有效则一次返回 `GEOPOS` 坐标，无效则顺手 `HDEL`+`ZREM` 并返回空 `List` | 「判定 + 惰性清理」必须同一次 EVAL，否则残留永久存在 |
>
> 返回类型也随之变化：`READ_DRIVER_LOCATION` 是 4 个 `DefaultRedisScript<List>` 之一，调用方 `DriverGeoIndex.location(driverId)` 一次拿到 `Location(status, point)`；`UNAVAILABLE`（脚本返回 null / 连接异常）现在由 `DriverLocationServiceImpl` 抛 **503**，而不是旧版「降级到 DB」。见 [16 篇](16-driver-online-heartbeat.md)。

下面是**改造前**的 `CHECK_DRIVER_ONLINE` 代码块，保留作对照（它的推导至今成立）：

```lua
local beat = redis.call('HGET', KEYS[1], ARGV[1])
local online = false
if beat then
    local beatNum = tonumber(beat)
    if beatNum and (tonumber(ARGV[2]) - beatNum) <= tonumber(ARGV[3]) then
        online = true
    end
end
if not online then
    redis.call('HDEL', KEYS[1], ARGV[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    return 0
end
return 1
```

这是"共享 key 不能设 TTL"这一约束直接逼出来的脚本。

`driver:geo:online` 是全体司机共用的 GEO key，`EXPIRE` 只能作用在 key 上 —— 一旦设置，任何一个司机的心跳都会刷新整个 key 的 TTL，于是**只要池子里还有一个人在心跳，离线司机的成员就永远不会被清除**，池子只增不减。所以成员级失效判定只能另存一份心跳时间戳（`driver:online:beat`），读取时逐成员比对。

于是「判定离线」与「清理」必须原子：拆成两次调用中间失败，会留下"心跳已删、位置还在"的孤儿成员 —— 而该 GEO key **没有 TTL 兜底**，这份残留永久存在，一个早已离线的司机永远留在在线池里，被派单派到天涯海角。

三态返回值的处理当年值得对照：脚本 `1` → `ONLINE`，`0` → `OFFLINE`，**脚本返回 `null`（连接中断）→ `UNAVAILABLE`**，交给上层降级而不是当成"离线"。当前实现把这个判断收敛进 `DriverGeoIndex.location()`，并把 `UNAVAILABLE` 映射为 503。见 [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md)、[`15-nearby-order-geo.md`](15-nearby-order-geo.md)。

### 4.6 脚本缓存与 `DefaultRedisScript`（当前构造方式）

旧的 `script(lua, Long.class)` 工厂方法、无参构造 + `setScriptText` + `setResultType` 三件套**都已删除**。现在每个常量都是直接构造：

```java
// util/RedisScripts.java:136-139
public static final DefaultRedisScript<Long> HASH_SET_WITH_EXPIRE = new DefaultRedisScript<>("""
        redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
        return redis.call('EXPIRE', KEYS[1], ARGV[3])
        """, Long.class);
```

四个设计点（按当前代码更新）：

1. **`public static final` + 私有构造**：脚本对象全局只创建一次，类尾只有 `private RedisScripts() {}`；Java 文本块（`"""`）让 Lua 源码在 Java 里保持原格式，可读性接近 `.lua` 文件。
2. **构造器中直接传入脚本文本与返回类型**：内容编译进 class 文件，不依赖外部 `.lua` 资源；少一次 `setScriptText` 的调用，也避免「忘了调 setter 导致空脚本」这类启动期才暴露的错误。
3. **18 个 `Long` + 4 个 `List`**：不再是「7 个全是 Long」。`List` 的四支（`READ_DRIVER_LOCATION`、`SEARCH_ORDER_GEO_POOL`、`READ_TICKET_POOL`、`CLAIM_DUE_ORDERS`）需要把「版本号 + 多个成员」一起返回，`DefaultRedisScript<List>` 配合 `StringRedisTemplate.execute` 得到 `List<Object>`；`Long` 的用于返回码/版本号。4.3 节「脚本必须显式返回整数」的原理仍适用于 `Long` 型脚本。
4. **底层走 `EVALSHA`**：`DefaultScriptExecutor` 先从脚本内容算 SHA1 并尝试 `EVALSHA`，Redis 未缓存该脚本时（`NOSCRIPT`）自动回退到 `EVAL` 并缓存。高频路径（限流、抢单预检）传输的是 40 字节 SHA1 而不是整段 Lua；`SCRIPT FLUSH` / `DEBUG RELOAD` 之后首批请求会走一次回退，属正常现象。

> **排障补充**：`TicketServiceImpl` 与 `OrderSearchTool` 用完全限定名 `new org.springframework.data.redis.core.script.DefaultRedisScript<>(...)` 声明内联脚本，所以按 `new DefaultRedisScript` 精确搜索会**漏计 2 个**。统计脚本总数时要同时搜 `DefaultRedisScript<>` 与全限定名；这也是「22 + 11 = 33」容易被数成 31 的原因（见 3.3）。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 主流程脚本集中在 `RedisScripts` 一个类，领域状态机脚本内联到业务类 | 全部集中 / 全部就地拼 | 当前 22 个主脚本能被一眼看全（本文档就是它的目录）；会话版本、验证码、订单确认这 11 段只服务一个类，内联反而让 `KEYS`/`ARGV` 约定与状态机内聚 |
| `Long` 返回整数码，需要多值时用 `List` | 全部返字符串 / 返 JSON | 18 个 `Long` 用整数码让 Java 侧直接判分支；4 个 `List` 用于「版本 + 成员列表」这类复合返回，避免把结果序列化成字符串再拆 |
| 脚本内 `SET ... EX` / `HSET`+`EXPIRE` 合并 | 脚本外补 `EXPIRE` | 写与设过期必须同生共死，见 4.5.1 |
| `OVERWRITE_IF_VERSION_MATCH` 绕暂存 key | 把数据整体当 `ARGV` 传 | 大 value 不适合走脚本参数；也避免 `EVALSHA` 的请求体随数据增长 |
| `APPEND_WITH_VERSION_BUMP` 先 `INCR` 后 `RPUSH` | 先 `RPUSH` 后 `INCR` | 出错时宁可"租约过早失效"（放弃回填），不能"租约过晚失效"（覆盖新数据），见 4.5.3 |
| 校验失败时**不抛错**，返回码上浮 | 脚本里 `error()` | 脚本报错会污染"是否执行成功"的判断；用返回码让调用方显式处理每一种拒绝原因 |
| 共享 key 一律不设 TTL，成员级失效下沉到脚本 | 给共享 key 设 TTL | 共享 key 的 TTL 会被任何一次成员写入刷新，"最后一个活跃的人活着，全池永不过期"，见 4.5.7（脚本已换，推导不变） |
| **版本 key 用 `PERSIST` 永久保留** | 与数据 key 同 TTL（旧设计） | 数据过期/清空后版本若归零，旧租约会「复活」并覆盖新数据；当前 5 个脚本 6 处 `PERSIST`，见 [09 篇](09-lease-token.md) 4.8 |
| **时间源放 Redis `TIME`** | 应用传 `System.currentTimeMillis()` | 多实例应用时钟偏斜会让窗口/心跳判定抖动，且复制语义不安全；三支脚本改用 `TIME`，限流脚本加 `redis.replicate_commands()` |

**一处刻意的"不统一"**：`HASH_SET_WITH_EXPIRE` 用的是 `EXPIRE`（秒），`SLIDING_WINDOW_RATE_LIMIT` 用 `PEXPIRE`（毫秒）。这不是疏漏 —— 会话锁的粒度是分钟级（60 分钟），窗口限流的粒度是秒级甚至更短，用毫秒才能表达亚秒窗口。聊天历史的 `APPEND_*` 系列同样用 `PEXPIRE` 接受**毫秒** TTL（`ARGV[1]`）。

---

## 六、边界与已知问题

1. **原子性只覆盖单次脚本，不覆盖"脚本 + 业务代码"**。`GRAB_ORDER_ATOMIC` 之后还有 DB 乐观锁和失败回滚，`OVERWRITE_IF_VERSION_MATCH` 之前还有一次 `rightPushAll` 写暂存 key（`RedisMemory.java:140-141`）。这些跨步骤的窗口是存在的，只是被设计成"出偏差时结果偏保守"。

2. **可恢复会话的 `expire` 仍是独立命令**（`ChatManager.getRestorableChatId(String userId)` 读路径）。当前方法先按 DB 做归属过滤、再读 Redis Hash 找可恢复会话；续期失败只导致 key 提前过期、恢复能力下降，不影响正确性，所以没进 `RedisScripts`。旧的 `getRestorableChat` 无参版本与 `GET /chat/restorable` 路径都已过时（现在是 `GET /chat/v2/restore`）。

3. ✅ **已修复：`rollbackGrab` 的读-写不再非原子**。当前回滚走 `RELEASE_LOCK_IF_MATCH` Lua（校验锁令牌才释放），并在事务 `afterCompletion` 回调里执行；占位释放与 DB 回滚对齐。旧版「先读回当前值、确认是我写的、再写回空闲哨兵」两条命令的窗口已消除，见 [14 篇](14-grab-order-lua.md)。

4. **`SLIDING_WINDOW_RATE_LIMIT` 的 `ZREMRANGEBYSCORE` 在成员极多时是 O(log N + M)**。按 `config/ChatGuardProperties.java` 的默认值（`rateLimitMax = 30`、`rateLimitWindowSeconds = 60`）ZSet 成员数被封在阈值附近，不构成风险；但如果把阈值调到几千，这条命令的成本需要重新评估。入口层那套 120/60 限流复用同一脚本，阈值更高，评估时要一并算进去。

5. ✅ **已修复：`HASH_SET_WITH_EXPIRE` 不再恒返回 `1`**。当前 `return redis.call('EXPIRE', KEYS[1], ARGV[3])`，返回 `1`/`0` 反映过期是否设置成功；`ChatManager.isLocked` 也改成两分支判断，旧文档里 `//其实这里始终为真` 的注释已从代码删除。仍需注意：返回值反映的是 **EXPIRE**，不是 `HSET` 是否新建字段，做「首次加锁才放行」这类分支依旧不能直接用它。

6. **`GRAB_ORDER_ATOMIC` 的占位 TTL 现在是统一的 5 分钟（300 秒）**，不再是 6 小时（`OrderGrabService.CACHE_TTL`）。正常路径靠 `releaseDriver` / 回滚显式释放；TTL 只在释放路径因异常未执行（进程崩溃、DB 异常）时防止司机被永久锁在「忙」。**占位信息的正确性最终仍由 DB 保证**（司机账号行锁 + 活跃订单 `FOR UPDATE` + 条件更新），缓存在这里不是正确性依赖。另外 `SYNC_ORDER_STATUS` 保证状态单调推进，回池到 `CREATED` 这种数值回退必须走 `refreshOrderStatus` 删 key，不能靠该脚本覆盖。

7. **司机位置清理仍是惰性的，但脚本换了三支**。`UPDATE_DRIVER_LOCATION` / `REMOVE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION` 都可在原子执行内 `HDEL`+`ZREM`；仍没有后台定时清理任务，孤儿成员只在有人查询该司机、或该司机刷新位置时才会被摘掉。这是有意为之（池子只在有人查询时才有意义）；代价是「永不被查询的离线司机」会留在 GEO key 里，但每次读都会用 `TIME` 重新判定，残留不影响查询结果。旧版 `CHECK_DRIVER_ONLINE` 整节已删除。

---

## 七、如何验证

```bash
# ---------- 0. 通用：确认脚本真的走了 EVALSHA 而不是每次传整段 Lua ----------
redis-cli INFO commandstats | grep -E 'evalsha|eval|script'
# 期望：evalsha 的 calls 远多于 eval（eval 只在首次/SCRIPT FLUSH 后各出现一次）

# 查看某个脚本是否已在服务端缓存
redis-cli SCRIPT EXISTS <sha1>      # sha1 可用 SCRIPT LOAD 预先算好，或从 MONITOR 里看

# ---------- 1. HASH_SET_WITH_EXPIRE ----------
redis-cli HGETALL chat:info:{chatId}
redis-cli TTL    chat:info:{chatId}     # 期望 3600 左右，而不是 -1
# 关键验证：TTL 必须是正数。旧实现在 HSET 成功而 EXPIRE 失败时会是 -1（永不过期）

# ---------- 2. RELEASE_LOCK_IF_MATCH ----------
redis-cli SET order:lock:123 someone-elses-token EX 30
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 order:lock:123 my-token
# 期望返回 (integer) 0，且 order:lock:123 仍然存在 —— 令牌不匹配时不删
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 order:lock:123 someone-elses-token
# 期望返回 (integer) 1，且 key 消失

# ---------- 3. 聊天历史版本 key（当前实现：版本 PERSIST）----------
redis-cli GET  chat:history:version:{chatId}    # 当前版本号
redis-cli TTL  chat:history:version:{chatId}    # 期望 -1 —— 版本 key 被 PERSIST 永久保留
redis-cli LLEN chat:history:{chatId}
redis-cli PTTL chat:history:{chatId}            # 期望 ≤ 86400000（聚焦 24h）/ ≤ 1800000（非聚焦 30min）
# 触发一轮对话后：LLEN 增加 n，version 也递增（写路径走 APPEND_HISTORY_IF_VERSION_MATCH）
# 若版本 key 的 TTL 不是 -1，说明它仍带过期时间 —— 数据过期后版本归零会让旧租约「复活」，见 09 篇 4.8
# 执行清空历史后：版本继续递增、数据 key 被删、版本 key 依旧存在（CLEAR_HISTORY_WITH_VERSION_BUMP）

# ---------- 4. OVERWRITE_IF_VERSION_MATCH（含 false 判定的验证）----------
redis-cli DEL chat:history:staging:test:1
redis-cli EVAL "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end" 1 chat:history:staging:test:1
# 期望 (integer) -1 —— 暂存 key 不存在
redis-cli EVAL "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end" 1 chat:history:staging:test:1
# 若把脚本写成 if redis.call('EXISTS', KEYS[1]) then ... end，因为 Lua 里 0 是真值，这里永远不会返回 -1

# ---------- 5. GRAB_ORDER_ATOMIC ----------
redis-cli SET order:status:123 10 EX 300        # 当前统一 CACHE_TTL = 5 分钟（旧值 7200/21600 已失效）
redis-cli SET driver:active:7 __IDLE__ EX 300
redis-cli EVAL "local status = redis.call('GET', KEYS[1]); if status == false or status ~= ARGV[1] then return -1 end; local active = redis.call('GET', KEYS[2]); if active == false or active ~= ARGV[2] then return -2 end; redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4]); return 1" 2 order:status:123 driver:active:7 10 __IDLE__ 123 300
# 期望 (integer) 1，且 driver:active:7 变为 "123"
# 再执行一次同样的命令：期望 (integer) -2（司机已被自己占住）
# SYNC_ORDER_STATUS 的单调性：SET order:status:123 90 后，再用 ARGV[1]=10 调用该脚本应返回 0（旧状态不能覆盖新状态）

# ---------- 6. SLIDING_WINDOW_RATE_LIMIT（当前：Redis TIME + 3 个 ARGV）----------
redis-cli DEL chat:ratelimit:1
for i in $(seq 1 35); do
  redis-cli EVAL "redis.replicate_commands(); local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); local window=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now-window); local used=redis.call('ZCARD', KEYS[1]); if used >= limit then return -1 end; redis.call('ZADD', KEYS[1], now, ARGV[3]); redis.call('PEXPIRE', KEYS[1], window); return limit-used-1" 1 chat:ratelimit:1 60000 30 "m-$i"
done
# 期望：前 30 次返回 29..0，第 31 次起返回 -1
redis-cli ZCARD chat:ratelimit:1     # 期望正好 30（超限的调用不留 member）
redis-cli PTTL  chat:ratelimit:1     # 期望接近 60000
# 注意：不再传 $(date +%s%3N)，时间由 Redis 服务器 TIME 提供；两套限流（userId 30/60、来源地址 120/60）复用同一脚本

# ---------- 7. 司机位置三脚本（替代已删除的 CHECK_DRIVER_ONLINE）----------
# 走真实接口写入（UPDATE_DRIVER_LOCATION 内用 Redis TIME 记心跳）
curl -s -X POST http://localhost:8080/order/driver/location -H 'Content-Type: application/json' \
     -H 'X-Auth-Token: <DRIVER_TOKEN>' -d '{"lng":116.4,"lat":39.9}'
redis-cli HGET    driver:online:beat 7   # 期望：Redis TIME 生成的毫秒时间戳
redis-cli GEOPOS  driver:geo:online 7    # 期望：坐标（GEOADD 与心跳在同一 EVAL 内写入）
# READ_DRIVER_LOCATION：调用 GET /order/driver/location 后观察
redis-cli TTL     driver:geo:online      # 期望 -1 —— 共享 key 仍不设 TTL
# 把心跳改成很旧的时间戳，再读一次：
redis-cli HSET    driver:online:beat 7 1000000000000
# 期望 online=false、坐标为空，且脚本顺手清理：
redis-cli HGET    driver:online:beat 7   # 期望 (nil)
redis-cli ZSCORE  driver:geo:online 7    # 期望 (nil) —— 孤儿成员被摘掉
# 主动下线（REMOVE_DRIVER_LOCATION）：POST /order/driver/offline 后两者同时消失
# 注意：服务端只按 ONLINE_TTL=60s 判定，不再由客户端传 now；位置服务不可用时返回 503，不是静默判离线
```

> 脚本可以用 `redis-cli SCRIPT LOAD "<lua 源码>"` 预先加载，拿到 SHA1 后改用 `redis-cli EVALSHA <sha1> ...` 反复调用，省去每次粘贴整段脚本。

---

## 八、延伸阅读

- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— `GRAB_ORDER_ATOMIC` 的完整业务链路，以及"Lua 预检 + DB 乐观锁"的双层裁决
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RELEASE_LOCK_IF_MATCH` 的误删他人锁时序，以及手写锁与 Redisson 的对照
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `HASH_SET_WITH_EXPIRE` 修掉的永久锁死问题，以及仍未解决的 TTL 语义矛盾
- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— 同一个 `chat:info` Hash 上的三套令牌；`CANCEL` / `CONSUME` 已把「校验 + 删除」原子化，仍在的是预检、冗余删除与业务执行分离
- [`09-lease-token.md`](09-lease-token.md) —— Lease 令牌机制的完整设计，以及**版本 key 为何必须 `PERSIST` 而不能与数据同生命周期**
- [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 替代 `CHECK_DRIVER_ONLINE` 的三支位置脚本，以及 Redis `TIME` 与 60 秒判定
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— `GEORADIUS` 与"共享 key 不设 TTL"的推导（当前为整池失效重建）
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— `CLAIM_DUE_ORDERS` 与 DB 补偿扫描
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— 工单池三支 Lua 与无 TTL 的版本令牌
- [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) —— 滑动窗口与固定窗口的对比、两套限流并存
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— `__NULL__` / `__IDLE__` 哨兵与预热思路（当前统一 5 分钟 TTL，旧 `__MISSING__` 已删除）
- [`README.md`](README.md) —— 25 篇的统一落点表、术语与状态标注规范、跨文档引用枢纽
- 项目内素材：`docs/01-redis-application-points.md`（P0-1、P1-1、P1-2）、`docs/02-cache-consistency-race.md`（第二、三节）
- Redis 官方文档：[Scripting with Lua](https://redis.io/docs/manual/programming/lua/)、[EVAL](https://redis.io/commands/eval/)、[Lua 与 Redis 的类型转换表](https://redis.io/docs/manual/programming/lua-api/)
