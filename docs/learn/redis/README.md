# Redis 考点学习笔记

一个考点一篇文档，每篇回答三个问题：

1. **这个 Redis 能力在本项目里具体落在哪几行代码？**
2. **为什么这么写** —— 当时否掉了哪些方案？
3. **边界在哪** —— 哪些是已修复的旧缺陷，哪些是仍然存在的坑？

素材来自 `docs/01-redis-application-points.md`（应用点清单）与 `docs/02-cache-consistency-race.md`（缓存一致性竞态盘点）。
那两份文档是**改造前**的规划稿，部分缺陷已修复、行号已漂移 —— 本系列一律以**当前代码**为准。

---

## 阅读约定

| 项 | 说明 |
|---|---|
| 路径基准 | 所有路径相对 `src/main/java/com/fancy/rideagenthub/`（当前项目 `RideAgentHub`）；25 篇抬头的旧包 `com/fancy/taxiagent/` 为历史快照，已统一更正 |
| 代码版本 | 本系列撰写于历史快照 `bdc40d7`（"司机工单池改用 GEO 附近排序"，旧分支、项目旧名 TaxiAgent）。仓库存在**两条被重写/合并的分支**（`75a68df` merge 后 HEAD 为 `a8f4e33`「修复订单与会话安全漏洞并完善并发一致性保护」），因此全部 `文件:行号` 已整体漂移：一律以「**类名 + 方法名 + 章节名**」定位当前源码，行号仅作历史参照 |
| 每篇结构 | 业务场景 → Redis 结构选型 → 代码落点 → 实现拆解 → 设计取舍 → 边界与已知问题 → 如何验证 → 延伸阅读 |
| 验证命令 | 各篇末尾的 `redis-cli` 命令基于默认配置（`application.yaml` 的 `spring.data.redis.*`，Lettuce 连接池）。**状态说明**：10/09/11/12/13/14/15/16/18/19/20/22/23/25 篇的第七章命令在 2026-11-13 反向审计中确认**仍基于改造前 key / TTL / 参数个数**，各篇篇首【当前实现对照】已给出修正值与出处；实机验证需自备 Redis，请勿直接照抄旧命令 |
| 正文阅读约定 | 25 篇的二～八章正文写于改造前快照，**作为缺陷推导教材完整保留**；凡与篇首【当前实现对照/总览】冲突之处，**一律以篇首 + 当前源码为准**。篇首「本篇勘误」表逐条列出已确认的失效断言 |
| 状态标注 | 统一四态：`✅ 已修复` / `⚠️ 部分修复` / `❌ 仍存在` / `📐 设计中`；规划稿里的「现状」一律读作「改造前实现」，详见下方「术语与状态标注规范」 |

### 基础设施现状

| 项 | 现状 |
|---|---|
| 客户端 | Spring Data Redis。全项目只用 `StringRedisTemplate`，**没有一处裸 `RedisTemplate`** —— 值一律是字符串/JSON，避免 JDK 序列化写出不可读的二进制 key |
| 连接池 | Lettuce，配置在 `src/main/resources/application.yaml` 的 `spring.data.redis`（当前 `:27-40`，`max-active 12`、`max-wait 3000ms`） |
| 分布式锁 | Redisson（`pom.xml` 的 `redisson-spring-boot-starter`）自动装配，**项目里没有自定义 `RedissonConfig` 类**；订单状态机用 Redisson 看门狗锁，缓存重建用手写 `RedisLock` |
| Lua 脚本 | **主要**集中在 `util/RedisScripts.java`：当前 278 行、**22** 个 `DefaultRedisScript`（其中 4 个返回 `List`）；另有 **11 个内联脚本**分散在 `service/impl/TokenServiceImpl`(3)、`service/base/EmailCodeService`(4)、`service/impl/TicketServiceImpl`(1)、`service/base/OrderConfirmation`(2)、`agentbase/tool/OrderSearchTool`(1)，全项目合计 33 段 Lua。旧的「7 个脚本」「全部集中」均已过时，详见 [10 篇](10-lua-scripts-overview.md) 的 22 行速查表 |
| Key 常量 | **主要**集中在 `constant/RedisKeyConstants.java`，但至少 6 处在业务类就地拼 key：`ToolResponseMemory.cache()` 的 `chat:tool:v2:{chatId}:{callId}`、`IdGeneratorConfig` 的 `id:node:{dc}:{worker}`、`TokenServiceImpl` 的 `auth:session-version:{userId}`、`EmailCodeService` 的 `:attempts/:owner/:claim` 后缀、`RequestRateLimitInterceptor` 的 `request:rate:{摘要}`、`ChatRequestRunner` 的 `chat:execution:{chatId}`、`TicketServiceImpl` 的 `ticket:no:{yyyyMMdd}`、`EmailCodeService` 的 `:attempts/:owner/:claim` 后缀；`TOOL_RESPONSE_KEY` / `toolResponseKey()` 已成 0 调用方的死代码（**`OrderConfirmation` 不算** —— 它全部走 `RedisKeyConstants.chatInfoKey(chatId)`） |

---

## 目录

### 一、登录态与验证码

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 01 | [共享 Session 与 Token](01-shared-session-token.md) | String 存登录态 + Set 反向索引 | `service/impl/TokenServiceImpl.java` |
| 02 | [验证码与发送冷却](02-email-code-cooldown.md) | 双 key TTL + 失败回滚 | `service/base/EmailCodeService.java` |

### 二、缓存三大经典问题

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 03 | [缓存穿透：布隆过滤器](03-cache-penetration-bloom.md) | Redisson `RBloomFilter` | `service/base/UserUsernameBloomFilterService.java` |
| 04 | [缓存击穿：互斥锁 + 双检](04-cache-breakdown-mutex.md) | 分布式锁重建热点 key | `service/impl/TicketServiceImpl.java` |
| 05 | [缓存雪崩：随机 TTL](05-cache-avalanche-random-ttl.md) | TTL 抖动打散过期时刻；统计缓存为 **20~30 秒**随机 TTL（`20 + nextInt(11)`） | `service/impl/TicketServiceImpl.java`（随机 TTL 常量 `:87-88`、使用点 `:1191-1195`）；**2 小时是 `TicketPoolIndex.INDEX_TTL` / `OrderGeoPool.INDEX_TTL`，且仅发布时 `PEXPIRE` 一次，不是统计缓存 TTL** |
| 06 | [空值哨兵与状态预热](06-null-sentinel-and-prewarm.md) | 把「未知」与「已知的空」分开；当前统一 5 分钟 TTL | `CityCodeUtil` / `OrderGrabService` |

### 三、缓存一致性与竞态

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 07 | [延迟双删](07-delayed-double-delete.md) | 第二次删除兜住回填覆盖 | `service/base/DelayedCacheEvictor.java` |
| 08 | [墓碑机制](08-city-code-tombstone.md) | 用状态标记替代猜时间 | `agentbase/amap/util/citycode/CityCodeUtil.java` |
| 09 | [Lease 令牌](09-lease-token.md) | 版本校验 + 原子回填 | `agentbase/memory/RedisMemory.java` |

### 四、原子操作与分布式锁

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 10 | [Lua 脚本总览](10-lua-scripts-overview.md) | 22 个主脚本 + 11 个内联脚本的原子性速查表 | `util/RedisScripts.java`、5 个业务类 |
| 11 | [工具调用的多阶段令牌](11-two-phase-confirm-token.md) | Hash 状态位做流程门禁（三套令牌） | `agentbase/tool/OrderTool.java`、`OrderSearchTool.java`、`service/base/OrderConfirmation.java` |
| 12 | [会话锁的原子化](12-chat-lock-atomic.md) | `HSET` + `EXPIRE` 合并；另有单轮执行锁 | `agentbase/chatinfo/ChatManager.java`、`service/base/ChatRequestRunner.java` |
| 13 | [分布式锁：手写 vs Redisson](13-distributed-lock.md) | 误删他人锁、看门狗续期（订单锁已启用） | `service/base/RedisLock.java`、`RideOrderServiceImpl.withOrderLock` |

### 五、订单与司机（高并发核心）

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 14 | [司机抢单：Lua 原子预检](14-grab-order-lua.md) | 秒杀模型，判断与占位一步完成 | `service/base/OrderGrabService.java` |
| 15 | [附近订单：GEO](15-nearby-order-geo.md) | `GEORADIUS` 按距离排序（兼容 Redis 5）+ 整池失效重建 | `service/base/OrderGeoPool.java` |
| 16 | [司机在线心跳](16-driver-online-heartbeat.md) | 共享 GEO + 心跳 Hash，时间源为 Redis `TIME` | `service/base/DriverGeoIndex.java` |
| 17 | [订单超时：ZSet 延迟队列](17-order-timeout-delay-queue.md) | `CLAIM_DUE_ORDERS` Lua 原子领取 + 每分钟 DB 补偿扫描 | `service/base/OrderDelayQueue.java`、`OrderTimeoutJob.java` |

### 六、其它应用点

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 18 | [全局唯一 ID](18-global-unique-id-incr.md) | `INCR` 日序号与雪花 ID + 节点租约两套并存 | `TicketServiceImpl.generateTicketId`、`config/IdGeneratorConfig.java` |
| 19 | [多级缓存 L1/L2/L3](19-multi-level-cache.md) | Heap → Redis → MySQL 逐级回填 + Lease 版本令牌（`PERSIST`） | `agentbase/memory/` |
| 20 | [工具调用结果缓存](20-tool-response-cache.md) | `chat:tool:v2:{chatId}:{callId}` 的 Redis + MySQL 两级辅助索引 | `agentbase/memory/ToolResponseMemory.java` |
| 21 | [LLM 分类结果缓存](21-llm-classification-cache.md) | `v2` 长度前缀指纹 + TTL≤0 禁用 | `service/base/ClassificationCache.java` |
| 22 | [滑动窗口限流](22-sliding-window-rate-limit.md) | ZSet + Redis `TIME` Lua；两套限流并存 | `service/base/ChatRateLimiter.java`、`interceptor/RequestRateLimitInterceptor.java` |
| 23 | [工单池 ZSet 排序](23-ticket-pool-zset.md) | score 位权拼接复合排序 + 无 TTL 版本令牌 | `service/base/TicketPoolIndex.java` |
| 24 | [用户位置 Hash](24-user-location-hash.md) | Hash 存结构化小对象 | `UserServiceImpl.saveUserLocation` |
| 25 | [Agent 的 HITL 等待确认](25-agent-hitl-state.md) | `break=yes/resuming` 两态 + `confirmationId` 状态机 | `agents/OrderAgent.java`、`service/base/OrderConfirmation.java` |

---

## 建议阅读路径

**按认知递进**（对应 `docs/02` 的演练顺序，每一步都让上一步的缺陷暴露出来）：

```
04 击穿互斥锁  →  07 延迟双删  →  08 墓碑  →  09 Lease 令牌
（先有回填）      （加删除还不够）  （别猜时间） （回填本身是破坏性的）
```

**按面试高频度**（简历上最常被追问的几个）：

```
14 抢单 Lua  →  13 分布式锁  →  15 GEO  →  17 延迟队列
→  04 击穿  →  09 Lease  →  10 Lua 总览
```

**按 Redis 数据结构**（想复习"什么场景用什么结构"）：

| 结构 | 本项目用在哪 |
|---|---|
| String | 01 Token、02 验证码、05 随机 TTL、06 哨兵、18 唯一 ID、21 分类缓存 |
| Hash | 11 工具令牌、12 会话锁、16 心跳、24 用户位置、25 HITL |
| Set | 01 Token 反向索引 |
| ZSet | 17 延迟队列、22 限流窗口、23 工单池排序（+ 15/16 GEO 的底层） |
| List | 19 聊天历史 L2、09 回填暂存 |
| GEO | 15 附近订单、16 司机位置池 |
| Lua | 10 总览（抢单 / 限流 / 锁释放 / 版本校验 / 成员级清理） |
| 布隆过滤器 | 03 用户名防穿透（Redisson） |

---

## 未实现的考点

以下是 `docs/01` P2 清单里列出、但**当前代码中确认没有实现**的项（grep 验证过，不是遗漏）：

| 考点 | 证据 |
|---|---|
| BitMap 出勤签到 | 全项目无 `setBit` / `bitCount` / `BITFIELD` 调用 |
| ZSet 排行榜 | 无 `ZINCRBY`；ZSet 仅用于延迟队列、限流、工单池 |
| HyperLogLog UV/DAU | 无 `pfAdd` / `pfCount` 调用 |
| Pub/Sub 实时推送 | 无 `convertAndSend` / `RedisMessageListenerContainer` |
| 独立权限缓存 | `aspect/PermissionAspect.java` 从 `security/UserTokenContext`（ThreadLocal）读角色与用户 ID，**不查 DB、也不查 Redis**；因此「没有独立权限缓存」的结论不变，但旧证据「每次直查 DB」是错的 |
| Token 滑动续期 | `TokenServiceImpl` 设固定 TTL，读取时不刷新（**注意**：`ChatManager` 续的是会话 key，不是 token） |
| 接口幂等（通用机制） | 仍**无**幂等注解/切面；但已有三处**服务端签发的一次性令牌**落地：`OrderConfirmation.CONSUME`（创建权限）、`OrderSearchTool.CANCEL`（取消权限）、`EmailCodeService`（验证码一次性消费），另有 [14 抢单预检](14-grab-order-lua.md) 的 DB 条件更新；不再只是「局部近似」 |

### 已实现但本系列尚未写笔记的能力

> 25 篇笔记聚焦 Redis 数据结构与并发原语，下面这批 `a8f4e33` 引入的能力属于 **Agent 编排 / RAG / 安全边界**，已移到 `../agent/` 目录单独成篇。这里做索引，避免「索引里没写 = 项目没实现」的误读。

| 能力 | 代码落点 | 对应笔记 |
|---|---|---|
| 会话单轮执行锁 `chat:execution:{chatId}`（Redisson 看门狗、无显式 TTL）+ **单次模型流** 3 分钟超时 + 统一异常出口；整轮 SSE 无总时长上限 | `service/base/ChatRequestRunner.java`、`agents/AgentStreams.java` | [agent/01](../agent/01-orchestration-and-sse.md)、[12 篇](12-chat-lock-atomic.md) |
| 双线程池隔离（`ragExecutor` / `chatExecutor`）、工具事件 `ToolNotifySupport`、提示词即流程约束 | `config/AsyncWorkConfig.java`、`agentbase/tool/ToolNotifySupport.java`、`config/ChatProperties.java` | [agent/01](../agent/01-orchestration-and-sse.md) |
| RAG 最终一致性：事务内入队 → `FOR UPDATE SKIP LOCKED` → 5s/30s 重建 → 8 秒共享预算 → 双路 RRF → 权威库核对 | `agentbase/rag/RagService.java`、`QaIndexSynchronizer.java` | [agent/02](../agent/02-rag-consistency-and-budget.md) |
| 完整鉴权链、`sessionVersion` + `credentialStamp`、两套限流、支付默认拒绝、异常→HTTP 映射、DDL 迁移 | `interceptor/`、`security/`、`aspect/`、`config/PaymentConfig.java`、`exception/`、`sql/migrations/` | [agent/03](../agent/03-security-and-boundaries.md) |
| 雪花 ID + Redisson 节点永久租约、Jackson 长整型精度防护 | `config/IdGeneratorConfig.java`、`util/SnowflakeIdWorker.java`、`config/Config.java` | [18 篇](18-global-unique-id-incr.md) |
| 版本 key 无 TTL 的通用模式（工单池 / 聊天历史 / 城市编码） | `util/RedisScripts.java` 中多处 `PERSIST` | [09 篇](09-lease-token.md)、[23 篇](23-ticket-pool-zset.md) |

---

## 与原始文档的关系

| 原始文档 | 现状 | 本系列的处理 |
|---|---|---|
| `docs/01-redis-application-points.md` | 规划稿（旧分支 `8d5d574`）+ P1-3 事后补记。P0-1 阶段一已落地、**阶段二 Redis Stream 异步落库未实现**；P0-2 已落地但方案不同（`GEORADIUS` + 整池失效，非 `GEOSEARCH` + 单成员 `ZREM`）；P1-1 两套限流已落地、空结果哨兵未采纳；P0-3/P1-2/P1-3 已落地 | 已落地的拆成 14–23 篇，各节补【落地状态】框；未实现/未采纳的列在上表 |
| `docs/02-cache-consistency-race.md` | 通篇「现状」为改造前快照。2.1 统一失效 + 延迟双删已落地；2.2 key 规范化已修复，但**墓碑写路径未接入**（⚠️ 部分实现）；2.3 Lease 已落地且版本 key 改为 `PERSIST`；3.1 ⚠️ 部分修复（`restoreChat` 的 `persist` 仍在）、3.2 ✅ 已修复、3.3 ✅ 核心竞态已修复但预检/冗余删除/业务分离仍在、3.4 ❌ 布隆原地重建仍在 | 拆成 07/08/09 篇，竞态推导、时序图、方案对比原样保留作「改造前对照」，只在结论处改标注 |
| `docs/DOC_CONSISTENCY_DIAGNOSIS.md`、`docs-maintenance/文档更新执行计划.md` | 第一轮诊断与复核后的执行计划；诊断报告中「工具结果指针化已取消」「整篇规划稿」「一致无需修改」等 7 条结论已被复核推翻 | 以复核后的执行计划任务矩阵为准；本次更新按「当前代码 > 测试断言 > 学习文档 > 诊断推断」的事实优先级执行 |

> 另：`../agent/` 目录已建成 Agent 层学习笔记（编排与 SSE、RAG 一致性与预算、安全边界），见 [`../agent/README.md`](../agent/README.md)；记忆系统、工具调用、RAG 的深入讲解在那三篇里。

---

## 术语与状态标注规范

25 篇笔记写于改造前后交替的时期，规划稿与笔记都混用过「现状」「建议」「已实现」。为避免把**设计建议**误读成**当前能力**，全系列统一采用以下术语（这是跨文档一致性最底层的约定）：

| 术语 | 含义 | 当前项目中的实例 |
|---|---|---|
| **当前实现** | 当前 HEAD 源码中可检索到、且有调用方的行为 | `RedisScripts` 的 22 个脚本、`OrderConfirmation` 的 `CONFIRM`/`CONSUME`、Redisson 看门狗订单锁 |
| **改造前实现** | 历史快照里的代码，作为理解「为什么改」的对照保留 | `TokenServiceImpl` 的 `GET→SREM→DEL` 三步、`lockChat` 的 `put`+`expire` 两步、N 次 `ZREM` 认领 |
| **设计建议（未采纳）** | 规划稿提出，落地时选了别的方案 | `docs/01` P0-2 建议 `GEOSEARCH`/单成员 `ZREM` → 实际 `GEORADIUS` + 整池失效；P1-1 建议空结果写 `""` → 实际不缓存空结果；P0-1 阶段二 Redis Stream → 仍是事务内同步落库 |
| **机制已就位·写路径未接入** | 类、Lua、测试齐备，但生产代码 0 调用方 | `CityCodeUtil.invalidate(String)`：仅 `RedisCacheRegressionTest` 调用，城市表写路径未接线 |
| **部分实现** | 接口/契约存在，但缺真实适配或闭环 | `PaymentVerifier` 只有默认 503 Bean；RAG `cancel(true)` 只是逻辑取消，物理中断取决于底层客户端 |
| **设计中（骨架）** | 空类 / `return null` / 0 调用方 | `ESMemory` 空类、`MemoryAdvisor` 返回 `null` |
| **已废弃** | 提交中删除，或仅剩死代码 | `Chat2Controller`/`TestController` 与 `/order/chat/*`、`/test/rag`；`RedisKeyConstants.TOOL_RESPONSE_KEY` / `toolResponseKey()` |

配套的**四态缺陷标注**：`✅ 已修复`（代码 + 测试双重确认）、`⚠️ 部分修复`（消除了核心竞态但残留问题）、`❌ 仍存在`、`📐 设计中`。所有「改造前」代码块**一律保留、不删除**——缺陷的推导过程本身就是最好的教材；标注方式是「**篇首【当前实现对照/总览】+ 本篇勘误表**」统一声明（2026-11-13 反向审计补齐），而不是逐块改写正文。因此读到正文中以现在时陈述的旧缺陷时，先回看篇首勘误，不要当成当前实现。

---

## 配置清单与未外置默认值

理解 Redis 行为时经常要回答「这个数值从哪来」。`src/main/resources/application.yaml` 只外置了一部分，其余以 `@Value` / `@Scheduled` / Java 常量的**代码默认值**形式存在（改配置而不改代码并不会生效，这是排查生产问题的常见陷阱）：

| 配置族 | 位置 | 关键默认值 |
|---|---|---|
| Redis / Lettuce | `application.yaml` 的 `spring.data.redis` | `timeout 3000ms`、`pool.max-active 12`、`max-wait 3000ms`、`max-idle 12` |
| 对话守卫 | `application.yaml` 的 `chat.guard`；`config/ChatGuardProperties.java` 有同值兜底 | 分类缓存 `21600s`（6h）、指纹长度 `32`、限流 `30 次 / 60 秒` |
| 认证 | `application.yaml` 的 `auth` | Token `604800s`（7 天）、验证码 `300s`、冷却 `60s` |
| 订单超时 | `application.yaml` 的 `ride-order.timeout` | 接单 `15min`、到达 `10min`、支付 `30min`、轮询 `5000ms`、批量 `100` |
| **RAG（未外置）** | `agentbase/rag/RagService.java`、`QaIndexSynchronizer.java` | `@Value("${rag.search.timeout-ms:8000}")` 共享检索预算 8 秒；`@Scheduled(fixedDelayString="${rag.sync.interval-ms:5000}")` 同步间隔 5 秒；`jobs.defer()` 失败重试约 30 秒量级 |
| **ID / CORS（未外置）** | `config/IdGeneratorConfig.java`、`config/WebMvcConfig.java` | `${id.worker-id:1}`、`${id.datacenter-id:1}`；CORS 白名单默认 `http://localhost:5173,http://localhost:3000`，限流拦截器只挂 `/auth/**` 与 `/order/estimate`，鉴权拦截器挂 `/**` 并排除 `/error`、`/favicon.ico` |
| **线程池（未外置）** | `config/AsyncWorkConfig.java` | `ragExecutor` 4/8/32（`rag-search-`）、`chatExecutor` 8/32/64（`chat-request-`） |

> **设计与取舍**：把线程池、检索超时这类「运行时可能需要按环境调整」的参数写成代码默认值，优点是零配置即可跑通、示例项目更轻；代价是生产环境无法无重启动态调整，且 `application.yaml` 里看不到全貌。外置到配置中心是**可选的优化方向**，当前代码并未实现，不要写成已有能力。

---

## 统一计价与业务时区

`a8f4e33` 把散落在工具与 Service 里的价格计算收敛到 `service/base/RidePricing.java`，这是「模型不能自报价格」这条安全边界的落地：

- 起步价 `8.00` 元含 `2.0 km`；超出部分 `2.20 元/km`；`> 20.0 km` 的部分再加收 `2.20 × 0.50 = 1.10 元/km`；
- 时长按 `durationSec / 60` 用 `RoundingMode.CEILING` 向上取整，每分钟 `0.50` 元；
- 加急固定 `+5.00` 元；车型倍率 `1 → 1.0`、`2 → 1.6`、`3 → 2.5`，加急再给倍率 `+0.20`（`finalMultiplier = 车型倍率 + 0.20`）—— **`+0.20` 与费率同在 `RidePricing.calculate()` 内计算（`:49-63`），工具层没有独立加价**，只把 `estPriceRadio` 展示出去；
- `subTotal = 起步（含里程费）+ 时长费 + 远途费`，`estPrice = (subTotal + 固定加急费 5.00) × (车型倍率 + 加急倍率 0.20)`，全程 `BigDecimal` 且 `setScale(2, HALF_UP)`；非法车型/加急返回 400，里程/时长非法返回 502。

调用点：`RideOrderServiceImpl.calculatePrice()` 被估价、创建订单、结束行程复用；`OrderTool.getEstPrice()` 与 `OrderSearchTool.calcEstPriceByOrderToolRule()` 也复用同一规则。**这样设计的理由**：价格是资金相关字段，如果让 LLM 从对话里自行填写，提示词注入就能改价；服务端重算使客户端与模型传的价格都只是"展示值"。

时区与安全序列化同样是「一致性」的一部分：`util/BusinessTime.java` 固定 `ZoneId.of("Asia/Shanghai")` 生成业务时间；`TicketPoolIndex` 的 score 时间部分用 `toInstant(ZoneOffset.UTC)`，避免多实例时区/夏令时导致排序漂移；`config/Config.java` 的 `jacksonCustomizer()` 把 `BigDecimal` 与超过 `2^53-1`（`9007199254740991`）的 `Long` 序列化成字符串，防止雪花 ID 在 JavaScript 里丢精度。

---

## 跨文档引用枢纽

改一处实现，要同步检查引用它的篇目，否则会出现「同一实现两种说法」：

| 枢纽 | 影响范围 |
|---|---|
| [10 篇](10-lua-scripts-overview.md) 3.1 脚本总表 | 旧统计「15 篇、19 处引用 7 个脚本」已作废；当前统一口径为 **22 个主脚本 + 11 个内联 = 33 段**，内联脚本清单影响 01/02/11/12/13/14/18/22 篇 |
| [06 篇](06-null-sentinel-and-prewarm.md) 哨兵与 TTL 分档 | 03/04/05/07/08/09/10/14/15/16/19/21/24 篇 |
| [13 篇](13-distributed-lock.md) `RELEASE_LOCK_IF_MATCH` 与看门狗 | 01/04/07/09/10/11/12/14/15/17/18/22/23 篇 |
| [09 篇](09-lease-token.md) 版本 key `PERSIST` | [19 篇](19-multi-level-cache.md)、[12 篇](12-chat-lock-atomic.md)、[10 篇](10-lua-scripts-overview.md)、[23 篇](23-ticket-pool-zset.md)、根 `README.md` |
| [12 篇](12-chat-lock-atomic.md) `HASH_SET_WITH_EXPIRE` | 10 篇、15 处；`return EXPIRE` 与锁定入口数量 |
| `docs/01` 的 `P0-x/P1-x`、`docs/02` 的 `2.x/3.x` 编号 | 25 篇抬头的「来源」锚点，**禁止重排或改名** |
| [15](15-nearby-order-geo.md)/[16](16-driver-online-heartbeat.md)/[17](17-order-timeout-delay-queue.md) 的 TTL 对照 | 三篇互引，必须同批改 |
