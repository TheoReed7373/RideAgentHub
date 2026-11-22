# 第 24 章 四层实验与清理：从读源码到可控验证

> 状态：**已通过复审（P7-2，2026-11-20），并于 2026-11-22 独立复核**。已按 P2-R1～R4 修订；本次复核更正了 L1-1 实验卡里替身所在行号的归属。证据与检查记录见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)。本章靠源码、测试源码与 Markdown 静态核查完成，**实际运行：无**（未启动应用/容器、未连接中间件、未发外部请求、未读取凭据）；文档中的"预期"与"实际运行证据"分开写。

## 学习目标

读完本章，你应该能：

1. **按层选择实验**：面对一个具体小问题，判断它该停在纯阅读、已有单测、隔离中间件还是真实外部服务，而不是一上手就装齐所有依赖。
2. **写出可执行的实验卡**：每张卡说清学习目标、所属层与前置、资源与副作用、步骤、预期观察与观察位置、失败解释、清理或"本卡无写入"、能证明与仍未验证的点。
3. **正确解读测试结果**：按本次 Maven 构建阶段、选中的方法、`target/surefire-reports/` 与退出状态，分辨"构建错误 / 未选中测试 / Skipped / 断言失败 / 测试运行异常 / 成功"六类结果，并说明 mock 替身没有验证哪些真实行为。
4. **守住隔离边界**：从实例启动记录、端口映射和测试写死的 `127.0.0.1` 核对"端口属于我自己独占的一次性实例"；识别 UUID 后缀、随机 schema、固定 key 与动态建删库的风险，知道哪一步必须停止，绝不指向共享或业务实例。**独立端口不等于独占实例。**
5. **用跨层证据链说明同一结论**：从 P1 的源码推演，到测试源码断言，再到隔离实例运行，逐层说明新增了什么证据、还缺什么证据；某层缺证据就标注缺项，不用相邻证据冒充。

## 前置知识与阅读时间

- **硬前置**：[第 23 章 P1《三条完整业务旅程》](01-trace-three-journeys.md)。本章所有卡片都从那三条旅程里取小问题，回链写作"P1 §…"。P1 已通过复审（2026-11-20）。
- **按卡选读**（不必先读完）：建单确认链看 [第 18 章 §二～§六](../04-chat-and-agent/03-order-confirmation.md#二报价quoted-到底写了什么)，客服取消预检看 [第 18 章 §十五](../04-chat-and-agent/03-order-confirmation.md#十五客服取消预检)（同一章的两套一次性令牌，不要混用字段）；订单锁与超时看 [第 10 章](../02-orders/04-timeouts-and-recovery.md)；RAG 回查看 [第 21 章](../05-rag/01-retrieval-and-grounding.md)；索引同步看 [第 22 章](../05-rag/02-index-sync-and-budget.md)；计价与支付门禁看 [第 11 章](../02-orders/05-pricing-and-payment.md)。
- **工具与版本假设**：Java 21、Spring Boot 3.5.9（据 `pom.xml:5-9,30`）。仓库有 `.mvn/wrapper/maven-wrapper.properties`（wrapper 属性 3.3.4、distribution 3.9.12），**但没有 `mvnw`/`mvnw.cmd` 脚本**，所以命令示例用系统 `mvn`；如果你的环境通过其他方式提供了 wrapper，命令形式按你的环境替换，本文不假设它存在。`pom.xml` 无 `<profiles>`，无显式 surefire/failsafe 插件配置，沿用 Boot parent 的默认测试命名（`*Test.java` 等）；MySQL 驱动版本由 parent BOM 管理，未在 `pom.xml` 显式写出。
- **默认终端**：Windows PowerShell。所有 `-Dtest=Class#method` 参数**带引号**，避免 `#` 被 PowerShell 当作注释起始。
- **阅读时间估计**：L0 两张卡约 25～35 分钟；L1 约 20～40 分钟（含首次 Maven 下载依赖）；L2 每张卡约 15～30 分钟（不含你自行准备一次性实例的时间）；L3 每张卡约 10～20 分钟（不含申请外部服务与时间）。**这些是估计值，不是实测耗时。**

## 零、四层路线：每层新增什么、还缺什么

从 P1 的三条旅程里挑一个小问题，例如"确认编号是否能重复消费""接单后旧超时待办会怎样""旧知识库候选为什么能返回新答案"。然后按下面顺序**逐层加证据**：

| 层 | 你拥有什么 | 新增的证据 | 仍然没有的证据 | 进入下一层的条件 |
|---|---|---|---|---|
| **L0 纯阅读** | 仓库源码、测试源码、P1 的推演 | 调用顺序、判断分支、返回值文本、Lua 语句、字段读写 | 代码是否真跑、并发是否如推演、数据是否真落库 | 想核对"测试作者是否也这么断言" |
| **L1 已有测试（不连业务中间件）** | L0 + 本地 JDK/Maven（首次可能下载依赖到本地缓存） | 测试确实对某个方法/类做了断言；mock 参数与调用次数；本地 `target/` 构建产物 | 替身替掉的真实组件行为、真实网络与数据库 | 想验证 Redis/MySQL 的真实原子性与事务 |
| **L2 隔离中间件** | L1 + 一次性 Redis/MySQL 实例与独立端口 | 真 Redis Lua、ZSet、真 MySQL 事务/DDL 下的行为 | 应用编排、HTTP、模型质量、外部网络 | 想验证已接线的真实调用链 |
| **L3 真实模型/外部服务** | L2 + 你自己的凭据与一次性环境 | 真地图/Embedding/ES 的返回与费用/限流现实 | 生产稳定性、别人账号的体验；不是回归测试 | 通常到此为止 |

**并排路径**：同一个机制可以同时给"静态追踪"和"真实资源验证"，两者互不强迫。例如"接单覆盖超时分数"在 L0 读 `OrderDelayQueue#schedule` 与 L2-R2 读 `OrderDelayQueueIntegrationTest#rescheduledOrderIsNotClaimedUntilNewDeadline` 的断言都能得到结论；你完全可以停在 L0，或者只读断言不启动 Redis。

**安全边界先写在前面**：L2 与 L3 的写/删动作只对**明确一次性、可由你单独控制、与业务或共享实例物理隔离**的实例执行。不要传入 `3306`（主配置默认端口，`src/main/resources/application.yaml:19`）或任何生产/共享地址；不要把 `-Dtest.*.port` 当作"随便一个本机端口"。任一步无法确认实例身份时**停止**，改为读断言（L1/L2 的"无中间件替代路线"）。

---

## 一、L0 纯阅读卡（可立即完成，零命令、零写入）

统一模板：学习目标 / 所属层与前置 / 资源与副作用 / 步骤 / 预期与观察位置 / 失败解释 / 清理 / 能证明与未验证。卡片中的人物与 ID 沿用 P1 的虚构值（林小雨 `910001`、chatId `7f9c24e8-8b3d-4c1e-9a6f-2d5e8b1c4a70`、报价编号 `c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e`、订单 `5004`）。

### 卡 L0-1：确认异常与工具异常的分叉

- **学习目标**：从入口分清 `confirm` 异常与 `consume` 异常各走哪条传播链，以及缺 `ReadyForCreate` 时连 Lua 都不会调用。
- **所属层与前置**：L0；P1 §2.4、§2.8、§4.2 已读；不需要任何服务。
- **资源与副作用**：只读 `service/base/OrderConfirmation.java`、`agentbase/tool/OrderTool.java`、`agents/OrderAgent.java`、`service/impl/ChatServiceImpl.java`、`agents/AgentStreams.java`；**本卡无写入**。
- **虚构初态**：Redis Hash `chat:info:7f9c24e8-…` 有 `quoteId=c8a17e2d-…`、`quoteCreatedAt=T0`、`ReadyforConfirm=true`、`break=yes`、`pendingToolCall=call-1`；**尚无** `confirmedQuote/ReadyForCreate`（它们是接受后由 CONFIRM Lua 写入，不是 `resume` 写入）；`now = T0 + 900001ms`；请求 `/r` 带 `confirmationId=c8a17e2d-…, confirmed=true`。统一前提：单轮锁与归属检查通过、`prompt` 非空或可按 `confirmed` 补为"确认"、`canStartChat` 的提示内容及限流检查均通过，否则流程更早结束，本节不作数。
- **步骤**：
  1. 读 `OrderConfirmation#confirm` 与 `CONFIRM` Lua：编号 `quoteId == confirmationId` → 仅 `yes` 检查严格 `now - quoteCreatedAt > 900000` → `break=resuming` 同决定重放或 `break=yes` 写 `break=resuming/confirmationAccepted`，**接受时由 Lua 写 `confirmedQuote=ReadyForCreate=quoteId`**。
  2. 判断 `now - created = 900001 > 900000` 是否触发 return 0；Java 侧抛什么异常、在哪条线程？
  3. 把时间改为 `T0 + 100ms`：CONFIRM 成功写入许可后，`OrderAgent#resume` 才检查 `break=="resuming"`、补工具应答并**删除** `break/pendingToolCall/confirmationAccepted`；它不写许可字段。
  4. 分别推演四个独立分支：缺 `ReadyForCreate` 的早退；前置全部通过、进入 `consume` 后 Lua 返回 0；`ChatServiceImpl#resume` 里 `confirm` 抛 409；超龄确认。
- **预期结果与观察位置**：① 超龄确认：Lua 返回 0 → `confirm` 抛 `BusinessException(409,"确认已失效或已被使用，请重新核对订单")`，发生在 `chatExecutor` 后台任务主体，由 `AgentStreams#guard` 尝试发通用 error 事件；② 正常恢复：许可由 `CONFIRM` Lua 写入，`OrderAgent#resume` 只清暂停字段并继续 `runLoopStep`；③ 缺 `ReadyForCreate`：`OrderTool#createOrder` 在调用 `consume` **之前**直接 `return "订单非可创建态"`，这是独立分支；④ 前置（锁、归属、`ReadyForCreate`、模型确实调用 `createOrder`）都通过、但 `CONSUME` 因年龄或许可不匹配返回 0：`consume` 抛 409，被 `OrderAgent#executeTool` 的 catch 转成 `"Tool execution failed: 尚未取得有效的用户确认，或本次确认已使用"`。观察位置：`OrderConfirmation.java` 两段 Lua、`ChatServiceImpl#resume`、`OrderAgent#resume/#executeTool`、`OrderTool#createOrder`。
- **失败解释**：把写许可算到 `resume` 头上，或把"前置通过"与"缺 `ReadyForCreate`"混成一个分支，都会读错责任归属；把④接到 `AgentStreams#guard`，是没分清异常发生在工具内还是后台任务主体；"900000ms 一到就超时"忽略源码是严格大于，正好 900000ms 不拒绝。
- **清理**：本卡无写入，无需清理。
- **能证明 / 仍未验证**：能证明源码分支与字符串文本；不能证明事件送达、HTTP 状态、模型下一轮是否转述（P1 §2.8、§4.2 已将可见性分四层）。

### 卡 L0-2：接单覆盖超时分数

- **学习目标**：说明"接单成功覆盖同一 ZSet member 的 score"为何让旧待办不再按旧到期时间领取，并区分"未到期重排"与"到点取消/回池"。
- **所属层与前置**：L0；P1 §2.9 已读；不需要 Redis。
- **资源与副作用**：只读 `service/base/OrderDelayQueue.java`、`util/RedisScripts.java`（`CLAIM_DUE_ORDERS`）、`service/impl/RideOrderServiceImpl.java`（`driverAcceptOrder`、`handleTimeoutOrderUnderLock`、`handleAcceptTimeout`、`handleArriveTimeout`、`reschedule`、`systemCancel`）；**本卡无写入**。
- **虚构初态**：订单 `5004` 为 `CREATED(10)`，ZSet `delay:order:timeout` 中 `member="5004"`、`score=T+15m`；司机 `920002` 心跳在 60 秒内持续有效。
- **步骤**：
  1. 读 `OrderDelayQueue#schedule`：死线在**调用时**由 `System.currentTimeMillis() + delay.toMillis()` 算出（第 55 行），但真正的 `ZADD` 由 `afterCommit(...)` 注册，**事务提交成功后才执行**；失败只记日志并等数据库补偿扫描。把它拆成"算 deadline"与"写 ZSet"两个时间点。
  2. 推演接单在 `T+14m59s` 调用 `schedule(5004, 10m)` 并成功提交：提交后回调把同一 member 的 score 覆盖为约 `T+24m59s`。
  3. 读 `CLAIM_DUE_ORDERS`：`ZRANGEBYSCORE 0 now LIMIT ...` + `ZREM`；判断 `T+15m05s` 是否还会返回 5004。
  4. 换前提：若旧待办已被 `claimDue` 领走，读 `handleTimeoutOrderUnderLock`——它在**订单锁内重新读 DB**，按状态与**业务锚点**（CREATED 用 `updateTime`/预约时间，DRIVER_ACCEPTED 用 `driverAcceptTime`，FINISHED_WAIT_PAY 用 `finishTime`）判断；这与队列 score 是两类时间，不要混。状态 20 未到锚点期限会 `reschedule`，已到期 20→10 回池；10 已到期则 `systemCancel` 10→90。
- **预期结果与观察位置**：提交后 ZADD 覆盖成功 → 旧 score 不再命中；到新期限且锁内重读状态=20 时走 `handleArriveTimeout`，按 `driverAcceptTime` 锚点判未到期/到期。观察位置：`OrderDelayQueue#schedule/#claimDue`、`RedisScripts.CLAIM_DUE_ORDERS`、`RideOrderServiceImpl#handleTimeoutOrderUnderLock/#handleAcceptTimeout/#handleArriveTimeout/#systemCancel`。
- **失败解释**：把"调用 schedule"当成"立即写 Redis"，漏掉提交后回调；把队列 score 当成业务锚点；写"状态 20 未到期什么都不做"漏掉 `reschedule`；把条件更新 0 行写成 409，实际走 `rollbackGrab + refreshOrderStatus` 返回 false。
- **清理**：本卡无写入。
- **能证明 / 仍未验证**：能证明源码的时间点拆分与分支自洽；**L2-R2 只验证同一 member 改期的 Redis 行为，不补证应用事务提交早晚、订单锁先后或业务锚点判断**——这部分仍是本卡静态推导，需应用链证据才能实测。

### 卡 L0-D（示范卡）：旧候选经权威库回查返回新答案

这张卡**已经按模板填好**，示范你怎么写一张卡。它取自 P1 §3.5 与 [第 21 章 RAG 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md) 的现成材料。

- **学习目标**：区分"ES 候选"与"本次 MySQL 回查的答案"，说明单个新答案不能证明整次索引重建完成。
- **所属层与前置**：L0；会读 `RagService#authoritative` 与 `updateAnswer` 即可，不必先读完 R2。
- **资源与副作用**：只读 `agentbase/rag/RagService.java`、`domain/entity/QaInfo.java`、`QaElasticMap.java`、`controller/RagController.java`；**本卡无写入**。
- **虚构初态与四个前提**：`sys_qa_info` 中 `groupId=301` 的 `answer` 原为 v1；`sys_qa_es` 的 `question="乘客取消订单违约金怎么算"`；ES 文档同 question/groupId，answer 副本为 v1。管理员在事务 `T1` 内把 `sys_qa_info.answer` 改为 v2 并 `enqueue(301)`，`T1` 在 `T1c` 提交。"返回新答案"这条推演**同时要求**：① 旧候选在本次搜索时**仍可见**（索引尚未重建可能删旧写新，也可能旧文档仍在，不能只凭"尚未重建"保证它还在）；② 至少一路（向量或 BM25）召回它；③ 它进入按 groupId 组内去重后的**前五**；④ 通过 `authoritative` 五条件，且**本次 MySQL 回查实际读到 T1c 提交的 v2 行**（回查发生时刻与读到哪一行分别判断，源码没有跨查询快照）。四个前提缺一个，就换下面的分支。
- **步骤**：① 读 `RagService#updateAnswer` 的 `@Transactional` 与 `syncJobs.enqueue` 同事务；② 读 `searchAnswers` 的双路召回→RRF→按 groupId 去重取前五；③ 读 `authoritative`：按 ES id 查 `sys_qa_es`（实体 `QaElasticMap`），按 groupId 查 `sys_qa_info`（实体 `QaInfo`），五条件通过后 `doc.setAnswer(info.getAnswer())`；④ 按四前提成立/不成立分别写观察结果。
- **预期结果与观察位置（三种分支）**：
  - **四个前提全成立、回查读到 v2** → 工具回执"标准回答"是 v2，即使 ES 副本仍可能写着 v1；
  - **回查实际读到 v1**（例如回查早于 T1c 提交，或本次读取使用较早快照）→ 即使旧候选召回并通过五条件，结果仍是 v1——这就是"旧结果"分支；
  - **没有候选**（旧文档已删而新文档尚未可见，或没进召回/前五）→ `authoritative` 收到空列表或全部候选被剔除 → `RagTool` 返回"知识库未找到有效的相关信息..."——**空库/无候选不能验证回查覆盖**。
  观察位置：`RagService#updateAnswer/#searchAnswers/#authoritative`、`QaInfo`/`QaElasticMap` 的 `@TableName`、P1 §3.5。
- **失败解释**：若你说"索引刷新成功才有新答案"或"索引未重建一定返回新答案"，都把两条独立路径混成一条；若把实体名当表名，查不到 `sys_qa_info`/`sys_qa_es`；把空列表当成"答案错误"也错——它可能是无候选。
- **清理**：本卡无写入。
- **能证明 / 仍未验证**：能证明"最终答案由本次权威回查决定"及其三个观察分支；不能证明真实 ES 分词召回、两表查询读时点、重建是否整体完成（P1 §3.5 三种情形）。

---

## 二、L1 已有测试卡（不连业务中间件，可只读或运行）

### 怎么选测试

先打开 `src/test/java/`，用文件名搜小问题关键词：`SecurityRepairRegressionTest`（身份覆盖、工具授权、事件）、`agentbase/rag/RagConsistencyRegressionTest`（RAG 写库/入队、回查、任务失败）。这两类**没有 `@EnabledIfSystemProperty` 门控**，字段用 Mockito 替身（`mock(...)`），不连真实 Redis/MySQL/ES。替身的含义要明确：`mock(ElasticsearchClient.class)` 没有验证 ES 真能写入与刷新，`mock(QaInfoMapper.class)` 没有验证 SQL 与事务。

`pom.xml` 无显式 surefire/failsafe 配置；默认执行 `*Test.java` 这类命名。仓库**没有 testcontainers**，也没有 `src/test/resources` 专用配置；集成测试的端口只能由运行时系统属性传入。

**L1 的"无业务写入"不等于"完全无副作用"**：`mvn test` 会走 Maven 生命周期（编译主代码与测试代码，产物在 `target/classes`、`target/test-classes`），生成 `target/surefire-reports/` 的 XML/文本报告，并可能解析、下载依赖到本机 Maven 缓存。这些是本地构建产物，不是业务中间件的写入；把两件事分开写，不要声称"零写入"。

**六种结果的判别口径（只看本次运行）**：先确认 Maven 生命周期到达 `test` 阶段、再看本次选中的类/方法、然后读 `target/surefire-reports/` 中**本次时间戳**的报告（skipped/failure/error 与堆栈）和命令退出状态：

| 结果 | 主要判据 | 不要这样推断 |
|---|---|---|
| 构建错误 | 编译/`-Dtest` 参数阶段失败，未进入测试的 tests run | 把编译失败当断言失败 |
| 未选中测试 | Maven 报"No tests were executed!"或选中的类/方法不匹配（与 Skipped 不同） | 与类级条件跳过混为一谈 |
| Skipped | 报告写了 skipped，通常因 `@EnabledIfSystemProperty` 门控属性缺失/非数字 | 把跳过当"测试通过" |
| 断言失败 | 报告 `Failures>0`，报告里有断言行与消息 | 直接归因为"mock 默认值没配"——要先用报告定位是哪条断言、哪个期望值 |
| 测试运行异常 | 报告 `Errors>0`（连接、NPE、超时等），与断言失败分开 | 把它当业务结论错误 |
| 成功 | 本次报告 `Tests run: n, Failures: 0, Errors: 0, Skipped: 0`，退出状态为成功 | 只看终端最后一行或上次遗留报告；旧报告的 0 失败不是本次证据 |

### 卡 L1-1：只读测试导读 + 单方法运行

- **学习目标**：用测试源码核对"工具不能自行授权创建"与"客户端价格/身份被服务端覆盖"两件事，并说明替身边界。
- **所属层与前置**：L1；P1 §2.2、§2.4、§4.2 已读；有 JDK 21 与 Maven；**不需要** Redis/MySQL。
- **资源与副作用**：`src/test/java/com/fancy/rideagenthub/SecurityRepairRegressionTest.java`；真实对象是 `OrderTool` / `RideOrderServiceImpl`。类字段替身在第 41～47 行（`RideOrderMapper`、`AmapRouteService`、`OrderRouteService`、`PaymentVerifier` 与构造服务用的若干 mock，`OrderLockTestSupport.availableClient()` 是 mock Redisson）；`mock(StringRedisTemplate.class)`、`mock(OrderConfirmation.class)` 在用例方法内（第 229 行起）。`@BeforeEach` 写 ThreadLocal，`@AfterEach` 清 ThreadLocal（第 54～59 行）；**无外部存储写入，本卡无清理**。
- **步骤**：
  1. 只读看第 229～235 行 `modelCannotGrantItselfCreationPermission`：`mock(OrderConfirmation.class)` 的 `isConfirmed` 默认 false，断言 `markOrderReadyForCreate(context()).contains("尚未")` 与 `verifyNoInteractions(redis)`。
  2. 只读看第 82～96 行 `creationOverwritesClientIdentityPriceAndTrace`：DTO 带 `userId=2/estPrice=0.01/radio=0.01/mongoTraceId="untrusted"`，替身高德返回 `estKm="10", estTime="600"`；断言捕获的 insert 行 `userId==1L`、价格等于 `RidePricing.calculate("10","600",1,0).getEstPrice()`、trace 不是 untrusted、并验证 `routes.addOrder`。
  3. 可选运行（PowerShell，在仓库根目录）：

```powershell
mvn "-Dtest=SecurityRepairRegressionTest#modelCannotGrantItselfCreationPermission" test
mvn "-Dtest=SecurityRepairRegressionTest#creationOverwritesClientIdentityPriceAndTrace" test
```

- **预期结果与观察位置**：源码预期两条断言通过；运行后看 `target/surefire-reports/` 的对应 XML/文本报告与终端 `Tests run: ...`。失败位置看报告里的堆栈行号与断言消息；替身调用看 `verify(...)` 所在行。
- **失败解释**：按上面的六类判别表定位。① 这类无门控，若 Skipped，先查 JUnit/运行参数，不要归因于测试设计；② "No tests were executed!" 属未选中，核对类名、方法大小写与 `#` 引号；③ 断言失败要从本次报告的断言消息与行号判断是 stub 不匹配（例如把 `isConfirmed` stub 成 true）还是基线源码变了，**不要先写"mock 默认值没配置"作结论**；④ `Errors>0` 看堆栈区分构建/运行异常；⑤ 成功看本次报告四项计数与退出状态。本章未实际运行，不保证你的环境输出逐字一致。
- **清理 / 副作用**：测试自身只清 ThreadLocal；不写缓存、库表或外部服务。但 `mvn test` 会编译并生成本地 `target/` 与 surefire 报告、可能下载依赖到本机 Maven 缓存——这是构建产物，不是业务写入。
- **能证明 / 仍未验证**：能证明路径替身下"标记工具在 `isConfirmed=false` 时返回含'尚未'且未触碰 Redis"与"服务端覆盖客户端 userId/价格/trace"这两条断言按源码应通过；**不能**证明 Lua 编号绑定、consume、`createOrder` 重放被拒（这个测试没测）、UUID 真由 `UUID.randomUUID()` 生成（`assertNotEquals` 不是类型证明）、radio 被重算（断言未覆盖），也不能证明真实 Redis/DB/地图参与。

### 卡 L1-2：RAG 回查的替身边界

- **学习目标**：用测试源码核对"答案来自当前数据库记录"与"旧 question 被拒绝"，并知道哪些真实行为被 mock 掩盖。
- **所属层与前置**：L1；P1 §3.4/§3.5；不需要 ES/MySQL。
- **资源与副作用**：`src/test/java/com/fancy/rideagenthub/agentbase/rag/RagConsistencyRegressionTest.java`（注意它在 `agentbase/rag/` 子目录，不在测试根包）。类字段 `mock(QaInfoMapper/QaElasticMapMapper/QaSyncJobMapper/ElasticsearchClient/EmbeddingModel)`；`@BeforeAll` 只初始化 MyBatis 元数据（第 25～28 行），`@BeforeEach` 反射注入 mapper 与雪花 ID（第 29～32 行）；**本卡无外部写入**。`embeddingSharesTheOverallSearchDeadline` 自建单线程池并在 `finally` 关闭（第 75～87 行）。
- **步骤**：
  1. 读第 48～52 行 `answerAlwaysComesFromCurrentDatabaseRecord`：stub 映射表 question="q"，`QaInfo.answer="current"`，ES 文档 answer="stale"；断言返回 "current"。
  2. 读第 53～57 行 `staleQuestionVectorIsRejectedUntilResynchronized`：映射表 question="new question"，文档 question="old question"；断言结果空。
  3. 可选运行：`mvn "-Dtest=RagConsistencyRegressionTest#answerAlwaysComesFromCurrentDatabaseRecord" test`，再按需运行整类 `mvn "-Dtest=RagConsistencyRegressionTest" test`。
- **预期结果与观察位置**：按源码预期两条断言通过；报告位置同 L1-1。这里 `authoritative` 的五条件被**手工 stub** 满足/破坏，没有真实 SQL 的 `groupId IN (...)`、没有真实 ES 文档。
- **失败解释**：按本次报告定位：若 `infos.selectList` 未 stub 会返回默认空列表导致断言失败，先读报告的期望/实际值再判断是替身配置还是源码变更；测试名"always comes from current database record"**不是结论本身**，只说明这一条断言，不等于所有读取路径都覆盖。结果分类同 §二的六类表。
- **清理 / 副作用**：无业务中间件写入；超时用例在 `finally` 自行 `countDown` 与 `shutdownNow`。`mvn test` 仍会生成本地 `target/` 报告并可能下载依赖。
- **能证明 / 仍未验证**：能证明全 mock 下 `authoritative` 用本次给出的 `info.getAnswer()` 覆盖文档 stale 副本、旧 question 候选被剔除；**不能**证明真实两表 SQL 与读时点、ES 召回、事务隔离或重建完成，也不能补证"某次新答案证明整次同步完成"（P1 §3.5/自测 5）。

---

## 三、L2 隔离中间件卡（仅限一次性实例）

### 进入前的可操作路线：确认一次性实例 → 核对身份 → 运行 → 收尾

这一步决定你能不能运行 L2；**核验失败就退回只读路线**（读门控、调用顺序与断言，不启动实例）。下面是可以照做的路径；工具与版本用范围表述，因为我不能替你验证本机安装。

**0. 所需工具与版本范围（静态事实 + 环境假设）**

- JDK 21 与 Maven（`pom.xml:30` 写 `java.version=21`；仓库无 `mvnw` 脚本）。你的 `mvn -version` 能显示实际 JDK/Maven，本文未运行、不知道你的版本。
- Redis 卡：一个可连接的一次性 Redis；测试用 Lettuce（宿主 `127.0.0.1`，端口来自系统属性）与 `OrderConfirmation` 的 Lua。本文不钉死 Redis 小版本；能执行这些脚本即可，但这是**实验前提**，不是我替你验证过的兼容矩阵。
- MySQL 卡：一个一次性 MySQL 实例；测试 URL 写死 `127.0.0.1` + `test.mysql.port`、用户 `root`、空密码（`RepairDatabaseIntegrationTest.java:36-38`）。DDL 中 `sys_user` 有 `GENERATED ALWAYS AS (...) STORED`（MySQL 5.7+ 语法）、`sys_messages` 有 `(chat_id, id DESC)` 降序索引（MySQL 8.0+ 语法，`src/main/resources/sql/init.sql:13-14,99`）；教材锁定读口径按 MySQL 8.4 手册，但仓库**没有钉死服务端小版本**，也没有 compose/Dockerfile。请按实例实际 `SELECT VERSION()` 与 DDL 兼容性自行确认；`init.sql` 要能成功执行。
- **root/空密码是测试源码写死的连接方式，不是让你放宽共享实例的安全设置。** 它只适用于你自己新建、只供本次实验使用、可随时丢弃的本地实例。任何"给共享实例加/改 root 空密码"的做法都停止。

**1. 建立只供本实验的实例（条件化示例，不是仓库自带配置）**

下面示例表示"由你创建、你独占、你负责销毁"的一次性实例；**端口仅作占位**。若你用图形界面或已有本地工具，按同样的核对点做即可，不必照抄命令。这些命令不属于仓库配置，也不会修改仓库文件。

```powershell
# 示例：本地一次性 Redis（端口占位，容器名只供本实验识别）
docker run -d --rm --name lab-p2-redis -p 127.0.0.1:16379:6379 redis:8
docker port lab-p2-redis 6379/tcp # 核对 127.0.0.1:16379
docker logs lab-p2-redis          # 核对服务就绪

# 示例：本地一次性 MySQL（端口/口令仅用于本次实验，不是仓库默认）
docker run -d --rm --name lab-p2-mysql -p 127.0.0.1:13306:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8.4
docker port lab-p2-mysql 3306/tcp # 核对 127.0.0.1:13306
docker logs lab-p2-mysql          # 核对初始化完成
```

启动前使用本机 Docker 上下文，确认 Docker Engine 28 或更新版本，且容器名称及宿主机端口未被占用。记录每次启动成功返回的完整容器 ID。启动失败、绑定不符或连接探测失败时，停止本卡，勿借用同名容器。实验期间只让本机可信操作者访问这些实例。

**2. 核对实例身份（每一步不过就停）**

1. 核对容器 ID、`docker port` 与启动日志，确认绑定在 `127.0.0.1`，宿主机映射端口就是你准备传给 `-Dtest.*.port` 的那个数字，且容器/进程名是本实验专用；端口映射存在 ≠ 你知道这个实例里原来有没有数据。
2. 确认 `127.0.0.1:<端口>` 到达的是这个实例：用你自己的客户端连接后查询实例自身信息（例如 Redis 的 `INFO server`/`INFO clients`，MySQL 的 `SELECT VERSION(), @@port`），再对照启动记录。测试源码写死 `127.0.0.1`（Redis：`SecurityRedisIntegrationTest.java:35`、`OrderDelayQueueIntegrationTest.java:33`；MySQL：`RepairDatabaseIntegrationTest.java:36`），所以不要把端口写成别的网卡地址或容器内部端口。
3. 确认实例是空的/本实验专用：Redis 上是新建空实例，没有别人的待办或会话；MySQL 上先用 `SHOW DATABASES` 看新建实例在首次运行前是否只有系统库——看到业务库、他人库或任何不认识的库名就停。
4. 确认你能在实验结束后停止并（对容器实例）删除这个实例；做不到就没有"一次性"前提，停在只读路线。
5. 不要把 `3306` 传给 `test.mysql.port`：`src/main/resources/application.yaml:19` 的默认 MySQL URL 指向 3306 的 `taxi_agent` 库，实验不得连接应用使用的 MySQL 实例。JDBC 会连接给定端口并在该实例新建临时 schema。

**3. 租户/DB 边界与门控**

- Redis 卡：`SecurityRedisIntegrationTest`/`OrderDelayQueueIntegrationTest` 连接的是该实例默认 DB（测试源码没有选择其他 database），不靠"换个 DB 号"隔离。Lua 与 key 都在 `127.0.0.1:<独立端口>` 这一实例内，**独立端口本身不保证实例归你**。
- MySQL 卡：测试类 `@BeforeAll` 在自己连接的实例上 `CREATE DATABASE ride_repair_<uuid>`，随后把数据源切到该 schema；`@BeforeEach` 的 7 张表 `DELETE FROM` 都发生在这一临时 schema 内。
- 门控：`SecurityRedisIntegrationTest`/`OrderDelayQueueIntegrationTest` 类级 `@EnabledIfSystemProperty(named="test.redis.port", matches="\\d+")`；`RepairDatabaseIntegrationTest` 为 `test.mysql.port`。**不传属性 = 整类 Skipped**（`@BeforeAll` 不执行）；属性是数字但实例不通 = 连接阶段异常。两者都不是断言结论。

**4. 停止条件（任一命中就停，退回只读断言）**

端口是 3306 或指向应用默认库；实例上有他人/业务数据；无法解释测试会写哪些 key/schema；无法从启动记录确认端口归属；Redis 不是独占实例（L2-R2 会删固定 key）；MySQL 实例不是你新建的一次性实例（测试需要建删库与执行 DDL）；运行中看到未预期残留；你想通过修改共享实例账号配置来满足测试。以上任一都不要继续。

**5. 收尾与残留（按实例与测试分别处理）**

- 正常 teardown：Redis 卡的 `@AfterEach` 清理它各自负责的 key；`@AfterAll` 关闭连接工厂。MySQL 卡 `@AfterAll` 在正则守卫通过后 `DROP DATABASE` 临时 schema，`@BeforeEach` 清表仅在该 schema。报告通过只说明断言通过，**不单独证明清理真的删干净**；如需核对，只在自己的一次性实例里查看相关 key/schema 是否还在。
- 启动失败：若 `@BeforeAll` 未成功，测试方法不会执行，后续清理仍取决于生命周期和实际执行结果；Redis 可能无写入，MySQL 可能停在"已 `CREATE DATABASE` 但未执行 `init.sql`"或"已建表但未跑用例"的阶段。
- 测试被中止（手动中断/进程退出）：`@AfterEach`/`@AfterAll` 不一定执行；Redis 可能留下带 suffix 的 key、token 索引/版本 key 或固定 key；MySQL 可能留下临时 schema 与其中数据。
- 删除失败：Redis 的 `keys(...)+delete(...)` 若执行中报错可能只删一部分；MySQL 的 `DROP DATABASE` 只在正则匹配时执行，且连接已断时不会执行。**不要补写宽匹配手工删除或通用删库命令**；对一次性实例，确认停止状态后丢弃整个实例/卷，是本章允许的收尾方式。
- 确认收尾：用启动时记录的完整容器 ID 核对归属，再用 `docker stop <本轮容器ID>` 停止自己创建的一次性容器，确认容器已移除且原端口不再响应。上面的 `--rm` 会移除容器及其匿名卷。另行挂载的命名卷或宿主机目录仍会保留，单独核对其归属及残留。

### 卡 L2-R1：真 Redis 验证确认编号绑定与一次性消费

- **学习目标**：在真 Redis 上验证 `confirmationId` 绑定当前报价、`consume` 只能成功一次，以及 `resuming` 同决定重放分支——补齐 L0/L1 缺失的"真 Lua 原子执行"证据。
- **所属层与前置**：L2；P1 §2.4、L0-1、L1-1 已读；一次性 Redis 实例 + 独立端口（示例用 `16379`，**仅占位**，换成你自己的隔离端口）。
- **资源与副作用**：`src/test/java/com/fancy/rideagenthub/SecurityRedisIntegrationTest.java#confirmationIsBoundToQuoteAndConsumedOnce`（第 78～86 行）。类级门控第 27 行；`@BeforeAll` 建 Lettuce 连接（第 34～37 行），`@AfterAll` 销毁（第 38 行）；`@BeforeEach` 生成 `suffix=UUID.randomUUID()`（第 40 行），`@AfterEach` 执行 `redis.keys("*" + suffix + "*")` 再 `delete(keys)`（第 44～46 行）。写入：`confirmation.quoted(suffix)` 写 `chat:info:{suffix}` 的 `quoteId/ReadyforConfirm/quoteCreatedAt` 与 24h TTL，`begin` 写 `pendingToolCall/break=yes`，`confirm` 写 `resuming/confirmedQuote/ReadyForCreate`，`consume` 删两个许可字段。smtp 邮件发送替身在本方法未用。
- **步骤**：
  1. 再次确认目标 Redis 是一次性实例；**先把端口写进命令**，不要依赖任何默认配置：

```powershell
mvn "-Dtest=SecurityRedisIntegrationTest#confirmationIsBoundToQuoteAndConsumedOnce" "-Dtest.redis.port=16379" test
```

  2. 只读核对测试源码第 78～86 行的调用顺序：错误 id 确认 → 正确 id 确认 → 同决定重放 → 相反决定 → `isConfirmed` → `consume` 两次。
  3. 运行后看报告；若想观察 key，用你自己的一次性实例客户端查 `chat:info:{suffix}` 的字段——但测试每例会按 suffix 删 key，运行后可能看不到。仅凭缺失无法判断是否曾写入或清理。
- **预期结果与观察位置**：按源码断言，错误 id 抛 `BusinessException`、正确 id 成功、同决定重放不抛、相反决定抛、`isConfirmed` 为 true、第一次 `consume` 成功第二次抛。观察位置：测试第 80～85 行与 `OrderConfirmation.java` 的两段 Lua；报告中的 `Tests run`。
- **失败解释**：类 Skipped → 没传 `-Dtest.redis.port` 或值非数字；连接异常 → 端口/实例不对；断言失败 → 先检查你是否对同一实例并发跑其他测试（suffix 只能隔离它自己的 key）；`KEYS` 扫描虽是 `"*" + suffix + "*"` 而非全库 `KEYS *`，**仍要求独占实例**。本卡片未实测，以上是条件化预期。
- **清理（选中方法与全类其他方法分开看）**：
  - **本选中方法**：只写 `chat:info:{suffix}`（suffix 是 UUID）；`@AfterEach` 用 `redis.keys("*" + suffix + "*")` 扫描并 `delete(keys)` 删除匹配项。运行后看不到这些 key **不能单独证明**清理真的执行——也可能是测试没写入、连到别的实例，或断言前就失败；要核对就在自己的一次性实例上按 suffix 查。
  - **同类其他方法，不在本卡执行**：验证码与节点租约写固定形态 key；`revocationFencesLateTokenPublicationAndRemovesWholeIndex` 用随机 `userId` 写 `auth:session-version:{userId}`、`auth:user_tokens:{userId}`、`auth:token:{suffix}`。前两类 key 不含 suffix，三类均由方法内 `finally` 尝试删除；`duplicateNodeConfigurationIsRejectedAndLostLeaseStopsGeneration` 用 Redisson 写固定 `id:node:1:1`，方法内显式 `redis.delete("id:node:1:1")`、由 try-with-resources 关闭租约，`client.shutdown()` 关闭 Redisson 客户端（第 148～169 行）。若这些方法中途异常，可能残留对应 key 或租约。
  - **正常/失败/中止**：`@AfterAll` 销毁连接工厂；启动失败或进程被中止时 teardown 不保证执行；删除命令报错时可能只删一部分。**不要补通用清空命令**：对本实验的一次性实例，确认停止后丢弃实例/卷即可（§三第 5 点）。
- **能证明 / 仍未验证**：能证明真 Redis 下这组**顺序调用**的 Lua 编号绑定与一次性消费；不能证明 HTTP `/r`、事件送达、并发确认，也不能由"看不到 key"反推清理成功（测试没有断言清理结果）。

### 卡 L2-R2：真 Redis 验证"改期覆盖同一 member"

- **学习目标**：用真 ZSet 验证 `schedule` 覆盖 score 的语义，与 L0-2 的推演形成跨层对照。
- **所属层与前置**：L2；L0-2；一次性 Redis（独占）。
- **资源与副作用**：`src/test/java/com/fancy/rideagenthub/OrderDelayQueueIntegrationTest.java`，类级门控第 24 行；`@AfterEach` 删除**固定 key** `RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY`（`"delay:order:timeout"`，第 45～48 行）。方法 `rescheduledOrderIsNotClaimedUntilNewDeadline`（第 79～88 行）先写 `schedule("100", ZERO)` 再 `schedule("100", 1h)`，断言 `claimDue` 为空、`zCard==1`，再改回 0 后能领到一次。另有 `parallelConsumersClaimEachDueOrderOnceAndPreserveFutureTasks` 与 `existingQueueEntriesRemainReadableAndBatchLimitIsRespected`，后者写入固定成员 `"old"`。
- **风险说明**：这个测试类没有 suffix；清理删除的是全实例唯一的固定 key。因此**只能在你独占的一次性 Redis 上运行**；若与他人共用，别人的待办会被你删除，你的断言也会被别人干扰。任何无法确认独占性的场景请停在这里，只读第 79～96 行断言。
- **步骤**：

```powershell
mvn "-Dtest=OrderDelayQueueIntegrationTest#rescheduledOrderIsNotClaimedUntilNewDeadline" "-Dtest.redis.port=16379" test
```

- **预期结果与观察位置**：按源码，先改期到 1h 后领不到；再次改到 0 后 `claimDue` 返回 `["100"]`。观察位置：测试第 81～87 行、`OrderDelayQueue#schedule/#claimDue`、`RedisScripts.CLAIM_DUE_ORDERS`。
- **失败解释**：Skipped → 门控未满足；连接错误 → 端口/实例不对；断言失败 → 固定 key 上可能有残留成员（例如同类其他方法写入的固定成员 `"old"`），先确认实例独占与刚跑过什么，而不是手工删 key 继续跑。
- **清理**：`@AfterEach` 对整个实例唯一的 `delay:order:timeout` 执行 `delete`；`parallelConsumersClaimEachDueOrderOnceAndPreserveFutureTasks` 还用 `finally` 关闭线程池并 `awaitTermination(5s)`。一般断言失败也会进入清理；若进程被中止或 `delete` 失败，固定 key 的成员会残留，且无法靠 suffix 区分归属——这就是必须独占实例的原因。不给手工替代清 key 命令：确认停止后丢弃一次性实例/卷即可。
- **能证明 / 仍未验证**：只证明真 Redis 里同一 member 改期覆盖 score、`ZRANGEBYSCORE + ZREM` 领取的队列语义；**不补证应用接单事务、订单锁的早晚或提交后回调顺序**（那是 P1 §2.9 的源码推演，需应用链证据才能验证）。

### 卡 L2-M1：真 MySQL 验证批量失败回滚

- **学习目标**：确认 `RagService#addQAs` 在中途抛错时，真 MySQL 里 QA 数据与同步任务一起回滚——补齐 L1 全 mock 缺失的事务证据。
- **所属层与前置**：L2；P1 §3.5、L1-2；§三已确认归属的一次性 MySQL 实例 + 独立端口（示例 `13306`）。测试**写死 root/空密码**，所以该实例必须是你能全权丢弃的本地实例；不要为共享实例改账号来验证它。所需权限：`CREATE DATABASE`、`DROP DATABASE`、在该库执行 `init.sql` 全部 DDL（含 InnoDB 表、生成列、降序索引）与 DML；版本按 §三第 0 点自行核对。
- **资源与副作用**：`src/test/java/com/fancy/rideagenthub/RepairDatabaseIntegrationTest.java`（类注释第 28 行"绝不使用应用 schema"）。`@BeforeAll`（第 35～50 行）以写死的 root/空密码连到 `127.0.0.1:<test.mysql.port>`，生成 `schema = "ride_repair_" + UUID...`（第 39 行），执行 `CREATE DATABASE`，把数据源切到该 schema，再由 `ScriptUtils.executeSqlScript` 执行完整 `src/main/resources/sql/init.sql`；`@AfterAll`（第 51～53 行）**只在 `schema.matches("ride_repair_[0-9a-f]{32}")` 时** `DROP DATABASE`——正则只守护变量形状，不证明实例是你的；实例身份靠 §三核对。`@BeforeEach`（第 54～59 行）对 7 张表逐表 `DELETE FROM`（无 WHERE）——只在临时 schema 内。所选两个方法（本卡的 `failedBatchRollsBackBothQaDataAndSyncJobs` 与下一卡 L2-M2）执行 `init.sql` 建表与自己的 SQL，**不运行** `migrationUpgradesOldSchemaWithoutDeletingExistingAccounts` 里的 `ALTER TABLE ... DROP INDEX/DROP COLUMN`（第 82～83 行）。方法本体（第 90～101 行）使用真实数据源与 `session.getMapper(...)` 真 mapper；服务是 `new RagService(...)` **直接构造**，事务由外层 `TransactionTemplate` 以 `READ_COMMITTED` 包裹，替身只有 `mock(EmbeddingModel.class)`/`mock(ElasticsearchClient.class)`。这不是 Spring 代理下的 `@Transactional` Service 测试。
- **步骤**：

```powershell
mvn "-Dtest=RepairDatabaseIntegrationTest#failedBatchRollsBackBothQaDataAndSyncJobs" "-Dtest.mysql.port=13306" test
```

- **预期结果与观察位置**：按源码，`addQAs(List.of(valid, invalid))` 因第二条空 DTO 抛 `IllegalArgumentException`，随后 `sys_qa_info`/`sys_qa_es`/`sys_qa_sync_job` 三表 `COUNT(*)` 都为 0。观察位置：测试第 96～100 行、`src/main/resources/sql/init.sql:162-182`、`src/main/resources/sql/migrations/20261113_security_consistency.sql`（本方法不执行迁移）。
- **失败解释**：Skipped → 门控属性缺失；连接/权限失败 → 端口、账号或 DDL 权限不符；"Table doesn't exist" → `init.sql` 未成功执行或版本不兼容；断言失败 → 按本次报告区分异常类型不符与回滚后三表计数不符。测试没有断言异常消息，也没有 `verifyNoInteractions(es)`——别把"事务回滚"扩大成"ES 没有调用"的证据。
- **清理**：正常结束时 `@AfterAll` 按正则守卫删掉本次临时 schema；`@BeforeEach` 清表只在该库。若 `@BeforeAll` 在 `CREATE DATABASE` 后失败、测试中止、连接断开导致 `DROP DATABASE` 未执行，可能留下 `ride_repair_<uuid>` 库及其中的表/数据；正则匹配只说明名字像，不等于"这是本轮实例"。收尾顺序：先用启动记录判断实例及临时 schema 的归属，再停止并清理确认属于本轮的一次性实例（§三第 5 点），否则停手请实例所有者按实际库名处理。**不给通用删库命令，也不清空共享库。** 迁移脚本 `20261113_security_consistency.sql` 不可重放，本方法不执行它。
- **能证明 / 仍未验证**：能证明在这个外层事务模板下，`new RagService(...)` 的批量写入与入队一起回滚；不能证明 Spring 代理的 `@Transactional` Service、ES 写入、并发同步器 `version` 比较，或生产实例隔离级别差异。

### 卡 L2-M2：真 MySQL 验证"持锁写入不丢任务"

- **学习目标**：读断言理解 `FOR UPDATE` 持锁期间新 `enqueue` 如何保留任务；这是 P1 里"索引同步最终一致"的数据库层前提。
- **所属层与前置**：L2；L1-2 的 `failedIndexSynchronizationRetainsJob` 已读；与 L2-M1 同一个一次性 MySQL。
- **资源与副作用**：`RepairDatabaseIntegrationTest#newMutationAfterLockedSyncCannotLoseItsJob`（第 116～137 行），同一类级 fixture 与清理。它写固定 `group_id=1`，用三个 latch 编排：reader 事务 `SELECT ... FOR UPDATE` 后持锁，writer 事务 `jobs.enqueue(1L)`，断言 writer 100ms 内未完成后放行、最终 `COUNT(*)=1`。`finally` 先 `release.countDown()` 再 `pool.shutdownNow()`；若 `await` 4 秒超时抛 `AssertionError`，残留行由后续 `@BeforeEach`/`DROP DATABASE` 处理。
- **步骤**：可选运行 `mvn "-Dtest=RepairDatabaseIntegrationTest#newMutationAfterLockedSyncCannotLoseItsJob" "-Dtest.mysql.port=13306" test`；至少只读第 116～137 行，画出 reader/writer 两条时间线并标注锁获取点与断言时刻。
- **预期结果与观察位置**：writer 在锁持有期间未完成；放行后最终仍有一行 `group_id=1`。观察位置：测试第 121～130 行、`src/main/resources/sql/init.sql:177-182` 的 `sys_qa_sync_job` DDL。
- **失败解释**：100ms 未完成是该受控交错下 Future 的等待现象，**不单独证明唯一等待原因、死锁或真实锁等待超时**；测试未检查保留行的 `version/next_attempt_at`，也未验证 `claimBatch/defer` 语义（那是 R2 的范围）。方法本体用真实 mapper 与事务模板，不经过 Spring Service 代理。
- **清理**：正常结束由类级 `@AfterAll` 按正则删临时 schema，行残留由 `@BeforeEach` 处理；`finally` 先 `release.countDown()` 再 `pool.shutdownNow()`，但如果 `await(release)` 4 秒超时抛 `AssertionError`，线程或连接可能未及时释放。中止/连接断开时的残留按 L2-M1 的收尾顺序处理：确认实例停止后丢弃一次性实例/卷，不给通用删库命令。
- **能证明 / 仍未验证**：能证明指定交错下 writer 的 Future 在 100ms 内未完成、放行后最终仍有一行 `group_id=1`；不能由此断定唯一等待原因、version 值、真实同步器成功，也不能把"任务不丢"写成无条件保证（R2 已登记 defer 失败等窗口）。

**无中间件时的替代路线**：没有确认归属的一次性实例时，停在 L0/L1：读门控注解、测试方法的 `assert/verify` 与 `RagConsistencyRegressionTest`/`SecurityRepairRegressionTest` 的替身设置，按 L0 的方法推演 Lua、ZSet 与事务。得到的是"源码与测试源码断言"，**不是**"真 Redis/MySQL 行为"——把结论写进"仍未验证"栏，不要删掉这一栏，也不要用别的层的证据冒充本层。

---

## 四、L3 真实模型或外部服务卡（可选，显式前提）

### 卡 L3-1：地图报价（已接线的 HTTP 入口）

- **学习目标**：在一次性环境用**真实地图**核对一次估价入口的调用链、返回与请求副作用，区分"估价预览价"与"创建订单重算价"。
- **所属层与前置**：L3；P1 §2.3/§2.4、[第 11 章](../02-orders/05-pricing-and-payment.md)、[第 20 章](../04-chat-and-agent/05-external-tools-and-prompts.md)；需要你自己的地图 key、实验令牌与一次性运行环境。**不要求**先完成所有 L2 卡。若无法确认运行实例的归属/有效配置，退回本卡末尾的静态替代。
- **资源与副作用（不只是一次地图请求）**：`controller/RideOrderController.java#estimate`（`POST /order/estimate`，`@RequirePermission`）→ `RideOrderServiceImpl#estimatePrice` → `agentbase/amap/service/AmapRouteService#getDrivingRouteEst` → `fetchDrivingRouteResponse` → 高德 REST。请求到达控制器前还有两道有副作用的拦截：`RequestRateLimitInterceptor#preHandle` 对 `request:rate:{远端地址指纹}` 执行 `SLIDING_WINDOW_RATE_LIMIT`（60000ms 窗口、上限 120；Lua `ZREMRANGEBYSCORE` 移除旧成员、`ZCARD` 计数、放行则 `ZADD` 本次请求 id 并 `PEXPIRE`，超限返回 -1 → **应用自身** `BusinessException(429,"请求过于频繁，请稍后重试")`）；`AuthTokenInterceptor#preHandle` 调 `TokenServiceImpl#getToken` 读 Redis 里的 UserToken，并回查 MySQL 的 `sys_user`（角色/状态、`credentialStamp`）+ 比对 `sessionVersion`，有效才写 ThreadLocal；令牌过期分支会 `deleteToken`，失败分支不写上下文（见 P1 §1.3）。`estimate` **不创建订单、不落业务库、不调 Mongo 轨迹**；它会写上面那个 Redis 限流 ZSet，并可能删过期令牌。`getDrivingRouteEst` 最多尝试 3 次（最多 2 次重试）。code≥500 的 `BusinessException` 在前两次可重试，最后一次原样抛出，较低业务码直接抛出。其他 `RuntimeException` 在第 3 次才包装为 `BusinessException(502,"高德路径规划失败(重试3次仍失败)")`（第 125～184 行）。所以**一次 HTTP 估价可能产生最多 3 次上游请求/计费**，不是一次网络调用。配置键：`amap.url/key/timeout`（`src/main/resources/application.yaml:99-102`，key 用你自己的占位值；本文不写真实 key，也不要求使用仓库默认配置）。
- **启动整体依赖（区分一次请求与进程后台）**：应用带 `@EnableScheduling`（`RideAgentHubApplication.java:8`）。启动即创建的 `IdGeneratorConfig#idNodeLease` 会取 Redis 锁 `id:node:{datacenterId}:{workerId}`（默认 1:1），拿不到就抛 `IllegalStateException`——应用可能因此起不来（见 P1/S4 的节点租约说明）。启动后 `QaIndexSynchronizer#synchronize`（`fixedDelayString="${rag.sync.interval-ms:5000}"`、`READ_COMMITTED`）与 `OrderTimeoutJob#pollTimeoutOrders`（`${ride-order.timeout.poll-interval-ms:5000}`）、`recoverOverdueOrders`（fixedDelay 60000ms）会周期性领取任务、写 ES/MySQL/Redis；这些**不是本次请求触发**，但会与你的实验共用同一实例。若实例里还有 RAG 待同步任务，启动时可能已有 ES 写入。用一次性实例就是为了把这类后台写入和代价纳入可丢弃范围。
- **显式前提与虚构输入**：一次性应用环境（含它专用的 Redis/MySQL）；你自己的配置覆盖（不给真实值/默认凭据）；一名实验账号的令牌占位 `Bearer <YOUR_LAB_TOKEN>`（**不要使用真实账号**）；虚构坐标起点 `30.20,120.15`、终点 `30.25,120.22`，车型 `1`、不加急 `0`；请求体字段按 `RideOrderController.PriceEstimateReqDTO` 写 `startLat/startLng/endLat/endLng/vehicleType/isExpedited`。
- **前置检查点（启动和请求分开核对）**：启动前先核对 Spring 与 Redisson 实际启用的全部连接，包括 Redis、MySQL、ES 和 MongoDB，确保均指向一次性实验资源，外部连接仅指向获准的实验服务。启动后检查：① 应用日志显示启动完成、没有 `id:node:...` 租约异常；② 你看到的有效配置指向你的一次性 Redis/MySQL 与自己的地图 key（配置来源可用环境变量或启动参数覆盖，具体值由读者环境提供；本文不读取也不写出）；③ 实验令牌能访问 `/order/estimate` 但不携带任何真实业务身份；④ 你已确认本实例没有他人请求在跑，能解释限流 ZSet 的写入；⑤ 计划只发少量请求（例如 1～3 次），每次最多触发 3 次地图调用，按你的 provider 配额估算费用。
- **步骤（PowerShell，占位值）**：

```powershell
# 仅在一次性环境、替换占位值后执行；本文未执行
$body = @{ startLat = 30.20; startLng = 120.15; endLat = 30.25; endLng = 120.22; vehicleType = 1; isExpedited = 0 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://<LAB_HOST>:<LAB_PORT>/order/estimate" -Headers @{ Authorization = "Bearer <YOUR_LAB_TOKEN>" } -ContentType "application/json" -Body $body
```

- **预期观察和观察位置**：按源码是 `Result.ok(PriceEstimateVO)`，估距/估时/价格/倍率来自 `RidePricing.calculate`；每次放行还应在你的一次性 Redis 看到 `request:rate:{指纹}` 的 ZSet 成员增加、TTL 被设置（由 `SLIDING_WINDOW_RATE_LIMIT` 写入），身份复核若读到有效令牌不会修改 token key。观察位置：HTTP 响应体、`RideOrderServiceImpl#estimatePrice`、`RidePricing.java`、高德调用日志与发生重试时的告警。**真实数值、重试次数与限流计数取决于你的环境，本文不编造响应或日志文本。**
- **失败定位（看响应来源）**：401 `请先登录` → `PermissionAspect` 未拿到身份；估价入口的 `@RequirePermission` 未指定角色；应用自身 429 `请求过于频繁，请稍后重试` → `RequestRateLimitInterceptor` 的滑动窗口（看上面那个 Redis key），**不要**把地图 provider 的限流混进来；502 `高德路径规划失败...` 或 `高德 API 业务失败` → `AmapRouteService` 的重试与包装（可能是 3 次上游调用后的结果）；`amap.key 未配置` 是 `fetchDrivingRouteResponse` 的配置守卫。价格与手算不同先核对单位（秒/公里）与公式（[第 11 章](../02-orders/05-pricing-and-payment.md)），不要先怀疑服务端作弊。
- **清理**：本卡不创建订单、不写业务表；会留下 Redis 限流 ZSet（TTL 60s）与可能的调度后台数据。停应用只停止进程，不删除数据：确认一次性实例/容器已停止、端口不再响应后，由你决定是否丢弃实例与持久卷；地图 provider 侧配额/请求记录无法删除。清空限流 key 的通用命令本文不给。
- **静态替代**：若上述前提任一项无法核验，只读 `RideOrderController#estimate` → `RideOrderServiceImpl#estimatePrice` → `AmapRouteService#getDrivingRouteEst/#fetchDrivingRouteResponse`、`WebMvcConfig#addInterceptors`、`RequestRateLimitInterceptor#preHandle`、`RedisScripts.SLIDING_WINDOW_RATE_LIMIT`、`AuthTokenInterceptor#preHandle`、`TokenServiceImpl#getToken`，按 P1 §1.4 与 [第 20 章](../04-chat-and-agent/05-external-tools-and-prompts.md) 的静态口径写明"预期会写限流 ZSet、复核令牌、最多 3 次地图请求"，不声称已运行。
- **能证明 / 仍未验证**：能证明你的环境里该入口返回了什么、限流 key 是否如源码所写；不能证明 Agent 报价与创建重算同价、支付通道可用，也不能用一次请求代表生产稳定性。

### 卡 L3-2：知识库检索（ADMIN 入口 + Embedding + ES）

- **学习目标**：在**已有可核验隔离数据集**的一次性环境，用真实 Embedding 与 Elasticsearch 做一次知识库检索冒烟检查，核对返回结构；若数据集满足条件，再核对 `authoritative` 用 MySQL 覆盖答案。
- **所属层与前置**：L3；P1 §3.4/§3.5、[第 21 章](../05-rag/01-retrieval-and-grounding.md)、[第 22 章](../05-rag/02-index-sync-and-budget.md)；ADMIN 实验账号、ES/DashScope 由你自己的环境提供。**这是可选卡**：仓库没有提供一键种子脚本或禁用调度的开关，本文也不编造；若无法核验下列预置条件，退回静态替代并只验证能读到的代码路径。
- **必须先核验的数据与配置前提（缺一就只做冒烟或退回静态）**：
  1. 一次性环境里有 ADMIN 角色实验账号（令牌用占位 `Bearer <YOUR_ADMIN_LAB_TOKEN>`，不给真实值）；不要用真实用户账号或真实问题/答案。
  2. `sys_qa_info`（`group_id`、`answer`）与 `sys_qa_es`（`elastic_id`、`group_id`、`question`）中存在你为实验准备的映射与答案行；两表 DDL 见 `src/main/resources/sql/init.sql:162-182`。**仓库没有内置预置流程**，这些行必须由你的隔离数据集提供；没有它们就谈不上验证"回查覆盖"。
  3. ES 索引 `qa_knowledge_base` 存在，其中的文档与 `sys_qa_es` 的 question/groupId 对应，并且**已 refresh 可搜索**；`authoritative` 的五条件要求 `mapping.question == doc.question` 且 groupId 一致（`RagService.java:395-419`）。文档不可见时返回空列表不是 bug。
  4. 向量维度与 Embedding 模型兼容：`src/main/resources/application.yaml` 明示 `spring.ai.dashscope.embedding.options.model=text-embedding-v4`、`dimensions=1024`（这是 **YAML 显式值**，不是 `RagService` 的 Java 默认值；Java 侧 `searchTimeoutMillis` 默认 8000ms、`RRF_K=60`、`WINDOW_SIZE=100`、`@Value` 覆盖见第 48、303～304 行）。若索引向量维度和当前模型输出不一致，向量路会失败，不能把错误归给 RRF。
  5. 先按 L3-1 核对实际启用的全部连接，确认都指向各自的一次性实验资源，再启动并确认应用就绪；启动后 `QaIndexSynchronizer`（`@Scheduled(fixedDelayString="${rag.sync.interval-ms:5000}")`、`READ_COMMITTED`）会按 `sys_qa_sync_job` 后台重建索引，**它是独立于本次请求的写入**，可能在实验期间改变文档可见性。
- **资源与副作用**：`controller/RagController.java#search`（`GET /rag/search?question=...`，`@RequirePermission({"ADMIN"})`）→ `RagService#searchAnswers`（第 306 行起）→ `embeddingModel.embed`、向量/BM25 两路（ES 索引 `qa_knowledge_base`）、RRF、按 groupId 去重取前五 → `authoritative` 回查 `sys_qa_es`/`sys_qa_info`。配置键（只列键名，值由读者环境覆盖）：`spring.elasticsearch.uris/username/password`、`spring.ai.dashscope.api-key`、`spring.ai.dashscope.embedding.options.model`、`spring.ai.dashscope.embedding.options.dimensions`；API 文档 `src/main/resources/api/rag-consistency.md`。入口只读业务数据，但 Embedding 会产生费用，ES 查询消耗资源；后台同步可能同时写 ES。
- **虚构输入**：实验问题"乘客取消订单违约金怎么算？"——仅当你的隔离数据集确有为这道题准备的行时使用；否则把它换成该数据集里存在的测试问题，并在卡上写明替换值。**不要读取或复制任何真实用户问题/答案。**
- **步骤（PowerShell，占位值）**：

```powershell
# 仅在一次性环境、替换占位值后执行；本文未执行
Invoke-RestMethod -Method Get -Uri "http://<LAB_HOST>:<LAB_PORT>/rag/search?question=%E4%B9%98%E5%AE%A2%E5%8F%96%E6%B6%88%E8%AE%A2%E5%8D%95%E8%BF%9D%E7%BA%A6%E9%87%91%E6%80%8E%E4%B9%88%E7%AE%97%EF%BC%9F" -Headers @{ Authorization = "Bearer <YOUR_ADMIN_LAB_TOKEN>" }
```

- **预期观察和观察位置**：有匹配数据且前提 2～4 成立时，返回 `Result.ok(List<QaDocument>)`，每条答案由 `authoritative` 用本次 MySQL 行覆盖（P1 §3.5）；观察位置是 HTTP 响应体与 `RagService#authoritative`。**空列表也可能自洽**：无候选/候选未进前五/五条件失败都可能返回空，不能据此声称验证了回查覆盖。`RagService` 没有输出"检索耗时"日志，不要去找不存在的日志行；要定位超时看响应与异常包装。此卡走 HTTP，检索异常传播到 `GlobalExceptionHandler`，工具回执文案只适用于 Agent 调用。
- **失败定位**：403 → ADMIN 角色守卫；空列表 → 先按 P1 §3.5 的三情形与前提 2～4 排查；超时 → `searchAnswers` 的共享 deadline、`ragExecutor` 队列（[第 22 章](../05-rag/02-index-sync-and-budget.md)）；Embedding/ES 连接错 → 外部配置，不是 RRF 公式；答案不符合预期 → 核对本次回查读到的 `sys_qa_info` 行，而不是某个 ES 文档副本。
- **清理**：入口本身不写业务表；后台任务可能写 MySQL、Redis 或 ES，令牌复核也可能删除过期 token。`/rag/search` 不在源码所列 IP 限流器路径中。停应用只停进程；确认一次性实例/容器停止后再决定是否丢弃实例与持久卷。ES/模型侧费用无法删除；本文不给通用清库/删索引命令。
- **静态替代**：没有可核验的隔离数据集时，只读 `RagController#search`、`RagService#searchAnswers/#authoritative`、`QaIndexSynchronizer#synchronize`、两表 DDL 与 YAML 键名，用 L0-D 的三分支写清"有候选/无候选/回查读到哪个版本"，停留为源码+测试源码结论，不声称访问过真实 ES/模型。
- **能证明 / 仍未验证**：前提齐备时可观察这条链的实际返回。要单独验证覆盖，再用一个已知 ES 副本与 MySQL 答案不同、且观测期间未被重建的样本对照返回值；**不能**由一次新答案证明整次索引同步完成（P1 自测 5），也不能在空库上证明回查覆盖。

### 边界阅读（不是支付测试）

默认支付能力来自 `config/PaymentConfig.java` 的 `@ConditionalOnMissingBean(PaymentVerifier.class)` Bean：它只抛 `BusinessException(503, "支付渠道尚未接入，无法核验支付结果")`。`RideOrderServiceImpl#payOrder` 还要先过归属与状态 50 守卫。[第 11 章](../02-orders/05-pricing-and-payment.md) 已讲清：**看到 503 只能证明默认门禁生效，不是支付通道测试成功**；当前仓库没有可验证的真实支付渠道配置键或脚本，本文不提供"支付成功"实验。邮件同理：`spring.mail.*` 只给键名与占位，不提供真实发信步骤。

---

## 五、跨层对照：同一结论的三种证据

以"确认编号绑定当前报价、只能消费一次"为例（P1 §2.4/§2.8）：

| 层 | 入口 | 你实际看到的 | 新增证据 | 仍未验证 |
|---|---|---|---|---|
| L0 | `OrderConfirmation#confirm/#consume` 两段 Lua、`OrderTool#createOrder` | 编号不匹配返回 0；严格 `>900000ms`；同决定 `resuming` 重放；CONFIRM 写许可、consume HDEL 两字段；缺 `ReadyForCreate` 直接返回文本 | 分支、字段归属与字符串来自源码 | 代码是否真跑、Lua 是否原子 |
| L1 | `SecurityRepairRegressionTest#modelCannotGrantItselfCreationPermission` | "尚未取得用户对当前报价的确认"断言与 `verifyNoInteractions(redis)`（另有覆盖身份/价格/trace 的断言） | 标记工具授权边界与服务端覆盖的**关联证据** | **缺项**：这条用例不测确认编号绑定、Lua 编号比较或 consume；跨层表不把它写作该结论的 L1 证据 |
| L2 | `SecurityRedisIntegrationTest#confirmationIsBoundToQuoteAndConsumedOnce` | 真 Redis 下错误 id 抛错、同决定重放不抛、consume 二次抛错 | 真 Lua 原子执行与字段写入（**顺序调用**，无并发） | HTTP `/r`、事件送达、并发确认、清理是否真的删净 |
| L3 | 无对应支付/地图卡 | — | 这一结论不需要外部服务 | 不应虚构一个"外部确认 API" |

第二个对照（L0-2 ↔ L2-R2）：L0 读 `OrderDelayQueue#schedule` 的 member=orderId、调用时算 deadline、提交后 ZADD 与 `CLAIM_DUE_ORDERS` 推出的"改期覆盖旧 score"，L2-R2 用真 ZSet 断言"改期后 `claimDue` 为空、再改回 0 才能领到"。**L2-R2 不补证应用订单锁/事务提交的早晚**；L1 没有对应无依赖卡（该类门控 Redis），所以写"L1 无覆盖"，不要为了表格整齐编一个测试。

第三个对照（L0-D ↔ L1-2 ↔ L2-M1 ↔ L3-2）："答案由本次回查决定"在 L0 是四前提推演；L1 用全 mock 固定 `info.getAnswer()`；L2-M1 是批次回滚、L2-M2 是持锁交错，**都不是真库回查测试**；L3-2 只有在隔离数据集齐备时才验证真实回查。若只做到 L2-M1，就把"回查覆盖"标为缺项，不能用回滚证据补。

**跨层方法**：每往下一层，只问"上一层缺什么"；不要因为 L3 更"真实"就删掉 L0 的条件说明。四层结论冲突时，先检查前提是否一致（实例、端口、替身、时间锚点），再谈代码 bug。

---

## 六、一页复习

- **选层**：问题能用源码回答就停 L0；想核对断言走 L1；要原子性/事务走 L2（确认归属的一次性实例）；要真实返回与费用才走 L3。某层没有同一结论的证据就写"缺项"。
- **实例身份**：独立端口不等于独占实例；从启动记录、端口映射、实例自身信息与已有数据核对，任一不过退回只读。测试写死 `127.0.0.1`，Redis 不换 DB 隔离，MySQL 写死 root 空密码只用于你自己的一次性实例。
- **门控**：`test.redis.port` / `test.mysql.port` 缺失 = Skipped，不是成功；属性数字存在但服务不通 = 连接异常，也不是断言失败。
- **六种结果**：构建错误 / 未选中（`-Dtest` 不匹配）/ Skipped / 断言失败（Failures）/ 测试运行异常（Errors）/ 成功。按本次 `target/surefire-reports/`、构建阶段与退出状态判断；不从测试名、上次遗留报告或只看终端最后一行得结论。
- **替身边界**：mock 掉的 Redis/ES/Mapper 没有验证真实组件；L2-M1/M2 是外层事务模板 + 直接 new 的服务，不是 Spring 代理 Service 测试；L2-R1 是顺序调用，不证明并发消费。
- **隔离与收尾**：Redis 有 UUID suffix 的 `KEYS "*suffix*"` 与固定 key `delay:order:timeout` 两种清理；token 索引/版本 key 与 `id:node:1:1` 属其他方法的显式清理；MySQL 有随机 `ride_repair_<uuid>` schema + 正则守卫 + 建删库 + 逐表 `DELETE`，正则只守名字。停应用不等于删数据；确认实例停止后丢弃一次性实例/卷。不给 `FLUSHALL`、不给通用清库/删 key 命令，端口不要写 3306。
- **版本**：Java 21、Boot 3.5.9；仓库无 wrapper 脚本，示例用 `mvn`；DDL 生成列/降序索引提示 MySQL 5.7+/8.x 语法，仓库未钉死小版本，按实例 `VERSION()` 自行核对。
- **证据句**：本章未实际运行任何测试或服务；"源码预期 / 测试源码断言 / 假设输出 / 外部运行证据"分开写。

## 七、自测与答案（6 题）

<details>
<summary>1. 你只想知道"确认编号不匹配时 Lua 返回什么"，没有 Redis 也没有应用。该停在哪一层？写出观察位置。</summary>

L0。读 `service/base/OrderConfirmation.java` 的 CONFIRM 脚本第 2 行 `HGET quoteId ~= ARGV[1] then return 0`，再看 `confirm` 把非 1 结果转成 `BusinessException(409,...)`。P1 §2.4/§4.2 已给同类推演。L1 的 `SecurityRepairRegressionTest` 没有覆盖这条；L2-R1 才用真 Redis 验证。没有实例时不要为了让表格好看而写"已运行"。
</details>

<details>
<summary>2. 运行 mvn "-Dtest=SecurityRedisIntegrationTest#confirmationIsBoundToQuoteAndConsumedOnce" test 后报告显示 Skipped，最可能的原因是什么？这能说明确认逻辑正确吗？</summary>

类级 `@EnabledIfSystemProperty(named="test.redis.port", matches="\\d+")`：没传 `-Dtest.redis.port` 或值不是数字时整类跳过，`@BeforeAll` 不执行。Skipped 对确认逻辑零证据；要补 `-Dtest.redis.port=<一次性实例端口>` 并确认独占性。若传了数字仍失败，先看是连接异常还是断言失败。本文未实测你的环境输出。
</details>

<details>
<summary>3. 为什么 OrderDelayQueueIntegrationTest 只能跑在独占 Redis，而 SecurityRedisIntegrationTest 的 UUID suffix 也不算"绝对安全"？请分清所选方法与同类其他方法写的 key。</summary>

队列用例 `@AfterEach` 删除整个实例唯一的固定 key `delay:order:timeout`；共用实例上别人的待办会被删，你的断言也会被干扰。确认卡本身写 `chat:info:{suffix}`（UUID suffix），`@AfterEach` 用 `redis.keys("*" + suffix + "*")` 扫描删除——是双通配 KEYS 扫描，且"运行后看不到 key"不能单独证明清理成功。**同类其他方法不属于本卡**：令牌撤销用例用随机 `userId` 写 `auth:session-version:{id}`、`auth:user_tokens:{id}` 和 `auth:token:{suffix}`。前两种不含 suffix，靠方法内 finally 删除；节点租约用例写固定 `id:node:1:1`，显式 `delete` 并关闭 Redisson 客户端。它们中途异常都可能残留，所以两者都要求由你确认归属的一次性独占实例；独立端口或 UUID 都不等于独占。
</details>

<details>
<summary>4. RepairDatabaseIntegrationTest 的正则守卫能保证删的是本轮临时库吗？L2-M1/M2 测的是 Spring 代理下的 Service 事务吗？</summary>

不能保证。正则 `ride_repair_[0-9a-f]{32}` 只检查变量形状，不证明这个 schema 在你的一次性实例里、也不证明它属于本轮；实例身份要靠启动记录、端口映射、`SELECT VERSION()` 与 `SHOW DATABASES` 核对。逐表 `DELETE FROM`（7 张表）没有 WHERE，但数据源在 `@BeforeAll` 已切到刚 `CREATE DATABASE` 的临时 schema，所以只在一次性实例内可接受；发现端口实际指向应用实例则停止。L2-M1/M2 都用**外层 `TransactionTemplate`（READ_COMMITTED）以及真实数据源和 mapper**。M1 直接构造服务，M2 直接调用数据库查询与 mapper；它们证明的是这个事务模板下的回滚/持锁交错，不是 Spring `@Transactional` Service 代理。L2-M2 的 100ms Future 未完成也不证明唯一等待原因，且未检查保留行的 version。
</details>

<details>
<summary>5. RagConsistencyRegressionTest 的回查断言、L2-M1 的批次回滚，能证明"真库回查覆盖答案"吗？跨层表该怎么写？</summary>

都不能代替。L1 用全 mock mapper 固定 `sys_qa_info` 行和文档，只验证 `authoritative` 用本次给出的 `info.getAnswer()` 覆盖 stale 副本；真实 SQL、两表读时点与 ES 召回没参与。L2-M1 的 `failedBatchRollsBackBothQaDataAndSyncJobs` 验证的是外层事务模板下批次失败后三表计数为 0，**没有调用 `searchAnswers/authoritative`**，不能补证真库回查。跨层表里"确认编号绑定/消费"这条的 L1 证据应标**缺项**（`modelCannotGrantItselfCreationPermission` 只测标记工具）；"回查覆盖"这条要到 L3-2 且隔离数据集前提齐备才有直接证据。某层缺证据就写缺项，不用相邻层证据冒充。
</details>

<details>
<summary>6. POST /order/estimate 除了价格还会留下什么？一次请求遇到 429/502/401 分别在哪一层？能说支付通道可用吗？</summary>

副作用不止价格：`RequestRateLimitInterceptor` 对被挂 `/order/estimate` 的路径执行滑动窗口 Lua，放行会写 `request:rate:{指纹}` ZSet 成员并 `PEXPIRE` 60s，超限返回应用自身的 `BusinessException(429,"请求过于频繁，请稍后重试")`；`AuthTokenInterceptor` 的 `getToken` 读 Redis 并回查 MySQL 的 `sys_user`（角色/状态/凭据指纹/版本），过期令牌会被删除，未拿到身份则 `PermissionAspect` 抛 401。之后 `AmapRouteService#getDrivingRouteEst` 最多 3 次尝试（最多 2 次重试）。上游 HTTP 或业务失败通常由该调用链转成 502，业务异常的精确消息见 L3-1——所以一次 HTTP 估价可能对应最多 3 次地图调用/计费。入口**不创建订单、不支付**；默认 `PaymentVerifier` 只在 `payOrder` 通过前置守卫后抛 503，那只是门禁生效，不是支付通道测试成功。Agent 报价与创建重算价可能不同（P1 §2.4、[第 11 章](../02-orders/05-pricing-and-payment.md)）。
</details>

## 八、导航与源码入口

- **上一章**：[第 23 章 三条完整业务旅程](01-trace-three-journeys.md)（P1，已通过复审）——本卡所有案例的回溯索引。
- **下一章**：[第 25 章 间隔复习与综合题](03-review-and-self-test.md)（P3，已通过复审）。
- **相关专题**：[第 10 章 超时与恢复](../02-orders/04-timeouts-and-recovery.md)、[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)、[第 18 章 订单确认、恢复与消费](../04-chat-and-agent/03-order-confirmation.md)、[第 21 章 RAG 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md)、[第 22 章 RAG 索引同步与检索预算](../05-rag/02-index-sync-and-budget.md)。

**本轮只读核验的源码/配置/测试入口**（相对仓库根）：`pom.xml`；`src/main/resources/application.yaml`；`src/main/resources/sql/init.sql`、`src/main/resources/sql/migrations/20261113_security_consistency.sql`；`src/main/resources/api/{auth-security,chat-security,driver-location,order-security,rag-consistency}.md`；`src/test/java/com/fancy/rideagenthub/{SecurityRepairRegressionTest,SecurityRedisIntegrationTest,OrderDelayQueueIntegrationTest,RepairDatabaseIntegrationTest}.java`；`src/test/java/com/fancy/rideagenthub/agentbase/rag/RagConsistencyRegressionTest.java`；生产代码入口见各卡的"资源与副作用"栏。定位优先用"类 + 方法"，行号会漂移。

**状态：已通过复审（P7-2，2026-11-20），2026-11-22 独立复核。** 见 [最终检查记录](../_meta/review-2026-11-22.md)。
