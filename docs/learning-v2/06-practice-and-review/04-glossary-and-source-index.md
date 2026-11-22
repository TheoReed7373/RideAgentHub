# 第 26 章 术语、源码与 Key 索引：四个入口找回已审教材与当前源码

> **已通过第二次复审（P7-4，2026-11-21），并于 2026-11-22 独立复核**。本章是查阅工具，不是新机制讲解：每个条目只给"它是什么、在哪、别和谁混、去哪一章看完整推导"。事实来源为当前工作区源码、SQL 与测试源码的静态阅读；**实际运行：无**（未编译、未启动服务、未连接任何中间件、未读取凭据）。本次复核更正了 `/error` 排除写法与 §2.9 测试行数；检查范围、证据台账与未知项见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)。

## 学习目标

读完本章（更准确地说：用过本章几次）你应该能：

1. **在 30 秒内从任意 Redis key 模板定位**到构建它的常量或内联方法、主要读写方、默认 TTL、主讲章节。
2. **给一个术语说出四件事**：中文主词、英文或常用别名、在本项目里的具体落点、最容易和它混的邻词以及两者各保护什么。
3. **从一个失败现象反查到第一失败点**：401/403、接单 200+false 或 409、统计缓存陈旧、CONFIRM/CONSUME 失败、断开后"会话忙"、工具配对异常、旧索引返回新答案。
4. **区分五种"时间口径"**：整 key TTL、成员级心跳、ZSet 截止分数、Lua 里的 `TIME` 判断、看门狗续期；以及"每次写入都会重算"与"其他路径会覆盖"两种寿命变化。
5. **判断实现程度**：某类、某方法、某 key 是已接线、占位、未注册还是遗留；不按名字推断用途。

## 前置知识与阅读时间

**硬前置：无。** 本章是增量维护的索引，可以在任何阶段查阅；它不引入需要先读的前置机制。

**但条目本身假设你读过对应主讲章。** 索引只负责"找回"，不负责"第一次讲清"。如果你完全没读过某一章，先读那一章的学习目标与"最少概念"，再回来看条目。

**阅读时间估计**：第一次通读约 35～50 分钟（主要是扫表和试两次查阅）；之后按需查阅，每次 30 秒～2 分钟。这些是估计值，不是实测耗时。

---

<a id="demo"></a>
## 零、先做一次查阅：40 秒从一条日志回到章节与源码

这一节是本章的使用示范。你不需要先读完后面所有表，跟着做一遍即可。

**场景**：你把日志过滤出这样一行（虚构日志，只为演示路径）：

```text
WARN  TicketServiceImpl - 工单统计缓存失效失败: key=ticket:statistics:20261120
```

**目标**：40 秒内说出——这个 key 是谁写的、默认活多久、失败后旧值还能留多久、完整推导在哪一章。

| 步 | 动作 | 时间 | 你应该看到什么 |
|---|---|---|---|
| 1 | 在本章 [§3.5 工单、统计与全站限流](#keys-ticket) 里找 `ticket:statistics:` | 约 10 秒 | 模板 `ticket:statistics:{yyyyMMdd}`；类型 String（JSON）；读写方 `TicketServiceImpl#cacheTicketStatistics` / `#evictTicketStatistics`；单次 TTL `20 + random(0..10)` 秒；主讲 [S2](../03-support-and-cache/02-ticket-index-and-cache.md) |
| 2 | 在本章 [§2.3 服务与守卫](#src-service) 按类名找 `TicketServiceImpl` | 约 10 秒 | 条目给出"先读 `getTicketStatistics`，再走到 `queryTicketStatisticsFromDb`"，并指向 [S2 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删) |
| 3 | 打开 [第 13 章 工单池索引与统计缓存 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删) | 约 10 秒 | 命中/未命中/重建锁/双删/降级五条分支 |
| 4 | 回到本章 [§3.7](#time-kinds) 读"单次 TTL 不是收敛上界" | 约 10 秒 | 新写会重算 TTL，所以"提交时刻 + 30 秒"推不出系统何时收敛 |

**一次合格查阅的判据**：你能说出"真值在哪、谁写谁读、失败留下什么、完整推导在哪"，而不是只找到文件名。

**两条使用习惯**：

1. **先查 key，再查类，最后查章。** key 和类名是源码里最稳定的锚点；章节小节号会随返工微调。
2. **查不到时按"未核实"处理，不要按名字猜。** 本章 §六 列出已知的未核实项与命名差异；遇到不在表里的 key，先在 `constant/RedisKeyConstants.java` 与 `src/main` 全目录搜字符串，再判断。

---

## 一、术语索引：按使用频率分组

<a id="code-map"></a>
### 1.1 先固定编号与用词规约

本教材用两套编号：**课程代号**（F1、O2、C3、P4…）用于课程设计与协作记录，**章号**（第 8 章、第 18 章…）用于正文导航。两套编号一一对应，查表时以章号为准，因为正文里的"第 N 章"就是读者看到的顺序。

| 代号 | 章号 | 文件（相对 `docs/learning-v2/`） | 一句话主题 |
|---|---|---|---|
| F1 | 第 1 章 | [00-foundations/01-project-map.md](../00-foundations/01-project-map.md) | 业务地图与一次真实下单调用链 |
| F2 | 第 2 章 | [00-foundations/02-request-path-and-config.md](../00-foundations/02-request-path-and-config.md) | 请求分层、DTO/Entity/VO、配置来源 |
| F3 | 第 3 章 | [00-foundations/03-data-and-transactions.md](../00-foundations/03-data-and-transactions.md) | 表关系、条件更新、事务与提交后回调 |
| F4 | 第 4 章 | [00-foundations/04-redis-flux-and-sse-basics.md](../00-foundations/04-redis-flux-and-sse-basics.md) | Redis 类型/TTL/Lua 与 Flux/SSE 最小概念 |
| A1 | 第 5 章 | [01-auth/01-login-and-session.md](../01-auth/01-login-and-session.md) | 验证码→登录→令牌→请求身份 |
| A2 | 第 6 章 | [01-auth/02-authorization-and-defense.md](../01-auth/02-authorization-and-defense.md) | 身份/角色/归属、限流、异常映射 |
| O1 | 第 7 章 | [02-orders/01-order-lifecycle.md](../02-orders/01-order-lifecycle.md) | 七状态与真实迁移 |
| O2 | 第 8 章 | [02-orders/02-grab-and-concurrency.md](../02-orders/02-grab-and-concurrency.md) | 抢单两类竞态与 DB 条件更新 |
| O3 | 第 9 章 | [02-orders/03-location-and-discovery.md](../02-orders/03-location-and-discovery.md) | 司机心跳、附近订单池、乘客位置 |
| O4 | 第 10 章 | [02-orders/04-timeouts-and-recovery.md](../02-orders/04-timeouts-and-recovery.md) | 延迟队列、超时迁移与补偿 |
| O5 | 第 11 章 | [02-orders/05-pricing-and-payment.md](../02-orders/05-pricing-and-payment.md) | 计价公式、金额来源、支付门禁 |
| S1 | 第 12 章 | [03-support-and-cache/01-ticket-lifecycle.md](../03-support-and-cache/01-ticket-lifecycle.md) | 工单 0～4 状态机与角色边界 |
| S2 | 第 13 章 | [03-support-and-cache/02-ticket-index-and-cache.md](../03-support-and-cache/02-ticket-index-and-cache.md) | 工单池索引与统计缓存 |
| S3 | 第 14 章 | [03-support-and-cache/03-cache-races.md](../03-support-and-cache/03-cache-races.md) | 穿透/击穿/雪崩、哨兵、墓碑、双删 |
| S4 | 第 15 章 | [03-support-and-cache/04-locks-leases-and-ids.md](../03-support-and-cache/04-locks-leases-and-ids.md) | 锁令牌、版本租约、两套唯一 ID |
| C1 | 第 16 章 | [04-chat-and-agent/01-chat-round-and-sse.md](../04-chat-and-agent/01-chat-round-and-sse.md) | 一轮对话的锁、订阅与事件 |
| C2 | 第 17 章 | [04-chat-and-agent/02-routing-and-tool-loop.md](../04-chat-and-agent/02-routing-and-tool-loop.md) | 分类、四类 Agent、工具循环 |
| C3 | 第 18 章 | [04-chat-and-agent/03-order-confirmation.md](../04-chat-and-agent/03-order-confirmation.md) | 报价、确认、恢复、一次性消费 |
| C4 | 第 19 章 | [04-chat-and-agent/04-memory-and-tool-results.md](../04-chat-and-agent/04-memory-and-tool-results.md) | 三层记忆、窗口、工具结果反查 |
| C5 | 第 20 章 | [04-chat-and-agent/05-external-tools-and-prompts.md](../04-chat-and-agent/05-external-tools-and-prompts.md) | 地图/天气接入、反序列化、提示词边界 |
| R1 | 第 21 章 | [05-rag/01-retrieval-and-grounding.md](../05-rag/01-retrieval-and-grounding.md) | 双路检索、RRF、回 MySQL 回查 |
| R2 | 第 22 章 | [05-rag/02-index-sync-and-budget.md](../05-rag/02-index-sync-and-budget.md) | 索引同步任务、重试、检索预算 |
| P1 | 第 23 章 | [06-practice-and-review/01-trace-three-journeys.md](01-trace-three-journeys.md) | 三条完整业务旅程 |
| P2 | 第 24 章 | [06-practice-and-review/02-isolated-labs.md](02-isolated-labs.md) | 四层实验与清理 |
| P3 | 第 25 章 | [06-practice-and-review/03-review-and-self-test.md](03-review-and-self-test.md) | 间隔复习与八道综合题 |
| P4 | 第 26 章 | `06-practice-and-review/04-glossary-and-source-index.md`（本章） | 术语、源码、Key 与现象索引 |

**用词规约**（同一概念在不同章节出现过不同措辞时，全教材以这里的主词为准）：

- "**单轮执行锁**"只指 `chat:execution:{chatId}`（Redisson）；"**会话锁定**"只指 `chat:info:{chatId}` 的 `locked=true`。"对话锁定"是口语说法，正式表述用前者或后者。
- "**订单锁**"只指 `order:lock:{orderId}`；"**司机占位**"只指 `driver:active:{driverId}`。
- "**租约**"有两种：**缓存回填版本租约**（比较数据代际）与**雪花节点租约**（`id:node:` 锁 + 发号前校验）。单独说"租约"时，指前者。
- "**真值**"指"没有它就无法判断业务事实"的存储。Redis 里的数据要分三类，不能一句"只有 `user:loc:` 一份"概括：① **无 DB 副本的用户位置** `user:loc:`（丢了只能重新上报）；② **只存 Redis 的暂态凭证/协调数据**——验证码族 `auth:email_code:*`、会话版本 `auth:session-version:`、确认许可 `confirmedQuote`/`ReadyForCreate`、司机心跳 `driver:online:beat`、各类锁与版本令牌（它们没有 MySQL 副本，但语义是"凭证/协调"而非"业务事实"）；③ **有权威库的派生缓存**——`order:status:`、`driver:active:`、`chat:history:`、统计/池索引、城市码等（真值在 MySQL/Mongo，Redis 只是副本）。详见 [§3.1](#key-readme)。
- "**候选**"专指 ES 检索返回、尚未回 MySQL 校验的文档；"**答案**"专指回查后交给模型的文本。

<a id="terms-auth"></a>
### 1.2 身份、角色与归属

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 身份 | identity / 登录态 | `UserTokenContext` 里的 `UserToken`：`userId`、`role`、`sessionVersion`、`credentialStamp` | 请求体里的 `userId`、模型参数里的 `userId` 都不是身份 | A1 §五 | Q1 |
| 角色 | role | `domain/enums/UserRole`：`USER`、`ADMIN`、`SUPPORT`、`DRIVER` | 角色不是许可：注解带角色数组才检查角色 | A2 §一 | — |
| 归属 | ownership | "这条数据属于谁"：`sys_ride_order.user_id` / `driver_id`、`sys_ticket` 的发起人与处理人、`sys_chat.user_id` | 角色够 ≠ 数据是你的；归属由 Service/Controller 查 | A2 §一、§四 | Q1(c) |
| 权限注解 | `@RequirePermission` | 不带参数 = 仅要求登录；带数组 = 检查角色（大小写不敏感） | 它不是路由守卫，也不查 DB/Redis | A2 §二 | Q1(a)(b) |
| 拦截器 | interceptor | `interceptor/AuthTokenInterceptor#preHandle`：解析 `Authorization: Bearer` 与 `X-Auth-Token`，写 ThreadLocal，正常路径恒 `return true` | 它自身不抛 401；过期令牌会 `deleteToken`。但 `getToken`/`deleteToken` 的 Redis 连接异常、DB 异常会**从拦截器向上传播**（最终映射未实测），与"正常无身份放行"是两回事 | [A1 §五](../01-auth/01-login-and-session.md#五带令牌的请求拦截器threadlocal-与双层校验) | [Q1](03-review-and-self-test.md#q1-一张乘客令牌与一张别人的订单身份归属)(a) |
| 凭据指纹 | `credentialStamp` | `security/CredentialStamp`（密码摘要）；令牌校验时与 `sys_user.password` 比对 | 与会话版本是两道独立栅栏 | A1 §六 | — |
| 会话版本 | `sessionVersion` | `auth:session-version:{userId}`（`TokenServiceImpl#versionKey` 内联） | 改密/注销撤销后旧令牌失效靠它 + 指纹 | A1 §六 | — |
| 工具身份 | `ToolContext` | `ToolContextKeyConstants.USER_ID` / `CHAT_ID`，由 Agent 从登录态注入 | 不在工具 schema 里，模型无法伪造 | C2 §八 | Q1(d) |
| 两套限流 | rate limit | `request:rate:{摘要}`（HTTP 拦截器**硬编码** 120 次/60000 ms，按 IP）与 `chat:ratelimit:{userId}`（对话侧默认 30 次/60 秒，由 `ChatGuardProperties` 提供默认值与 `chat.guard.*` 覆盖入口） | 前者超限抛 429、跳过 OPTIONS；后者超限拒绝、Redis 异常 fail-open；**只有对话侧窗口/阈值可配置**，HTTP 侧写死不可配置 | [A2 §五](../01-auth/02-authorization-and-defense.md#五两套限流同一条-lua不同的触发点与故障策略) | — |
| 用户名布隆 | bloom filter | `auth:user:username:bloom`（Redisson），`add`/`rebuild` 已接线，`mightContain` 无业务调用方 | 不在可用性主链，别写成"挡穿透的主力" | A2 §七、S3 §三 | — |

<a id="terms-order"></a>
### 1.3 订单真值、预检与条件更新

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 订单真值 | source of truth | MySQL 表 **`sys_ride_order`**（`RideOrder` 的 `@TableName`） | `order:status:` 是缓存，不批准任何操作 | O1 §二、O2 §五 | Q8 |
| 状态码 | status code | `RideOrderStatus`：10 `CREATED`、20 `DRIVER_ACCEPTED`、30 `DRIVER_ARRIVED`、40 `IN_TRIP`、50 `FINISHED_WAIT_PAY`、60 `PAID`、90 `CANCELLED` | 20 不是"行程中"，40 才是 | O1 §四 | Q8 |
| 预检 | pre-check | `RedisScripts#GRAB_ORDER_ATOMIC`：`order:status` **缺失或值不匹配**返 -1，`driver:active` 不匹配返 -2，否则占位 | 预检可以拒绝，不能批准；-1 不能反推 DB 状态已推进 | O2 §二 | Q2 |
| 条件更新 | conditional update / 乐观锁 | `LambdaUpdateWrapper`：`eq(orderId).eq(isDeleted,0).eq(orderStatus,CREATED).isNull(driverId)`，影响行数才是裁决 | 0 行不说明 DB 仍是旧状态 | [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)、[§四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵) | Q2、Q8 |
| 0 行 | 0 rows | `driverAcceptOrder` 的 `updated <= 0` → `rollbackGrab` + `refreshOrderStatus` + 返回 `false` | 200+false 与 409 是不同层 | O2 §四 | Q2 |
| 订单锁 | order lock | `order:lock:{orderId}`，`RideOrderServiceImpl#withOrderLock`：`tryLock(3s)`，看门狗续期；释放分两支——`isSynchronizationActive()` 为真注册 `afterCompletion` 释放，否则在 `finally` 里**立即**释放 | 它只串行化同一订单，不保证"一人一单" | [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)、[S4 §四](../03-support-and-cache/04-locks-leases-and-ids.md#四订单锁真实调用链与事务边界) | [Q2](03-review-and-self-test.md#q2-095959-的接单与-100003-的领取订单并发) |
| 司机占位 | driver slot | `driver:active:{driverId}`，空闲值 `__IDLE__`；每次 `tryGrab` 按 DB 活跃单修复 | 不是订单状态，也不是锁 | O2 §二 | Q2、Q8 |
| 改期 | reschedule | 同一 member 再 `ZADD` 覆盖 score；`OrderDelayQueue#schedule` 在方法内算 deadline，`schedule/#cancel`，经 `afterCommit` helper：有事务同步注册提交后写，**无同步立即写**；Redis 异常被捕获只 warn | "已领取"的旧待办不会因改期而复活 | [O4 §二](../02-orders/04-timeouts-and-recovery.md#二zset-待办安排领取与重排) | [Q2](03-review-and-self-test.md#q2-095959-的接单与-100003-的领取订单并发) |
| 锚点 | anchor | 判定超时的时间字段：`updateTime`/预约时间、`driver_accept_time`、`finish_time` | 锚点变了，"是否超时"就变 | O4 §三 | Q2、Q8 |
| 领取 | claim | `RedisScripts#CLAIM_DUE_ORDERS`：`ZRANGEBYSCORE` + `ZREM` 同一次执行 | 领取 ≠ 一定处理，处理要锁内重读 | O4 §二 | Q2 |
| 状态单调推进 | monotonic sync | `RedisScripts#SYNC_ORDER_STATUS`：当前值缺失、非数字或 ≤ 新值才写入（旧值不回写；相等也重写并续 TTL） | 只保护缓存，不保护 DB | O2 §五 | Q8 |
| 逻辑删除 | soft delete | `is_deleted=0` 出现在几乎所有条件更新与查询里 | 它不是状态码，也不等于"可忽略" | F3 §二 | — |

### 1.4 事务、提交与提交后动作

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 事务边界 | transaction boundary | `@Transactional` 方法（如 `MysqlMemory#append`、`TicketServiceImpl#submitTicket`）；`RideOrderServiceImpl#createOrder` 本身没有注解 | "方法在 Service 里"不等于"有事务" | F3 §四 | Q3 |
| 提交 | commit | DB 事务成功结束；此后其他连接才能读到 | 提交成功 ≠ 后续 Redis 写成功 | F3 §3.1 | Q3 |
| 提交后动作 | after-commit action | `TransactionSynchronizationManager.registerSynchronization(...).afterCommit()`：`OrderGrabService#afterCommit`、`OrderDelayQueue#afterCommit`、`TicketServiceImpl#evictTicketStatistics`、`TokenServiceImpl#deleteAllTokensByUserId` | 事务内调用 ≠ 提交后执行；且这些 helper 在**无事务同步时立即执行**（`deleteAllTokensByUserId` 用 `isActualTransactionActive` 判定），不是"一律提交后" | [F3 §3.5](../00-foundations/03-data-and-transactions.md#35-提交后回调) | [Q2](03-review-and-self-test.md#q2-095959-的接单与-100003-的领取订单并发)、[Q3](03-review-and-self-test.md#q3-100000-的仪表盘事务与缓存窗口) |
| 两个激活标志 | synchronization vs actual transaction | `isSynchronizationActive()` 与 `isActualTransactionActive()`：前者可注册回调，后者才是真事务 | 前者用于决定"现在注册还是立刻执行"（`OrderGrabService#afterCommit`）；后者用于跳过索引读（`TicketPoolIndex#slice`、`OrderGeoPool#search`），两者不能互换 | S2 §四 | — |
| 强制传播 | `Propagation.MANDATORY` | `OrderGrabService#tryGrab`：没有外层事务直接抛异常 | 它不是"必须加锁" | O2 §二 | Q2 |
| 回滚 | rollback | 默认对 `RuntimeException` 回滚；被 catch 后是否提交取决于是否标记 rollback-only | "catch 住"不等于"已回滚" | F3 §3.2 | Q3 |
| 跨库无事务 | no distributed transaction | Redis、MongoDB 的写入不在 MySQL 事务内，只能靠提交后回调与补偿 | 不能写"整体要么全成要么全不成" | F3 §五 | Q3 |
| 提交结果未知 | unknown outcome | 调用方没收到成功响应时，提交、回调、Redis 删除都可能已发生或未发生 | 与"已确认回滚"是两种状态 | S2 §5.5、S3 §五 | Q3 |
| TTL 抖动的落点 | jitter | 统计缓存 `20 + random(0..10)` 秒；工单池/订单池固定 2 小时 | 抖动只打散同一 key 的集中过期 | S3 §七 | Q3 |

### 1.5 锁、租约与唯一 ID

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 单轮执行锁 | per-turn execution lock | `chat:execution:{chatId}`（`ChatRequestRunner#run` 内联 key）：`tryLockAsync(0,-1)` 后 `get(3s)` | 它保护"一轮不被并发跑"，不管业务状态 | C1 §七 | Q6 |
| 会话锁定 | chat lock | `chat:info:{chatId}` 的 `locked=true`，`ChatManager#lockChat` 写，`ChatServiceImpl#chat` 读后直接结束 | 它表达"这轮对话结束"，不是互斥 | [C1 §二](../04-chat-and-agent/01-chat-round-and-sse.md#二最少概念fluxsink-与两种锁) | Q6、Q4 |
| 订单锁 | order lock | 见 §1.3；串行化同一订单的状态操作 | 不跨订单，不保证一人一单 | S4 §四 | Q2 |
| 节点租约 | node lease | `id:node:{datacenterId}:{workerId}`（`IdGeneratorConfig#idNodeLease`）：启动 `tryLockAsync(0,-1)`，每次 `nextId` 先 `isHeldByThread` | 与缓存回填租约毫无关系 | S4 §六 | — |
| 缓存重建锁 | rebuild lock | `ticket:pool:rebuild:lock:{status}`、`order:geo:pool:rebuild:lock`（各 10 秒）、`ticket:statistics:lock:{yyyyMMdd}`（5 秒） | 抢不到就降级回 DB，不排队重试（统计有 3×60ms 例外） | S2 §四、S3 §四 | Q3 |
| 令牌锁 | token lock | `RedisLock`：UUID 令牌 + `SET NX EX` + 比对删除（`RELEASE_LOCK_IF_MATCH`） | 不可重入；过期后同一 key 可被他人获得 | S4 §二 | — |
| 看门狗 | watchdog | Redisson `leaseTime=-1`：`order:lock:`、`chat:execution:`、`id:node:` 由 Redisson 客户端后台定时续期 | 续期依赖**客户端存活且能连通 Redis**，不是"持锁线程存活就自动保持"；客户端崩溃/网络中断续期失败仍会到期。与"固定 TTL 的令牌锁"是两套 | [S4 §二](../03-support-and-cache/04-locks-leases-and-ids.md#二三种机制对照)、[C1 §七](../04-chat-and-agent/01-chat-round-and-sse.md#七保护对象线程与超时) | [Q2](03-review-and-self-test.md#q2-095959-的接单与-100003-的领取订单并发)、[Q6](03-review-and-self-test.md#q6-第-2-秒关掉页面sse-断开) |
| 版本租约 | lease / version | `chat:history:version:{chatId}` 单调递增；回填脚本只在版本未变时覆盖 | 它没有"持有者"，比较的是数据代际 | C4 §四 | Q5 |
| 版本令牌 | version token | `ticket:pool:version`、`order:geo:pool:version`：阻止旧快照发布，随失效换新值 | 与回填版本不同：它保护"发布的完整性" | S2 §四 | — |
| 两套唯一 ID | ID generation | 工单号 `ticket:no:{yyyyMMdd}` 的 `INCR`（业务编号）；雪花 `SnowflakeIdWorker`（主键，JSON 里转字符串） | 业务编号可读、雪花不可读；失败边界不同 | S4 §六、S1 §三 | — |

<a id="terms-confirm"></a>
### 1.6 确认、许可与消费

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 报价编号 | `quoteId` | `OrderConfirmation#quoted`：每次询价生成新 UUID，写 `chat:info:{chatId}` 的 `quoteId`/`quoteCreatedAt`/`ReadyforConfirm` | 不是订单号，也不是数据库主键 | [C3 §二](../04-chat-and-agent/03-order-confirmation.md#二报价quoted-到底写了什么) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 暂停 | break | `chat:info` 的 `break=yes`：模型调 `notifyUser`（`begin`）后停在等待用户决定 | 不是锁，也不是错误状态；首次成功处理用户决定时把 break 置 `resuming`，`resume` 调模型前再 `HDEL` 清掉 | [C3 §三](../04-chat-and-agent/03-order-confirmation.md#三准备确认notifyuser-与-begin) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 确认编号 | `confirmationId` | `/r` 请求体字段，与 `quoteId` 相等（`quoteId=confirmationId`） | 它与"许可"是两个字段，不是一件事 | [C3 §四](../04-chat-and-agent/03-order-confirmation.md#四用户决定confirm-脚本逐行) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 许可 | permit | `confirmedQuote` + `ReadyForCreate`（都等于当前 `quoteId`）；由 CONFIRM 的 `yes` 分支写入 | 许可由 Lua 写，`resume` 只清暂停字段 | [C3 §四](../04-chat-and-agent/03-order-confirmation.md#四用户决定confirm-脚本逐行) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 消费 | consume | `OrderConfirmation#consume` → `CONSUME` Lua：三字段相等且未超龄才 `HDEL` 两字段 | 与"预检/条件更新"不同：它删的是许可，不是订单状态 | [C3 §六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 恢复 | resume | `POST /chat/v2/r/{id}`：`ChatServiceImpl#resume` → `confirm` → `OrderAgent#resume`（补悬空工具应答、`HDEL` 暂停字段、进模型） | 恢复不等于写许可 | [C3 §五](../04-chat-and-agent/03-order-confirmation.md#五恢复post-chatv2rid-的真实顺序) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌)、[Q5](03-review-and-self-test.md#q5-缺一条工具应答的历史记忆批次配对) |
| 早退 | early return | `OrderTool#createOrder` 第一行：`ReadyForCreate == null` → 返回 `订单非可创建态`，不调 Lua | 早退是正常工具回执，不是异常 | [C3 §六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 超龄 | age limit | CONSUME 与 CONFIRM 的 `yes` 分支严格 `> 900000` 毫秒才拒绝；正好 900000 不触发超龄拒绝；CONFIRM 的拒绝分支不查年龄 | 不是 `>=`；年龄只是通过条件之一 | [C3 §4.5](../04-chat-and-agent/03-order-confirmation.md#45-900000-与两个时钟) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |
| 幂等 | idempotency | **没有全局幂等注解**；四种字段语义要分开：建单许可 `ReadyForCreate` 与取消预检 `readyForCancel` 由 Lua 原子 `HDEL` 消费；报价槽位 `ReadyforConfirm` 由 `quoted` 普通写、`begin` 普通读；参数槽位 `ReadyforRoute` 由 `isNewOrderReady` 写、`getEstRouteAndPrice` 读后 `redisDel`，是一次性进度标记但**不是 Lua 消费的一次性令牌** | 原子消费 ≠ 业务事务成功；`ReadyforConfirm`/`ReadyforRoute` 都不是确认令牌，不能互换 | [C3 §四](../04-chat-and-agent/03-order-confirmation.md#四用户决定confirm-脚本逐行)、[C3 §六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建)、[C3 §十五](../04-chat-and-agent/03-order-confirmation.md#十五客服取消预检) | [Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌) |

**两个 409 业务码的文案与可见层（逐字，别混）**：`confirm` 的 Lua 非 1 抛 `BusinessException(409, "确认已失效或已被使用，请重新核对订单")`，参数缺失抛 `BusinessException(400, "请提供确认编号和明确的确认结果")`；`consume` 的 Lua 非 1 抛 `BusinessException(409, "尚未取得有效的用户确认，或本次确认已使用")`。**同一个业务码 409，在不同入口的可见层完全不同**：

- CONFIRM 失败发生在 `ChatServiceImpl#resume`（`/r` 恢复任务），异常被 `AgentStreams#guard` 捕获 → 先**尝试** `tryEmitNext(AgentEvent.error("处理失败，请稍后重试"))`（通用文案，**不是**那句 409 原文），再**尝试** `tryEmitError(failure)` 终止上游。两个 `tryEmit` 的返回值都不检查；事件是否写出、届时 HTTP 响应状态**均未实测**，不承诺改 HTTP 状态，也不是同步 HTTP 409。
- CONSUME 失败发生在 `OrderTool#createOrder`（工具分派），被 `OrderAgent#executeTool` 的 `catch` 捕获 → 变成文本回执 `"Tool execution failed: 尚未取得有效的用户确认，或本次确认已使用"` 回灌模型，**既不是同步 HTTP 409，也不是 SSE error 事件**。
- `ReadyForCreate` 缺失的早退（`订单非可创建态`）是**正常工具回执**，不调 Lua、不抛异常，与"缺许可导致 CONSUME 409"是两条分支。

分层口径见 [§4.1](#layers)；逐字推导见 [C3 §4.6](../04-chat-and-agent/03-order-confirmation.md#46-java-侧的-400409-与流内呈现)、[Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌)。

### 1.7 消息历史、窗口与工具结果

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 消息历史 | message history | MySQL 表 **`sys_messages`**（`ChatMessage`），按 `id` 升序即时间序 | Redis `chat:history:` 与 Heap 都是缓存 | C4 §三 | Q5 |
| 三层记忆 | L1/L2/L3 | `HeapMemory`（进程内，无 TTL）→ `RedisMemory`（`chat:history:{chatId}`）→ `MysqlMemory`（`sys_messages`） | 不要与 MongoDB 轨迹、ES 知识库混为一谈 | C4 §二 | Q5 |
| 消息窗口 | message window | `MessageWindow#tail`：`limit` 是目标条数，会向前扩到批次边界 | 不是 `LIMIT n` 的语义 | C4 §五 | Q5 |
| 批次边界 | batch boundary | 一条 `AssistantMessage` 的 `tool_calls` 与对应 `ToolResponseMessage` 必须同进同出 | 未闭合批次只允许出现在历史末尾 | C4 §5.3 | Q5 |
| 工具回执 | tool response | 内存 `history` 里的 `ToolResponseMessage`（`executeTool` 的返回值） | 它不等于"辅助副本已写" | C2 §六、C4 §七 | Q5 |
| 工具结果副本 | tool response copy | `chat:tool:v2:{chatId}:{callId}`（Redis 24 小时）+ 表 **`sys_chat_tool_repsonse`**（源码拼写如此） | 与 `sys_messages` 是两份数据，用途不同 | [C4 §七](../04-chat-and-agent/04-memory-and-tool-results.md#七工具结果反查两份数据两种用途) | Q5 |
| 反查 | getCalling | `ToolRepPointerTool#getToolResponse`：先 `assertOwned`，再按 `callId`+`chatId` 查副本 | 是否调用由提示词驱动，不是代码保证 | C4 §7.3 | Q5 |
| 坏记录 | corrupt record | `MessageParser#unparseHistory` 遇坏行会清空并从下一条 `UserMessage` 重建；窗口只在配对错误时抛 | 解析层截断 ≠ 窗口层抛异常 | C4 §5.2 | Q5 |
| 清空与墓碑 | retire / history_deleted | `sys_chat.history_deleted=true` + `locked=true`（`ChatInfoService#retireHistory`）；此后读写报 410 | 不是"删缓存" | C4 §六 | — |
| 焦点切换 | focus chat | 当前对话 `chat:history` 24 小时；切走的对话改成 30 分钟 | 单次 TTL 不代表历史一定还在 | C4 §六 | — |

### 1.8 流、订阅与终态

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| SSE | Server-Sent Events | `text/event-stream`，控制器返回 `Flux<AgentEvent>` | 与普通 JSON 响应不是一条写出路径 | F4 §九、C1 §六 | Q6 |
| sink | `Sinks.Many` | `unicast().onBackpressureBuffer()`：单播、缓冲、尝试发出 | 不是广播，也不是"一定送达" | [C1 §二](../04-chat-and-agent/01-chat-round-and-sse.md#二最少概念fluxsink-与两种锁) | Q6 |
| 订阅 | subscribe | `publish().autoConnect(2)`：外层（HTTP/SSE）与内部各一个订阅者 | 只凑到 1 个不会连接上游 | C1 §五 | Q6 |
| 终态 | terminal | sink 的 `tryEmitComplete`/`tryEmitError` 实际成功；上游 `doFinally` 才执行 | "调用了"不等于"进入终态" | C1 §5.5 | Q6 |
| 尝试发出 | tryEmit | `tryEmitNext`/`tryEmitComplete`/`tryEmitError` 的返回值都没有检查 | 事件对象存在 ≠ 客户端收到 | C1 §5.5 | Q6 |
| 单次模型流超时 | per-stream timeout | `AgentStreams#subscribe` 的 `takeUntilOther(Mono.delay(3 分钟))` | 不是整轮上限；工具与保存没有 3 分钟计时 | C1 §7.5 | Q6 |
| 工具事件 | tool event | `AgentEvent.tool(...)`：`ToolNotifySupport` 通知监听器 | 与 `message`/`notify`/`error` 是不同事件 | C1 §六 | Q6 |

<a id="terms-rag"></a>
### 1.9 检索、索引与缓存一致性

| 主词 | 英文/别名 | 在本项目里的具体落点 | 最近邻（别混） | 主讲 | 回看 |
|---|---|---|---|---|---|
| 召回 | recall | `RagService#searchAnswers`：向量与 BM25 各取 `WINDOW_SIZE=100` | 召回不等于答案 | R1 §三 | Q7 |
| 融合 | RRF | `score = 1/(k+rank)`，`RRF_K=60`；按 `groupId` 组内去重取前五 | 不是加权求和，也不是重排模型 | R1 §三 | Q7 |
| 候选 | candidate | `Hit<QaDocument>`：尚未回 MySQL 校验的 ES 文档 | 候选里的 `answer` 不会直接给用户 | R1 §三 | Q7 |
| 回查 | authoritative re-query | `RagService#authoritative` 的五条件 + `doc.setAnswer(info.getAnswer())` | 用户看到的答案来自本次回查，不是 ES 副本 | R1 §3.6 | Q7 |
| 刷新 | refresh | `QaIndexSynchronizer#synchronizeGroup` 末尾调用；**调用不等于分片成功** | 与"事务提交"是两种可见性 | R2 §四 | Q7 |
| 索引同步任务 | sync job | 表 **`sys_qa_sync_job`**；`QaSyncJobMapper#enqueue/#claimBatch/#defer` | 任务删行 ≠ 文档可搜 | R2 §三 | Q7 |
| 预算 | deadline / budget | 共享 deadline + `remaining(deadline)`，逻辑取消 | 与"底层请求已取消"不同 | R2 §五 | — |
| 索引非权威 | index is not truth | ES 索引 `qa_knowledge_base` 只提供候选 | 与"缓存"区别：它不是业务数据的副本 | R1 §六 | Q7 |
| 空值哨兵 | null sentinel | `__NULL__`（城市码，"DB 确实没有"） | 与 `__IDLE__`（司机空闲哨兵）语义不同 | S3 §二、O2 §二 | — |
| 墓碑 | tombstone | `__TOMBSTONE__`（5 秒，"正在写、别回填"）；读路径已接线，业务写路径未接线 | 与"删除缓存"不同：墓碑本身是可读状态 | S3 §二、§六 | — |
| 双删 | delayed double delete | 提交后立即删 + 500ms 再删（`DelayedCacheEvictor`） | 双删不等于消灭所有旧值 | S2 §5.5、S3 §五 | Q3 |
| 穿透/击穿/雪崩 | penetration / breakdown / avalanche | 穿透=查不存在；击穿=热点 key 过期瞬间；雪崩=批量同时过期 | 三者判据不同，别用一个"缓存问题"概括 | S3 §一 | — |
| ready 标记 | ready marker | `ticket:pool:ready:{status}` / `order:geo:pool:ready`：区分"空"与"未建好" | 不是业务状态，也不保证内容完整 | S2 §二、O3 §三 | — |
| 整页回退 | fallback to DB page | `TicketServiceImpl#pageByTicketPoolIndex` 在版本变化/坏成员时整页回 DB | 不是"返回缓存残页 + DB 补尾" | S2 §三 | Q3 |
| 分数编码 | score encoding | `TicketPoolIndex#score`：`priority × 1e13 + epoch 毫秒`，成员为 19 位补零 ID | 浮点精度与同分字典序都要考虑 | S2 §二 | — |

---

## 二、源码索引：按维护模块，不只是文件名

### 2.1 怎么读这一节

每个条目回答四个问题：**先读哪个方法**、**它随后走到哪里**、**它属于哪条业务线**、**完整推导在哪一章**。路径基准是 `src/main/java/com/fancy/rideagenthub/`（代码块与 SQL 写完整仓库相对路径），定位优先用"类 + 方法"，不写行号。

三条阅读规则：

1. **从控制器进、从 Service 分叉、从守卫看失败。** 顺序读最省时间。
2. **看到"未接线"就以源码为准。** §2.8 单独列出当前没有业务调用方的类、方法与前缀。
3. **同名方法看参数。** 例如 `TicketServiceImpl#getTicketStatistics()` 无参、`RideOrderServiceImpl#getOrderDetail` 有两个重载，行为不同。

<a id="src-controller"></a>
### 2.2 控制器（HTTP 入口）

| 入口 | 先读什么 | 走到哪里 | 主讲 |
|---|---|---|---|
| `controller/AuthController.java`（`/auth`） | `POST /email-code` → `AuthServiceImpl#sendEmailCode`；`POST /register` → `#registerByEmailCode`；`POST /login/password`、`/login/email-code`；`POST /logout`；`DELETE /account` | `EmailCodeService#sendCode`、`TokenServiceImpl#saveToken/#deleteAllTokensByUserId` | A1 |
| `controller/RideOrderController.java`（`/order`） | `POST /order` → `RideOrderServiceImpl#createOrder`；`POST /driver/accept` → `#driverAcceptOrder`；`POST /{id}/pay` → `#payOrder`；`POST /{id}/cancel` → `#cancelOrder` | 分别进入计价/轨迹、订单锁 + 抢单、支付门禁、取消分支 | O1、O2、O5 |
| `controller/RideOrderController.java`（司机侧） | `POST /driver/arrive`、`/driver/start`、`/driver/finish`、`GET /driver/tasks`、`GET /driver/pool/page`、`POST /driver/location`、`POST /driver/offline`、`GET /driver/current` | `driverArriveStart`/`startRide`/`finishRide`、`DriverLocationServiceImpl#reportLocation`、`OrderGeoPool#search` | O1、O3 |
| `controller/ChatController.java`（`/chat/v2`） | `POST /c/{id}` → `ChatRequestRunner#run(..., false)`；`POST /r/{id}` → `run(..., true)`；`GET /restore` → `ChatManager#getRestorableChat`（只判候选并续期 60 分钟）；`POST /restore` → `ChatManager#restoreChat`；`POST /lock/{id}`；`POST /newuuid` | `ChatServiceImpl#chat/#resume`、`ChatInfoService#restoreChat`（`PERSIST`）、`ChatManager#lockChat` | C1、C3 |
| `controller/TicketController.java`（`/ticket`） | `POST /submit` → `TicketServiceImpl#submitTicket`（控制器从身份取 `userId`、按 DRIVER 定 `userType`）；`POST /admin/page` → `#getAdminTicketPage`；`GET /admin/statistics` → `#getTicketStatistics` | **（可选）关联订单归属校验 → 参数校验 → 编号生成 → 落库 + 初始系统消息 → 提交后失效**；分页走索引或回 DB | [S1 §三](../03-support-and-cache/01-ticket-lifecycle.md#三提交工单身份订单归属编号与写入)、[S2](../03-support-and-cache/02-ticket-index-and-cache.md) |
| `controller/RagController.java`（`/rag`，全部 `@RequirePermission({"ADMIN"})`） | `POST /admin/qa/update-answer`、`/update-question`、`/admin/qa/delete`、`POST /qa/add`、`GET /search` | `RagService#updateAnswer/#updateQuestion/#deleteQA/#addQA/#searchAnswers` | R1、R2 |
| `controller/UserController.java`（`/user`） | `POST /loc` → `UserServiceImpl#saveUserLocation`；`GET /loc`；`POST /rebuild` → `UserUsernameBloomFilterService#rebuild`；`/admin/*` 为管理员用户管理 | `user:loc:` Hash；布隆重建 | A1、A2、S3 |
| `controller/UserPOIController.java`（`/user/poi`） | `GET`、`GET /{id}`、`POST`、`PUT`、`DELETE /{id}`、`GET /order` | `UserPOIServiceImpl`；DTO/VO 混用是 F2 的例子 | F2、C5 |

**权限速查**：`@RequirePermission`（不带参）只要求登录；`{"DRIVER"}`、`{"ADMIN"}`、`{"ADMIN","SUPPORT"}`、`{"USER"}` 检查角色。鉴权拦截器在 `WebMvcConfig` 里挂 `/**`、**排除 `/error` 与 `/favicon.ico`**；限流拦截器只挂 `/auth/**` 与 `/order/estimate`。拦截器正常路径恒 `return true`、自身不返回 401，但 `getToken`/`deleteToken` 的依赖异常会向上传播（见 [§1.2](#terms-auth)）。401 的真正来源见 [§4.2](#phenomena)。

<a id="src-service"></a>
### 2.3 服务与守卫（`service/impl`、`service/base`）

| 类/方法 | 先读什么 | 走到哪里 | 主讲 |
|---|---|---|---|
| `RideOrderServiceImpl#createOrder` | 方法开头的参数校验与默认值 | 估价 → **内部重算正式 traceId（UUID，覆盖预览引用）** → `OrderRouteService#addOrder`（Mongo）→ `rideOrderMapper.insert`（`mongo_trace_id`=正式 ID） | O1 |
| `RideOrderServiceImpl#driverAcceptOrder` | `withOrderLock` 的 `tryLock(3s)` | `OrderGrabService#tryGrab` → DB 条件更新 → `orderDelayQueue.schedule` | O2 |
| `RideOrderServiceImpl#withOrderLock` | 锁 key 与 `afterCompletion` 释放 | Redisson `RLock`，leaseTime 未指定 → 看门狗 | S4 §四 |
| `RideOrderServiceImpl#handleTimeoutOrder` | 先看它只做"加锁" | `handleTimeoutOrderUnderLock` 的 `switch`（只有 10/20/50）→ `handleAcceptTimeout`/`handleArriveTimeout`/`handlePayTimeout` → `systemCancel`/`reschedule` | O4 |
| `RideOrderServiceImpl#assertCanViewOrder` | 三个角色分支 | USER 比 `user_id`；DRIVER 比 `driver_id`；SUPPORT/ADMIN 放行 | A2 §四 |
| `RideOrderServiceImpl#payOrder` | **入参校验 → `withOrderLock` → `getRequiredOrder`(404) → 归属(403"只能支付自己的订单") → 状态须 50(409)**，最后才到 `PaymentVerifier` | 默认 verifier 抛 503"支付渠道尚未接入…"；`payOrderUnderLock` 条件更新 50→60 后 `syncOrderStatus`+`delayQueue.cancel` | [O5 §四](../02-orders/05-pricing-and-payment.md#四支付链与验单门禁) |
| `ChatServiceImpl#chat` | `isLocked` → `canStartChat` → `assertOwned` → 实际读到 `break=yes` 才尝试发暂停提示 | 分类（`resolveClassification`）→ 分发到四个 Agent | C1、C2 |
| `ChatServiceImpl#resume` | `canStartChat` 后的 `confirm` | `OrderConfirmation#confirm` → `orderAgent.resume` | C3 |
| `ChatServiceImpl#resolveClassification` | 缓存读 → 模型调 → 缓存写 | `ClassificationCache#get/#put`、`ChatProperties.CLASSIFIER_*` | C2 §二 |
| `TicketServiceImpl#submitTicket` | **（可选）订单归属校验、参数校验都在前** | 之后才 `generateTicketId`（`ticket:no:`）→ `ticketMapper.insert` + 初始系统消息 → `afterTicketMutation` | [S1 §3.2](../03-support-and-cache/01-ticket-lifecycle.md#32-校验顺序与订单归属) |
| `TicketServiceImpl#getAdminTicketPage` | 判断"能不能用索引" | `pageByTicketPoolIndex`（内部 `TicketPoolIndex#slice`）；不可用或失败 → `pageAdminTicketsFromDb` | S2 §三 |
| `TicketServiceImpl#getTicketStatistics` | 命中**立即返回**；未命中且拿到锁才双检→重建；没拿到锁最多等 3×60ms（每轮重读缓存）后**直查 DB（不写缓存）** | `getCachedTicketStatistics`/`tryLockTicketStatistics`/`cacheTicketStatistics`/`queryTicketStatisticsFromDb` | S2 §五 |
| `OrderGrabService#tryGrab` | 三件事的顺序：司机行锁 → 活跃单 → Lua | `userAuthMapper ... FOR UPDATE`、`driver:active` 修复、`GRAB_ORDER_ATOMIC` | O2 §二 |
| `OrderDelayQueue#schedule/#claimDue` | `schedule` 经 `afterCommit` 安排写入，`claimDue` 直接执行领取脚本 | `ZADD` / `CLAIM_DUE_ORDERS`（`ZRANGEBYSCORE`+`ZREM`） | O4 §二 |
| `OrderTimeoutJob#pollTimeoutOrders/#recoverOverdueOrders` | 两个 `@Scheduled` 的分工 | `claimDue` → `handleTimeoutOrder`；DB 补偿按 10/20/50 筛选 | O4 §三 |
| `DriverGeoIndex#goOnline/#refreshIfExist/#location` | `update()` 的 `mode` 参数 | `UPDATE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION` | [O3 §二](../02-orders/03-location-and-discovery.md#二司机在线geo-与成员级心跳) |
| `OrderGeoPool#add/#remove/#search` | `add` 只调 `remove` | 提交后 `INVALIDATE_ORDER_GEO_POOL`；`search` 内双检 + 重建 + 发布 | [O3 §三](../02-orders/03-location-and-discovery.md#三附近订单池失效重建与-db-校验) |
| `TicketPoolIndex#syncByTicketId/#slice` | `syncByTicketId` 只做失效 | `INVALIDATE_TICKET_POOLS`；`slice` 内双检 + 发布 + `isCurrent` | S2 §四 |
| `OrderConfirmation#quoted/#begin/#confirm/#consume` | 四个方法各自的 Lua 与字段 | `chat:info` 的 `quoteId`/`break`/`confirmedQuote`/`ReadyForCreate` | C3 |
| `ChatRequestRunner#run` | `Flux.defer` 里的取锁与 `assertOwned`；三条出口分开：取锁失败→`Flux.just(error 数据事件)`；同步异常→`Flux.error`；执行器拒绝→直接 `tryEmitError`（无 error 数据事件） | `publish().autoConnect(2)`、`doFinally` 解锁、`chatExecutor`；guard 内异常→error 数据事件+`tryEmitError` | C1 §七 |
| `RedisLock#tryLock/#unlock` | `setIfAbsent(key, token, ttl)` | `RELEASE_LOCK_IF_MATCH` 比对删除 | S4 §二 |
| `DelayedCacheEvictor#evictAfter` | 单线程调度器；`schedule(task, 500ms)` 的 500ms 是**调度延迟**，非开始执行的硬上界 | 延迟到点才 `delete`；调度被拒（`RejectedExecutionException`）记 warn"延迟删除任务未被接收"、删除失败记 warn"延迟删除缓存失败"，均不抛调用方 | S3 §五 |

### 2.4 配置与拦截

| 类 | 关键点 | 影响的行为 | 主讲 |
|---|---|---|---|
| `config/WebMvcConfig` | 拦截器注册顺序与路径：限流只挂 `/auth/**`、`/order/estimate`；鉴权拦截器挂 `/**`、**排除 `/error` 与 `/favicon.ico`** | 并非全站都有限流；401 不来自拦截器自身（依赖异常另会传播） | [A2 §五](../01-auth/02-authorization-and-defense.md#五两套限流同一条-lua不同的触发点与故障策略) |
| `interceptor/AuthTokenInterceptor` | `extractToken` 两个头；过期时 `deleteToken`；正常路径 `return true` | 身份来源与"只认人不拒人" | A1 §五 |
| `interceptor/RequestRateLimitInterceptor` | 内联 key `request:rate:`、`120/60000` | IP 维度限流，超限 429 | [A2 §五](../01-auth/02-authorization-and-defense.md#五两套限流同一条-lua不同的触发点与故障策略) |
| `aspect/PermissionAspect` | `@annotation` 切点；无身份 401、角色不符 403 | 端点拒绝层 | A2 §二 |
| `config/AuthProperties` | `tokenTtlSeconds=604800`、`emailCodeTtlSeconds=300`、`emailCodeCooldownSeconds=60`；yaml 覆盖同名项 | 三个 TTL 的唯一来源 | A1 §一 |
| `config/ChatGuardProperties` | `classifyCacheTtlSeconds=21600`、`fingerprintLength=32`、`rateLimitMax=30`、`rateLimitWindowSeconds=60` | 分类缓存与对话限流 | C2 §三、A2 §五 |
| `config/RideOrderTimeoutProperties` | `accept=15`、`arrive=10`、`pay=30` 分钟；`pollIntervalMs=5000`、`batchSize=100`；`@PostConstruct` 校验正数 | 三条超时与扫描节奏 | O4 §三 |
| `config/IdGeneratorConfig` | `id.worker-id:1`、`id.datacenter-id:1`；`RLock` + `NodeLease` | 节点租约与发号校验 | S4 §六 |
| `config/PaymentConfig` | `@ConditionalOnMissingBean` 的 503 实现 | 默认"支付未接入" | O5 §四 |
| `config/AsyncWorkConfig` | `ragExecutor`（4/8/32）、`chatExecutor`（8/32/64） | 两个线程池隔离 | C1 §七、R2 §五 |
| `config/ChatProperties` | 领域提示词契约（含分类器提示词） | 提示词不是鉴权 | C5 §八 |
| `config/Config#jacksonCustomizer` | `Long`/`BigDecimal` 序列化 | 雪花 ID 在 JSON 里是字符串 | F2 §九 |

### 2.5 Agent 与工具

| 类 | 读什么 | 注册了哪些工具（`ToolCallbacks.from`） | 主讲 |
|---|---|---|---|
| `agents/DailyAgent` | `runLoopStep` 的循环与 `executeTool` 的 `case` 分派 | `CurWeatherTool`、`GeoRegeoTool`、`POISearchTool`、`UserPOITool`、`DailyTool`、`ToolRepPointerTool`、`POIAroundTool` | C2 §四 |
| `agents/OrderAgent` | `handleToolCalls` 的 `notifyUser` 特判 | `OrderTool`、`POISearchTool`、`ToolRepPointerTool`、`TrafficTool`、`UserPOISearchTool`、`GeoRegeoTool` | C2 §六、C3 |
| `agents/SupportAgent` | `executeTool` 的 `catch` → `Tool execution failed:`；工具注册与手写分派 | `OrderSearchTool`、`TicketTool`、`ToolRepPointerTool`、`RagTool` | [C2 §五（§5.4）](../04-chat-and-agent/02-routing-and-tool-loop.md#54-supportagent-的注册与分派)、[§九](../04-chat-and-agent/02-routing-and-tool-loop.md#九失败与边界)、[S1 §三](../03-support-and-cache/01-ticket-lifecycle.md#三提交工单身份订单归属编号与写入) |
| `agents/FallbackAgent` | 无工具、单次模型流 | **无**（纯文本兜底） | C2 §四 |
| `agents/AgentStreams` | `subscribe` 的 3 分钟 `takeUntilOther`、`guard` 的统一异常出口 | 被四个 Agent 共用 | C1 §7.5 |
| `agentbase/tool/OrderTool#createOrder` | 第一行的 `ReadyForCreate` 判空 | `consume` → `rideOrderService.createOrder` → `lockChat` | C3 §六 |
| `agentbase/tool/TicketTool#createTicket` | `userType=1` 与 `ToolContext` 取 `userId` | `TicketServiceImpl#submitTicket` | S1 §三 |
| `agentbase/tool/ToolRepPointerTool#recordCalling` | 失败只 warn | `ToolResponseMemory#save` | [C4 §七](../04-chat-and-agent/04-memory-and-tool-results.md#七工具结果反查两份数据两种用途) |
| `agentbase/tool/RagTool#searchKnowledgeBase` | 空结果文案 | `RagService#searchAnswers` | R1 §二 |
| `agentbase/tool/GeoRegeoTool#geo` | `CityCodeUtil#getCityCode` 的读接线 | 城市码缓存读路径 | S3 §二、C5 §四 |

### 2.6 记忆、对话状态与持久化

| 类 | 读什么 | 关键点 | 主讲 |
|---|---|---|---|
| `agentbase/memory/MessageMemory` | `get` 的三层顺序与两次取租约 | L1 命中就短路；`save` 先 MySQL 后缓存 | C4 §三 |
| `agentbase/memory/HeapMemory` | `Entry(messages, version)` 与 `compute` | 无 TTL、无容量上限；`clear` 保留递增版本 | C4 §二 |
| `agentbase/memory/RedisMemory` | `overwriteIfLeaseValid` 的两步 | 暂存 key → `RENAME`；版本不匹配则丢弃 | C4 §四 |
| `agentbase/memory/MysqlMemory` | `append` 的 `FOR UPDATE` on `sys_chat` | 会话被清空时抛 410 | C4 §六 |
| `agentbase/memory/MessageWindow#tail` | `boundaries` 与 `pending` | `limit` 是目标条数；未闭合批次只允许在末尾 | C4 §五 |
| `agentbase/memory/MessageParser#unparseHistory` | `recovering` 标记 | 坏行清空后从下一条 UserMessage 重建 | C4 §5.2 |
| `agentbase/memory/ToolResponseMemory` | `key()` 的内联拼接 | 物理 key 是 `chat:tool:v2:...`，不是常量的 `tool:` | [C4 §七](../04-chat-and-agent/04-memory-and-tool-results.md#七工具结果反查两份数据两种用途) |
| `agentbase/chatinfo/ChatManager` | `lockChat` 的 `HASH_SET_WITH_EXPIRE` | 原子 `HSET`+`EXPIRE`，避免永久锁死 | [C1 §二](../04-chat-and-agent/01-chat-round-and-sse.md#二最少概念fluxsink-与两种锁) |
| `service/base/ChatInfoService` | `findOwned` 的三类拒绝 | 404/403/410；`retireHistory` 落库墓碑 | C4 §六 |
| `agentbase/rag/RagService` | `searchAnswers` → `authoritative` | 双路 + RRF + 组内去重前五 | R1 §三 |
| `agentbase/rag/QaIndexSynchronizer` | `synchronize` 的 `claimBatch` | `synchronizeGroup`：删旧 → 读 info → 写文档 → refresh | R2 §四 |
| `service/base/OrderRouteService` | `mongoTemplate.save` | 集合 `order_routes`，只存轨迹 | F1 §八、O1 |

### 2.7 Mapper、SQL 与表名

**表名以 `@TableName` 与 DDL 为准**（`src/main/resources/sql/init.sql`）。读者最容易踩的坑是"章节里的简写"与"真实表名"不一致。

| 实体 | 真实表名 | 主要 Mapper | 说明 |
|---|---|---|---|
| `UserAuth` | `sys_user` | `mapper/UserAuthMapper` | 账号、角色、`status`、`is_deleted`、`password`；`selectUsernamesForBloom` 供布隆重建 |
| `UserPOI` | `sys_user_poi` | `mapper/UserPOIMapper` | 用户收藏地点；`UserPOIController` 的增删改查 |
| `RideOrder` | **`sys_ride_order`** | `mapper/RideOrderMapper` | 订单真值；状态、司机、金额、锚点时间都在这里 |
| `OrderRoute`（轨迹，`@Document(collection="order_routes")`） | MongoDB 集合 `order_routes` | 无 Mapper（`OrderRouteService` 用 `mongoTemplate`） | 字段 `mongoTraceId`(@Id)/`estRoute`/`estPolyline`/`realPolyline`；**不是 `RideOrder`** |
| `Chat` | `sys_chat` | `mapper/ChatMapper` | `chat_id`、`user_id`、`locked`、`history_deleted` |
| `ChatMessage` | `sys_messages` | `mapper/ChatMessageMapper` | 消息历史真值；一行一条 JSON |
| `ChatToolResponse` | **`sys_chat_tool_repsonse`** | `mapper/ChatToolResponseMapper` | 源码拼写如此（`repsonse`），不是笔误修正目标 |
| `Ticket` | `sys_ticket` | `mapper/TicketMapper` | 工单；`ticket_id` 是业务编号（`T+日期+类型+序号`） |
| `TicketChat` | `sys_ticket_chat` | `mapper/TicketChatMapper` | 工单聊天与处理记录 |
| `CityCode` | `bus_citycode` | `mapper/CityCodeMapper` | 城市码真值（`sql/bus_citycode.sql`） |
| `QaInfo` | `sys_qa_info` | `mapper/QaInfoMapper` | 答案真值 |
| `QaElasticMap` | `sys_qa_es` | `mapper/QaElasticMapMapper` | 问题 → ES 文档映射；`elastic_id` 是 ES 文档 id |
| `QaSyncJob` | `sys_qa_sync_job` | `mapper/QaSyncJobMapper` | `enqueue`/`claimBatch`（`FOR UPDATE SKIP LOCKED`）/`defer` |

**SQL 位置**：`src/main/resources/sql/init.sql`（建表与初始数据）、`src/main/resources/sql/bus_citycode.sql`（城市码）、`src/main/resources/sql/migrations/20261113_security_consistency.sql`（一次性迁移，含 ALTER；不要重复执行）。

<a id="unwired"></a>
### 2.8 目前未接线、占位与遗留

按当前源码核对，以下项**不能被写成"已生效的保护"**：

| 项 | 现状 | 影响 |
|---|---|---|
| `agentbase/memory/ESMemory` | 空占位类，全仓库无引用 | 不存在"ES 记忆层" |
| `agentbase/memory/MemoryAdvisor` | 返回 `null`，无调用方 | 不是 Spring AI 的 Advisor 接线 |
| `RedisKeyConstants#toolResponseKey`（`tool:{callId}`） | 只有定义，**全仓库无调用方**；真实 key 是 `chat:tool:v2:{chatId}:{callId}` | 按 `tool:` 查 Redis 会查不到 |
| `CityCodeUtil#invalidate` | 读路径已接线；**业务侧无调用方**（仅测试） | 墓碑写路径未进入业务链 |
| `UserUsernameBloomFilterService#mightContain` | 无业务调用方 | 布隆只写不读 |
| `agentbase/tool/ChatStatusTool` | 定义了 `lockSession` 工具，但四个 Agent 都没有注册它 | 模型无法调用"锁定对话" |
| `agentbase/tool/RegeoTool` | Spring `@Component`、定义了 `regeo` 工具，但**没有任何 Agent 注入或注册它**（全仓库无引用） | 实际生效的是 `GeoRegeoTool` |
| `TokenServiceImpl#addTokenToUserIndex` | 直接抛 `UnsupportedOperationException` | 令牌与索引只能由 `saveToken` 原子写 |
| `ChatInfoService#unlockChat` | 仅被 `restoreChat` 调用 | 不是通用解锁入口 |
| `MysqlMemory#getLastN` | 存在但 `MessageMemory` 用的是 `getAll` + 窗口 | "只取最近 N 条"发生在窗口层，不在 SQL 层 |
| `RedisMemory#append/#getLastN`（`APPEND_WITH_VERSION_BUMP`） | 主链保存走 `appendIfVersionMatch`；这两个入口只有测试/辅助路径调用（C4 §2.3 同口径） | 别把无条件追加写成历史写入主链 |

> 判定标准：有业务调用方（控制器/服务/Agent 的 `case` 分支）才算接线；只有测试调用、只有字段注入、只有 Javadoc 都不算。

<a id="tests"></a>
### 2.9 代表性测试（只读，未运行）

路径基准 `src/test/java/com/fancy/rideagenthub/`。**门控（系统属性）、真实依赖、替身是三件事，逐项分开**：

| 测试 | 覆盖 | 门控 | 真实依赖 | mock/替身 |
|---|---|---|---|---|
| `OrderGrabRegressionTest` | 接单分支矩阵 | 无 | 无中间件 | 大量 mock |
| `OrderGrabIntegrationTest` | 并发接单/一人一单/回滚/看门狗 | `test.mysql.port` | 真 MySQL（建/清/删随机 schema）+ 真 Redis（`test.redis.port`） | 部分 Mapper mock |
| `OrderLockRegressionTest` | 订单锁等待/释放顺序 | 无 | 无 | mock `RLock` |
| `OrderTimeoutRegressionTest` | 三条超时迁移与补偿 | 无 | 无 | mock |
| `OrderDelayQueueIntegrationTest` | 真 ZSet 改期/领取 | `test.redis.port` | 真 Redis | — |
| `TicketPoolRegressionTest` | 池排序/版本/重建/回表（纯逻辑） | 无 | 无中间件 | mock 协作者 |
| `TicketPoolRedisIntegrationTest` | 池索引真 Redis 行为 | `test.redis.port` | 真 Redis | mock DB Mapper |
| `TicketPoolDatabaseIntegrationTest` | 池索引真库回表 | **仅 `test.mysql.port`** | 真 MySQL（随机 schema `ticket_pool_regression_<uuid>`）**＋真 Redis**（初始化用 `test.redis.port`：`LettuceConnectionFactory(127.0.0.1, test.redis.port)` + 真 `RedisLock`/`TicketPoolIndex`） | mock `TicketChatMapper`/`UserAuthMapper`/`DelayedCacheEvictor` |
| `CacheConsistencyRegressionTest` | 记忆租约/保存顺序与工单双删 | 无 | 无 | mock |
| `SecurityRepairRegressionTest` / `SecurityRedisIntegrationTest` | 身份/归属/令牌边界 | 后者 `test.redis.port` | 后者真 Redis | 混合 |
| `GeoPoolRegressionTest` / `GeoPoolRedisIntegrationTest` | 订单池失效/重建/搜索 | 后者 `test.redis.port` | 后者真 Redis | 混合 |
| `ChatGuardRegressionTest` / `ChatGuardRedisIntegrationTest` | 分类缓存与限流 | 后者 `test.redis.port` | 后者真 Redis | 混合 |
| `RedisCacheRegressionTest` | 历史版本租约/回填、城市码墓碑/哨兵、锁脚本 | `test.redis.port` | 真 Redis | mock Mapper |
| `agentbase/rag/RagConsistencyRegressionTest` | 替身下的调用/回查断言（见下） | 无 | **无真实 ES、无事务** | `QaInfoMapper`/`QaElasticMapMapper`/`QaSyncJobMapper`/ES/Embedding 全 mock |
| `RepairDatabaseIntegrationTest` | 真库写入事务证据（见下） | `test.mysql.port` | 真 MySQL（随机 schema `ride_repair_<32hex>`，外层 `TransactionTemplate` READ_COMMITTED） | Redis/ES/Embedding/`DelayedCacheEvictor`/`RedisLock`/`TicketPoolIndex`/`UserAuthMapper` mock |

<a id="test-reading"></a>
#### 测试阅读与证据边界（练习回链直达此处）

**测试名不是断言。** 沿用例的准备、调用、断言和替身核查覆盖范围。本章与 P3 均为静态阅读。

- **`RagConsistencyRegressionTest`（8 个 `@Test`，无真实中间件或事务）**：新增用例校验两个 Mapper 的插入、入队且不调用 ES，改答案用例将更新返回值设为 1 并校验入队且不调用 ES。`answerAlwaysComesFromCurrentDatabaseRecord` 断言 DB 答案覆盖旧副本，`failedOrDeletedDocumentsAreNeverServedFromEsAlone` 在未准备有效映射的场景断言结果为空，`staleQuestionVectorIsRejectedUntilResynchronized` 断言问题失配时剔除候选。`failedIndexSynchronizationRetainsJob` 校验失败后延后任务且不删行，`completedIndexSynchronizationAcknowledgesJob` 校验同步替身成功后删行，`embeddingSharesTheOverallSearchDeadline` 断言阻塞的 Embedding 按预期抛出且没有 ES 调用。
- **`RepairDatabaseIntegrationTest`（真库写入事务证据）**：外层 `TransactionTemplate`（`DataSourceTransactionManager`，READ_COMMITTED）包裹用例；`failedBatchRollsBackBothQaDataAndSyncJobs`（整批回滚三表计数 0）、`retiredHistoryCannotBeRecreatedByLaterMessageSave`（断言 `BusinessException` 与消息行数为 0，未断言异常码）、`newMutationAfterLockedSyncCannotLoseItsJob`（持锁期间入队不丢）、`closingTicketPreventsConcurrentConfirmationFromOverwritingClosedState`（行锁）。**真 MySQL 随机 schema；Redis/ES/Embedding 等为 mock。**
- **`TicketPoolDatabaseIntegrationTest`**：门控只看 `test.mysql.port`，但 `setupServices` 还用 `test.redis.port` 起真实 Redis（`LettuceConnectionFactory` + 真 `RedisLock`/`TicketPoolIndex`），仅 `TicketChatMapper`/`UserAuthMapper`/`DelayedCacheEvictor` 是 mock。**只写"需要真 MySQL"会漏掉它对真实 Redis 的依赖。**

---

## 三、Redis key 索引

<a id="key-readme"></a>
### 3.1 读表须知

1. **模板以生成函数为准。** 本章的"物理 key"一列给出拼好的形态；占位符 `{...}` 是变量，不是字面量。
2. **TTL 只写"这一次写入设成多少"。** 是否被其他路径覆盖、是否每次写都重算，写在"TTL 与续期"列与 §3.7。
3. **"真值"列区分三类，不能一句"只有 `user:loc:` 一份"概括。** ① 无 DB 副本的用户位置 `user:loc:`；② 只存 Redis 的暂态凭证/协调数据（验证码族、`auth:session-version:`、确认许可、司机心跳、锁与版本令牌）；③ 有权威库的派生缓存（`order:status:`、`driver:active:`、`chat:history:`、统计/池索引、城市码）。详见 [§1.1](#code-map) 的"真值"定义。
4. **未核实的字段标"未知"。** 本章不把 Redisson 内部实现细节当作本项目保证。

<a id="keys-auth"></a>
### 3.2 认证与会话

| Key 模板（物理形态） | 类型 | 谁写 / 谁读 | TTL 与续期 | 真值与异常边界 | 主讲 |
|---|---|---|---|---|---|
| `auth:token:{token}` | String（`UserToken` JSON） | 写 `TokenServiceImpl#saveToken`（Lua `SAVE`）；读 `#getToken`、`#deleteToken`；拦截器每请求读 | `auth.token-ttl-seconds`=604800 秒；**读取不续期**（无滑动过期）；过期另由 `expiresAtEpochMs` 判断 | 令牌本身即会话状态；账号状态与密码摘要以 `sys_user` 为准。**`getToken` 的首次 Redis GET 在 try 外**；try 只捕获 `JsonProcessingException \| IllegalArgumentException`（格式无效→降级无身份），**Redis 连接异常、DB 异常（含 try 内 `currentVersion` 的 GET）会向上传播**；`deleteToken` 首次 GET 也在 try 外。依赖故障从拦截器上抛（最终映射未实测，[A1 §九](../01-auth/01-login-and-session.md#九边界与未验证)），与"正常无身份放行"分开 | [A1 §一](../01-auth/01-login-and-session.md#一最少概念三方状态与五组-key)、[§5.2](../01-auth/01-login-and-session.md#52-gettoken一次请求的完整校验) |
| `auth:user_tokens:{userId}` | Set | 写 `SAVE` 的 `SADD`；读 `REVOKE` 的 `SMEMBERS`；`REMOVE` 的 `SREM` | `SAVE` 里 `EXPIRE tokensKey, ttl×2` = 1209600 秒 | 只是反向索引；丢失只影响"按用户批量撤销" | A1 §六 |
| `auth:session-version:{userId}` | String（整数） | 写 `REVOKE` 的 `INCR`；读 `#currentVersion`（`saveToken` 校验、`getToken` 比对） | **无 TTL**（代码里没有任何 `EXPIRE`） | 只有 Redis 一份；归零会让旧 `sessionVersion` 重新匹配，故仍需 `credentialStamp` 兜底 | A1 §六 |
| `auth:email_code:{scene}:{email}` | String（6 位数字） | 写 `EmailCodeService#sendCode`（`ISSUE`）；读/删 `#verifyAndConsume`（`VERIFY`） | `auth.email-code-ttl-seconds`=300 秒 | 验证码真值只有 Redis；`reserve`（事务内）或 `DEL`（无事务） | [A1 §二](../01-auth/01-login-and-session.md#二发送验证码冷却与一次性签发)、[§三](../01-auth/01-login-and-session.md#三注册验证码的事务内预留) |
| `auth:email_code:{scene}:{email}:attempts` | String（计数） | `VERIFY` 的 `INCR` | `PEXPIRE` = 验证码剩余 `PTTL` | ≥5 次直接拒绝并删码 | A1 §二 |
| `auth:email_code:{scene}:{email}:owner` | String（issueId） | `ISSUE` 写；`CLEANUP`/`COMPLETE` 比对后删 | 与验证码同 TTL | 防止"发送失败清理"误删别人新发的码 | A1 §二 |
| `auth:email_code:{scene}:{email}:claim` | String（claimId） | `VERIFY` 的 `reserve` 写；`COMPLETE` 删 | `PX` = 验证码剩余 `PTTL` | 事务内预留的占位；回滚时由 `COMPLETE` 清 | A1 §三 |
| `auth:email_code:cd:{scene}:{email}` | String | `ISSUE` 写；`isInCooldown` 读 | `auth.email-code-cooldown-seconds`=60 秒 | 冷却窗口，不是验证码；发送失败会被 `CLEANUP` 删除 | A1 §二 |
| `auth:user:username:bloom` | Redisson `RBloomFilter` | 写 `#addUsername`、`#rebuild`；读 `#mightContain`（无业务调用方） | 应用不设 TTL；`rebuild` = `delete` + `tryInit(1_000_000, 0.01)` | 只写不读；`add` 失败不回滚注册 | A2 §七 |

<a id="keys-chat"></a>
### 3.3 对话、确认与记忆

| Key 模板（物理形态） | 类型 | 谁写 / 谁读 | TTL 与续期 | 真值与异常边界 | 主讲 |
|---|---|---|---|---|---|
| `chat:info:{chatId}` | Hash | 写：`ChatManager#lockChat`（`locked`）、`ChatInfoService#restoreChat`（`locked=false`+`persist`）、`ChatServiceImpl#chat`（`classification`）、`OrderConfirmation#quoted/#begin/#confirm`、`OrderAgent`（`break`）、`OrderTool`（报价槽位与 `ReadyforRoute`） | **四条改 TTL 的路径**（C3 §2.4）：`lockChat` 原子脚本设 3600 秒；`quoted` 设 24 小时；`ChatManager#getRestorableChat`（`GET /restore`）续期 60 分钟；`restoreChat` 直接 `PERSIST` → **无 TTL**。普通 `HSET` 不续期 | 会话状态与报价槽位共用同一 key；丢失后 `locked` 可回退到 `sys_chat.locked`，报价槽位无备份 | C1 §二、C3 §二 |
| `chat:history:{chatId}` | List（每条一行 JSON） | 业务写 `RedisMemory#appendIfVersionMatch`（`MessageMemory#save`）、`#overwriteIfLeaseValid`（回填）；读 `#getAll`；`#expire` 改 TTL；`#append/#getLastN` 仅测试/辅助路径（§2.8） | 当前对话 24 小时（`FOCUS_CHAT_TTL`）；切走的对话改 30 分钟（`NON_FOCUS_CHAT_TTL`）；追加成功（版本匹配且缓存存在）时 `PEXPIRE` 重设 | 缓存；真值 `sys_messages` | C4 §二、§六 |
| `chat:history:version:{chatId}` | String（整数） | 写：`APPEND_*`、`OVERWRITE_IF_VERSION_MATCH`、`CLEAR_*` 均 `INCR` 后 `PERSIST`；`CURRENT_HISTORY_VERSION` 缺失时置 0、带 TTL 时 `INCR` 自愈，末尾总 `PERSIST` | **无 TTL**（四个脚本全部 `PERSIST`） | 单调递增；刻意不过期，避免版本归零让旧租约复活 | C4 §四 |
| `chat:history:staging:{chatId}:{uuid}` | List | 写 `overwriteIfLeaseValid` 第一步（`MULTI`+`RPUSH`+`EXPIRE`）；被 `RENAME` 消费 | 60 秒兜底（`STAGING_TTL`） | 只在回填瞬间存在；失败靠 TTL 清理 | C4 §四 |
| `chat:execution:{chatId}` | Redisson `RLock` | `ChatRequestRunner#run`（**内联 key**，不在 `RedisKeyConstants`） | `tryLockAsync(0, -1)`：等待 0；leaseTime -1 → Redisson 看门狗续期；`doFinally` 释放 | 保护"一轮不被并发跑"；获取结果 false → 单事件 error；解锁结果不检查 | C1 §七 |
| `chat:tool:v2:{chatId}:{callId}` | String（工具结果原文） | 写 `ToolResponseMemory#save/#cache`（`recordCalling` 调用）；读 `#get` | 24 小时；`get` 命中直接返回，未命中回源 DB 后再写 | 辅助副本；`sys_chat_tool_repsonse` 是持久层；Redis 失败只 warn | [C4 §七](../04-chat-and-agent/04-memory-and-tool-results.md#七工具结果反查两份数据两种用途) |
| `chat:classify:{上下文特征指纹}:{组合指纹}` | String（分类结果） | 写 `ClassificationCache#put`；读 `#get`；入参 `(contextFeature, contextText, prompt)` | `chat.guard.classify-cache-ttl-seconds`=21600 秒；`<=0` 等于关闭 | **两段指纹**：第一段=`fingerprint("CTXv2"+encode(contextFeature))`；第二段=`fingerprint("v2"+encode(contextFeature)+encode(contextText)+encode(prompt))`，是**上下文特征+上下文正文+本轮输入三者组合**（常量形参名 `promptFingerprint` 易误导，并非仅 prompt）；`encode` 带长度前缀。只缓存非空结果；Redis 异常按未命中放行 | [C2 §3.1](../04-chat-and-agent/02-routing-and-tool-loop.md#31-key-的真实构成) |
| `chat:ratelimit:{userId}` | ZSet（成员=每次调用 UUID） | 写/读 `ChatRateLimiter#tryAcquire`（`SLIDING_WINDOW_RATE_LIMIT`） | 窗口=`chat.guard.rate-limit-window-seconds`（默认 60 秒）×1000，**可配置**；**仅脚本成功放行分支 `PEXPIRE`**，拒绝分支（`used>=limit` 返 -1，在 `ZADD/PEXPIRE` 之前）不刷 TTL | 计数在 Redis；Redis 异常时 fail-open 放行 | [A2 §五](../01-auth/02-authorization-and-defense.md#五两套限流同一条-lua不同的触发点与故障策略) |

<a id="keys-order"></a>
### 3.4 订单、位置与超时

| Key 模板（物理形态） | 类型 | 谁写 / 谁读 | TTL 与续期 | 真值与异常边界 | 主讲 |
|---|---|---|---|---|---|
| `order:status:{orderId}` | String（状态码） | 写 `OrderGrabService#tryGrab`（**仅缺失时** `setIfAbsent` 回源，TTL 300）、`#syncOrderStatus`（`afterCommit` 单调写）；删 `#refreshOrderStatus`（`afterCommit`）；读 `tryGrab` 的 Lua | 300 秒；**仅 `SYNC_ORDER_STATUS` 实际写值时重算**——当前缺失/非数字/`≤`新值才 `SET EX`（相等也重写续 TTL），更新的旧值被跳过则**不动 TTL** | 缓存；真值 `sys_ride_order.order_status`；陈旧会导致"预检放行、DB 拒绝" | [O2 §5.4](../02-orders/02-grab-and-concurrency.md#54-key-与-ttl-汇总) |
| `driver:active:{driverId}` | String（订单号或 `__IDLE__`） | 写 `tryGrab`（`SET EX 300`）与 Lua 占位；删 `rollbackGrab`（`RELEASE_LOCK_IF_MATCH` 比对删除，**立即执行**，由 `afterCompletion`(非 COMMITTED) 或 `releaseDriver` 的 `afterCommit` 触发）、`releaseDriver`（`afterCommit`：有同步注册、无同步立即） | 300 秒；每次 `tryGrab` 覆盖 | 缓存；每次接单按 DB 活跃单修复，故缓存丢失不破坏"一人一单" | [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线) |
| `order:lock:{orderId}` | Redisson `RLock` | `RideOrderServiceImpl#withOrderLock` | `tryLock(3s)`；leaseTime 未指定 → 看门狗；释放分两支：`isSynchronizationActive()` 真→`afterCompletion` 释放，否则 `finally` **立即**释放 | 只串行化同一订单；等待失败 → 409"订单正在处理中，请稍后重试" | [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)、[S4 §四](../03-support-and-cache/04-locks-leases-and-ids.md#四订单锁真实调用链与事务边界) |
| `delay:order:timeout` | ZSet（score=epoch 毫秒，member=orderId） | 写 `OrderDelayQueue#schedule/#cancel`（仅 `schedule` 计算 deadline，经 `afterCommit` helper：**有同步注册提交后、无同步立即**；Redis 异常捕获只 warn）；读+删 `#claimDue`（`CLAIM_DUE_ORDERS`，用 `System.currentTimeMillis()`） | **无 TTL**（整 key 即待办队列，过期会丢任务） | 只存"什么时候该检查"，不存订单状态；服务不可用靠每分钟 DB 补偿扫描 | [O4 §二](../02-orders/04-timeouts-and-recovery.md#二zset-待办安排领取与重排)、[§三](../02-orders/04-timeouts-and-recovery.md#三轮询处理与数据库补偿) |
| `order:geo:pool` | GEO（ZSet） | 写 `PUBLISH_ORDER_GEO_POOL`；读 `SEARCH_ORDER_GEO_POOL` | `PEXPIRE` 2 小时（发布时设，不因单笔订单续期） | 索引；真值 `sys_ride_order` 的 `CREATED` 行 | [O3 §三](../02-orders/03-location-and-discovery.md#三附近订单池失效重建与-db-校验) |
| `order:geo:pool:ready` | String（`version:count`） | 发布脚本**最后一条**命令 `SET ... PX`（先 `PEXPIRE pool` 再写 ready） | 2 小时 | 区分"空池"与"未就绪"；ready 之前失败 → 无 ready → `READ` 返回空触发重建。"ready 写成功后出错仍可能被读成完整池"是**工单池**的分支（其脚本先 ready 后 `PEXPIRE`，见 S2 §4.6），两个脚本顺序不同 | [O3 §三](../02-orders/03-location-and-discovery.md#三附近订单池失效重建与-db-校验) |
| `order:geo:pool:version` | String（UUID） | 写 `INVALIDATE_ORDER_GEO_POOL` 换新值、`setIfAbsent` 初始化 | **无 TTL** | 版本令牌；旧快照发布会被拒 | [O3 §三](../02-orders/03-location-and-discovery.md#三附近订单池失效重建与-db-校验) |
| `order:geo:pool:rebuild:lock` | String（UUID 令牌） | `OrderGeoPool#search` + `RedisLock` | 10 秒 | 重建互斥；抢不到直接降级 DB | [O3 §三](../02-orders/03-location-and-discovery.md#三附近订单池失效重建与-db-校验) |
| `driver:geo:online` | GEO（ZSet） | 写 `UPDATE_DRIVER_LOCATION`；删 `REMOVE_DRIVER_LOCATION`、`READ_DRIVER_LOCATION`/`UPDATE`(refresh) 的过期清理 | **无整 key TTL**——设了会连在线成员一起删整个集合；离线/异常成员由脚本按 beat 判定后 `ZREM` 清理 | 成员存活由 beat 判定，不由 key 过期 | [O3 §2.1](../02-orders/03-location-and-discovery.md#21-为什么不直接给共享-key-设-ttl) |
| `driver:online:beat` | Hash（field=driverId，value=epoch 毫秒） | 写 `UPDATE_DRIVER_LOCATION`（`HSET` Redis `TIME`）；读 `READ_DRIVER_LOCATION` | **无整 key TTL**；成员窗口 60 秒（`ONLINE_TTL`），脚本内用 Redis `TIME` 比对；**beat 缺失、`now < beat`（未来时间戳）、`now-beat>=窗口`（过旧）或 GEO 成员缺失**都会 `HDEL`+`ZREM` 清理 | 成员级 TTL 的真实实现；时间以 Redis `TIME` 为准，不是 Java 时钟 | [O3 §二](../02-orders/03-location-and-discovery.md#二司机在线geo-与成员级心跳) |
| `user:loc:{userId}` | Hash（`latitude`/`longitude`/`address`） | 写 `UserServiceImpl#saveUserLocation`（三次独立 `HSET`）；读 `#getUserLocation` | **无 TTL** | **只有 Redis 一份**，无 MySQL 副本；三次写不原子；缺失后需重新上报 | [O3 §1.5](../02-orders/03-location-and-discovery.md#15-乘客当前位置另一套数据)、C5 |

<a id="keys-ticket"></a>
### 3.5 工单、统计与全站限流

| Key 模板（物理形态） | 类型 | 谁写 / 谁读 | TTL 与续期 | 真值与异常边界 | 主讲 |
|---|---|---|---|---|---|
| `ticket:no:{yyyyMMdd}` | String（`INCR` 结果） | 写/读 `TicketServiceImpl#generateTicketId`（`TICKET_SEQUENCE` 内联脚本） | `EXPIRE 172800`（2 天），且**只在 `TTL<0` 时设置** | 业务编号的唯一性依赖 Redis；不可用时建单失败 | S1 §三、S4 §六 |
| `ticket:statistics:{yyyyMMdd}` | String（`TicketDataVO` JSON） | 写 `#cacheTicketStatistics`；读 `#getCachedTicketStatistics`；删 `#evictTicketStatistics`（立即 + 500ms） | `20 + random(0..10)` 秒；每次写重算 | 派生快照；真值四条独立 `COUNT`；删除失败、任务被拒都只记日志 | S2 §五 |
| `ticket:statistics:lock:{yyyyMMdd}` | String（UUID 令牌） | `#tryLockTicketStatistics` + `RedisLock` | 5 秒 | 重建互斥；未拿到锁者 3×60ms 等待后降级直查 | S2 §五 |
| `ticket:pool:{statusCode}` | ZSet（score=优先级×1e13+毫秒，member=19 位补零 ID） | 写 `PUBLISH_TICKET_POOL`；读 `READ_TICKET_POOL`；删 `INVALIDATE_TICKET_POOLS` | `PEXPIRE` 2 小时（发布时） | 索引；空 ZSet 会被 Redis 自动删除，故必须有 ready | [S2 §二](../03-support-and-cache/02-ticket-index-and-cache.md#二表键与排序编码)、[§四](../03-support-and-cache/02-ticket-index-and-cache.md#四冷池重建双检锁版本与发布) |
| `ticket:pool:ready:{statusCode}` | String（`version:count`） | 发布脚本先 `SET ready ... PX` 再 `PEXPIRE pool` | 2 小时 | 区分"空"与"未就绪"；`READ` 用 `count` 校验成员数；ready 写成功后 `PEXPIRE` 出错仍可能被 `READ` 当完整池（pool 可能无 TTL，S2 §4.6） | S2 §二 |
| `ticket:pool:version` | String（UUID，**所有状态池共享**） | 写 `INVALIDATE_TICKET_POOLS` 换新值、`setIfAbsent` 初始化 | **无 TTL** | 换值即让所有旧池失配；缓存重建必须带版本与锁令牌 | S2 §四 |
| `ticket:pool:rebuild:lock:{statusCode}` | String（UUID 令牌） | `TicketPoolIndex#slice` + `RedisLock` | 10 秒 | 重建互斥；抢不到整页回 DB | S2 §四 |
| `request:rate:{摘要}` | ZSet | `RequestRateLimitInterceptor#preHandle`（**内联 key**，摘要=`CredentialStamp.of(remoteAddr)`；**硬编码** `"60000","120"`） | **仅脚本成功放行才 `ZADD`+`PEXPIRE` 60000ms 刷 TTL**（拒绝在 ZADD 前返回、不刷）；120 次/60 秒不可配置 | 只挂在 `/auth/**` 与 `/order/estimate`；**跳过 OPTIONS**；超限抛 429 | [A2 §五](../01-auth/02-authorization-and-defense.md#五两套限流同一条-lua不同的触发点与故障策略) |
| `id:node:{datacenterId}:{workerId}` | Redisson `RLock` | `IdGeneratorConfig#idNodeLease`（**内联 key**，默认 1/1） | `tryLockAsync(0,-1)` → 看门狗续期（依赖客户端连通 Redis）；`nextId` 前 `isHeldByThread` 校验，租约失效拒绝发号；容器销毁 `NodeLease.close` 释放 | 节点号占位；抢不到抛"雪花ID节点号已被其他实例使用" | [S4 §6.2](../03-support-and-cache/04-locks-leases-and-ids.md#62-雪花-id) |

<a id="keys-city"></a>
### 3.6 城市码

| Key 模板（物理形态） | 类型 | 谁写 / 谁读 | TTL 与续期 | 真值与异常边界 | 主讲 |
|---|---|---|---|---|---|
| `amap:city_code:{city}` | String（编码 / `__NULL__` / `__TOMBSTONE__`） | 写 `CACHE_CITY_CODE_IF_VERSION_MATCH`（`SET NX EX`）、`INVALIDATE_CITY_CODE`（墓碑 `EX 5`）；读 `CityCodeUtil#getCityCode` | 正常值与空值哨兵都是 7 天；墓碑 5 秒 | 缓存；真值 `bus_citycode`；读到墓碑只查 DB 不回填；业务侧 `invalidate` 无调用方 | S3 §二 |
| `amap:city_code:version:{city}` | String（整数） | 写 `INVALIDATE_CITY_CODE` 的 `INCR`；读回填脚本与 `getCityCode` | **无 TTL** | 版本令牌；防止墓碑过期后旧值回填 | S3 §二、§六 |

<a id="time-kinds"></a>
### 3.7 五种时间口径：不要把一种当成全部

这是本章最容易被略过、也最容易答错的一节。同一个项目里"什么时候失效"有五种完全不同的机制：

| 口径 | 项目实例 | 它保证什么 | 它不保证什么 |
|---|---|---|---|
| **整 key TTL** | `auth:token:` 604800s；`chat:history:` 24h/30min；`chat:tool:v2:` 24h；`chat:classify:` 21600s；`ticket:statistics:` 20～30s；城市码 7 天 | 单个 key 在最后一次设置 TTL 后能活多久上限 | 中途被重写会重新计时；不是系统收敛上界 |
| **成员级心跳** | `driver:geo:online` + `driver:online:beat`（60 秒窗口） | 单个司机的"在线"由最后一次心跳决定 | 整 key 不设 TTL（设了会连在线成员一起删整个集合）；离线/异常成员由脚本按 beat 判定后 `HDEL`+`ZREM` 清理，**不是"离线成员永不过期"** |
| **ZSet 截止分数** | `delay:order:timeout`（score=到期 epoch 毫秒） | 到期时间是可查询、可覆盖、可领取的数值 | 队列本身没有 TTL；未到期的任务会一直在；`claimDue` 用 `System.currentTimeMillis()` 做上界 |
| **Lua 内的时间判断** | `UPDATE_DRIVER_LOCATION`/`READ_DRIVER_LOCATION`/`SLIDING_WINDOW_RATE_LIMIT` 用 `redis.call('TIME')`；`CONFIRM`/`CONSUME` 用 Java 传入的毫秒 | 判断与写入在同一原子执行内完成 | 两套时钟来源不同：前者是 Redis 时间，后者是应用服务器时间 |
| **看门狗续期** | `order:lock:`、`chat:execution:`、`id:node:`（Redisson，leaseTime=-1） | 客户端存活且能连通 Redis 时由后台定时续期，锁不因固定到期而丢 | 不是"持锁线程存活就自动保持"；客户端崩溃/网络异常续期失败仍会丢锁；`unlockAsync` 结果不检查 |

**另有两点必须单独记**：

1. **每次写入重算（限定实际写值）**：`driver:active`（每次 `tryGrab` 覆盖，300 秒）；`order:status` **仅 `SYNC_ORDER_STATUS` 实际写值时**重算 300 秒（被单调跳过的更新不动 TTL）；`chat:history`（追加成功时重设 24 小时）；统计缓存（每次写重算 20～30 秒）。因此"上次写入时间 + TTL"只在没有新写入时成立。
2. **其他路径会覆盖 TTL**：`chat:info:` 有四条改 TTL 的路径（C3 §2.4）——`ChatInfoService#restoreChat` 调 `persist` 变成**无 TTL**；`OrderConfirmation#quoted` 设 24 小时；`ChatManager#lockChat` 与 `ChatManager#getRestorableChat`（`GET /restore`）设 3600 秒/60 分钟。读 `chat:info:` 的 TTL 时，必须问"最后写它的是哪条路径"。

<a id="prefix-vs-key"></a>
### 3.8 约定前缀不等于物理 key

`constant/RedisKeyConstants.java` 是**大部分** key 的唯一来源，但不是全部。以下三类差异会导致"按常量搜不到"：

| 情况 | 具体 | 后果 |
|---|---|---|
| 常量存在但从未使用 | `TOOL_RESPONSE_KEY = "tool:"` 与 `toolResponseKey(String callId)`；全仓库只有定义，无调用方 | 真实工具结果 key 是 `chat:tool:v2:{chatId}:{callId}`（`ToolResponseMemory#key` 内联） |
| 内联拼接，常量类里没有 | `chat:execution:`、`auth:session-version:`、`ticket:no:`、`request:rate:`、`id:node:`、`chat:tool:v2:` | 查 `RedisKeyConstants` 找不到，要在组件里搜字符串 |
| 同一前缀多用途 | `chat:info:{chatId}` 既是会话状态（`locked`/`break`/`quoteId`），又是报价槽位（`START_LAT`…`EST_PRICE`/`ReadyforRoute`） | 看到这个 key 的 TTL，不能推断"对话会存活多久" |
| 构建函数有防御分支 | `amapCityCodeKey(null)` 返回裸前缀 `amap:city_code:`（带尾冒号） | 只在实际入参为 null 时出现；`getCityCode` 在调它之前已返回 null，正常链路不产生 |

**核验方法**：在 `src/main` 全目录搜 `"<前缀>` 字符串，再核对调用方；只搜常量名会漏掉内联 key。

---

## 四、按机制与现象反查

<a id="layers"></a>
### 4.1 先分层：同一个"失败"可能出现在六个地方

| 层 | 典型产生位置 | 对外可见 | 不能推断 |
|---|---|---|---|
| 拦截器 | `AuthTokenInterceptor#preHandle`（只认人） | 正常路径不产生错误；依赖异常会中断请求 | 不能把 401 归给它 |
| 权限切面 | `PermissionAspect#checkPermission` | `UnauthorizedException`→401「请先登录」；`ForbiddenException`→403「无权访问该资源」 | 不检查数据归属 |
| 业务服务 | Service 里的 `BusinessException(code, msg)` | 进入 MVC 异常处理时，HTTP 状态按异常码映射（`GlobalExceptionHandler#response`：400～599 直接映射，越界压 400）；`Result.code` 保留原值 | 不能从文案反推"哪一行" |
| 工具内部 | Agent 的 `executeTool` 的 `catch` | 工具回执字符串（常带 `Tool execution failed: ` 前缀） | 不能推断模型是否转述、SSE 是否送达 |
| 缓存/索引 | Redis 写失败、发布校验失败、`isCurrent` 失败 | 通常只记日志并降级 DB | 不能推断"数据已经一致" |
| 流内·数据事件 | `AgentStreams#guard` 的 `tryEmitNext(AgentEvent.error(...))`；`ChatRequestRunner` 取锁失败的 `Flux.just(error)` | 尝试发一条 `error` **数据事件**（流仍可正常完成，不等于终止） | 不能推断客户端收到、HTTP 状态 |
| 流内·终止信号 | `guard` 与执行器拒绝的 `tryEmitError`、同步异常的 `Flux.error` | Reactor **终止信号**，与 error 数据事件是两件事：guard 先尝试数据事件再尝试终止；执行器拒绝**只** `tryEmitError`、不发数据事件 | 不能推断 HTTP 状态已改写（写出时机/响应状态未实测） |

<a id="phenomena"></a>
### 4.2 八个现象入口

| 现象 | 先判断什么 | 下一跳源码 | 主讲 |
|---|---|---|---|
| 401 vs 403 | **401 不止切面一处**：切面「请先登录」（无身份）、登录「用户名或密码错误」/「账号状态已变化」/「账号不存在」、签发「登录状态已变化，请重新登录」/「无法签发缺少身份信息的令牌」——按**入口 + 文案**定位；403 再分"角色不够"（切面「无权访问该资源」）与"数据不属于你"（服务归属校验） | `PermissionAspect#checkPermission`、`AuthServiceImpl#loginByPassword/#loginByEmailCode`、`TokenServiceImpl#saveToken`、`RideOrderServiceImpl#assertCanViewOrder`、`GlobalExceptionHandler#handleUnauthorized` | [A2 §二](../01-auth/02-authorization-and-defense.md#二真实端点对照)、[§四](../01-auth/02-authorization-and-defense.md#四越权与失败案例) |
| 接单 200 + `data=false` | 预检通过但 DB 条件更新 0 行 | `RideOrderServiceImpl#driverAcceptOrder` 的 `updated <= 0` 分支 | [O2 §四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵) |
| 接单 409 | 五种来源：Lua `-1`（`order:status` **缺失或值不匹配**）、Lua `-2`、订单锁等待失败、`tryLock` 被中断、缓存缺失回源后 `ORDER_NOT_FOUND`（完整矩阵见 O2 §四） | `OrderGrabService#tryGrab`、`withOrderLock` 的 `tryLock` 失败/中断分支、`grabFailureMessage` | [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)、[§四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵) |
| 统计数字陈旧 | 旧值残留的**一种相容解释**是并发读回填，但**不是默认根因**；要结合双删时序、延迟调度是否受理/执行、有无后续删除/覆盖/驱逐判断（见[练习 4](#ex-4)） | `TicketServiceImpl#evictTicketStatistics`、`DelayedCacheEvictor#evictAfter` | [S2 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删)、[S3 §五](../03-support-and-cache/03-cache-races.md#五双删删除之后仍有旧值回填) |
| CONFIRM / CONSUME 失败 | 同为业务码 409 但**文案与可见层不同**：CONFIRM「确认已失效或已被使用，请重新核对订单」（参数缺失另抛 400「请提供确认编号和明确的确认结果」），在 `/r` 恢复任务里被 `guard` 捕获→SSE error 尝试；CONSUME「尚未取得有效的用户确认，或本次确认已使用」，在工具分派里被 `executeTool` 捕获→文本回执。**都不是同步 HTTP 409**（详见 [§1.6](#terms-confirm)） | `OrderConfirmation#confirm/#consume` 两段 Lua、`ChatServiceImpl#resume`、`OrderAgent#executeTool`、`AgentStreams#guard` | [C3 §四](../04-chat-and-agent/03-order-confirmation.md#四用户决定confirm-脚本逐行)、[§六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建) |
| 断开页面后"会话正在处理中" | 单轮执行锁仍被持有一轮未结束 | `ChatRequestRunner#run` 的 `acquired=false` 分支 | [C1 §五](../04-chat-and-agent/01-chat-round-and-sse.md#五竞争时间线与订阅边界)、[§七](../04-chat-and-agent/01-chat-round-and-sse.md#七保护对象线程与超时) |
| 工具配对异常 | `MessageWindow` 抛"工具记录不完整" | `MessageWindow#tail` 的 `invalid()` | [C4 §5.3](../04-chat-and-agent/04-memory-and-tool-results.md#53-messagewindowtail按批次边界扩展) |
| 旧索引返回新答案 / 检索为空 | 答案来自回查；空可能来自召回到回查任一层 | `RagService#searchAnswers/#authoritative`、`QaIndexSynchronizer#synchronizeGroup` | [R1 §3.6](../05-rag/01-retrieval-and-grounding.md#36-第-5-步回-mysql-核对)、[R2 §四](../05-rag/02-index-sync-and-budget.md#四synchronizegroup-的真实顺序与可见性) |

<a id="exercises"></a>
### 4.3 五个索引练习

每个练习只给"现象 + 索引路径 + 源码入口"，**结论与回看折叠**。做完再展开；只看结论等于背答案。

<a id="ex-1"></a>
#### 练习 1：从 key 查到对话与订单两章（跨两章）

**现象（单次快照，采样时点 T0）**：客服转来一条会话。在 T0 对 `chat:info:{chatId}` 做一次 `HGETALL`，**明确读到**：`break=yes`、`quoteId=Q1`（一个具体 UUID）、`pendingToolCall` 存在、`confirmedQuote` 与 `ReadyForCreate` **都不存在**、`OrderId` **不存在**、`locked` 不存在。同一时刻 `order:status:{N1}` 读到 `10`（N1 是题设给的订单号，**尚未核实属于本轮**）。用户说"我点了确认但没反应，司机也没看到我的单"。

**索引路径**：

1. 本章 [§3.3](#keys-chat) 查 `chat:info:` → 四条 TTL 路径（[C3 §2.4](../04-chat-and-agent/03-order-confirmation.md#24-24-小时-ttl-的适用条件同一个-key-有四个改-ttl-的路径)）、字段含义。
2. 本章 [§1.6](#terms-confirm) 查"暂停/许可/消费" → `break=yes` 是暂停；首次成功处理用户决定时把 break 置 `resuming`；许可由 CONFIRM 的 yes 分支写。
3. 本章 [§3.4](#keys-order) 查 `order:status:` → 缓存，真值 `sys_ride_order`。
4. 打开 [C3 §7.4](../04-chat-and-agent/03-order-confirmation.md#74-t4消费成功但创建失败或响应丢失)（消费成功但创建失败/响应丢失）、[C3 §六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建) 与 [O2 §五](../02-orders/02-grab-and-concurrency.md#五luakey-与-ttl)。

**源码入口**：`service/base/OrderConfirmation#quoted/#begin/#confirm/#consume`、`agents/OrderAgent#resume`、`agentbase/tool/OrderTool#createOrder`、`agentbase/chatinfo/ChatManager#lockChat/#getRestorableChatId`、`service/impl/RideOrderServiceImpl#createOrder`、`service/impl/ChatServiceImpl#chat/#resume`。

<details>
<summary>应得到的结论与答错回看（练习 1）</summary>

**第一问：T0 快照能说明什么**

- 快照记录的是当前暂停标记和待应答工具调用。基础时序是 `begin` 写入 `break=yes` 和 `pendingToolCall`，不写许可。CONFIRM 从 `yes` 首次接受用户决定时写 `resuming`，同决定的合格重放可以直接返回 1。恢复正常推进时，`OrderAgent#resume` 在调模型前删除暂停字段。CONSUME 只删除许可字段。
- 假设这是 Q1 的**第一次暂停**，且直到 T0 没有其他流程改写、删除或重建这个 Hash，可以手推两条与快照相容的时序。**A**：请求未到达本会话的确认脚本，例如前端未发出，或请求在校验或限流处结束。**B**：脚本读取到 `break=yes`，但确认编号不匹配或确认时报价超龄，返回 0，原暂停状态仍在。T0 无法区分两种时序。应核对那次请求的会话、确认编号、是否进入脚本和实际分支。
- 去掉“第一次暂停”的假设后，T0 无法排除 Q1 更早成功确认。举例，第一次接受 Q1，恢复时清掉暂停字段，CONSUME 删除许可，建单随后失败。若后来模型再次调用 `notifyUser`，且原 Hash 中仍有 Q1 和 `ReadyforConfirm`，`begin` 可以在同一报价上重新写 `break=yes` 和新的 `pendingToolCall`。只有重新报价成功写入时才会换编号。整 Key 消失本身无法留下 T0 中的字段。
- **OrderId 和 Redis locked 缺失只描述 T0 的 Hash**。`ChatManager#isLocked` 在读不到 Redis locked 时回查 MySQL。应检查 `sys_chat.locked` 和业务行，单凭缺字段无法断定之前未成功锁定或本轮未建单。

- `order:status:{N1}=10` 只是缓存值：不证明 N1 属于本轮、不证明订单真实存在、不证明没司机看过。真值在 `sys_ride_order`。

**第二问（独立后置观测 T1）：订单已建但绑定失败——三个具体分支**

设另一次会话在 T1 按**真实订单号**在 `sys_ride_order` 查到一行（orderStatus=10、driver_id=null、mongo_trace_id=某正式 UUID），但 `chat:info` 的 `OrderId` 缺失。`OrderTool#createOrder` 的真实顺序是 `consume`（删许可）→ `rideOrderService.createOrder`（建单）→ 清预览轨迹 → **读三个展示字段**（EST_DISTANCE_KM/EST_TIME/EST_PRICE）→ `lockChat`（先 DB 后 Redis）→ 写 `OrderId` → 成功回执。以下按三个独立分支推导。假设会话行存在，初始 DB locked 为 false、Redis locked 和 OrderId 均缺失，本次 CONSUME 成功，期间没有其他写入、清理或过期。表中记录各失败点刚发生时的状态，T1 明确回读的 OrderId 缺失用于排除响应丢失但已写入的情况。

| 分支 | 订单行 | DB locked | Redis locked | OrderId | 许可 | 工具回执 |
|---|---|---|---|---|---|---|
| ① 读展示字段失败 | 已存在（insert 已成功） | false（lockChat 未执行） | 无 | 无 | 已被 CONSUME 删 | `Tool execution failed: …`（executeTool catch） |
| ② lockChat 失败 | 已存在 | DB 明确未写则 false；对应行更新成功则 true；结果未知时回查 | 视失败点 | 无（未到写 OrderId） | 已删 | `Tool execution failed: …` |
| ③ 写 OrderId 失败/响应丢失 | 已存在 | **true** | **true**（lockChat 已成功） | 无**或不确定**（需回读） | 已删 | `Tool execution failed: …` |

- 分支③在确认 OrderId 未写入时，订单与会话锁定均已落下，会话绑定仍缺失。分支②还要看 DB 与 Redis 各自的更新和回读结果。`ChatInfoService#lockChat` 未检查受影响行数，实际案例须查对应会话行。
- **命令响应丢失需回读**：lockChat 的 HASH_SET、写 OrderId 若响应丢失，是否实际写入要另行 `HGET`/`HGETALL` 回读，不能凭"没收到响应"断言"没写入"（[C3 §7.5](../04-chat-and-agent/03-order-confirmation.md#75-t5redis-脚本客户端响应丢失)、[§7.7](../04-chat-and-agent/03-order-confirmation.md#77-成功失败未知三态)）。
- **正式轨迹 ID 在订单服务内重算**：`RideOrderServiceImpl#createOrder` 内部 `traceId=UUID.randomUUID()` 覆盖预览引用并写 `sys_ride_order.mongo_trace_id`；建单成功后工具尝试用预览阶段的 ID 删除 MongoDB 预览轨迹，删除异常只记日志。查询新订单要用正式 ID。查业务行按**真实订单号**（成功回执里的 orderId）；**缺订单号时 userId+时间窗口只能筛候选，不能凭预览 traceId/userId 认定命中本轮**。
- `ChatManager#getRestorableChatId` 先取该用户最近的未清空会话，再检查 classification 为 ORDER 且当前 OrderId 缺失。这个判断只使用会话字段，建单结果要查业务库。

**普通对话提示（前置条件 + 尝试发送）**：`ChatServiceImpl#chat` 依次过 `isLocked`（未锁）→ `canStartChat` → `assertOwned`（归属）→ **实际读到 break=yes** 才**尝试**发 notify"请先确认或拒绝当前订单，再继续对话"+complete（正常结束，非 error）。**单次 T0 快照不能代替"那次普通请求当时"的观察**——请求时会话可能已锁/已恢复/break 已变。

**两件事分开**：对话暂停态（chat:info）与订单未创建/未被接单（sys_ride_order + order:status）是两条线，不能互相推断。

**答错回看**：把 break 当锁 → [C1 §二](../04-chat-and-agent/01-chat-round-and-sse.md#二最少概念fluxsink-与两种锁)；把 order:status 当真值 → [O2 §五](../02-orders/02-grab-and-concurrency.md#五luakey-与-ttl)；把 quoteId 当许可 → [C3 §四](../04-chat-and-agent/03-order-confirmation.md#四用户决定confirm-脚本逐行)；由缺字段断言没创建 / 把消费拒绝当同一快照历史 / 用预览 trace 关联订单 → [C3 §7.4](../04-chat-and-agent/03-order-confirmation.md#74-t4消费成功但创建失败或响应丢失)、[C3 §六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建)、[Q4](03-review-and-self-test.md#q4-同一张确认卡的正常恢复与独立反事实确认令牌)。
</details>

<a id="ex-2"></a>
#### 练习 2：同一令牌，为什么一个 403 一个 200

**现象**：乘客林小雨带同一张令牌，`GET /order/my/ongoing` 返回 200，`GET /order/5005` 返回 403；两次都带了正确的 `Authorization: Bearer ...`。

**索引路径**：[§1.2](#terms-auth) 身份/角色/归属 → [§4.1](#layers) 分层表 → [§4.2](#phenomena) 第一行 → [A2 §四](../01-auth/02-authorization-and-defense.md#四越权与失败案例)。

**源码入口**：`aspect/PermissionAspect#checkPermission`（只看角色）、`service/impl/RideOrderServiceImpl#getOrderDetail`、`#assertCanViewOrder`、`exception/GlobalExceptionHandler#handleBusiness`。

<details>
<summary>应得到的结论与答错回看（练习 2）</summary>

- `/order/my/ongoing` 只按"当前登录用户"查自己的订单，不需要比较某条数据的归属，所以 200。
- `/order/5005` 的注解**不带角色**（只要求登录），切面放行；403 来自 Service：`assertCanViewOrder` 的 USER 分支比较 `order.user_id` 与当前用户，不等则抛 `BusinessException(403,"无权限查看该订单")`。
- **文案可以区分层**：切面的 403 是"无权访问该资源"，归属的 403 是"无权限查看该订单"。看到后者就去 Service 找，而不是怀疑注解。
- 拒绝发生在读取轨迹之前（`orderRouteService.getByTraceId` 在归属检查之后），所以这次请求不会产生 Mongo 读。

**答错回看**：把 403 全归给注解 → [A2 §二](../01-auth/02-authorization-and-defense.md#二真实端点对照)；以为拦截器会拒 → [A1 §五](../01-auth/01-login-and-session.md#五带令牌的请求拦截器threadlocal-与双层校验)。
</details>

<a id="ex-3"></a>
#### 练习 3：200 + `data=false` 与 409 不是一回事

**现象**：司机周师傅对订单 5004 接单，HTTP 200、`Result.data=false`；两分钟后司机邵师傅对同一单接单，HTTP 409。

**索引路径**：[§1.3](#terms-order) 的"预检/条件更新/0 行" → [§3.4](#keys-order) 的两个 key → [§4.2](#phenomena) 第二、三行 → [O2 §四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵)。

**源码入口**：`service/base/OrderGrabService#tryGrab`、`util/RedisScripts#GRAB_ORDER_ATOMIC`、`service/impl/RideOrderServiceImpl#driverAcceptOrder` 的 `updated <= 0` 分支、`#withOrderLock`。

<details>
<summary>应得到的结论与答错回看（练习 3）</summary>

- 200+false 的唯一来源是：Lua 预检返回 1（占位成功），但 DB 条件更新影响 0 行 → `rollbackGrab` + `refreshOrderStatus` + `return false`。它表示"预检放行、裁决拒绝"。
- 409 有五个来源，**靠实际文案/返回分支区分，不靠时间间隔**：Lua `-1`（`order:status` **缺失或值不是 10**）→ `grabFailureMessage` 映射"订单已被其他司机接走或已取消"；Lua `-2`（`driver:active` 不是 `__IDLE__`）→"司机已有未结束订单，无法接单"；订单锁等待 3 秒失败 →"订单正在处理中，请稍后重试"；`tryLock` 被中断 →"系统繁忙，请稍后重试"；缓存缺失回源后订单不存在（`ORDER_NOT_FOUND`）→"订单不存在"。完整矩阵见 [O2 §四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵)。
- **0 行不能反推 DB 仍是 10**：真实行可能已是 20/40/50/90，可能 `driver_id` 已非空，也可能被逻辑删除。
- 邵师傅那次 409 **在没有实际文案或 `tryGrab` 返回分支日志时无法唯一确定**：`-1`（执行脚本时状态 Key 缺失或值非 10）、锁等待失败、`-2`（执行脚本时司机占位不为空闲）都可能。"两分钟后"这个时间间隔本身不构成判据——要拿到那次响应的 `Result.message` 或服务端分支日志才能定。

**答错回看**：把预检当裁决 → [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)；把 0 行当"DB 没变" → [O2 §四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵)、[Q8 第 1 问](03-review-and-self-test.md#q8-缓存说-10数据库说-20跨模块综合)；用时间猜 409 来源 → [§4.2](#phenomena) 第三行。
</details>

<a id="ex-4"></a>
#### 练习 4：仪表盘少算 1，20 秒后自己好了

**现象**：客服苏晴的仪表盘"待分配"显示 7，实际数据库已有 8 条；约 20 秒后刷新变成 8。日志里没有异常，只有一条 debug 级别的"工单统计缓存已失效(双删)"。

**索引路径**：[§3.5](#keys-ticket) 的 `ticket:statistics:` → [§3.7](#time-kinds) 的"每次写入重算" → [§4.2](#phenomena) 第四行 → [S2 §5.5](../03-support-and-cache/02-ticket-index-and-cache.md#55-首次删除与-500ms-延迟双删)、[S2 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删)、[S3 §五](../03-support-and-cache/03-cache-races.md#五双删删除之后仍有旧值回填)。

**源码入口**：`service/impl/TicketServiceImpl#getTicketStatistics/#getCachedTicketStatistics/#cacheTicketStatistics/#evictTicketStatistics/#queryTicketStatisticsFromDb`、`service/base/DelayedCacheEvictor#evictAfter`。

<details>
<summary>应得到的结论与答错回看（练习 4）</summary>

- 真值是 MySQL 的四条独立 `COUNT`；Redis 里那份 JSON 是派生快照。
- "少 1"的**一种相容解释**（不是默认根因）：重建者的 DB 读发生在提交之前（快照是 7），随后把旧值回填进缓存。但"并发读回填"只是与现象相容的解释之一；日志与"20 秒后恢复"**只约束可能成因，不能证明根因**。
- **反例**：旧值在第一次删除之后、第二次成功删除之前写入，会被第二次删除清掉。
- **条件分支（残留何时成立）**：只有旧值写在**第二次实际删除之后**，或删除根本未生效（调度被拒、执行抛异常），旧值才**可能**残留；而且必须**此后没有再次成功删除、覆盖或驱逐**，才可能一直留到**该次写入**重算的 TTL（`20 + random(0..10)` 秒）失效。若期间又有写入/删除/驱逐，会更早消失。
- "20 秒后自己好了"与"单次 TTL 上界 30 秒"一致，但不能因此断言系统一定在 30 秒内收敛：如果这段时间又发生写入并回填，会重新计时。
- **四件事要分开：日志 / 调度受理 / 真正执行 / 删除结果**。`debug`"工单统计缓存已失效(双删)"在 `evictAfter` **返回后**打印，只证明"第一次删除已尝试、调用走到了调度这一步"。`evictAfter` 内部把 `RejectedExecutionException` 捕获成 warn"延迟删除任务未被接收"（不抛调用方），所以**即使调度被拒，debug 照样打印**——debug **不证明第二次删除被受理**。`500ms` 是提交给调度器的**延迟**（任务何时变得可运行），**不是开始执行的硬上界**：单线程调度器若忙，实际执行可能更晚；执行时 `delete` 抛异常也只 warn"延迟删除缓存失败"。**没有异常 ≠ 第二次删除成功**。

**答错回看**：把 Redis 当权威 → [S2 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删)；以为双删消灭所有窗口 → [S3 §五](../03-support-and-cache/03-cache-races.md#五双删删除之后仍有旧值回填)；把提交时刻 +30s 当收敛上界 → [§3.7](#time-kinds)；把 debug 当删除成功 → [Q3](03-review-and-self-test.md#q3-100000-的仪表盘事务与缓存窗口)。
</details>

<a id="ex-5"></a>
#### 练习 5：改完答案，旧文档却给出新答案；改完问题，检索空了

**现象**：管理员把 `groupId=301` 的答案从 v1 改成 v2。10 秒后用户问同一个问题，工具回执里的"标准回答"已经是 v2，但 ES 里那条文档的 `answer` 仍是 v1。另一天管理员把问题从 q1 改成 q2，之后用 q1 检索直接返回空。

**索引路径**：[§1.9](#terms-rag) 的"候选/回查/刷新" → [§4.2](#phenomena) 最后一行 → [R1 §3.6](../05-rag/01-retrieval-and-grounding.md#36-第-5-步回-mysql-核对)、[R2 §四](../05-rag/02-index-sync-and-budget.md#四synchronizegroup-的真实顺序与可见性)；替身/真库测试的证据边界见本章[测试阅读小节](#test-reading)。

**源码入口**：`agentbase/rag/RagService#searchAnswers`（双路 + RRF + 组内去重）、`#authoritative`（五条件 + `doc.setAnswer`）、`#updateAnswer/#updateQuestion`、`agentbase/rag/QaIndexSynchronizer#synchronizeGroup`、`mapper/QaSyncJobMapper#enqueue/#claimBatch/#defer`。

<details>
<summary>应得到的结论与答错回看（练习 5）</summary>

- 第一种现象是**正常路径**：`authoritative` 对通过校验的候选执行 `doc.setAnswer(info.getAnswer())`，答案来自本次 `sys_qa_info` 回查，不是 ES 副本。所以"旧候选 + 新答案"恰恰说明回查生效了，不能当作"索引已同步"的证据。
- 第二种现象要**条件化**：`updateQuestion` 只改 `sys_qa_es.question`（映射），旧 ES 文档的 `question` 仍是 q1。旧候选是否被剔除，取决于**本次 `authoritative` 实际读到的映射文本**——若本次读到 `mapping.question=q2` 而 `doc.question=q1`，不相等就 `continue` 剔除；若本次仍读到旧映射 q1，旧候选反而仍可通过。**整次结果为空，要求所有候选都没通过**（被剔除或根本没被召回），不是"有一条被剔除就空"。
- 两个现象都不证明 bug；也**都不能反推"整批重建已完成"**：单条 ES 文档的 `answer`/`question`、一次任务快照、一次 `refresh` 调用、或工具返回的文本，任何**单点观测**都不能独立证明整批重建完成。要判断重建，看的是该组文档是否删除/写入并 refresh 可见、任务是否被确认，而不是答案文本。
- 空的排查顺序：召回层（有没有候选、是否 refresh 可见）→ 融合层（是否进前五）→ 权威层（映射与 info 是否通过五条件）。

**答错回看**：以为 ES 的 `answer` 会被直接使用 → [R1 §3.6](../05-rag/01-retrieval-and-grounding.md#36-第-5-步回-mysql-核对)；以为任务提交=可搜 → [R2 §二](../05-rag/02-index-sync-and-budget.md#二端到端时间线从改答案到搜到)；把空当 bug、由单点断言重建完成 → [R1 §5.4](../05-rag/01-retrieval-and-grounding.md#54-时间线-t3问题改了索引还没跟上静态推导)、[Q7](03-review-and-self-test.md#q7-1356-的新答案rag-索引落后)。
</details>

---

<a id="legacy-topics"></a>
## 五、旧 Redis 与 Agent 专题的主题入口

旧资料（`docs/learn/`）保留为拓展阅读，但**不再要求先读它**。这一节回答的是另一个问题："我在旧文里记得有个考点，新教材从哪进去？"

覆盖路由来自 `_meta/coverage.md`；本节只做**主题入口**，不重复推导，也不宣称对旧文做过全库逐段扫描。

| 旧文 | 旧文主题 | 新教材入口（主讲） |
|---|---|---|
| `redis/01-shared-session-token.md` | 会话 String + 反向索引 + 会话版本 | [A1 §一](../01-auth/01-login-and-session.md#一最少概念三方状态与五组-key)、[§六](../01-auth/01-login-and-session.md#六退出改密注销旧会话怎么失效) |
| `redis/02-email-code-cooldown.md` | 验证码/冷却两组 key + Lua | [A1 §二](../01-auth/01-login-and-session.md#二发送验证码冷却与一次性签发)、[§三](../01-auth/01-login-and-session.md#三注册验证码的事务内预留) |
| `redis/03-cache-penetration-bloom.md` | 布隆挡用户名穿透 | [A2 §七](../01-auth/02-authorization-and-defense.md#七用户名布隆过滤器存在但不在当前判定主链)（补充 [S3 §三](../03-support-and-cache/03-cache-races.md#三用户名布隆过滤器只写不读的现状)） |
| `redis/04-cache-breakdown-mutex.md` | 互斥锁 + 双检重建 | [S2 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删)（补充 [S3 §四](../03-support-and-cache/03-cache-races.md#四击穿复用统计缓存的互斥重建)） |
| `redis/05-cache-avalanche-random-ttl.md` | 随机 TTL 打散 | [S3 §七](../03-support-and-cache/03-cache-races.md#七随机-ttl固定-ttl-与适用边界)（补充 [S2 §五](../03-support-and-cache/02-ticket-index-and-cache.md#五统计缓存命中重建降级与双删)、[O3 §五](../02-orders/03-location-and-discovery.md#五keyttl-与原子范围)） |
| `redis/06-null-sentinel-and-prewarm.md` | `__NULL__` 空值哨兵 | [S3 §二](../03-support-and-cache/03-cache-races.md#二城市码读路径从工具调用到-lua)（`__IDLE__` 见 [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)） |
| `redis/07-delayed-double-delete.md` | 提交后删 + 500ms 再删 | [S2 §5.5](../03-support-and-cache/02-ticket-index-and-cache.md#55-首次删除与-500ms-延迟双删)（补充 [S3 §五](../03-support-and-cache/03-cache-races.md#五双删删除之后仍有旧值回填)） |
| `redis/08-city-code-tombstone.md` | `__TOMBSTONE__` 墓碑 + 版本 | [S3 §二](../03-support-and-cache/03-cache-races.md#二城市码读路径从工具调用到-lua)、[§六](../03-support-and-cache/03-cache-races.md#六城市码回填与失效的两条竞争时间线)（补充 [C5 §二](../04-chat-and-agent/05-external-tools-and-prompts.md#二最少概念几个词需要先分清)） |
| `redis/09-lease-token.md` | 版本租约 | [C4 §四](../04-chat-and-agent/04-memory-and-tool-results.md#四版本租约的完整推导旧快照为什么会盖掉新消息)（补充 [S4 §五](../03-support-and-cache/04-locks-leases-and-ids.md#五消息回填版本最小必要机制)） |
| `redis/10-lua-scripts-overview.md` | Lua 原子性与脚本分布 | [F4 §五](../00-foundations/04-redis-flux-and-sse-basics.md#五追踪一个真实-luasliding_window_rate_limit)（脚本位置见本章 [§7.2](#src-verify)） |
| `redis/11-two-phase-confirm-token.md` | 两阶段确认令牌 | 建单许可：[C3 §四](../04-chat-and-agent/03-order-confirmation.md#四用户决定confirm-脚本逐行)、[§六](../04-chat-and-agent/03-order-confirmation.md#六从确认到订单isconfirmedconsume-与创建)；取消预检：[C3 §十五](../04-chat-and-agent/03-order-confirmation.md#十五客服取消预检)（补充 [O1 §十](../02-orders/01-order-lifecycle.md#十http-下单与-agent-下单)、[C5 §八](../04-chat-and-agent/05-external-tools-and-prompts.md#八提示词约束-vs-代码守卫三组逐条对照)） |
| `redis/12-chat-lock-atomic.md` | `HSET`+`EXPIRE` 合一 | [C1 §二](../04-chat-and-agent/01-chat-round-and-sse.md#二最少概念fluxsink-与两种锁) |
| `redis/13-distributed-lock.md` | 手写令牌锁 vs 看门狗 | [S4 §二](../03-support-and-cache/04-locks-leases-and-ids.md#二三种机制对照)（补充 [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)） |
| `redis/14-grab-order-lua.md` | 抢单 Lua 预检 + DB 兜底 | [O2 §二](../02-orders/02-grab-and-concurrency.md#二一次真实接单请求的完整时间线)、[§四](../02-orders/02-grab-and-concurrency.md#四失败分支与缓存条件矩阵) |
| `redis/15-nearby-order-geo.md` | GEO 三公里 + 整池重建 | [O3 §三](../02-orders/03-location-and-discovery.md#三附近订单池失效重建与-db-校验) |
| `redis/16-driver-online-heartbeat.md` | 共享 GEO + 成员级 TTL | [O3 §二](../02-orders/03-location-and-discovery.md#二司机在线geo-与成员级心跳) |
| `redis/17-order-timeout-delay-queue.md` | ZSet 延迟队列 + Lua 领取 | [O4 §二](../02-orders/04-timeouts-and-recovery.md#二zset-待办安排领取与重排)、[§三](../02-orders/04-timeouts-and-recovery.md#三轮询处理与数据库补偿) |
| `redis/18-global-unique-id-incr.md` | `INCR` 工单号 + 雪花 + 节点租约 | [S4 §六](../03-support-and-cache/04-locks-leases-and-ids.md#六两套-id业务编号与雪花-id)（补充 [S1 §三](../03-support-and-cache/01-ticket-lifecycle.md#三提交工单身份订单归属编号与写入)） |
| `redis/19-multi-level-cache.md` | Heap→Redis→MySQL 三层 | [C4 §二](../04-chat-and-agent/04-memory-and-tool-results.md#二最少概念存储键与寿命)、[§三](../04-chat-and-agent/04-memory-and-tool-results.md#三正常一轮从用户消息到三层落库) |
| `redis/20-tool-response-cache.md` | 工具结果两级反查 | [C4 §七](../04-chat-and-agent/04-memory-and-tool-results.md#七工具结果反查两份数据两种用途) |
| `redis/21-llm-classification-cache.md` | 分类结果按指纹缓存 | [C2 §三](../04-chat-and-agent/02-routing-and-tool-loop.md#三分类缓存内容指纹不是用户隔离) |
| `redis/22-sliding-window-rate-limit.md` | ZSet 滑动窗口 + 两套限流 | [A2 §五](../01-auth/02-authorization-and-defense.md#五两套限流同一条-lua不同的触发点与故障策略) |
| `redis/23-ticket-pool-zset.md` | score 编码 + 版本/ready | [S2 §二](../03-support-and-cache/02-ticket-index-and-cache.md#二表键与排序编码)、[§四](../03-support-and-cache/02-ticket-index-and-cache.md#四冷池重建双检锁版本与发布) |
| `redis/24-user-location-hash.md` | 用户位置 Hash，无 TTL | [O3 §1.5](../02-orders/03-location-and-discovery.md#15-乘客当前位置另一套数据) |
| `redis/25-agent-hitl-state.md` | `break` 两态 + 确认状态机 | [C3 §三](../04-chat-and-agent/03-order-confirmation.md#三准备确认notifyuser-与-begin)、[§五](../04-chat-and-agent/03-order-confirmation.md#五恢复post-chatv2rid-的真实顺序) |
| `agent/01-orchestration-and-sse.md` | 关卡、单轮锁、订阅、双线程池 | [C1](../04-chat-and-agent/01-chat-round-and-sse.md#二最少概念fluxsink-与两种锁)（事件见 [§六](../04-chat-and-agent/01-chat-round-and-sse.md#六事件http-状态与失败边界)、锁见 [§七](../04-chat-and-agent/01-chat-round-and-sse.md#七保护对象线程与超时)） |
| `agent/02-rag-consistency-and-budget.md` | 一致性、`SKIP LOCKED`、预算 | [R2](../05-rag/02-index-sync-and-budget.md#二端到端时间线从改答案到搜到)（数据模型与检索见 [R1](../05-rag/01-retrieval-and-grounding.md#二两条入口身份参数与结果来源)） |
| `agent/03-security-and-boundaries.md` | 鉴权链、支付门禁、异常映射 | 拆成三处：[A2 §二](../01-auth/02-authorization-and-defense.md#二真实端点对照)、[O5 §四](../02-orders/05-pricing-and-payment.md#四支付链与验单门禁)、[C3 §八](../04-chat-and-agent/03-order-confirmation.md#八五个不同的对象与信任边界) |

**旧文与新教材冲突时的口径**：以当前源码为准，正文以各章"主讲"章节为唯一解释来源；旧文里"只有考点没有调用方"的部分（如布隆读侧、墓碑写侧），以本章 §2.8 的现状表为准。

---

## 六、维护规则、已知缺口与全书复核清单

### 6.1 新增条目时遵守五条

1. **一条术语只选一个主讲章。** 其他章节只在"补充"里标用途（例如"补充 S3 §三"），不并列两个主讲。
2. **key 必须写生成函数或内联位置。** 只写前缀不算条目；物理 key 与常量前缀不同时要指出（见 §3.8）。
3. **类/方法必须写清"先读什么、走到哪里"。** 只写文件名不算索引项。
4. **实现程度按调用方判定。** 有业务调用方=已接线；只有测试/只注入/Javadoc=未接线，进 §2.8。
5. **不确定就标未知。** 不把一次默认值写成全生命周期常量，也不把约定前缀写成物理 key。

### 6.2 已知命名差异与未核实项

| 项 | 现状 | 处理 |
|---|---|---|
| 订单表名 | 实体 `@TableName("sys_ride_order")`、`init.sql` 建 `sys_ride_order`；正文与综合题里出现过 `ride_order` 简写 | 本章一律用 `sys_ride_order`；此前审查已把 P1/P3 正文与综合题的 14 处简写统一为 `sys_ride_order`（见 `_meta/review-2026-11-22.md`） |
| 工具结果表名 | `sys_chat_tool_repsonse`（源码拼写 `repsonse`） | 按源码写，不"纠正" |
| `tool:{callId}` 前缀 | `RedisKeyConstants#toolResponseKey` 无调用方 | 见 §2.8、§3.8 |
| Redisson 内部行为 | 看门狗续期周期、`RLock` 的 owner 标识格式未在项目内配置 | 标"由 Redisson 管理"，不写具体秒数 |
| `chat:info` 的"最终 TTL" | 四条改 TTL 的路径（`lockChat` 3600s / `quoted` 24h / `getRestorableChat` 60min / `restoreChat` 无 TTL，C3 §2.4） | 只写"最后写它的路径决定"，不给单一值 |
| 城市码业务侧失效 | `invalidate` 无业务调用方（仅测试） | 不写"写入后会自动失效" |
| 旧文覆盖范围 | `coverage.md` 记录的是按篇盘点（每篇 30～42 行），不是逐段全库扫描 | 本章沿用该口径，不宣称"全覆盖已核验" |
| 未逐一穷举的只读范围 | 本章核对了 §2.2～§2.7 列出的类与 key，未逐行通读全部 300+ 源文件 | 新增条目时按 §6.1 重新核对调用方 |

<a id="final-checklist"></a>
### 6.3 全书复核清单

全书复核不是章节。以下条目是本章交给复核者的抽查点——**本章只声明"已按当前源码写过一次"，复核结论见 `_meta/review-2026-11-22.md`**。

| 类型 | 抽查点 | 应指向 | 核验方法 |
|---|---|---|---|
| 练习 | 练习 1：`chat:info` 的 `break` + `order:status` 双章反查 | C3 §三、O2 §五 | 打开 `OrderConfirmation#begin` 与 `ChatServiceImpl#chat` 的 `break` 分支，确认字段名与文案 |
| 练习 | 练习 2：403 的两个来源 | A2 §四 | 对比 `PermissionAspect` 与 `assertCanViewOrder` 的两条文案 |
| 练习 | 练习 3：200+false 与 409 | O2 §四 | 读 `driverAcceptOrder` 的 `updated <= 0` 分支与 `grabFailureMessage` |
| 练习 | 练习 4：统计缓存旧值存活期 | S2 §五、§3.7 | 读三个常量：`TICKET_STATS_CACHE_BASE_SECONDS=20`、`TICKET_STATS_CACHE_JITTER_SECONDS=10`、`TICKET_STATS_DELAYED_EVICT=500ms` |
| 练习 | 练习 5：旧候选 + 新答案 | R1 §3.6 | 读 `authoritative` 的 `doc.setAnswer(info.getAnswer())` |
| 术语 | 单轮执行锁 vs 会话锁定 | C1 §二、§七 | 两个 key 的写入位置不同（`ChatRequestRunner` vs `ChatManager#lockChat`） |
| 术语 | 订单锁 vs 司机占位 vs 节点租约 | S4 §二、§四、§六 | 三个 key 的持有者语义不同（Redisson owner / 字符串值 / Redisson owner） |
| 术语 | 版本租约 vs 版本令牌 | C4 §四、S2 §四 | 前者比较 `chat:history:version`，后者比较 `ticket:pool:version` 与锁令牌 |
| 术语 | 工具回执 vs 工具结果副本 | [C4 §七](../04-chat-and-agent/04-memory-and-tool-results.md#七工具结果反查两份数据两种用途) | 一个在 `history`，一个在 `chat:tool:v2:` + `sys_chat_tool_repsonse` |
| 术语 | `chat:info` 的四条 TTL 路径 | C3 §2.4 | 逐一对照 `lockChat`/`quoted`/`getRestorableChat`/`restoreChat` 的 TTL 命令 |
| 结构 | 两个发布脚本的 ready 顺序 | O3 §三、S2 §4.6 | `PUBLISH_ORDER_GEO_POOL` 先 `PEXPIRE` 后 ready；`PUBLISH_TICKET_POOL` 先 ready 后 `PEXPIRE` |
| 术语 | 召回 vs 回查 vs 刷新 | R1 §三、§3.6、R2 §四 | 三个动作对应三个不同方法/语句 |
| 结构 | 全部相对链接可达 | 本文件 §七 | 逐个 `Test-Path` 解析 |
| 结构 | 编号一致性 | §1.1 对照表 | 抽查 A1/O2/C1/P1/P4 的章号与文件名 |

---

<a id="nav"></a>
## 七、导航与源码核验入口

### 7.1 导航

- **上一章**：[第 25 章 间隔复习与综合题](03-review-and-self-test.md)（P3，已通过复审）——本章与它的"一页速查""错题定位表"配合使用：先按 P3 做题，答错后到本章查条目。
- **上一章的上一章**：[第 24 章 四层实验与清理](02-isolated-labs.md)（P2，已通过复审）——本章不要求执行任何实验卡。
- **下一章**：无。全书复核（检查链接、前置顺序、状态/常量/术语一致性、旧专题覆盖）不是第 27 章，结论见 `_meta/review-2026-11-22.md`。
- **相关专题**（按用途标注，不是"必读"）：
  - 查登录与令牌字段 → [第 5 章 登录与会话](../01-auth/01-login-and-session.md)
  - 查 401/403 分层 → [第 6 章 权限与防护](../01-auth/02-authorization-and-defense.md)
  - 查订单状态码与金额字段 → [第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)、[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)
  - 查抢单顺序与失败矩阵 → [第 8 章 抢单与并发](../02-orders/02-grab-and-concurrency.md)
  - 查位置与心跳 → [第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)
  - 查超时与 ZSet → [第 10 章 超时与恢复](../02-orders/04-timeouts-and-recovery.md)
  - 查工单状态与统计 → [第 12 章 工单生命周期](../03-support-and-cache/01-ticket-lifecycle.md)、[第 13 章 工单池与统计缓存](../03-support-and-cache/02-ticket-index-and-cache.md)
  - 查缓存竞态与墓碑 → [第 14 章 缓存竞态](../03-support-and-cache/03-cache-races.md)
  - 查锁与 ID → [第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)
  - 查 SSE 与单轮锁 → [第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)
  - 查分类与工具循环 → [第 17 章 分类路由与工具循环](../04-chat-and-agent/02-routing-and-tool-loop.md)
  - 查确认与消费 → [第 18 章 订单确认、恢复与消费](../04-chat-and-agent/03-order-confirmation.md)
  - 查记忆与工具结果 → [第 19 章 三层记忆](../04-chat-and-agent/04-memory-and-tool-results.md)
  - 查外部工具与提示词 → [第 20 章 外部工具与提示词边界](../04-chat-and-agent/05-external-tools-and-prompts.md)
  - 查检索与索引同步 → [第 21 章 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md)、[第 22 章 索引同步与预算](../05-rag/02-index-sync-and-budget.md)
  - 查三条业务旅程 → [第 23 章 三条完整业务旅程](01-trace-three-journeys.md)

<a id="src-verify"></a>
### 7.2 本章源码核验入口（相对 `src/main/java/com/fancy/rideagenthub/`）

- **key 常量与脚本**：`constant/RedisKeyConstants.java`、`util/RedisScripts.java`。
- **内联 key 的位置**：`service/base/ChatRequestRunner.java`（`chat:execution:`）、`service/impl/TokenServiceImpl.java`（`auth:session-version:`）、`service/impl/TicketServiceImpl.java`（`ticket:no:`）、`interceptor/RequestRateLimitInterceptor.java`（`request:rate:`）、`config/IdGeneratorConfig.java`（`id:node:`）、`agentbase/memory/ToolResponseMemory.java`（`chat:tool:v2:`）。
- **锁与租约**：`service/base/RedisLock.java`、`service/impl/RideOrderServiceImpl.java#withOrderLock`、`agentbase/memory/RedisMemory.java`、`agentbase/memory/HeapMemory.java`、`config/IdGeneratorConfig.java`。
- **确认与记忆**：`service/base/OrderConfirmation.java`、`agentbase/tool/OrderTool.java#createOrder`、`agentbase/memory/{MessageMemory,MessageWindow,MessageParser,MysqlMemory}.java`、`agentbase/tool/ToolRepPointerTool.java`。
- **订单与地理**：`service/base/{OrderGrabService,OrderDelayQueue,OrderTimeoutJob,OrderGeoPool,DriverGeoIndex}.java`、`service/impl/RideOrderServiceImpl.java`。
- **工单与缓存**：`service/base/{TicketPoolIndex,DelayedCacheEvictor}.java`、`service/impl/TicketServiceImpl.java`、`agentbase/amap/util/citycode/CityCodeUtil.java`。
- **Agent 与工具**：`agents/{DailyAgent,OrderAgent,SupportAgent,FallbackAgent,AgentStreams}.java`、`agentbase/tool/`。
- **RAG**：`agentbase/rag/{RagService,QaIndexSynchronizer}.java`、`mapper/QaSyncJobMapper.java`。
- **SQL 与配置**：`src/main/resources/sql/init.sql`、`src/main/resources/sql/migrations/20261113_security_consistency.sql`、`src/main/resources/application.yaml`（只看键名与默认值，不抄凭据）。
- **测试源码（只读，未运行）**：`src/test/java/com/fancy/rideagenthub/` 下共 23 个测试源文件，§2.9 按 15 行覆盖其中 18 个测试类；未单列的还有 `MongoDbTest`、`RideAgentHubApplicationTests`、`ChatServiceRegressionTest`、`RedisLockRegressionTest`（纯 mock 锁测试）与辅助类 `OrderLockTestSupport`。

**路径基准**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置与测试写完整仓库相对路径。定位优先用"类 + 方法 + 常量名"，行号会漂移。

**已通过第二次复审（P7-4，2026-11-21），2026-11-22 独立复核。** 本章只做静态核查：未编译、未运行测试、未启动服务、未连接 Redis/MySQL/MongoDB/Elasticsearch、未读取凭据。条目中的 TTL、超时、阈值均来自源码常量与配置默认值；实际运行时是否被外部改写未验证。最终检查记录见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)。

