# 课程设计（建议的最终目录）

- 制定日期：2026-11-14
- 依据：源码核验（见 `evidence.md`）、旧资料盘点（见 `coverage.md`）
- 26 章均已通过章节审查；2026-11-22 完成一次独立全量复核，结论与遗留项见 [最终检查记录](review-2026-11-22.md)。

## 一、设计原则

1. **业务先行**：每章从"某个真实角色要做的一件事"开始，先走通一次正常流程，再拆机制。
2. **一条主线**：每个机制只安排一个主讲章节，其余章节只补本场景差异并链接过去，避免同一实现出现两种说法。
3. **可独立复习**：每章自带"一页复习"和折叠答案的自测题，读者不必回翻聊天记录或旧文。
4. **前置闭合**：写任何一章时，它的硬前置必须已经交付；不得把尚未编写的章节当作已经可读的内容。规划阶段允许前置链上都是计划章，但写作顺序必须按第五节的依赖清单推进（拓扑序）。
5. **状态诚实**：已实现 / 部分实现 / 占位未接入 / 仅提示词约束 / 教学假设，在正文中逐项标注。

## 二、建议目录

```text
docs/learning-v2/
  README.md                                 读者入口（本轮已写）
  00-foundations/
    01-project-map.md                       业务地图与一次真实调用链（本轮已写，样章）
    02-request-path-and-config.md           请求分层、DTO/实体/VO、依赖注入与配置
    03-data-and-transactions.md             表与存储职责、条件更新、事务与提交后回调
    04-redis-flux-and-sse-basics.md         Redis 类型/TTL/Lua 与 Flux/SSE 最小概念
  01-auth/
    01-login-and-session.md                 验证码→注册/登录→令牌→请求身份
    02-authorization-and-defense.md         角色、归属、失效、限流、异常映射
  02-orders/
    01-order-lifecycle.md                   报价→创建→接单→到达→行程→完成/取消
    02-grab-and-concurrency.md              抢单的两种竞态与 DB 条件更新
    03-location-and-discovery.md            用户位置、司机心跳、附近订单 GEO
    04-timeouts-and-recovery.md             延迟队列、超时状态迁移与兜底扫描
    05-pricing-and-payment.md               计价公式、金额来源与支付门禁边界
  03-support-and-cache/
    01-ticket-lifecycle.md                  工单创建、认领、处理、角色边界
    02-ticket-index-and-cache.md            工单池排序索引与统计缓存
    03-cache-races.md                       穿透/击穿/雪崩、空值、双删、墓碑
    04-locks-leases-and-ids.md              锁令牌、回填版本、两套唯一 ID
  04-chat-and-agent/
    01-chat-round-and-sse.md                一轮对话的状态机、SSE 事件与锁
    02-routing-and-tool-loop.md             分类、四类 Agent、工具注册与循环
    03-order-confirmation.md                报价、人工确认、一次性令牌与恢复
    04-memory-and-tool-results.md           三层记忆、消息窗口、工具结果反查
    05-external-tools-and-prompts.md        地图/天气接入、反序列化、提示词边界
  05-rag/
    01-retrieval-and-grounding.md           文档/问题/答案模型、双路检索与核对
    02-index-sync-and-budget.md             索引同步任务、重试与耗时预算
  06-practice-and-review/
    01-trace-three-journeys.md              登录、订车、客服三条完整旅程
    02-isolated-labs.md                     四层实验：阅读/测试/隔离中间件/真实外部
    03-review-and-self-test.md              间隔复习法与综合题
    04-glossary-and-source-index.md         术语、源码、Redis key 与机制索引
  _meta/                                    baseline / curriculum / coverage / evidence / progress
```

## 三、相对任务路线的调整与理由

路线文件声明"目录是建议命名，允许拆分、合并或重排，但要说明先修关系与覆盖去向"。本轮做了三处调整：

**调整 1：`00-foundations/02` 由"reading-and-running"收窄为"request-path-and-config"。**
理由：把"如何跑起来"与"请求如何分层"放在一章，会让同一章出现两个互不依赖的学习目标；而运行步骤高度依赖本地中间件，容易过期。现在把"最小阅读路径 / 最小运行路径"作为该章最后一节（只写前置条件与检查点，不承诺一键跑通），主目标改为"看清一次 HTTP 请求在代码里的分层与配置来源"。运行细节在 `06-practice-and-review/02-isolated-labs.md` 再按四层展开。

**调整 2：从 `02-orders/01` 拆出 `02-orders/05-pricing-and-payment.md`。**
理由：原计划把"金额从哪来"和"支付边界"并入订单生命周期。但生命周期本身已有状态机、角色边界、取消规则三个主题；计价公式（`RidePricing`，被估价/创建/结束行程/Agent 工具四处复用）和支付门禁（默认 503）各有独立的证据链与常见误解，合并后一章会有四个学习目标。拆分后 `02-orders/01` 只交代"金额由服务端写入 `estPrice`/`realPrice`，公式见 O5"，并在当章给出一个最小结论，读者不必等 O5 才能读状态机。

**调整 3：`03-support-and-cache/04` 明确为"横向对比章"，并被 `04-chat-and-agent/04` 依赖。**
理由：锁令牌（`RedisLock`）、回填版本/租约（`RedisMemory`）、唯一 ID（雪花 + 节点租约）三者在旧文里是分散的（Redis 09/13/18）。把它们放在一处横向对比，能让读者在进入记忆章节前就掌握"令牌 vs 版本"的区别；否则 `04/04` 会重复解释租约，或迫使读者跳回旧文。

**主讲归属要写清**：Redis 09 的完整租约机制由 `04-chat-and-agent/04` 主讲（竞态推导、`PERSIST`、暂存区都在那里）；S4 只做"锁令牌 vs 回填版本"的最小对比以及两套 ID 的分工，不重复推导。这样既避免两章各讲一遍，也不要求读者向后跳。

**未做的调整**：没有把 RAG 合并成一篇（索引同步与检索的证据链、失败模式差异大），也没有新增"项目部署/运维"章（超出教材目标，且依赖仓库外信息）。

## 四、章节卡片

字段说明：**能力**=学完能做什么（可验收）；**前置**=必须先读的已完成章节（未标"推荐"的一律按硬前置处理，权威清单见第五节）；**例子**=贯穿案例；**源码**=主要入口；**难点**=最容易被略过或讲错的地方；**练习**=动手形式；**关系**=与前后章的衔接。

### 00-foundations

#### F1 `00-foundations/01-project-map.md`（本轮已写）

- 能力：能说出四类角色各自能做什么；能画出"HTTP 请求→鉴权→控制器→服务→存储"与"对话→Agent→工具→同一服务"两条路径；能按真实顺序复述一次下单调用链；能说清 MySQL/MongoDB/ES/Redis 各自存什么、谁是权威。
- 前置：Java 语法、能读 Spring 注解；不需要 Redis / Agent 知识。
- 例子：乘客林小雨下单、司机老陈接单、乘客向客服咨询费用。
- 源码：`RideOrderController#create`、`AuthTokenInterceptor#preHandle`、`RideOrderServiceImpl#createOrder`、`OrderTool#createOrder`、`ChatServiceImpl#chat`、`OrderConfirmation#consume`、`GlobalExceptionHandler`。
- 难点：区分"模型产生文字""工具执行业务""服务端授权""数据库落地"四件事；不把 Redis 缓存当订单真值；不把 MongoDB 当对话库。
- 练习：纯源码追踪（不启动服务），按 5 个检查点定位；8 道自测题。
- 关系：全书入口。F2 解释本章出现的分层与配置，O1 展开订单状态机，C2 展开 Agent 工具循环。

#### F2 `00-foundations/02-request-path-and-config.md`

- 能力：能解释一个 `@RestController` 方法如何被调用、参数如何绑定、返回值如何序列化；能区分 DTO / Entity / VO 并指出各自出现的位置；能说明配置项从 `application.yaml` 到 `@Value` / `@ConfigurationProperties` 的路径；能解释为什么雪花 ID 在 JSON 里是字符串。
- 前置：F1。**状态：已通过 P1-1 复审（2026-11-14）。**
- 例子：同一个订单在"创建请求体（DTO）→ 表行（`RideOrder`）→ 详情响应（`RideOrderVO`）"三种形态间的字段差异；`POST /order` 只返回 `String`，详情接口才构造 VO。
- 源码：`RideOrderController`（内部静态 DTO）、`UserPOIController`、`domain/entity/RideOrder`、`domain/vo/RideOrderVO`、`domain/response/Result`、`config/Config#jacksonCustomizer`、`config/AuthProperties`、`config/RideOrderTimeoutProperties`、`config/IdGeneratorConfig`、`config/WebMvcConfig`、`exception/GlobalExceptionHandler`。
- 难点：`@RequestBody` 与 `@RequestParam` 的差别不只是位置；`Result` 的 `code` 与 HTTP 状态码是两套（见 `GlobalExceptionHandler`）；DTO/Entity/VO 不是必经流水线（`POST /order` 无 VO、`GET /user/poi` 直接返回 Entity、`POST /user/poi` 用 VO 当输入）；`LambdaQueryWrapper` 不是 Mapper 类型。
- 练习：字段追踪（`POST /order` 从 JSON 到 String）与配置追踪（`auth` 配置到消费者、`@Value` 默认值）各一组纯阅读练习；自测覆盖绑定位置、类型转换、注入、配置层级、HTTP 与业务码、String 与 Long。
- 关系：依赖 F1 的场景；为 F3（表结构、事务边界）、A1（令牌 DTO）、O1（订单 DTO）提供基础。

#### F3 `00-foundations/03-data-and-transactions.md`

- 能力：能画出主要表之间的关系；能解释 `@Transactional` 的边界、`READ_COMMITTED` 在这里意味着什么；能说明"条件更新"如何替代"先查再改"；能解释 `afterCommit` 回调与"提交后才失效缓存"的关系；能说明 MySQL 与 MongoDB 之间没有跨库事务。
- 前置：F2。**状态：已通过 P1-2 复审（2026-11-14）。**
- 例子：订单从 `CREATED` 到 `DRIVER_ACCEPTED` 的条件更新；订单落库失败时尝试删除 Mongo 轨迹；两名司机接同一单、同一司机接两单。
- 源码：`src/main/resources/sql/init.sql`、`sql/migrations/20261113_security_consistency.sql`、`RideOrderController`、`RideOrderServiceImpl`（`createOrder`、`driverAcceptOrder`、`withOrderLock`）、`OrderGrabService`（`tryGrab`、`afterCommit`）、`OrderGeoPool`、`OrderDelayQueue`、`OrderRouteService`、`DriverGeoIndex`、`RedisScripts`；测试 `OrderGrabRegressionTest`、`OrderLockRegressionTest`、`OrderGrabIntegrationTest`（只读，未运行）。
- 难点：`update ... where status = 旧值` 的影响行数才是裁决依据；`FOR UPDATE` 锁的是索引记录、范围依赖计划，普通一致性读与锁定读当前读要分开；`MANDATORY` 只在经过代理时生效；方法/lambda 返回不等于事务结束，内层代理标记 rollback-only 后外层捕获仍可能 `UnexpectedRollbackException`；"事务回滚"不会撤销已写入 Redis/Mongo 的副作用；`tryGrab` 先做数据库查询/行锁再做 Redis 预检；扫描只对部分超时待办有范围地兜底；订单锁释放绑定 `afterCompletion`。
- 练习：给创建/接单两条路径标事务与外部副作用；推演同单多司机、同司机多单两层时间线。
- 关系：F1 的下单链在这里被拆成"事务内/事务外"；O2 展开完整抢单与 Lua，O4 展开超时补偿，S3/C4 复用条件更新与提交后回调概念。

#### F4 `00-foundations/04-redis-flux-and-sse-basics.md`

- 能力：能按"数据结构→本项目用途"举出 String/Hash/Set/List/ZSet/GEO 的真实例子；能区分 key TTL、业务结束、成员级心跳和有条件续期，并说明恢复来源；能追踪一个 Lua 的 KEYS/ARGV/返回值与原子边界；能解释 Servlet 应用返回 `Flux` 时响应如何按 Accept 协商写出、以及"客户端断开"与"服务端任务终态"是两件事；能说明单轮执行锁与单次模型流超时的区别。
- 前置（硬）：F3（事务/回调边界）。**状态：已通过 P1-3 复审（2026-11-14）；本批不拆篇，两部分连续写在同一文件。**
- 例子：`auth:token:{token}` 的 String+Set 原子签发；`chat:history:{chatId}` 的 List 与 MySQL 回填；`delay:order:timeout` 的 ZSet；`driver:online:beat` 的成员级心跳；`SLIDING_WINDOW_RATE_LIMIT`；`/chat/v2/c/{id}` 的 `Flux<AgentEvent>` 与 `chat:execution:{chatId}` 锁。
- 源码：`pom.xml`、`src/main/resources/application.yaml`、`constant/RedisKeyConstants`、`util/RedisScripts`、`TokenServiceImpl`、`EmailCodeService`、`OrderGrabService`、`DriverGeoIndex`、`OrderDelayQueue`、`RedisMemory/MessageMemory`、`ClassificationCache`、`ChatRateLimiter`、`OrderGeoPool/TicketPoolIndex`、`ChatController`、`AgentEvent`、`ChatRequestRunner`、`AgentStreams`、`AsyncWorkConfig`、`ChatServiceImpl`、四个 Agent、`src/test/llm_judge_eval.py`、相关测试。
- 难点：`Flux` 不是"WebFlux 应用"；控制器没有 `produces`，不能断言任意 Accept 都是 SSE；SSE 线格式是框架生成、仓库未实测；流内 error 事件与 HTTP 状态、正常完成与错误终态不同；Redis 单线程/Lua 只保证 Redis 侧原子执行，不保证跨库事务或错误回滚；3 分钟是单次模型订阅超时而非整轮总时限；Redis 丢失不只会变慢，也不都能从 MySQL 恢复。
- 练习：一组按 key 找结构/TTL/恢复来源；一组从 chat 入口追踪锁、订阅、事件与终态；另给可选隔离 Redis 观察命令，标未实测。
- 关系：承接 F3 的事务/回调边界；A1/A2（令牌与限流）、O2/O3（Lua/GEO/超时）、C1/C4（SSE 与记忆租约）建立在本章之上。

### 01-auth

#### A1 `01-auth/01-login-and-session.md`

- 能力：能完整复述"发验证码→注册→密码登录/验证码登录→拿到 token→带 token 请求"；能说明验证码的冷却、有效期、尝试次数与一次性消费各由谁保证；能解释改密/注销后旧令牌为什么失效（版本号 + 凭据指纹双栅栏）。
- 前置：F2、F4（推荐 F3）。**状态：已通过 P2-1 复审（2026-11-14）。**
- 例子：林小雨用邮箱验证码注册并登录，随后在第二台设备登录，再重置密码。
- 源码：`AuthController`、`AuthServiceImpl`、`EmailCodeService`、`TokenServiceImpl`、`AuthTokenInterceptor`、`UserTokenContext`、`UserToken`、`CredentialStamp`、`AuthProperties`、`WebMvcConfig`、`RequestRateLimitInterceptor`、`UserAuthServiceImpl`、`UserUsernameBloomFilterService`、相关 DTO/VO/枚举与 `sys_user` DDL、`SecurityRedisIntegrationTest`（只读，未运行）。
- 难点：验证码"消费"与"业务事务"的关系（注册/重置 reserve + afterCompletion，登录 consume 即删）；token 的 TTL 是固定值而非滑动续期；`sessionVersion` 与 `credentialStamp` 分别挡住什么；`getToken` 通过 Redis 前置检查后回查 MySQL 的取舍；SMTP 失败清理的 issueId 归属校验与剩余窗口。
- 练习：从一封"邮件"追到一行 `sys_user`；三组 key 的生命周期与多设备/改密状态推演；另有可选隔离 Redis 只读观察（未实测）。
- 关系：为 A2 提供"身份从哪来"；O1/C1 的所有接口都依赖本章的 token。

#### A2 `01-auth/02-authorization-and-defense.md`

- 能力：能判断一个接口是"仅需登录"还是"需要角色"；能指出某次越权是被切面、Controller 辅助检查还是 Service 拦下；能说明异常如何映射到 HTTP 状态与 `Result.code`；能解释 IP 限流与聊天限流的不同故障策略。
- 前置：A1、F3。**状态：已通过 P2-2 复审（2026-11-14）。**
- 例子：司机查看不属于自己的订单（403）；客服认领工单时指定他人为处理人（400）；乘客看别人工单详情（403）与取消别人工单（400）的差异。
- 源码：`PermissionAspect`、`RequirePermission`、`UserTokenContext`、`GlobalExceptionHandler`、`WebMvcConfig`、`RequestRateLimitInterceptor`、`ChatRateLimiter`、`RideOrderController/RideOrderServiceImpl`、`TicketController/TicketServiceImpl`、`ChatRequestRunner/ChatInfoService`、`UserUsernameBloomFilterService`、相关测试。
- 难点：角色检查与归属检查是两层，只用其中一层会漏；异常类型决定 401/403/400/404/409，不能用消息文案反推；提示词不是鉴权；两套限流的 key、额度、返回与 Redis 故障策略不同；布隆当前不在判定主链。
- 练习：给 8 个端点标注"能进到方法体 / 被切面拦 / 进方法体后被 Controller 或 Service 拒"；对比两套限流。
- 关系：O1 的角色边界、S1 的工单权限、C3 的确认令牌都引用本章的两层模型。

### 02-orders

#### O1 `02-orders/01-order-lifecycle.md`

- 能力：能画出订单状态机并标出每个迁移的触发者与守卫条件；能说清乘客/司机/管理员各自能做什么；能解释取消的违约金规则与状态限制；能指出金额字段由服务端写入（公式见 O5）；能区分支付门禁与真实渠道。
- 前置：A2、F3。**状态：已通过 P3-1 复审（2026-11-15）。**
- 例子：林小雨从估价、创建、陈师傅接单/到达/开始/结束，到默认支付门禁 503 或取消；取消分支覆盖接单前 0 元、接单 12 分钟 8.50 元、行程中角色差异。
- 源码：`RideOrderController`、`RideOrderStatus`、`RideOrderServiceImpl`（create/accept/arrive/start/finish/cancel/pay/timeout 与 `assertCanViewOrder`）、`RidePricing`、`OrderGrabService`、`OrderDelayQueue`/`OrderTimeoutJob`、`OrderRouteService`、`PaymentVerifier`/`PaymentConfig`、`OrderTool`/`OrderSearchTool`/`OrderConfirmation`。
- 难点：状态码是数值区间而非枚举序；`USER_MAX_CANCEL_STATUS` / `DRIVER_MIN_CANCEL_STATUS` 的含义；状态守卫与条件更新各自的拒绝路径；`create/finish/pay/cancel` 无本地事务注解、当前调用链无外层事务，而接单/超时有事务注解；支付默认 503 不等于真实渠道。
- 练习：给 6 个状态迁移判定"合法/非法/取决于守卫"；跟踪创建/接单资源顺序及取消副作用。
- 关系：O2～O4 分别深挖其中一段；O5 补金额；C3 用同一订单讲人工确认。

#### O2 `02-orders/02-grab-and-concurrency.md`

- 能力：能用 T1/T2 时间线说明"多人抢一单"和"一人抢多单"两种竞态；能按真实顺序解释订单锁、司机行锁、Redis 状态/占位/Lua、DB 条件更新与提交后回调；能分析缓存缺失/陈旧、Redis 异常、Lua 拒绝、DB 0 行/异常、事务回滚/未知和补偿失败。
- 前置：O1、F3、F4、A2。**状态：已通过 P3-2 复审（2026-11-15）。**
- 例子：两名司机同时点"接单"；同一司机连点两个订单；另有缓存旧值、占位陈旧、释放失败等可解释失败。
- 源码：`RideOrderController#driverAccept`、`RideOrderServiceImpl#driverAcceptOrder/#withOrderLock/#releaseOrderLock`、`OrderGrabService#tryGrab/#rollbackGrab/#refreshOrderStatus/#syncOrderStatus/#releaseDriver`、`RedisScripts` 三段 Lua、`RedisKeyConstants`、`OrderGeoPool`/`DriverGeoIndex`/`OrderDelayQueue` 调用点、相关订单测试。
- 难点：Redis 预检在部分 DB 查询之后、DB 才是裁决；订单锁只串行同单，司机行锁串行同司机；`MANDATORY` 与外层事务；回调登记与执行、best-effort 补偿；`schedule` deadline 计算在 guarded callback 前；占位匹配删除。
- 练习：同单与同司机两条并发推演、六个失败分支判断；隔离集成测试阅读（未运行）。
- 关系：O3 接收 GEO 失效/司机位置的当前调用点后深化；O4 复用订单锁并讲超时；S4 对比锁令牌与版本租约。

#### O3 `02-orders/03-location-and-discovery.md`

- 能力：能追踪司机上线/心跳/下线/在线状态、附近订单池查询，以及乘客位置保存/读取；能解释司机 GEO 与成员级心跳、附近订单池整池失效/重建/回表校验；能分析 Redis 故障、失效失败、并发重建和不一致窗口。
- 前置（硬）：O1、F4。推荐：O2（与 O3 可互换）、F3、A2。**状态：已通过 P3-3 复审（2026-11-15）。**
- 例子：陈师傅上报 `120,30` 后查附近订单；接单失效与重建竞争；主动下线与接单刷新竞争；乘客保存当前位置。
- 源码：`RideOrderController`（driver location/offline/pool）、`DriverLocationServiceImpl`、`DriverGeoIndex`、`OrderGeoPool`、`RedisLock`、`UserServiceImpl#saveUserLocation/#getUserLocation`、`UserController`、`RedisScripts`、`RedisKeyConstants`、`GeoPoolRegressionTest`/`GeoPoolRedisIntegrationTest`。
- 难点：共享 GEO/心跳 key 无 TTL、成员级 60 秒窗口；`add()` 实为失效；版本/ready/重建锁与发布校验；Redis 候选必须回表校验 `CREATED(10)`；Redis 故障不是司机离线；乘客位置字符串 Hash 不校验、无 TTL/删除。
- 练习：只读追踪上线与附近查询；接单失效/重建竞争；下线与接单刷新；六道判断。
- 关系：O4 的退回车池会再次失效/重建；C5 提示词会读取乘客位置；S4 可对比 RedisLock 与其他锁。

#### O4 `02-orders/04-timeouts-and-recovery.md`

- 能力：能追踪三种超时的安排/领取/重读/条件更新/重排；能解释 ZSet key/member/score、deadline 计算、事务登记与执行；能分析 Redis 轮询和 DB 补偿的覆盖范围、竞争与故障窗口；能说出到达超时退回可重复。
- 前置：O1、O2、O3、F3、F4。**状态：已通过 P3-4 复审（2026-11-15）。**
- 例子：订单 5001 下单 15 分钟无人接单；订单 5002 接单 10 分钟未到达；订单 5003 结束 30 分钟未支付；迟到 3 分钟与未来预约分支。
- 源码：`OrderDelayQueue`（`schedule/cancel/claimDue/afterCommit`）、`RedisScripts#CLAIM_DUE_ORDERS`、`OrderTimeoutJob#pollTimeoutOrders/#recoverOverdueOrders/#processOrders`、`RideOrderServiceImpl#handleTimeoutOrder/#handle*Timeout/#systemCancel/#reschedule/#withOrderLock`、`RideOrderTimeoutProperties`、`BusinessTime`。
- 难点：锚点是重读后的状态字段；到达超时 20→10 且可重复；`updateTime` 复用为回池锚点；领取即移除，default/0 行不会重建已领取 member；deadline 在提交前计算；支付无本地事务而超时有事务；业务时间/队列时间/DB 补偿时间来源不同；DB 兜底只筛选 10/20/50，选中处理可能执行副作用但不保证成功。
- 练习：无人接单迟到、到达超时两轮、领取后进程退出、六道判断；8 道状态/时间/失败自测。
- 关系：O1 状态机由本章推动；O2 的锁/状态缓存/占位、O3 的 GEO 失效在此被复用；S3 的延迟双删不是 ZSet 待办。

#### O5 `02-orders/05-pricing-and-payment.md`

- 能力：能按真实公式手算报价/结算并说明每项来源；能区分服务端重算与仍受客户端/Agent 输入影响的坐标、车型、加急、轨迹和结束时间；能追踪创建、改目的地、结束行程的落点；能解释支付门禁、默认 503、条件更新和取消费。
- 前置（硬）：O1。推荐：O2、O4、F3。**状态：已通过 P3-5 复审（2026-11-15）。**
- 例子：2 公里/60 秒边界、10 公里/600 秒、25 公里/3599 秒专车加急；修改目的地；支付与超时竞争。
- 源码：`RidePricing`、`AmapRouteService#getDrivingRouteEst/#calcKm`、`RideOrderServiceImpl#estimatePrice/#createOrder/#finishRide/#payOrder/#cancelOrder/#calcCancelFee`、`OrderSearchTool#modifyOrderDestination`、`OrderTool#getEstRouteAndPrice/#createOrder`、`PaymentVerifier/#PaymentConfig`、VO/SQL。
- 难点：起步价含 2km、远途费叠加、秒向上取分钟、倍率在加总后乘；明细先各自舍入但总价用中间值计算；Agent `ReadyforRoute`/`noRoute` 与确认、二次规划、旧 Redis 预览的差价；DTO 副本覆盖；修改目的地异常不等于未提交；轨迹只做格式解析，可解析不等于可信；`arriveTime` 实际是结束时间；默认 verifier 503；`true` 不是外部清算。
- 练习：三组手算、客户端来源判断、修改目的地追踪、支付/取消六道判断、7 道自测。
- 关系：O1 的金额字段在此拆开；O4 的超时竞争与支付互相衔接；C3 用同一报价讲确认令牌；A03 补安全与异常。

### 03-support-and-cache

#### S1 `03-support-and-cache/01-ticket-lifecycle.md`

- 能力：能画出工单 0～4 状态机并说明每个动作的角色限制；能区分注解角色、发起人和当前处理人；能追踪提交/订单归属/编号生成；能解释认领、管理员重新分配、客服 `TRANSFER` 与用户确认/取消的差异；能分析行锁、条件更新和提交后缓存失效边界。
- 前置（硬）：A2、O1。推荐：F2、F3、O5。**状态：已通过 P4-1 复审（2026-11-15）。**
- 例子：乘客因费用争议提交工单并关联订单；客服认领、回复、处理完成、转交/驳回；管理员重新分配；用户追加、升级、确认评价或取消。
- 源码：`TicketController`、`TicketService/TicketServiceImpl`、`TicketStatus/TicketType/TicketPriority/TicketSenderRole`、`Ticket/TicketChat`、`Ticket*ReqDTO/Ticket*VO`、SQL、`PermissionAspect/UserTokenContext`、`TicketTool/SupportAgent`、`TicketPoolIndex/DelayedCacheEvictor/RedisLock/RedisScripts`、相关测试。
- 难点：状态与 `handler_id` 共同决定"能不能处理"；`@RequirePermission` 只检查角色，Service/Controller 另有归属检查；SUPPORT 精确字符串比对只可认领自己；状态条件多为 `status = 旧值`；`sendMessage` 的自动重开逻辑未接当前入口；评价与聊天没有独立评分列；工单不触发退款/改订单/通知；Redis 编号与 afterCommit 缓存动作都不在 MySQL 事务中。
- 练习：只读追踪提交；判断五个操作；两名客服认领并发；转交与处理竞争；六个常见误区判断；10 道自测。
- 关系：S2 讲这份数据如何被物化成索引及统计缓存；C2 的 `TicketTool` 复用同一服务；S4 深讲唯一 ID 失败边界。

#### S2 `03-support-and-cache/02-ticket-index-and-cache.md`

- 能力：能解释工单池 ZSet 的 score 如何编码优先级、更新时间与主键；能说明"版本 + ready + 重建锁"三者各自解决什么；能追踪整页回退与回表校验；能说清统计缓存的 TTL、重建、等待降级与双删窗口。
- 前置：S1、F4。
- 例子：客服打开工单池第 2 页；仪表盘刷新统计；写入与重建竞争。
- 源码：`TicketPoolIndex`、`TicketServiceImpl#getAdminTicketPage`/`getTicketStatistics`、`RedisLock`、`DelayedCacheEvictor`、`RedisScripts`、`RedisKeyConstants`、四个池/缓存测试。
- 难点：score 是浮点数但按"优先级权重 + 时间毫秒"编码；等分时的成员字典序；ZSet 空集合会被 Redis 删除，因此需要 ready 标记；Lua 原子隔离不等于回滚；统计缓存的 Redis 读/锁异常不会自动降级。`RedisLock` 在缓存重建场景中的用法给最小解释（令牌、固定 TTL、解锁比对），完整的锁类型对比在 S4。
- 练习：给定四条工单算 score 并排序；判断"索引未就绪"与"确实没有工单"如何区分；用 T1/T2 推演版本/锁/双删窗口。
- 关系：S3 从这里的缓存出发推导竞态；S4 对比重建锁与订单锁。
- **状态：已通过 P4-2 再次复审（2026-11-16）。** 两次返工修正：统计锁只抢一次/重建者需为新调用；失效与 isCurrent 的顺序分支；TTL 与失败的条件表述；PUBLISH 分阶段异常与空池边界；READ 未命中 ≠ 回 DB（三次读取阶段）、三处版本检查、DB 回退两条语句不强一致、`INVALIDATE` 异常不抛调用方、延迟删除按已登记任务/新写入区分。

#### S3 `03-support-and-cache/03-cache-races.md`

- 能力：能分别给出穿透、击穿、雪崩在本项目的具体例子；能用 T1/T2 说明"删除后仍有旧值回填"的窗口；能解释双删、墓碑、空值哨兵各自的适用条件与残留窗口；能判断墓碑是否已接线。
- 前置：S2、F3。
- 例子：查询不存在的城市名；统计缓存到期瞬间的并发刷新；城市数据变更。
- 源码：`UserUsernameBloomFilterService`、`TicketServiceImpl` 的统计缓存方法、`DelayedCacheEvictor`、`agentbase/amap/util/citycode/CityCodeUtil`、`RedisScripts` 的 `INVALIDATE_CITY_CODE`/`CACHE_CITY_CODE_IF_VERSION_MATCH`。
- 难点：三种"缓存问题"的判据是"查的数据存不存在/是否热点/是否批量同时过期"；墓碑与版本的先后；布隆过滤器不在可用性主链路上。
- 练习：为每个问题写一个"如果没有这层保护会怎样"的具体数字；判定墓碑读/写路径是否都接入。
- 关系：S4 把"版本"抽象出来与锁对比；C4 把同一思路用于消息列表。
- **状态：已通过复审（P4-3，2026-11-16）。** 两次返工修正：命中/miss 互斥时间线、版本校验与 NX 拒绝、响应丢失与命令报错；`__NULL__`/墓碑语义与布隆目标误判率；注册 add 前/中/后失败的跨库边界；13 个测试的键名收集、暂存键生命周期；锁在有效期内限制同一锁键的令牌获取，租期后可重叠。首次编写与两次审查记录保留。

#### S4 `03-support-and-cache/04-locks-leases-and-ids.md`

- 能力：能区分"锁令牌"与"回填版本"保护的对象；能解释手写锁与 Redisson 看门狗的分工；能说明两套唯一 ID 的用途与失败边界。
- 前置（硬）：S3、O2。推荐：先读 O4 的超时场景，便于理解"回调里释放锁"。
- 例子：缓存重建锁被误删；记忆回填覆盖并发追加；工单编号与订单号的生成。
- 源码：`RedisLock`、`RideOrderServiceImpl#withOrderLock`、`RedisMemory` 的版本脚本、`TicketServiceImpl#generateTicketId`、`IdGeneratorConfig`、`SnowflakeIdWorker`。
- 难点：**"锁的持有者"不是一种东西**——`RedisLock` 用随机 `UUID` 令牌标识持有者（不可重入，释放时比对令牌），Redisson `RLock` 用 owner（线程 + 实例标识，支持重入与看门狗续期）。版本租约则根本没有"持有者"，它比较的是数据代际。此外要说清 `PERSIST` 的版本 key 为什么不能过期、节点租约续期与"发号前校验"。
- 练习：给 4 个场景选择"锁/版本/条件更新"；画出锁在事务提交后的释放顺序。
- 关系：本章是**横向对比**，不重复讲租约的完整推导——Redis 09 的租约机制由 `04-chat-and-agent/04-memory-and-tool-results.md` 完整主讲，这里只做最小对比。C4 直接使用本章的版本概念；R2 的同步任务也用到"领取即占有"的思路。
- **状态：已通过复审（P4-4，2026-11-16）。** 返工修正：真实 `MessageMemory#save` 链与版本脚本分支/-1·0·1 返回、false 语义；锁回调注册≠释放成功、事务代理与目标方法返回区分、`FOR UPDATE` 顺序；序列溢出循环等待与实例内存状态、跨实例唯一性边界；三类测试的固定/随机 key 与未清理对象。首次编写与首次审查记录保留。

### 04-chat-and-agent

#### C1 `04-chat-and-agent/01-chat-round-and-sse.md`

- 能力：能按顺序说出一次发消息经过的关隘（限流、归属、分类、分发、循环、落库、结束）；能解释单轮执行锁与"对话锁定"的区别；能说明客户端断开后后台为什么继续、锁何时释放；能列出 SSE 事件类型与各自载荷。
- 前置：F4、A2、S4。
- 例子：林小雨连续发两条消息；第一条处理中又发第二条；中途关闭页面。
- 源码：`ChatController`、`ChatRequestRunner#run`、`ChatServiceImpl#chat`、`ChatManager`、`AgentStreams`、`AgentEvent`、`AsyncWorkConfig`。
- 难点：`doFinally` 与"任务真正结束"的差别；`publish().autoConnect(2)` 的意图；单次模型流超时与整轮无总超时。
- 练习：给出一条消息的事件序列（含被拒绝的情况）；判断三个时间点锁是否仍被持有。
- 关系：C2 进入 Agent 内部；C3 处理"暂停后恢复"；C4 处理"历史从哪来"。
- **状态：已通过复审（P5-1，2026-11-17）。** 按源码核验：`UUID.fromString` 在 `Flux.defer` 之前同步校验；单轮执行锁 `chat:execution:{chatId}` 不等待、看门狗、`get(3s)` 不是总超时；`autoConnect(2)` 未凑齐两个订阅者时上游不连接、后台不启动、`doFinally` 不运行；`doFinally` 发起解锁不等于成功释放；会话元数据由首轮 `initChat` 写 `sys_chat`，聊天消息由 Agent 终态保存，确认暂停会先保存；旧 `classRedis=ORDER` 分支不写新分类、仍调用 `OrderAgent`；有效拒绝不等于确认失败；GET/POST restore 与 `/r` 的语义已区分。C2 已通过复审；C3 已通过复审（P5-3，2026-11-17）；C4 已通过复审（P5-4，2026-11-17）。

#### C2 `04-chat-and-agent/02-routing-and-tool-loop.md`

- 能力：能说明分类结果如何决定四个 Agent；能解释"模型请求调用工具→服务端执行→结果回填模型"的循环如何终止；能对比四个 Agent 的工具集与循环差异；能指出分类没有独立类。
- 前置：C1、O1、S1。
- 例子：同一句话在"新会话"与"下单流程中"被路由到不同 Agent。
- 源码：`ChatServiceImpl#resolveClassification`、`ClassificationCache`、`ChatProperties`（四段提示词）、`OrderAgent`/`DailyAgent`/`SupportAgent`/`FallbackAgent`、`agentbase/tool/*`、`ToolNotifySupport`。
- 难点：分类缓存 key 为什么要带上下文指纹；工具循环的终止条件（模型不再请求工具 / 达阈值 / 显式 `notifyUser`）；`FallbackAgent` 没有工具循环。
- 练习：为三条输入写出分类与目标 Agent；找出工具注册与方法注解的对应关系。
- 关系：C3 是循环中的一个"主动暂停"；C5 展开工具内部的外部调用与提示词约束。
- **状态：已通过复审（P5-2，2026-11-17）。** 已写明分类上下文（`SINGLE_TURN`/旧分类/最后 Assistant 文本及 `SINGLE_TURN` 异常例外）、缓存 key 的双指纹/`encode`/TTL/碰撞与 `buildKey` 边界、DANGER/null/旧 ORDER 分支；四 Agent 的注册/分派/提示词/模型/循环差异；工具回执与 `getCalling`、工具/辅助索引/聊天历史三种副作用；`ToolContext` 身份与“回执不是授权”；正常、当前轮模型错误、上一轮工具后错误、阈值/暂停时间线及测试阅读。C3 已通过复审（P5-3，2026-11-17）；C4～C5 仍为计划。

#### C3 `04-chat-and-agent/03-order-confirmation.md`

- 能力：能完整复述"报价→挂起→用户确认→恢复→消费→落单"；能说明三处校验分别防什么；能解释重放与过期为什么会被拒。
- 前置（硬）：C2、O5。推荐：O1（订单状态机，便于理解"确认后才创建"）。
- 例子：用户先确认再反悔；两个标签页同时点确认；15 分钟后才点确认。
- 源码：`OrderConfirmation`（`quoted`/`begin`/`confirm`/`isConfirmed`/`consume`）、`ChatServiceImpl#resume`、`OrderAgent#resume`、`OrderTool#createOrder`、`ChatParamDTO`。
- 难点：`quoteId` 就是 `confirmationId`；`break` 的两态；消费发生在调用服务之前；提示词里的"必须确认"只是流程约束。
- 练习：为三种异常各写一段"哪一步返回什么"；判断"直接 POST /order 能不能绕过确认"。
- 关系：C4 解释恢复时历史如何配对；A2 的"两层授权"在此获得 Agent 侧对应物。
- **状态：已通过复审（P5-3，2026-11-17），2026-11-22 独立复核。** 已按 C3-R1～R5 返工，并处理暂停/恢复阶段、订阅时机、时间线单位、Redis 失败条件与客户端可见性不一致。C4、C5 均已通过复审。

#### C4 `04-chat-and-agent/04-memory-and-tool-results.md`（已通过复审）

- 能力：能说明三层记忆的读写顺序与各自寿命；能解释"回填覆盖并发追加"的竞态与版本解法；能说清工具结果的两次存储（消息全文 / 反查索引）各自服务什么；能按工具批次计算消息窗口并辨别坏记录恢复。
- 前置（硬）：C1、S4。**本章是 Redis 09（Lease 租约）的完整主讲章**；S4 只提供"锁 vs 版本"的最小对比，不替代这里的竞态推导。推荐 C3（恢复时 `getPersisted`/`answered` 的上下文）。
- 例子：长对话被截断后重新打开；模型要求反查上一轮工具结果。
- 源码：`MessageMemory#get/#getPersisted/#save/#clearChat`、`HeapMemory`、`RedisMemory`、`MysqlMemory`、`MessageWindow#tail`、`MessageParser#parse/#unparseHistory/#simpleParse`、`ToolResponseMemory`、`ToolRepPointerTool#recordCalling/#getToolResponse`、五段历史 Lua、`ChatManager`/`ChatInfoService`、相关实体/DDL。
- 难点：L1 无 TTL 且无容量上限；版本 key `PERSIST` 与 data key TTL 的区别；窗口按批扩展、`limit` 不等于条数；提示词中的 `call_id:` 压缩不是当前源码已实现；`chat:tool:v2:{chatId}:{callId}` 才是实际反查 key；工具层归属检查 + 双条件查询共同作用，不把隔离归功于单层。
- 练习：A～E 五组只读追踪（读链/脚本三态/窗口坏历史/反查边界/清空恢复）＋§10.1 逐测试阅读卡；10 折叠自测覆盖部分成功、响应丢失与测试范围。
- 关系：C5 的工具结果体积问题在此被解释；C1 的上下文来源在此闭合。
- **状态：已通过复审（P5-4，2026-11-17），2026-11-22 独立复核。** 返工逐项记录见 `evidence.md` C4 小节；本次复核更正了“`Agent#handleToolCalls`”这个不存在的类名与 `MessageParser` 的 null 归因。实际运行：无；只读源码与测试源码，未启动服务、中间件或模型。

#### C5 `04-chat-and-agent/05-external-tools-and-prompts.md`

- 能力：能指出一个工具方法从参数到外部 HTTP 调用的路径；能解释为什么地图返回的空数组字段要特殊反序列化；能说明提示词在哪些地方承担流程约束、在哪些地方不能替代校验。
- 前置：C2、O3、O5。
- 例子：用户说"我在西湖边，帮我叫车去机场"。
- 源码：`agentbase/amap/*`（`AmapRouteService`、`AmapGeoRegeoService`、`StringOrEmptyArrayToNullDeserializer`）、`agentbase/qweather/*`、`CurWeatherTool`、`POISearchTool`、`config/ChatProperties`。
- 难点：外部字段类型不稳定（空数组 vs 字符串）；超时与降级；提示词里"不得编造"与代码里"必须校验"的分工。
- 练习：给一个外部响应样例，判断反序列化后字段值；找出提示词中三处"流程约束"与对应代码守卫。
- 关系：全书 AI 侧的收尾；R1 讲知识库如何被工具检索。
- **状态：已通过复审（P5-5，2026-11-18），2026-11-22 独立复核。** C5-R1～R5 主体已修订；本次复核定点修正了城市码 key 规范化与 `AmapGeoRegeoService` 的“完整方法”标注。未运行服务、测试或模型。

### 05-rag

#### R1 `05-rag/01-retrieval-and-grounding.md`

- 能力：能说明一组问答在 MySQL 与 ES 里分别存成什么；能解释向量检索、BM25、RRF 各自的作用；能说明"召回后还要回权威库核对"的原因。
- 前置（硬）：F3。推荐：C5（`RagTool` 的调用位置在那一章；不读 C5 也能读本章，但会少一环"模型怎么用到检索结果"）。
- 例子：用户问"订单可以提前多久预约"。
- 源码：`RagController`、`RagService#searchAnswers`、`QaInfo`、`QaElasticMap`、`QaDocument`、`RagTool`。
- 难点："问题"与"答案"是 1:N（group）关系；ES 里的 answer 不参与索引；去重发生在 group 维度。
- 练习：给定 3 条召回，手算 RRF 排序；判断"删除后仍被召回"会返回什么。
- 关系：R2 解释索引为什么可能落后；S3 的缓存一致性思路在此以"索引一致性"形式重现。
- **状态：已通过复审（P6-1，2026-11-18），2026-11-22 独立复核。** R1-R1～R5 已复核并作局部修正；证据见 `evidence.md`。

#### R2 `05-rag/02-index-sync-and-budget.md`

- 能力：能解释"写接口成功≠立刻能搜到"；能说明同步任务的领取、重试与失败处理；能指出检索预算如何分配、取消是不是真的取消了底层请求。
- 前置：R1。
- 例子：管理员改答案后立刻搜索；同步任务连续失败；检索超时。
- 源码：`QaIndexSynchronizer`、`QaSyncJobMapper`、`QaSyncJob`、`AsyncWorkConfig`（`ragExecutor`）、`RagService` 的 deadline 逻辑。
- 难点：`FOR UPDATE SKIP LOCKED` 的语义；任务版本在当前实现中的实际作用；`Future.cancel(true)` 与底层 HTTP 取消的差别。
- 练习：画出"改答案→可检索"的时间线并标出可能滞后的区间；判断三种失败各自留下什么状态。
- 关系：全书最后一个机制章；P1 的三条旅程会再次经过这里。
- **状态：已通过复审（P6-2，2026-11-19），2026-11-22 独立复核。** R2-R1～R5 已逐项静态返工，依据见 `evidence.md`；本次复核删除了正文中重复的一处 `fixedDelay` 说明。

### 06-practice-and-review

#### P1 `06-practice-and-review/01-trace-three-journeys.md`

- 能力：能独立完成登录、订车（含 Agent 下单）、客服咨询三条旅程的逐步追踪，并在每一步说出"谁调用、改了什么、失败会怎样"。
- 前置（硬）：A1、O1、O4、S1、C1、C3。推荐：O2、O3（旅程里包含接单与位置上报）。
- 例子：三条旅程串成一天的故事。
- 源码：各章入口的汇总，不复制讲解。
- 难点：跨章衔接处（如"HTTP 下单"与"Agent 下单"的差异）。
- 练习：每步一个检查点，答不出时给出回溯链接。
- 关系：P2、P3 的基础。
- **状态：已通过复审（P7-1，2026-11-20），2026-11-22 独立复核。** 覆盖登录/OTP 边界与双 401/403 失败分支、Agent 下单全链（分类→工具循环→报价→暂停→确认→恢复→消费→创建→接单→超时竞争）、四层保护对照、客服咨询（双路检索回查、改答案/改问题两个交错）、跨旅程正向检查点与反向定位表、三组只读练习与 6 道折叠自测。已按 P1-R1～R5 修订登录写入/OTP 与 Lua 口径、异常传播链、`quoted→begin→保存→confirm` 时间点、接单 DB 活跃单与心跳前提、并发时间线、RAG 读时点、工单入口与四层可见性；本次复核补上 `ORDER_NOT_FOUND` 这个 409 来源。更正证据见 `evidence.md`。

#### P2 `06-practice-and-review/02-isolated-labs.md`

- 能力：能按四层（纯阅读 / 已有测试 / 隔离中间件 / 真实外部服务）选择实验，并说清每层能验证什么、不能验证什么。
- 前置：P1。
- 例子：用一次性 Redis 实例验证 Lua 的原子性；用现有回归测试观察条件更新。
- 源码：`src/test/java/` 下的测试类（只读，说明 mock 了什么）。
- 难点：测试通过 ≠ 生产正确；`FLUSHALL` 之类危险命令一律不给。
- 练习：按模板写实验卡（前提、步骤、预期、失败解释、清理）。
- 关系：P3 的综合题会引用实验结论。
- **状态：已通过复审（P7-2，2026-11-20），2026-11-22 独立复核。** 本章包含：四层路线与选层表；L0 两张静态练习卡 + 一张填好的示范卡；L1 两卡；L2 四卡；L3 两卡与支付/邮件边界阅读；跨层对照、一页复习、6 道自测。已按 P2-R1～R4 补正一次性实例身份确认/端口映射/收尾闭环与各方法清理边界、L3 的限流与重试前提、L0 字段归属、测试六分类与跨层缺项；本次复核更正 L1-1 中替身所在行号的归属。更正证据见 `evidence.md`。

#### P3 `06-practice-and-review/03-review-and-self-test.md`

- 能力：能按间隔复习计划复述核心机制；能完成覆盖身份归属、订单并发、缓存窗口、令牌重放、SSE 断开、RAG 落后的综合题。
- 前置：P1（硬）；P2 可选（不读也能完成，实验卡只用于继续验证）。
- 例子：综合题以真实故障叙述给出。
- 源码：题目附带源码入口。
- 难点：答案要给推导而不是结论。
- 练习：首次学习 / 隔日 / 一周后三轮。
- 关系：与 P4 索引配合使用。
- **状态：已通过复审（P7-3，2026-11-20），2026-11-22 独立复核。** 正文位于 `06-practice-and-review/03-review-and-self-test.md`：三轮复习（含时长估计、验收标准、最小组合）、七步只读源码路线、八道综合题（覆盖身份归属、订单并发、事务与缓存窗口、确认令牌重放、记忆工具批次配对、SSE 断开、RAG 索引落后；Q1/Q3/Q8 跨模块）、折叠答案与推导、15 行错题定位表、一页速查、P2 回看与 P4 导航。已按 P3-R1～R5 返工；本次复核逐题复算数值与源码入口，未发现新错误。实际运行：无；只读源码与测试源码。返工证据见 `evidence.md`。

#### P4 `06-practice-and-review/04-glossary-and-source-index.md`

- 能力：能按术语、类名、Redis key、机制四个入口反查章节。
- 前置：无（增量维护，随章节完成而扩充）。
- 例子：从 `order:status:` 反查 O2 与 C1；从 `ticket:statistics:` 反查 S2 与 S3；从 `chat:info` 的 `break` 反查 C3 与 O1。
- 源码：§2.2～§2.7 按控制器、服务与守卫、配置与拦截、Agent 与工具、记忆与持久化、Mapper/SQL/表名、代表性测试分组，条目给"先读什么 → 走到哪里"；§2.8 单列 11 项未接线/占位/遗留。
- 难点：同一概念在不同章节的措辞必须一致（如"对话锁定"与"单轮执行锁"）；约定前缀不等于物理 key（`tool:` 无调用方、`chat:tool:v2:` 内联）；同一 `chat:info:{chatId}` 有四条改 TTL 的路径（C3 §2.4）；五种时间口径（整 key TTL、成员心跳、ZSet 截止分数、Lua `TIME`、看门狗）不能互相替代。
- 练习：5 道索引练习（现象 → 索引路径 → 源码入口 → 结论 → 答错回看），其中 1 道从 key 跨对话与订单两章；另有 8 类现象入口与 14 行 P8 核验清单。
- 关系：工具章，不承担讲解；P3 的错题定位表与本章条目互相指向。
- **已通过第二次复审（P7-4，2026-11-21），2026-11-22 独立复核。** 已验收四类索引、八个现象入口、五道练习、28 个旧专题入口和核验清单。正文为 `06-practice-and-review/04-glossary-and-source-index.md`；本次复核更正 `/error` 排除写法与 §2.9 测试行数，并重新静态核验全部相对链接与显式锚点。

## 五、依赖清单（唯一口径）

下表是硬前置、推荐阅读与拓展去向的**唯一权威来源**。第四节的卡片、下面的依赖图、第六节的三种路线都必须与它一致；如有冲突，以本表为准并修卡片。

| 章 | 硬前置（必须先读） | 推荐先读（不读也能开始） | 读完后主要衔接 |
|---|---|---|---|
| F1 | — | — | F2、A1 |
| F2 | F1 | — | F3、A1 |
| F3 | F2 | — | A2、F4、O1、R1 |
| F4 | F3 | — | A1、O2、O3、C1 |
| A1 | F2、F4 | F3 | A2、P1 |
| A2 | A1、F3 | — | O1、S1、C1 |
| O1 | A2、F3 | — | O2、O3、O5、S1 |
| O2 | O1、F4 | O4（超时释放） | O4、S4 |
| O3 | O1、F4 | — | O4、C5 |
| O4 | O2、O3 | O5 | P1 |
| O5 | O1 | O2 | C3、C5、P1 |
| S1 | A2、O1 | — | S2、C2 |
| S2 | S1、F4 | — | S3 |
| S3 | S2、F3 | — | S4、C1 |
| S4 | S3、O2 | O4 | C1、C4 |
| C1 | F4、A2、S4 | O1 | C2、C4、P1 |
| C2 | C1、O1、S1 | — | C3、C5 |
| C3 | C2、O5 | O1 | P1 |
| C4 | C1、S4 | C3 | C5 |
| C5 | C2、O3、O5 | C4 | R1 |
| R1 | F3 | C5（`RagTool` 调用位置） | R2 |
| R2 | R1 | — | P1 |
| P1 | A1、O1、O4、S1、C1、C3 | O2、O3 | P2、P3 |
| P2 | P1 | — | P3 |
| P3 | P1 | P2 | — |
| P4 | — | 无（增量维护） | 任何阶段可查，不作前置 |

由硬前置推出的依赖图（只画硬边；推荐关系见上表）：

```text
F1 → F2
F2 → F3
F3 → F4
F3 → A2
F2 → A1
F4 → A1
A1 → A2
A2 → O1
F3 → O1
F4 → O2
O1 → O2
F4 → O3
O1 → O3
O2 → O4
O3 → O4
O1 → O5
A2 → S1
O1 → S1
S1 → S2
F4 → S2
S2 → S3
F3 → S3
S3 → S4
O2 → S4
F4 → C1
A2 → C1
S4 → C1
C1 → C2
O1 → C2
S1 → C2
C2 → C3
O5 → C3
C1 → C4
S4 → C4
C2 → C5
O3 → C5
O5 → C5
F3 → R1
R1 → R2
A1 → P1
O1 → P1
O4 → P1
S1 → P1
C1 → P1
C3 → P1
P1 → P2
P1 → P3
P4：不参与依赖
```

一致性说明：

- **F3 → F4**：F4 以 F3 的事务/回调边界为硬前置（Redis 侧副作用、`afterCommit`、有范围补偿、一致性读可见性）；F2 关系由 F3 传递。P1-3 已按此调整卡片、表格与依赖图。
- **O5 → C3**：箭头统一表示“先修 → 后学”。确认令牌建立在"报价由服务端产生"之上，所以 O5 是 C3 的硬前置。
- **C1 → C4**：三层记忆的读写在一次对话内发生，C1 讲完整轮次，因此 C4 不能先于 C1。
- **C5 不再依赖 C4**：外部工具章讲的是地图/天气/反序列化与提示词，不需要先读记忆；C4 只作为推荐。
- **S4 → C1**：单轮执行锁与"对话锁定"的区分需要锁的最小概念；S4 提供横向对比。
- **C5 → R1 仅为推荐关系，不画硬边**：检索章可以独立讲清数据模型与检索，`RagTool` 的调用位置只是衔接。
- O2 与 O3 之间无硬依赖，可交换；O4 需要两者。
- P4 若作为前置会形成循环（它索引所有章节），因此永久排除。

## 六、三种阅读路线

### 路线 A：初学（建议 10～12 周，每周 4～6 小时）

| 阶段 | 章节 | 可检查的成果 |
|---|---|---|
| 1 地图 | F1、F2 | 能不看笔记画出两条路径图；能说出 DTO/Entity/VO 各出现在哪一层 |
| 2 地基 | F3、F4 | 能给一个写操作标出事务边界与副作用；能解释 `Flux` 响应为什么不是 WebFlux |
| 3 身份 | A1、A2 | 能复述登录全链；能判断 5 个端点的授权层级 |
| 4 订单 | O1、O2、O3、O5、O4 | 能画状态机；能用时间线说明抢单竞态；能手算一次估价 |
| 5 工单与缓存 | S1、S2、S3、S4 | 能区分三种缓存问题的项目实例；能解释锁与版本的区别 |
| 6 Agent | C1、C2、C3、C4、C5 | 能追踪一条消息到工具与数据库；能说明确认令牌为什么不可绕过 |
| 7 RAG | R1、R2 | 能解释"接口成功≠可检索" |
| 8 综合 | P1、P2 | 能独立完成三条旅程；能写一张实验卡 |

检查方式：每阶段结束后只做该阶段章节的自测题，答错率 > 1/3 则重读该章的"源码导读"。

说明：阶段顺序按第五节表格的**硬前置**排列；表中"推荐先读"不强制，但跳过时要知道自己少读了什么。任何阶段都不要求先读完无关模块。

### 路线 B：复习（建议 2～3 天 + 每周 1 小时）

| 步骤 | 材料 | 可检查的成果 |
|---|---|---|
| 1 | 各章"一页复习"（每章约 1 页） | 能在 10 分钟内复述一章的流程与约束 |
| 2 | P3 综合题 | 能对 6 类故障给出定位思路 |
| 3 | 答错项回到对应章的"源码导读"与 `_meta/evidence.md` | 能指出自己错在"事实"还是"推理" |
| 4 | P4 索引抽查 | 从任意 key / 类名 30 秒内定位到章节 |

复习不要求重读全书；但每章的一页复习必须能独立成立（不依赖前后章）。

### 路线 C：专项（面向已有基础的读者）

以下列出硬前置闭合后的完整顺序。为避免每行重复，定义公共基础 **B = F1 → F2 → F3 → F4 → A1 → A2 → O1**。B 是展开记号，不是新增章节。已经学过的章节只需确认学习目标与自测；未满足的硬前置不能因表格省写而跳过。

| 专项 | 补齐前置（按序） | 主读（按序） | 产出 |
|---|---|---|---|
| Redis 与并发 | B | O2 → S1 → S2 → S3 → S4 → O3 → O4 | 能用时间线解释两处竞态，并说清残留窗口 |
| 订单业务 | B | O2 → O3 → O4 → O5 | 能画完整状态机与金额来源 |
| Agent 编排 | B → O2 → O3 → O5 → S1 → S2 → S3 → S4 | C1 → C2 → C3 → C4 → C5 | 能追踪一条消息到工具与落库，并区分授权来源 |
| RAG | F1 → F2 → F3 | R1 → R2 | 能解释索引滞后与预算边界；C5 仅为可选拓展，不是硬前置 |
| 安全边界（含 Agent 确认） | F1 → F2 → F3 → F4 → A1 | A2 → O1 → O2 → O5 → S1 → S2 → S3 → S4 → C1 → C2 → C3 | 能列出客户端、模型、服务端之间的可信边界 |

两条规则：

1. **硬前置不能跳过。** 例如直接读 O2 会因为不认识 `@Transactional`、`FOR UPDATE` 与"条件更新"而停在表面。
2. **用已有能力替代整章，必须检查全部学习目标并完成该章自测。** 正确率至少达到 5/6，且涉及身份来源、事务边界、数据恢复等关键约束的题目均正确；否则按初学路线补读。自测尚未编写时，只能作为计划中的检查方式，不能宣称已经通过。

专项路线的前置长度来自当前硬依赖设计，不代表每个读者都要重新读完整本。已有基础者可以逐章自测后跳过已掌握的内容；若后续实写发现硬前置过重，应先提出具体的最小概念前移方案、同步卡片与依赖表，经审查后再缩短路线。

## 七、旧资料覆盖与新增内容

逐篇路由见 `coverage.md`。摘要：

- 25 篇 Redis 专题全部有主承载章节，分散在 F4（Lua 基础）、A1、A2、O2～O4、S2～S4、C1～C5。
- 3 篇 Agent 专题分别主承载于 C1（编排与 SSE）、R2（索引一致性与预算，R1 补充检索侧）、A2（鉴权与边界，O5/C3 补充支付与工具授权）。
- 两个 README 仅作为导航参考，不进入正文引用链。
- 旧资料缺少、必须新写的内容：业务全貌与角色（F1）、请求分层与 DTO/VO（F2）、表关系与事务（F3）、Redis/Flux 最小概念（F4）、登录端到端（A1）、订单生命周期与计价支付（O1、O5）、工单业务生命周期（S1）、对话一轮与事件（C1）、分类与工具循环（C2）、记忆三层（C4）、RAG 数据模型（R1）、三条旅程与复习法（P1～P4）。

## 八、设计决定与复核状态

已接受的设计决定：F2 收窄为"请求路径与配置"、单独拆出 O5、S4 先横向对比后 C4 深讲、暂不建术语索引空壳、保留样章主干。当时留下的五个关注点，复核结论如下：

1. **F3 与 F4 的边界**：F3 只讲数据库事务、条件更新与提交后回调，F4 只讲 Redis 原子性/Lua 与 Flux 终态，并明确"Lua 不保证跨库事务"；已分工，无重复主讲。
2. **O1 的金额解释**：O1 给出"金额由服务端写入、公式见 O5"的最小结论和金额字段表，O5 主讲公式与手算；两章口径一致。
3. **S4 的横向对比**：C1 与 C4 对锁/版本的引用都能在 S4 找到最小定义，完整租约推导在 C4；无需回跳旧文。
4. **依赖表的"推荐"关系**：只标关键章，硬前置由第五节表格闭合；未穷举的推荐关系不影响路线可读性。
5. **P4 增量索引**：已随 26 章完成而补全，包含四类入口、未接线清单与全书复核清单。
