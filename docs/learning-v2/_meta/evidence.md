# 证据台账（源码基线 2026-11-22）

本文件回答三个问题：**哪一章的哪些断言被核对过、依据在源码哪里、还有哪些没确认。**

- 源码基线：分支 `feature-wtc-261114`，HEAD `40158f0d6938a0924732146dc8927c2337f1b139`；`git status --short` 当时只显示两个教材目录未跟踪。仓库源码、SQL、测试、配置、`pom.xml` 在本次审查中**未改动**。
- 定位口径：优先"类 + 方法 + 常量名"，行号只对应当前 HEAD，会漂移。Java 类相对 `src/main/java/com/fancy/rideagenthub/`。
- 本台账按章归并内容单元；逐条问题的"原文 → 源码证据 → 改法"见 [最终检查记录](review-2026-11-22.md)。

## 一、证据级别

| 级别 | 含义 | 本次使用范围 |
|---|---|---|
| 静态阅读 | 读过实现与调用方，未运行 | 全部业务结论 |
| 调用方核对 | 除实现外确认了调用方、参数与分支 | 状态迁移、缓存失效、工具链 |
| 测试阅读 | 读过测试源码的类名、门控与断言，未执行 | 各章"测试阅读"小节 |
| 未实测 | 结论由代码结构推导，运行时行为未观察 | HTTP 响应、并发交错、Redis/ES/外部服务 |
| 教学假设 | 为讲解构造的订单号、时间线、金额、模型输出 | 各章示例与练习 |

**本次没有任何"实际运行"级证据**：未编译、未启动服务、未连接 Redis/MySQL/MongoDB/Elasticsearch、未调用模型或高德/和风/邮件/支付。凡正文写"未实测"的地方都保持原状，没有把静态阅读改写成跑通。

## 二、逐章台账

读法：表中"已核对"指静态阅读/调用方核对；标"测试"的依据只到测试源码阅读；金额、TTL、时间线、score 都重新算过。**具体问题的原文、源码证据、改法与状态见[最终检查记录 §四](review-2026-11-22.md#四本次修正)**；每章仍未验证的内容见本文 §五。

| 章 | 本次核对的内容单元 | 主要源码/配置/测试依据 | 结论 |
|---|---|---|---|
| F1 业务地图 | 角色与 `username#role`、7 个控制器入口、下单 5 步链、四类存储职责、`sys_chat`/`sys_messages`/`business` 表、401 来源、Mongo 轨迹唯一副本、Agent 四层与工具 | `domain/enums/UserRole`、7 个 `controller/*`、`RideOrderController#create`、`RideOrderServiceImpl#createOrder`、`AuthTokenInterceptor#preHandle`、`init.sql`、`bus_citycode.sql`、`OrderRoute`、`ChatProperties` | 已核对；修正 `bus_citycode` 的脚本归属；补 `PermissionAspect` 只作用于注解方法与 IP 限流一站 |
| F2 请求路径与配置 | 9 站调用链、映射注解、参数绑定、DTO/Entity/VO/Result、注入、配置来源、Jackson 规则、异常映射 | `RideOrderController`、`CreateOrderDTO`、`RideOrder`、`RideOrderVO`、`Result`/`PageResult`、`Config#jacksonCustomizer`、`AuthProperties`、`WebMvcConfig`、`application.yaml`、`GlobalExceptionHandler` | 已核对；补两个拦截器站（含 429）；练习 A 第 1 步措辞改为"第一个无路径的 `@PostMapping`" |
| F3 数据与事务 | 建表与索引、主键/业务 ID、无外键、`@Transactional` 仅两处、`READ_COMMITTED`、条件更新、`MANDATORY`、`afterCommit`/`afterCompletion`、跨库边界、Mongo 清理 | `init.sql`、`migrations/20261113_security_consistency.sql`、`RideOrderServiceImpl#createOrder/#driverAcceptOrder/#handleTimeoutOrder`、`OrderGrabService#tryGrab`、`OrderDelayQueue`、`OrderRouteService` | 已核对，未改正文；教材已注明"创建订单链无事务"这一现状 |
| F4 Redis 与 SSE 基础 | 六类结构落点、key/TTL、Lua 的 KEYS/ARGV/返回、令牌签发、延迟队列、GEO、滑动窗口、Flux/SSE、单轮执行锁、线程池 | `RedisKeyConstants`、`RedisScripts`、`TokenServiceImpl`、`OrderDelayQueue`、`DriverGeoIndex`、`ChatRequestRunner`、`AgentStreams`、`AsyncWorkConfig`、`application.yaml` | 已核对；修正"`overwriteIfLeaseValid` 是辅助路径"与"`RepairDatabaseIntegrationTest` 调用 `RedisMemory`"两处归类错误 |
| A1 登录与会话 | 五组验证码 key、ISSUE/VERIFY/COMPLETE/CLEANUP、冷却与尝试次数、注册/登录/重置、令牌三 key、TTL、`sessionVersion`、`credentialStamp`、拦截器、401 映射 | `EmailCodeService`、`AuthServiceImpl`、`TokenServiceImpl`、`AuthTokenInterceptor`、`CredentialStamp`、`RedisKeyConstants`、`AuthProperties`、`init.sql` | 已核对；修正 scene 小写 key（含练习命令）、COMPLETE 的 `return 1`、重置密码 404/401 分端点、`deleteToken` 索引残留说明 |
| A2 权限与防护 | 身份/角色/归属分层、74 个映射与 67 个注解统计、端点拒绝层、订单/工单/会话越权、两套限流、异常映射、布隆现状 | `PermissionAspect`、`RequirePermission`、`WebMvcConfig`、`RequestRateLimitInterceptor`、`ChatRateLimiter`、`RideOrderServiceImpl#assertCanViewOrder`、`TicketServiceImpl#assertTicketReadable`、`UserUsernameBloomFilterService` | 已核对，未改正文 |
| O1 订单生命周期 | 七状态与数值、11 条迁移与守卫、取消边界常量、违约金公式、支付门禁 503、事务/锁/Redis/Mongo 副作用、HTTP 与 Agent 对照 | `RideOrderStatus`、`RideOrderServiceImpl#createOrder/#driverAcceptOrder/#driverArriveStart/#startRide/#finishRide/#payOrder/#cancelOrder/#handleTimeoutOrder`、`RidePricing`、`PaymentConfig`、`OrderTool` | 已核对；修正结束行程"四项"为五项计价、`resume` 归属、`PayOrderReqDTO` 路径 |
| O2 抢单与并发 | `tryGrab` 真实顺序、两把锁、GRAB/RELEASE/SYNC 三段 Lua、占位 TTL、缓存陈旧、六个失败分支、两条时间线、测试门控 | `OrderGrabService`、`RideOrderServiceImpl#driverAcceptOrder/#withOrderLock`、`RedisScripts`、`OrderGrabRegressionTest`、`OrderGrabIntegrationTest` | 已核对；修正"预检失败返回 false"（实为 409）与测试门控说明 |
| O3 位置与发现 | 上线/心跳/查询/下线、成员级 60 秒窗口、`refreshIfExist`、GEO 池版本/ready/重建锁/2 小时、回表校验、乘客位置 Hash | `DriverGeoIndex`、`DriverLocationServiceImpl`、`OrderGeoPool`、`RedisScripts`、`UserServiceImpl#saveUserLocation`、`GeoPoolRegressionTest`、`GeoPoolRedisIntegrationTest` | 已核对，未改正文；练习 A 的 `"120"`/`"30"` 与题设 `lng=120,lat=30` 一致 |
| O4 超时与恢复 | ZSet key/member/score、`schedule/cancel/claimDue`、领取即移除、重排、三种超时迁移、DB 补偿范围、时区/多实例、时间线 A～D | `OrderDelayQueue`、`RedisScripts#CLAIM_DUE_ORDERS`、`OrderTimeoutJob`、`RideOrderServiceImpl#handleTimeoutOrder/#handle*Timeout/#systemCancel`、`RideOrderTimeoutProperties` | 已核对；修正时间线 D 的题设时刻（10:18→10:28），使"剩余约 2 分钟"成立 |
| O5 计价与支付 | 计价公式与顺序、六组手算、估价/创建/改目的地/结束的金额来源、支付门禁与 503、取消费、字段归属、测试 | `RidePricing`、`AmapRouteService`、`RideOrderServiceImpl#estimatePrice/#finishRide/#payOrder/#calcCancelFee`、`OrderSearchTool`、`PaymentVerifier`、`OrderGrabRegressionTest` | 已核对，全部示例复算一致；仅修正 `RideOrderVO#toString` 的适用范围表述 |
| S1 工单生命周期 | 0～4 状态机、15 个入口、四层检查、条件更新与行锁、编号、提交后失效、`TRANSFER`/评价、无退款边界、测试 | `TicketController`、`TicketServiceImpl`、`TicketStatus/Type/Priority/SenderRole`、`PermissionAspect`、`init.sql`、6 个测试类 | 已核对；补 C 端分页未配置分页拦截器的缺口、`resolveHandlerRoleDesc` 忽略大小写、门控 `matches` |
| S2 工单池与统计缓存 | score 编码与手算、ZSet/ready/版本/重建锁、整页回退、回表、统计 TTL/等待/双删、PUBLISH/READ/INVALIDATE | `TicketPoolIndex`、`RedisScripts`、`TicketServiceImpl#getAdminTicketPage/#getTicketStatistics`、`DelayedCacheEvictor`、`RedisLock`、4 个测试类 | 已核对；修正 `DATETIME(3)` 与 score 同精度时"排序可能不同"的错误结论 |
| S3 缓存问题 | 穿透/击穿/雪崩判据、`__NULL__`、墓碑/版本、布隆只写不读、统计锁与 500ms 双删、时间线 A～E | `TicketServiceImpl`、`DelayedCacheEvictor`、`CityCodeUtil`、`RedisScripts`、`UserUsernameBloomFilterService`、`RedisCacheRegressionTest` | 已核对；补 `username IS NOT NULL`、限定 `CacheConsistencyRegressionTest` 的统计缓存覆盖范围 |
| S4 锁、租约与 ID | `RedisLock` 令牌/固定 TTL/比对解锁、RLock 看门狗、版本租约 -1/0/1、`PERSIST`、7 个订单锁入口、工单号、雪花与节点租约 | `RedisLock`、`RideOrderServiceImpl#withOrderLock`、`RedisMemory`、`RedisScripts`、`TicketServiceImpl#generateTicketId`、`IdGeneratorConfig`、`SnowflakeIdWorker` | 已核对；修正 `APPEND_WITH_VERSION_BUMP` 的 TTL 分支表述 |
| C1 一次对话与 SSE | 入口/归属/UUID、单轮执行锁、`autoConnect(2)`、`doFinally`、五类事件、会话元数据、暂停保存、恢复语义、断开与超时 | `ChatController`、`ChatRequestRunner`、`ChatServiceImpl#chat`、`ChatManager`、`ChatInfoService`、`AgentStreams`、`AgentEvent`、`AsyncWorkConfig` | 已核对；修正 `chat.locked` 为 `sys_chat.locked`，补下单后自动 `lockChat` 与未注册的 `ChatStatusTool` |
| C2 分类与工具循环 | 分类上下文/指纹/TTL、规范化与 DANGER/旧 ORDER 分支、四 Agent 注册/分派/提示词、200 条终止、工具回执、身份边界 | `ChatServiceImpl#resolveClassification`、`ClassificationCache`、`ChatProperties`、4 个 Agent、`agentbase/tool/*`、`ToolRepPointerTool` | 已核对；修正"先 consume 再组装 DTO"的顺序、200 条统计口径、工具数量表述 |
| C3 订单确认与消费 | 报价→暂停→确认→恢复→消费→创建、`quoteId=confirmationId`、`begin`/`CONFIRM`/`CONSUME`/`CANCEL`、`/r` 阶段、`isConfirmed`、客服取消预检 | `OrderConfirmation`、`ChatServiceImpl#resume`、`OrderAgent#resume`、`OrderTool#createOrder`、`SupportAgent`、`OrderSearchTool#verifyCancelConditions/#cancelOrder` | 已核对；修正 `break` 第三态（不存在而非 `false`）、`getPersisted` 先做归属查询 |
| C4 三层记忆与工具结果 | Heap/Redis/MySQL 顺序、`getPersisted`、版本租约与五段 Lua、窗口配对、坏记录、`ToolResponseMemory/getCalling`、清空/恢复 | `MessageMemory`、`HeapMemory`、`RedisMemory`、`MysqlMemory`、`MessageWindow`、`MessageParser`、`ToolResponseMemory`、`ToolRepPointerTool`、`RedisScripts` | 已核对；修正不存在的 `Agent#handleToolCalls` 与 `MessageParser` 未知类型的归因 |
| C5 外部工具与提示词 | 工具注册/分派/服务端授权四层、高德重试与反序列化、和风天气、城市码缓存、提示词 vs 代码守卫 | `AmapRouteService`、`AmapGeoRegeoService`、三个反序列化器、`QweatherWeatherService`、`CurWeatherTool`、`CityCodeUtil`、`ChatProperties` | 已核对；修正城市码 key 规范化（两个构造器都会 trim+lowercase）与"完整方法"标注 |
| R1 检索与溯源 | MySQL/ES 数据模型、`answer index=false`、1024 维、双路召回、RRF、group 去重、Top5、回 MySQL 回查、删除/改答案影响 | `QaInfo`、`QaElasticMap`、`QaDocument`、`RagService#searchAnswers`、`RagTool`、`init.sql` | 已核对，RRF 分数与排序复算一致；未改正文 |
| R2 索引同步与预算 | 写后异步、`SKIP LOCKED`、失败延后、`version` 无比较、`ragExecutor`、8000ms 共享预算、逻辑取消、时间线 | `QaIndexSynchronizer`、`QaSyncJobMapper`、`QaSyncJob`、`AsyncWorkConfig`、`RagService` | 已核对；删除一处重复段落 |
| P1 三条旅程 | 登录/受保护入口、Agent 订车全链、客服咨询双路检索、跨旅程反查 | 各章入口汇总；`RideOrderServiceImpl`、`OrderTool`、`TicketServiceImpl`、`RagService` | 已核对；补 `ORDER_NOT_FOUND` 这个 409 来源 |
| P2 四层实验 | 选层表、10 张实验卡、门控属性、PowerShell、清理范围、结果六分类、6 道自测 | `src/test/java/` 23 个文件、`application.yaml`、`llm_judge_eval.py` | 已核对；更正 L1-1 替身所在行号归属；未发现危险命令 |
| P3 复习与综合题 | 三轮计划、七步源码路线、8 道综合题与折叠答案、错题定位表、一页速查 | 各题源码入口；`RidePricing`、`MessageWindow`、`ChatRequestRunner`、`RagService` | 已核对，数值与时间线复算一致；未改正文 |
| P4 术语与索引 | 四类索引、§2.2～§2.9、Redis key 表、未接线 11 项、28 个旧专题入口、5 道练习、显式锚点 | 全部控制器/服务/工具/Mapper、`RedisKeyConstants`、`docs/learn/*` | 已核对；修正 `/error` 排除写法与 §2.9 行数（15 行覆盖 18 类） |

## 三、跨章的唯一口径

以下概念只允许一种说法，各章引用而不重新定义：

| 概念 | 唯一口径 | 主讲章 |
|---|---|---|
| 单轮执行锁 | `chat:execution:{chatId}`，Redisson RLock，`tryLockAsync(0,-1)` + 看门狗，订阅期获取，`doFinally` 发起释放 | C1 |
| 会话锁定 | `sys_chat.locked` + `chat:info:{chatId}` 的 `locked` 字段，TTL 3600 秒 | C1 |
| 一次性确认许可 | `quoteId=confirmationId`，CONFIRM 写 `ReadyForCreate`，CONSUME 校验并删除，900000ms | C3 |
| 回填版本租约 | 版本 key `PERSIST`，data key TTL；`-1/0/1` 三态 | C4（S4 只做对比） |
| 订单状态缓存 | `order:status:{id}` 五分钟 TTL，`SYNC_ORDER_STATUS` 单调推进，回池用 `refreshOrderStatus` 删除 | O2 |
| 工单池 score | `priority×1e13 + updated_at(UTC)毫秒 + 补零 id`，`ZREVRANGE` | S2 |
| 缓存双删 | afterCommit 首次删除 + 500ms 延迟第二次，失败只登记不重试 | S2/S3 |
| RAG 预算 | 8000ms 共享 deadline，`Future.cancel(true)` 只做逻辑取消 | R2 |

## 四、代码侧已知缺陷（教材记录，不在本次修改）

这些是实现问题，不是文档错误；教材按"当前行为"描述，读者不应把它们当成推荐做法。

1. `service/base/OrderTimeoutJob#recoverOverdueOrders` 用 `LocalDateTime.now()`（宿主机时区）计算补偿 cutoff，业务时间写入用 `BusinessTime.now()`（Asia/Shanghai）。宿主机非 CST 时阈值整体偏移。
2. `service/base/ChatInfoService#restoreChat` 对整把 `chat:info:{chatId}` 执行 `PERSIST`，会抹掉 `ChatManager`（60 分钟）与 `OrderConfirmation`（24 小时）设置的 TTL。
3. `RideOrderServiceImpl` 的三个私有方法 `requiredNonnegative`、`getVehicleMultiplier`、`estimateDurationMin` 无调用方；其中 `getVehicleMultiplier` 返回 1.30/1.60，与真正生效的 `RidePricing`（1.60/2.50）矛盾，容易被误读。
4. `RideOrderServiceImpl#createOrder` 的 `order.getOrderId() == null` 分支不可达（雪花 ID 恒非空）。
5. `RideOrderServiceImpl#cancelOrder` 的 `cancelRole == null` 分支不可达（前面已把 null 判为 403）。
6. `RideOrderServiceImpl#getRequiredOrder` 把 `String` 订单号直接绑到 `BIGINT` 列，依赖 MySQL 隐式转换；`GET /order/abc` 会走查库后 404，而不是参数绑定阶段的 400。
7. 仓库没有 `MybatisPlusInterceptor`/`PaginationInnerInterceptor`，`TicketServiceImpl#getUserTicketPage` 的 `selectPage` 不生成 `LIMIT`、`total` 恒为 0；B 端 DB 回退自行写了 `selectCount + limit`，不受影响。
8. `TicketServiceImpl#assignTicket/reassignTicket` 在 Service 内读 `UserTokenContext`，非 HTTP 调用会得到 401；越权分配抛 `IllegalArgumentException`（400），与订单侧同类越权用 403 不一致；`reassignTicket(null)` 可写出"状态 1 且 handler 为空"的组合（HTTP 路径有兜底，当前不可达）。
9. `TokenServiceImpl#deleteToken` 在 token JSON 损坏时只删正文，跳过索引 `SREM`，`auth:user_tokens:{userId}` 可能残留 member。
10. `agentbase/amap/util/citycode/CityCodeUtil#invalidate` 在 `src/main/java` 无调用方，墓碑写路径未接线（教材已如实写明）。
11. `config/PaymentConfig` 把 `@ConditionalOnMissingBean` 放在普通 `@Configuration` 上，Bean 注册顺序可能让默认 503 与自定义实现的表现不稳定。
12. `service/base/ChatRequestRunner` 用随机 `long` 作为 owner，却在补偿分支调用 `isHeldByThread(owner)`；Redisson 比较的是线程 id，该判断很可能恒为 false（静态可疑，未实测）。
13. `agentbase/tool/ChatStatusTool#lockSession`、`RegeoTool`、`OrderAgent#getLastNotifyUserToolCallId`、`RedisKeyConstants#toolResponseKey` 无调用方/未注册（教材列为"未接线"）。

## 五、当前无法静态确认

1. 框架内部行为：Spring Framework 补丁版本、Jackson 未知字段与 `LocalDateTime` 默认格式、Redisson 看门狗默认 30 秒、`selectPage` 无拦截器时的精确 SQL。
2. 运行时表现：SSE 线格式与客户端可见性、HTTP 状态与响应体、`afterCommit` 是否执行、Lua 在真实 Redis 的执行结果、ES `refresh` 后可搜索时延、真实 Redis/MySQL 故障传播。
3. 部署差异：生产 schema 是否与 `init.sql` 一致、Redis 单机/Cluster、宿主机时区、真实凭据与外部服务配额。
4. 前端行为：超过 `2^53-1` 的 ID 如何被消费（仓库无前端源码）。
5. 所有测试均未运行：测试卡只证明"源码这样写"，不证明"跑起来通过"。

## 六、旧文档

`docs/learn/` 的 30 个文件保留原样，逐篇核对结论与 5 处已确认错误单列在[最终检查记录 §六](review-2026-11-22.md#六旧文档待处理错误只记录不修改)。
