# Redis 应用点全景

> 目标：盘点 RideAgentHub 当前代码中**已经落地**的 Redis 应用点，讲清每个点对应的业务需求、选型理由与代码落点。
> 定位原则：**嫁接在业务本来就有的并发点上**，不为用 Redis 而用 Redis。
> 代码定位格式为「类名 + 方法名」，路径基准 `src/main/java/com/fancy/rideagenthub/`（下称 `<base>/`）。
> 每个应用点的深入拆解见 `docs/learn/redis/` 对应笔记（下文各节均给出链接）。

---

## 一、应用点总览

| 业务需求 | Redis 考点 | 核心能力 | 代码落点 | 笔记 |
|---|---|---|---|---|
| 多实例共享登录态、按用户踢下线 | 共享 Session | String 存 token + Set 反向索引 + 会话版本 | `service/impl/TokenServiceImpl` | [01](learn/redis/01-shared-session-token.md) |
| 邮箱验证码有效期与发送冷却 | 双 key TTL | 验证码 / 冷却 / 失败计数 / 归属 / 预留五组 key，四段 Lua | `service/base/EmailCodeService` | [02](learn/redis/02-email-code-cooldown.md) |
| 用户名布隆登记与重建（未接入查询拦截） | 布隆过滤器 | Redisson `RBloomFilter` + 全量重建 | `service/base/UserUsernameBloomFilterService` | [03](learn/redis/03-cache-penetration-bloom.md) |
| 工单统计热点 key 重建 | 缓存击穿 | 互斥锁 + 双检 + 有限重试 + 降级直查 DB | `service/impl/TicketServiceImpl` | [04](learn/redis/04-cache-breakdown-mutex.md) |
| 统计缓存批量过期 | 缓存雪崩 | TTL 随机抖动（20 + 0~10 秒） | 同上 | [05](learn/redis/05-cache-avalanche-random-ttl.md) |
| 「key 不存在」语义收敛 | 空值哨兵 | `__NULL__` / `__IDLE__` 状态编码 | `CityCodeUtil`、`OrderGrabService` | [06](learn/redis/06-null-sentinel-and-prewarm.md) |
| 工单写操作后的脏缓存 | 延迟双删 | 提交后立即删 + 500ms 二次删 | `TicketServiceImpl` + `DelayedCacheEvictor` | [07](learn/redis/07-delayed-double-delete.md) |
| 城市编码长 TTL 缓存失效 | 墓碑机制 | 5 秒墓碑 + 版本号，阻止旧快照回填 | `agentbase/amap/util/citycode/CityCodeUtil` | [08](learn/redis/08-city-code-tombstone.md) |
| 聊天历史回填丢消息 | Lease 令牌 | 版本租约 + Lua 原子校验回填 | `agentbase/memory/RedisMemory` 等 | [09](learn/redis/09-lease-token.md) |
| 多命令原子化 | Lua 脚本 | 22 个主脚本 + 11 个内联脚本 | `util/RedisScripts` + 5 个业务类 | [10](learn/redis/10-lua-scripts-overview.md) |
| 工具调用的多阶段确认 | 流程门禁 | 取消 / 创建两套一次性令牌，另有非一次性的订单槽位状态 | `OrderTool`、`OrderSearchTool`、`OrderConfirmation` | [11](learn/redis/11-two-phase-confirm-token.md) |
| 会话锁与单轮执行 | 原子写 + 锁 | `HASH_SET_WITH_EXPIRE`、`chat:execution:{chatId}` | `agentbase/chatinfo/ChatManager`、`service/base/ChatRequestRunner` | [12](learn/redis/12-chat-lock-atomic.md) |
| 订单状态机串行化 | 分布式锁 | Redisson 看门狗（订单）+ 手写令牌锁（缓存重建） | `RideOrderServiceImpl`、`service/base/RedisLock` | [13](learn/redis/13-distributed-lock.md) |
| 司机抢单 | 秒杀模型 | Lua 原子预检 + 占位，DB 行锁兜底 | `service/base/OrderGrabService` | [14](learn/redis/14-grab-order-lua.md) |
| 司机端附近订单排序 | GEO | `GEORADIUS` + 整池失效重建 | `service/base/OrderGeoPool` | [15](learn/redis/15-nearby-order-geo.md) |
| 司机在线状态 | 成员级 TTL | 共享 GEO + 心跳 Hash + Redis `TIME` | `service/base/DriverGeoIndex` | [16](learn/redis/16-driver-online-heartbeat.md) |
| 订单超时兜底 | ZSet 延迟队列 | Lua 原子领取 + DB 补偿扫描 | `service/base/OrderDelayQueue`、`OrderTimeoutJob` | [17](learn/redis/17-order-timeout-delay-queue.md) |
| 工单编号唯一性 | INCR 日序号 | `TICKET_SEQUENCE` Lua，与雪花 ID 并存 | `TicketServiceImpl`、`config/IdGeneratorConfig` | [18](learn/redis/18-global-unique-id-incr.md) |
| 对话上下文读取加速 | 多级缓存 | Heap → Redis → MySQL + 版本租约 | `agentbase/memory/MessageMemory` | [19](learn/redis/19-multi-level-cache.md) |
| 工具结果按需反查 | 两级辅助索引 | Redis 24h + MySQL | `agentbase/memory/ToolResponseMemory` | [20](learn/redis/20-tool-response-cache.md) |
| LLM 分类调用降本 | 结果缓存 | 指纹 key + TTL 可禁用 | `service/base/ClassificationCache` | [21](learn/redis/21-llm-classification-cache.md) |
| 对话与入口限流 | 滑动窗口 | ZSet + Lua，两套并存 | `service/base/ChatRateLimiter`、`interceptor/RequestRateLimitInterceptor` | [22](learn/redis/22-sliding-window-rate-limit.md) |
| 工单池分页排序 | ZSet 复合排序 | score 位权 + 版本令牌发布 | `service/base/TicketPoolIndex` | [23](learn/redis/23-ticket-pool-zset.md) |
| 用户位置存取 | Hash | 三字段结构化小对象 | `UserServiceImpl.saveUserLocation` | [24](learn/redis/24-user-location-hash.md) |
| 订单 HITL 等待确认 | 状态机 | `break=yes/resuming` + `confirmationId` | `agents/OrderAgent`、`service/base/OrderConfirmation` | [25](learn/redis/25-agent-hitl-state.md) |

---

## 二、按业务域的应用点详述

### 2.1 登录态与验证码

**共享 Session（`TokenServiceImpl`）**。多实例部署下传统 `HttpSession` 需要粘性会话或会话复制，两者都把登录态钉死在某台机器上。当前实现把每个 token 序列化成 JSON 存进 `auth:token:{token}`，再用 Set `auth:user_tokens:{userId}` 建反向索引，使「重置密码 / 注销账号后批量踢下线」从 `KEYS` 全库扫描降为 O(该用户 token 数)。写入（`SAVE`）、撤销（`REVOKE`）、单删（`REMOVE`）都是内联 Lua 原子执行；`auth:session-version:{userId}` 无 TTL，每次读令牌都会比对会话版本与数据库凭据摘要，改密后旧令牌立即失效。

**验证码与发送冷却（`EmailCodeService`）**。同一个「邮箱 + 场景」下维护验证码、冷却、失败计数、归属、预留五组 key，全部由 `ISSUE` / `VERIFY` / `CLEANUP` / `COMPLETE` 四段内联 Lua 维护：发送原子判定冷却，校验原子计数（5 次错误后验证码作废），事务场景先 `reserve`、提交 / 回滚后再消费或释放，邮件发送失败按 `issueId` 归属清理，不会误删他人的冷却状态。

### 2.2 缓存三大经典问题

**穿透：布隆过滤器（`UserUsernameBloomFilterService`）**。Redisson `RBloomFilter`（容量 100 万、误判率 1%）登记已注册用户名。注册后 `addUsername` 只登记用户名，`mightContain` 无生产调用方，不承担前置拦截；可用性检查 `isUsernameAvailable` 直查数据库，正确性由生成列唯一索引兜底，布隆重建期的假阴性不影响业务结果。

**击穿：互斥锁 + 双检（`TicketServiceImpl.getTicketStatistics`）**。统计接口面向 B 端仪表盘，是典型热点 key。未命中先抢 `RedisLock`（5 秒 TTL），锁内双检后回源 DB；未抢到锁的请求以 60ms 间隔重试读缓存最多 3 次，仍无则降级直查 DB —— 宁可多打一次数据库，也不让请求无限等待。

**雪崩：随机 TTL（`TicketServiceImpl.cacheTicketStatistics`）**。统计缓存 TTL = `20 + nextInt(11)` 秒（20~30 秒随机），让每次重建的存活时间在范围内抖动；当前日期 key 由所有实例共享，不存在每实例副本。索引类 key（GEO 池 / 工单池）则用固定 2 小时 TTL + 写入即失效重建，限制失效失败后旧索引的存活时间。

**空值哨兵（`CityCodeUtil` / `OrderGrabService`）**。`__NULL__` 把「DB 里确实没有这条数据」缓存下来，防止同一个查不到的城市反复穿透；`__IDLE__` 表示司机当前无进行中订单，是抢单占位的初始态。哨兵把「key 不存在」（未知）收敛为「值为哨兵」（已知的空），两种语义不再混淆。

### 2.3 缓存一致性三解法

三个真竞态点分别对应三种解法，完整的竞态推导见 [`docs/02-cache-consistency-race.md`](02-cache-consistency-race.md)：

| 竞态形态 | 解法 | 落点 |
|---|---|---|
| 删除被并发回填覆盖 | 延迟双删 | `TicketServiceImpl.evictTicketStatistics` + `DelayedCacheEvictor` |
| TTL 太长、时间窗口猜不准 | 墓碑 | `CityCodeUtil`（版本 key + `INVALIDATE_CITY_CODE` / `CACHE_CITY_CODE_IF_VERSION_MATCH` 两段 Lua） |
| 回填本身就是破坏性覆盖 | Lease 令牌 | `RedisMemory` / `HeapMemory`（版本租约 + 五段历史脚本） |

三者的适用边界：延迟双删要求写路径存在删除动作；墓碑要求存在可标记的失效动作；当回填本身会把并发写入整段抹掉时，只有版本校验能解。

### 2.4 原子操作与分布式锁

**Lua 脚本（`RedisScripts` + 5 个业务类）**。22 个主脚本集中维护于 `util/RedisScripts.java`，另有 11 个内联脚本分散在 `TokenServiceImpl`(3)、`EmailCodeService`(4)、`TicketServiceImpl`(1)、`OrderConfirmation`(2)、`OrderSearchTool`(1)。总览与逐脚本拆解见 [10 篇](learn/redis/10-lua-scripts-overview.md)。

**多阶段确认令牌**。Hash 字段做流程门禁：取消订单前由 `verifyCancelConditions` 写入绑定订单号与时间的令牌，`CANCEL` Lua 在执行时校验 5 分钟时效并原子消费；订单创建权限由 `OrderConfirmation` 的 `CONFIRM` / `CONSUME` 两段 Lua 管理，报价 15 分钟有效、一次性消费、相同决定可幂等重放。见 [11 篇](learn/redis/11-two-phase-confirm-token.md)。

**会话锁（`HASH_SET_WITH_EXPIRE`）**。`chat:info:{chatId}` Hash 的 `locked` 字段与 60 分钟过期在一段 Lua 内原子写入，避免「HSET 成功、EXPIRE 失败」把会话永久锁死。它与单轮执行锁 `chat:execution:{chatId}` 是两个 key、两种语义，后者由 `ChatRequestRunner` 用 Redisson 看门狗管理，客户端断开不提前放锁。见 [12 篇](learn/redis/12-chat-lock-atomic.md)。

**分布式锁双轨（`RideOrderServiceImpl.withOrderLock` / `RedisLock`）**。订单的 7 个状态变更统一走 Redisson `tryLock(3, TimeUnit.SECONDS)`（不传 leaseTime，启用看门狗续期），持锁到事务 `afterCompletion`，锁竞争直接返回 409 且不触碰业务数据；统计缓存、GEO 池、工单池的重建走手写 `RedisLock`（`setIfAbsent` + `RELEASE_LOCK_IF_MATCH` 令牌校验释放，固定 5~10 秒 TTL 兜底）。两类锁的分工与原因见 [13 篇](learn/redis/13-distributed-lock.md)。

### 2.5 订单与司机（高并发核心）

**抢单 = 秒杀模型（`OrderGrabService`）**。「订单可抢 + 司机空闲」的判断与「占住司机位」在 `GRAB_ORDER_ATOMIC` Lua 内一次完成（`driver:active:{driverId}` 写入 orderId，5 分钟 TTL）；正确性由数据库兜底：`tryGrab` 标注 `@Transactional(MANDATORY)`，事务内对司机账号行和在途订单行 `FOR UPDATE`，再由「订单状态为 CREATED 且司机为空」的条件更新裁决。回滚经 `afterCompletion` 用 `RELEASE_LOCK_IF_MATCH` 只释放本订单的占位。状态同步走 `SYNC_ORDER_STATUS` 单调推进，迟到的旧状态不能覆盖新状态。见 [14 篇](learn/redis/14-grab-order-lua.md)。

**附近订单 GEO（`OrderGeoPool`）**。4 个 key：版本、就绪标记、GEO 池、重建锁。订单变更（新增 / 接单 / 取消）在事务提交后整池失效（换版本号），下次查询抢锁 → 全量回表 → `PUBLISH_ORDER_GEO_POOL` 原子发布（校验版本与锁令牌），读取用 `SEARCH_ORDER_GEO_POOL` 内的 `GEORADIUS … ASC COUNT 200 WITHDIST`（兼容 Redis 5）。回表后发现版本变化则整页降级 DB。见 [15 篇](learn/redis/15-nearby-order-geo.md)。

**司机在线心跳（`DriverGeoIndex`）**。共享 GEO key 存坐标 + 心跳 Hash 存最后活跃时间，时间源统一为 Redis `TIME`（避免多实例时钟偏斜）。三段 Lua（`UPDATE_DRIVER_LOCATION` / `REMOVE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION`）原子完成上线、刷新（只刷仍在线的司机）、读取（过期即顺手清理两个成员），60 秒无心跳判定离线。见 [16 篇](learn/redis/16-driver-online-heartbeat.md)。

**订单超时 ZSet 延迟队列（`OrderDelayQueue` + `OrderTimeoutJob`）**。score = 到期时间戳，member = orderId（`ZADD` 天然按 member 覆盖实现改期）。排期 / 取消走 `afterCommit`；领取用 `CLAIM_DUE_ORDERS` Lua 一次完成 `ZRANGEBYSCORE` + 逐条 `ZREM`，多实例无需额外锁即不重复领取；处理失败按轮询间隔重排；`OrderTimeoutJob.recoverOverdueOrders()` 每分钟扫数据库三类锚点兜底，覆盖入队失败、进程退出与历史订单。见 [17 篇](learn/redis/17-order-timeout-delay-queue.md)。

### 2.6 其它应用点

**全局唯一 ID（两套并存）**。对外工单编号用 `ticket:no:{yyyyMMdd}` 的 `INCR` 日序号（`TICKET_SEQUENCE` Lua 保证 `INCR` 与 48 小时 `EXPIRE` 原子）；内部主键（订单 ID、userId、RAG 主键）用 `SnowflakeIdWorker` 雪花 ID，`IdGeneratorConfig` 以 Redisson `id:node:{dc}:{worker}` 看门狗续期租约保证节点号唯一，租约丢失时 `nextId()` 拒绝发号。见 [18 篇](learn/redis/18-global-unique-id-incr.md)。

**多级缓存（`MessageMemory`）**。Heap(L1) → Redis(L2) → MySQL(L3) 分层读取，全量读取后由 `MessageWindow.tail` 截取完整的工具调用批次；回填持版本租约；写入先落 MySQL 再按租约追加 L2 / L1。见 [19 篇](learn/redis/19-multi-level-cache.md)。

**工具结果缓存（`ToolResponseMemory`）**。历史消息中的 TOOL 响应保存全文；`chat:tool:v2:{chatId}:{callId}`（24h）+ MySQL 两级提供按 `(callId, chatId)` 的辅助反查，`getCalling` 工具据此把长结果重新注入上下文。见 [20 篇](learn/redis/20-tool-response-cache.md)。

**LLM 分类缓存（`ClassificationCache`）**。key 用 `v2` 长度前缀指纹（SHA-256）防注入与语境串味；结果归一化为 5 个标签，空结果不缓存；`TTL ≤ 0` 时整体禁用。见 [21 篇](learn/redis/21-llm-classification-cache.md)。

**滑动窗口限流（两套并存）**。对话层按 userId 30 次/60 秒（`ChatRateLimiter`，Redis 异常放行）；入口层按来源地址摘要 120 次/60 秒（`RequestRateLimitInterceptor`，`OPTIONS` 放行）。共用 `SLIDING_WINDOW_RATE_LIMIT` Lua，时间源为 Redis `TIME`。见 [22 篇](learn/redis/22-sliding-window-rate-limit.md)。

**工单池 ZSet 排序（`TicketPoolIndex`）**。score = 优先级 × 1e13 + 更新时间毫秒（UTC），member 用 `%019d` 补零使字典序等于主键数值序；就绪标记 `{version}:{count}` 原子校验；写入提交后整池失效，读取时抢锁全量重建，回表校验失败整页退回 DB。版本 key 无 TTL。见 [23 篇](learn/redis/23-ticket-pool-zset.md)。

**用户位置 Hash（`UserServiceImpl.saveUserLocation`）**。三字段小对象用 Hash 而非 JSON String，与司机在线 GEO 是两套独立数据。见 [24 篇](learn/redis/24-user-location-hash.md)。

**订单 HITL 状态机（`OrderAgent` + `OrderConfirmation`）**。`chat:info:{chatId}` Hash 的 `break` 字段有 `yes` / `resuming` 两态；`confirm` 事件携带服务端签发的 `confirmationId`，恢复链路经 `CONFIRM`（15 分钟时效校验、幂等重放）与 `CONSUME`（一次性消费）两段 Lua 授权。见 [25 篇](learn/redis/25-agent-hitl-state.md)。

---

## 三、贯穿全局的设计模式

### 3.1 事务回调统一模式

所有「数据库提交后才应生效」的缓存与索引变更，一律挂到 `TransactionSynchronization.afterCommit` / `afterCompletion`：

| 组件 | 提交后动作 | 回滚时行为 |
|---|---|---|
| 工单统计 / 索引 | `afterTicketMutation`：注册索引失效 + 统计缓存双删 | 不触发，索引停留在写前状态 |
| GEO 池 | `OrderGeoPool.remove` → 整池失效 | 不发布事务中的坐标 |
| 延迟队列 | `schedule` / `cancel` | 不入队 / 不取消 |
| 抢单占位 | `releaseDriver` → 校验令牌释放 | `rollbackGrab` 只还本订单占位 |
| 订单状态缓存 | `syncOrderStatus` 单调推进 | 不写回 |

回滚不发布、提交才生效，数据库与缓存看到的变更顺序天然一致。

### 3.2 版本 key 无 TTL

聊天历史版本通过 Lua `PERSIST` 保留并单调递增；工单池 / GEO 池版本是无 TTL 的 UUID，用 SET / SETNX 更新；城市编码版本只在 `invalidate` 首次 INCR 时创建，无主动 TTL，普通查询不会创建。后两者没有调用 PERSIST。版本 key 不应与数据一起清理，避免在途校验失效。代价是这些 key 不随数据回收，用少量内存换单调性。

### 3.3 固定 TTL 不因写入续期

GEO 池与工单池索引发布时 `PEXPIRE` 一次（2 小时），之后不因查询或写入续期：即使失效操作失败，漂移窗口也被固定 TTL 封顶；统计缓存靠随机 TTL 打散过期。续期类 TTL 只出现在语义上确实「活跃即应续命」的场景（`OrderConfirmation.quoted` 的 24 小时、`getRestorableChat` 的 60 分钟）。

### 3.4 故障降级策略按组件区分

| 组件 | Redis 故障时的行为 |
|---|---|
| GEO 池 / 工单池索引 | 整页降级为数据库查询 |
| 对话限流 / 分类缓存 | 放行 / 视为未命中 |
| 延迟队列 | 本轮跳过，数据库补偿扫描兜底 |
| 抢单路径 | 依赖 Redisson 锁与预检，不能承诺继续接单 |

原则：兜底能力宁可晚到不可打挂调度线程；但涉及资金与正确性的路径不降级。

### 3.5 时间源统一到 Redis

心跳判定（`UPDATE_DRIVER_LOCATION` 等）与滑动窗口限流（`SLIDING_WINDOW_RATE_LIMIT`）都在 Lua 内用 `redis.call('TIME')` 取当前时间：多实例部署下应用服务器时钟可能偏斜，Redis 单点时间是唯一可信的公共时钟。

---

## 四、未引入的 Redis 能力

以下是学习路线图中常被提到、但**当前代码确认没有实现**的能力（全库检索验证，不是遗漏）：

| 考点 | 现状 |
|---|---|
| BitMap 出勤签到 | 无 `setBit` / `bitCount` / `BITFIELD` 调用 |
| ZSet 排行榜 | 无 `ZINCRBY`；ZSet 仅用于延迟队列、限流、工单池 |
| HyperLogLog UV/DAU | 无 `pfAdd` / `pfCount` |
| Pub/Sub 实时推送 | 无 `convertAndSend` / `RedisMessageListenerContainer`，事件推送走 SSE |
| Token 滑动续期 | `TokenServiceImpl` 设固定 7 天 TTL，读取不刷新 |
| 通用接口幂等注解 | 无幂等切面；已有三处服务端一次性令牌（`OrderConfirmation.CONSUME`、`OrderSearchTool.CANCEL`、验证码一次性消费） |

若要在本仓库练手，这些是合适的扩展方向；但请注意不要把它们当作现有能力来描述。
