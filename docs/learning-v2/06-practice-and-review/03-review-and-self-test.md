# 第 25 章 间隔复习与综合题：三次复习、八道推演与一张速查

> 状态：**已通过复审（P7-3，2026-11-20），并于 2026-11-22 独立复核**。已按 P3-R1～R5 返工：Q2/Q8 的独立时间线与 20/40 区分、Q3 的交错与 COUNT/未知提交、Q1 的双头缺失与写范围、Q4 的正常主线/反事实与报价记号、Q5 的条件性副作用、Q6 的订阅起点与工具循环、Q7 的独立召回/回查时间线；本次复核逐题复算数值与源码入口，未发现新错误。检查记录见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)。实际运行范围为无。

## 学习目标

读完本章并完成三轮复习，你应该能：

1. **按间隔计划复述七个机制**：身份归属、订单并发、事务与缓存窗口、确认令牌重放、记忆工具批次配对、SSE 断开、RAG 索引落后。对每一个，能说出"真值在哪、第一道守卫是谁、失败后留下什么"。
2. **在具体场景里预测结果**：给定初态、角色、交错与时间，写出结论、第一失败点、状态变化和可观察信号，而不是只背结论。
3. **并列推演两种结局**：当并发、模型或网络使结果不唯一时，写清"两种结局各自需要什么前提"，不把不确定结果伪装成唯一答案。
4. **区分四层可见性**：同步 HTTP 状态与 `Result.code`、SSE 事件、工具回执字符串、模型文本/客户端实收。知道哪一层是源码可确认的，哪一层只是"尝试发出"。
5. **按错误类型回到具体章节**：把错题归因为概念错误、调用顺序错误或条件遗漏，并立刻找到对应新教材小节与源码方法重读。

## 前置知识与阅读时间

**硬前置**：[第 23 章 三条完整业务旅程](01-trace-three-journeys.md)（P1，已通过复审）。P1 的硬前置（A1、O1、O4、S1、C1、C3）必须已经读过；本章不再补讲登录、订单状态机、超时、工单、SSE 与确认令牌的基础概念。

**可选前置**：[第 24 章 四层实验与清理](02-isolated-labs.md)（P2，已通过复审）。不读 P2 也能完成本章；P2 的价值在于"某个结论想再验证一层"时，给你现成的实验卡与清理边界。本章所有题都设计成**离线只读源码即可完成**，不需要 Redis、MySQL、Elasticsearch 或模型。

**推荐同时放在手边的章节**（不必重读，只需按题回溯）：

| 主题 | 主要回溯章节 | 本章对应题 |
|---|---|---|
| 身份、角色、归属、ToolContext | [A2 权限与防护](../01-auth/02-authorization-and-defense.md)、[S1 工单生命周期](../03-support-and-cache/01-ticket-lifecycle.md) | Q1 |
| 订单锁、Redis 预检、条件更新、回调 | [O2 抢单与并发](../02-orders/02-grab-and-concurrency.md)、[O4 超时与恢复](../02-orders/04-timeouts-and-recovery.md) | Q2、Q8 |
| 统计缓存、双删、重建锁 | [S2 工单池与统计缓存](../03-support-and-cache/02-ticket-index-and-cache.md)、[S3 缓存竞态](../03-support-and-cache/03-cache-races.md) | Q3 |
| 报价、暂停、恢复、消费 | [C3 订单确认](../04-chat-and-agent/03-order-confirmation.md) | Q4 |
| 消息窗口、工具结果反查 | [C4 三层记忆](../04-chat-and-agent/04-memory-and-tool-results.md) | Q5 |
| 单轮执行锁、SSE、订阅边界 | [C1 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md) | Q6 |
| 双路检索、权威回查、索引同步 | [R1 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md)、[R2 索引同步与预算](../05-rag/02-index-sync-and-budget.md) | Q7 |

**阅读时间估计**：首次通读本章约 125～160 分钟（含做题与对答案）；三轮复习的时长见下一节。**这些是估计值，不是实测耗时。**

---

## 一、三次复习安排：每一轮只做不同的事

本章不要求你重读 24 章。三轮复习分别解决"建立骨架""修补最容易混的三处""在间隔后重建长期记忆"，每轮有明确任务、验收标准和答错后的回归入口。

### 1.1 第一轮：首次学习后（估计 125～160 分钟，可拆成两次）

**目标**：把七个机制的"真值—守卫—失败出口"写成一张自己的表，而不是看懂别人的表。

| 任务 | 做什么 | 时间估计 | 验收标准 |
|---|---|---|---|
| R1-A 空白复述 | 合上书，对七个主题各写三行：真值在哪、第一道守卫是谁、失败后留下什么。写不出就留空，不要翻书 | 30～40 分钟 | 七个主题至少各写出两行；空白处就是下一轮的重点 |
| R1-B 做前四题 | 完成 Q1～Q4。每题先写"结论 / 第一失败点 / 状态变化 / 可见层"四行，再展开答案 | 45～60 分钟 | Q1～Q4 中至少 3 题的"第一失败点"正确；Q4 能区分早退、CONSUME 失败、CONFIRM 失败与恢复异常 |
| R1-C 做后四题 | 完成 Q5～Q8。重点写"两种结局各自的前提"，不要只选一个 | 40～50 分钟 | Q2、Q3、Q8 至少各写出两种结局；Q5 能手算一次窗口 |
| R1-D 对照速查 | 用章末"一页速查"核对自己的表，只改错的行 | 10 分钟 | 能用自己的话解释每一行的"最容易记错" |

**答错后回到哪里**：先看**第四节**的错题定位表。不要立刻重读整章；先判断错在"概念""调用顺序"还是"条件遗漏"，再只读对应小节和源码方法。

### 1.2 第二轮：隔日（估计 35～45 分钟）

**目标**：在遗忘刚开始时，专门修补三处最容易混的地方——订单并发、确认令牌、SSE 订阅。

| 任务 | 做什么 | 时间估计 | 验收标准 |
|---|---|---|---|
| R2-A 回忆不变量 | 不看书，写出七条"一句话不变量"（例如"DB 条件更新才是接单裁决"） | 5 分钟 | 至少写出 6 条；写不出的直接进入 R2-B |
| R2-B 重做三题 | 只做 Q2、Q4、Q6，仍按四行格式写 | 15 分钟 | Q2 能画出"锁→预检→条件更新→提交后回调"的顺序；Q4 能区分早退、CONSUME 失败、CONFIRM 失败、恢复异常；Q6 能说出"断开不等于取消后台" |
| R2-C 对答案并回到源码 | 对完答案后，只读三处源码：`OrderGrabService#tryGrab`、`OrderConfirmation` 的 CONFIRM/CONSUME、`ChatRequestRunner#run` | 15 分钟 | 能指出每个方法里"决定结果的那一行"，而不是只复述结论 |
| R2-D 更新错题本 | 把仍然错的题写成"我原来的答案 → 正确第一失败点 → 我漏了哪个条件" | 5 分钟 | 每条错题都有可重读的章节与源码方法 |

### 1.3 第三轮：一周后（估计 50～70 分钟）

**目标**：在间隔后检查"是否还能独立重建"，而不是"是否还有印象"。

| 任务 | 做什么 | 时间估计 | 验收标准 |
|---|---|---|---|
| R3-A 速查热身 | 读一遍章末"一页速查"与七条不变量 | 10 分钟 | 能说出每个主题的真值来源 |
| R3-B 八题快判 | 八道题都做，但每题只写三行：第一失败点、可观察信号、下一步查哪个方法；不写完整推导 | 30 分钟 | 8 题中至少 7 题的"第一失败点"正确 |
| R3-C 两题深挖 | 从 R3-B 的错题中选两题，重新写完整推导，再对答案 | 15 分钟 | 能说出"两种结局各自的前提"，不再把条件句写成结论句 |
| R3-D 选一张实验卡 | 只读 P2 中与错题对应的实验卡，判断"要不要在隔离环境跑"；不要求真的运行 | 10 分钟 | 能说出该卡新增什么证据、不能证明什么 |

### 1.4 时间不足时的最小复习组合

不是每个人每轮都有完整时间。下面给出三档，按"最少的题覆盖最多的失败模式"选择：

| 可用时间 | 组合 | 覆盖的失败模式 | 说明 |
|---|---|---|---|
| 10 分钟 | 章末"一页速查" + 七条不变量 | 真值来源与守卫 | 只适合考前唤醒，不替代做题 |
| 20 分钟 | 速查 + Q1 + Q4 | 身份层级、工具回执、确认重放 | Q1 的四个子案例一次覆盖 401/403/业务越权/工具 catch |
| 30 分钟 | 速查 + Q2 + Q6 + Q7 | 并发裁决、SSE 订阅、RAG 回落 | 三题都要求"两种结局/条件"，适合检查是否只会背结论 |
| 45 分钟 | Q3 + Q5 + Q8 + 对答案 | 缓存窗口、消息批次、跨模块综合 | 这三题最容易暴露"顺序/条件"错误 |

**最小组合的硬要求**：无论选哪档，都要写出"第一失败点"和"可观察信号"；只看答案不算复习。

### 1.5 只读源码路线：不启动任何服务也能完成

这条路线只读源码，不需要 Redis、MySQL、Elasticsearch、模型或任何外部服务。建议按顺序走，每步 8～10 分钟，总计约 56～70 分钟。

| 步骤 | 读什么 | 要能回答的问题 | 读完后自查 |
|---|---|---|---|
| S1 身份 | `interceptor/AuthTokenInterceptor#preHandle`、`aspect/PermissionAspect#checkPermission`、`RideOrderServiceImpl#assertCanViewOrder`、`TicketTool#createTicket` + `SupportAgent#executeTool` | 缺令牌、角色不符、看别人订单、工具拿别人订单，分别在哪一层被拒？ | 能区分 401/403/工具回执三种可见层 |
| S2 订单 | `OrderGrabService#tryGrab`、`RideOrderServiceImpl#driverAcceptOrder/#withOrderLock`、`OrderDelayQueue#schedule/#claimDue`、`handleTimeoutOrderUnderLock` | Redis 预检成功是否等于接单成功？DB 0 行返回什么？改期与旧待办谁先？ | 能画出"锁→预检→条件更新→提交后回调" |
| S3 缓存 | `TicketServiceImpl#getTicketStatistics/#evictTicketStatistics`、`DelayedCacheEvictor#evictAfter`、`RedisLock#tryLock` | 统计的真值在哪？第一次删除失败会怎样？500ms 之后回填的旧值谁来清？ | 能说出"TTL 上界"和"双删窗口" |
| S4 确认 | `OrderConfirmation` 的 CONFIRM/CONSUME、`OrderTool#createOrder`、`OrderAgent#resume`、`ChatServiceImpl#resume` | 重复 createOrder 早退、CONSUME 失败、CONFIRM 失败、恢复异常，四种出口各是什么？ | 能说出 `resume` 清哪些字段、Lua 写哪些字段 |
| S5 记忆 | `MessageWindow#tail`、`MessageParser#unparseHistory`、`ToolResponseMemory#save/#get`、`MessageMemory#save` | `limit` 是条数上限吗？缺一条工具应答在什么位置会抛异常？`getCalling` 查的是哪份数据？ | 能手算一次窗口起点 |
| S6 SSE | `ChatRequestRunner#run`、`AgentStreams#guard/#subscribe`、`ChatServiceImpl#chat/#resume` | 两个订阅者分别是谁？客户端断开后后台为什么继续？只凑到一个订阅会怎样？ | 能区分"已接通/订阅不足/终态/尝试发出" |
| S7 RAG | `RagService#searchAnswers/#authoritative/#updateAnswer/#updateQuestion`、`QaIndexSynchronizer#synchronize/#synchronizeGroup`、`QaSyncJobMapper` | 改答案与改问题为什么结果不同？`info == null` 时旧索引会怎样？refresh 由谁调用？ | 能说出"新答案不等于同步完成" |

### 1.6 想再验证一层：P2 实验卡怎么接

P2 的卡不是本章的前置。它们只在你"想把某个源码结论再推进一步"时使用；每张卡都标明了新增证据与仍未验证的点，不能拿相邻层的证据补缺项。

| 本章题 | 可接的 P2 卡 | 新增什么证据 | 不能证明什么 |
|---|---|---|---|
| Q1 | L1-1（身份覆盖、标记工具） | 测试源码断言了 `markOrderReadyForCreate` 的"尚未"文本与服务端覆盖 userId/价格 | 不覆盖工单归属、不覆盖 `TicketTool` 的 403 |
| Q2 | L0-2（改期覆盖）、L2-R2（真 Redis 改期） | 真 ZSet 下同一 member 改期后旧 score 不再被领取 | 不补证订单锁、事务提交早晚、业务锚点 |
| Q3 | 无专用卡；L2-M1/M2 是 RAG 事务与持锁，不是统计缓存 | 只补"外层事务模板/持锁"证据 | 不能补统计缓存的 500ms 回填窗口 |
| Q4 | L0-1（确认/消费分叉）、L2-R1（真 Redis 顺序调用） | 真 Lua 下编号绑定与一次性消费 | 不证明并发确认、HTTP `/r`、事件送达 |
| Q5 | 无专用卡；读 C4 §10.1 的逐测试阅读卡更直接 | 测试源码对窗口/工具结果的断言 | 不能补真实 MySQL 历史与并发写入 |
| Q6 | 无专用卡；读 C1 §九的测试阅读 | 测试对事件/锁的断言 | 不能证明真实客户端断开与容器行为 |
| Q7 | L0-D（三分支）、L1-2（mock 回查）、L3-2（真实检索冒烟）；L2-M1/M2 只覆盖写入事务/持锁，**不补检索** | mock 断言 → 真实检索返回；写入事务/锁另列 | 任一层都不能单独证明“整次索引重建完成”；批量回滚/锁测试不能补真实回查 |
| Q8 | L0-2（队列）、L2-R2（ZSet）；无"状态缓存陈旧"专用卡 | 队列改期语义 | 不能补 `order:status` 陈旧与 afterCommit 失败 |

**使用边界**：P2 的 L2/L3 卡要求一次性实例、独立端口、身份核对与清理；本章不要求你执行它们。若执行，按 P2 的停止条件与收尾步骤做，不要把本章的时间线当成运行脚本。

---

## 二、综合题怎么用：先写预测，再看答案

### 2.1 统一的答题格式

每道题都按下面四行写，再展开：

```text
结论：谁会成功/失败，返回什么
第一失败点：哪个类、哪个方法、哪一行判断（或"没有失败，走到哪一步"）
状态变化：MySQL / Redis / 内存 / Mongo 各自留下什么
可见层：同步 HTTP / SSE 事件 / 工具回执 / 模型文本，哪一层能看到
```

答案的折叠块会逐项对照。**先写四行，再展开**；直接看答案会失去检验作用。

### 2.2 四层可见性：不要把"尝试发出"写成"客户端收到"

| 层 | 典型产生位置 | 源码能确认什么 | 不能确认什么 |
|---|---|---|---|
| 同步 HTTP | `@RequirePermission` 端点、控制器、`GlobalExceptionHandler` | 状态码与 `Result{code,message}` 的映射（在异常正常冒泡到控制器时） | 后台线程里的异常不会自动变成同步 HTTP 状态 |
| SSE 事件 | `sink.tryEmitNext(...)`、`AgentStreams#guard` | 事件类型与 payload 的构造；调用的是 `tryEmit*` | 客户端是否收到、HTTP 是否已提交、容器是否按 SSE 写出 |
| 工具回执 | `executeTool` 的返回值、工具方法自己的 `catch` | 字符串原文；是否被 `OrderAgent/SupportAgent#executeTool` 包上 `Tool execution failed:` | 模型是否转述、辅助存储是否成功、是否发出 message 事件 |
| 模型文本/客户端实收 | 模型生成与服务端发送之后 | 没有任何源码保证 | 文案、是否发送、是否送达 |

**规则**：题目问"客户端看到什么"时，先问"你说的是哪一层"。源码能确认的通常只有工具回执原文和同步端点的映射；SSE 事件只能写"尝试发出"。

### 2.3 不从测试名推断运行结果

测试名是线索，不是结论。本章会引用测试，但只把它当作"测试源码断言了什么"的证据，并明确它 mock 了什么、没有覆盖什么。**本章没有运行任何测试**；不要因为某个测试名叫 `...Always...` 或 `...Cannot...` 就写成"运行通过"。

---

## 三、八道综合题

难度标记是"读者完成这道题所需的推理步数"，不是分值；星数越多，需要的推理与跨模块步骤越多。

### Q1 一张乘客令牌与一张别人的订单（身份归属，★★☆☆☆）

**虚构初态**

| 对象 | 值 |
|---|---|
| 乘客林小雨 | userId `910001`，令牌 `T-A`（USER，有效） |
| 乘客顾小舟 | userId `910009`，是订单 `5005` 的乘客（本题不给他令牌） |
| 司机周师傅 | userId `920002`，是订单 `5005` 的接单司机（本题不给他令牌） |
| 订单 `5005` | 属于顾小舟，状态 `DRIVER_ACCEPTED(20)`，`driver_id=920002` |

令牌 `T-A` 的 `sessionVersion` 与 `credentialStamp` 都匹配，MySQL `sys_user` 中账号 `status=1`、`is_deleted=0`。IP 限流与聊天限流均放行。以下四个请求在 10:00:00 各自独立发生，不是并发：

- **(a)** **同时不带** `Authorization` 和 `X-Auth-Token`（不是空字符串头，也不是过期令牌），调用 `GET /order/my/ongoing`。
- **(b)** 带 `T-A`，调用 `POST /order/driver/accept`，请求体 `{"orderId":"5005"}`。
- **(c)** 带 `T-A`，调用 `GET /order/5005`。
- **(d)** 林小雨在一个已通过鉴权与归属检查的对话里，模型调用 `createTicket` 工具：`orderId="5005"`、`title="费用争议"`、`content="..."`、`type=2`、`priority=1`。假设模型确实调用了工具，参数也能被 `SupportAgent#executeTool` 解析。

**让你预测**

1. 四个请求分别在**哪一层**被拒绝（拦截器 / 权限切面 / 业务归属 / 工具内部 catch）？
2. 每一层对外可见的是什么：HTTP 状态与 `Result.code`、工具回执文本，还是 SSE 事件？
3. 哪些请求在拒绝前已经写了 MySQL/Redis？工具调用是否可能"用参数冒充顾小舟"？
4. 如果只看 `AuthTokenInterceptor`，能解释其中任何一个拒绝吗？

**可观察信号**：HTTP 响应体、`Result.code`、工具回执字符串、对话历史中的 `ToolResponseMessage`、`sys_ticket` 行数、Redis `ticket:no:20261120` 是否被 INCR。**下一步查哪里**：`AuthTokenInterceptor#preHandle`、`PermissionAspect#checkPermission`、`RideOrderServiceImpl#getOrderDetail/#assertCanViewOrder`、`TicketTool#createTicket`、`SupportAgent#executeTool`、`TicketServiceImpl#submitTicket`。

<details>
<summary>参考答案与推导（Q1）</summary>

**先给一张总表**

| 请求 | 第一失败点 | 可见结果 | 未写哪些业务数据（范围限定） |
|---|---|---|---|
| (a) 两个头都缺 | `PermissionAspect#checkPermission`：`UserTokenContext.getNullable()==null` | HTTP 401，`Result{code:401,"请先登录"}` | 未写工单/订单/编号；拦截器正常无身份路径不写业务数据，控制器方法未进入 |
| (b) USER 令牌接单 | `PermissionAspect`：注解要求 `DRIVER`，当前角色 `USER` | HTTP 403，`Result{code:403,"无权访问该资源"}` | 未写工单/订单/编号；没进 `driverAcceptOrder`，没有 tryGrab |
| (c) 看别人的订单 | `RideOrderServiceImpl#assertCanViewOrder`：USER 分支比较 `order.userId` | HTTP 403，`Result{code:403,"无权限查看该订单"}` | 未写工单/订单/编号；只读订单，未写 |
| (d) 工具关联别人的订单 | `TicketServiceImpl#submitTicket` 的归属校验，抛 `BusinessException(403,"只能关联自己的订单")`；被 `SupportAgent#executeTool` catch | 工具回执 `"Tool execution failed: 只能关联自己的订单"`；对话可能继续 | 未写工单行/编号（`generateTicketId` 未执行、Redis 编号未 INCR、`ticketMapper.insert` 未执行）；**工具辅助存储可能写、对话消息可能在后续轮次保存** |

**“否”只限定在工单/订单/编号这些业务行**：不要把 (a)～(d) 的“未写业务行”扩展成“所有存储都没写”。工具辅助副本（`recordCalling`）与对话消息（`saveNewMessages`）是另外两份数据，是否写入取决于后续执行。

**逐项推导**

**(a) 401 不是拦截器产生的，但拦截器也不是“永远只读”。** `AuthTokenInterceptor#extractToken` 先看 `Authorization: Bearer`，再看 `X-Auth-Token`；两个头都没有时返回 null，`preHandle` 既不设置 ThreadLocal，也不抛异常，走 `return true`。正常“未识别身份”路径下，控制器方法被下一层 `PermissionAspect` 拦住：`getNullable()==null` → `UnauthorizedException("请先登录")` → HTTP 401、`Result{401,"请先登录"}`；控制器方法体没有执行，因此没有业务读写。

但要把这一支与另外三种情况分开：① 令牌存在但**过期**：拦截器调用 `tokenService.deleteToken`，这是 Redis 写，不是“只读”；② 令牌存在但**无效/不存在**：只记日志，不设置上下文；③ `TokenService`/Redis/MySQL **依赖异常**：异常从拦截器冒出，可能不是 401，也不会走到切面。**“始终放行”只描述正常无身份/无效身份不在这里拒绝，不覆盖依赖异常和过期删除。**

**(b) 角色不匹配是切面层的 403。** 拦截器用 `T-A` 调 `getToken`，把 USER 身份写入 ThreadLocal；`PermissionAspect` 读到角色 `USER`，而 `@RequirePermission({"DRIVER"})` 要求 `DRIVER`，`hasPermission=false` → `ForbiddenException("无权访问该资源")` → HTTP 403。这里同样没有进入 `driverAcceptOrder`，所以没有订单锁、没有 Lua、没有 DB 条件更新。**不要把它和 (c) 的业务 403 混为一层**：一个是"角色不够"，一个是"角色够但数据不属于你"。

**(c) 业务归属 403 发生在服务内部。** `RideOrderController#detail` 只有 `@RequirePermission`（不指定角色），所以切面放行；`getOrderDetail(orderId)` 先 `getRequiredOrder` 读订单，再 `assertCanViewOrder`：角色是 USER，`order.getUserId()=910009` 与当前 `910001` 不等 → `BusinessException(403,"无权限查看该订单")`。`handleBusiness` 直接透传异常自带 code，所以 HTTP 403、`Result.code=403`。拒绝发生在 `orderRouteService.getByTraceId` 之前，因此没有读 Mongo 轨迹；全程只读。**这条路径证明"注解只检查角色，归属必须由业务再查"**。

**(d) 工具身份来自 `ToolContext`，不来自模型参数。** `SupportAgent#handleToolCalls` 构造 `ToolContext`，其中 `USER_ID` 来自 `invoke(..., userId, ...)`，源头是 `ChatController` 从 `UserTokenContext` 取出的登录身份；模型看到的只有工具 schema 与历史，`userId` 不在工具参数里。`TicketTool#createTicket` 固定 `userType=1`，把 `orderId=5005` 解析成 Long 后交给 `submitTicket`；`submitTicket` 在 `ticketMapper.insert` 之前做归属校验：当前用户 `910001` 与订单 `5005` 的 `userId=910009` 不等 → `BusinessException(403,"只能关联自己的订单")`。异常被 `SupportAgent#executeTool` 的 `catch (Exception e)` 捕获，返回 `"Tool execution failed: " + e.getMessage()`；随后 `recordCalling` **尝试**把回执写入辅助存储，`ToolResponseMessage` 加入内存历史，下一轮模型可能转述。**能确认的是回执字符串；不能确认模型一定转述、SSE 一定送达、HTTP 一定 200**——这些属于第二、四层。

**关于“写没写”**：`generateTicketId` 在归属校验之后才调用，所以 Redis 的 `ticket:no:` 不会被 INCR；`ticketMapper.insert` 也在其后，所以 `sys_ticket` 没有新行。工具辅助存储是另一份数据（`chat:tool:v2:{chatId}:{callId}` 与表 `sys_chat_tool_repsonse`，源码表名拼写如此）：`SupportAgent#executeTool` 返回失败文本后，`recordCalling` **尝试**写辅助副本；写成功与否取决于 MySQL insert/Redis set，不能拿来证明“工单创建失败但什么都没写”。对话消息 `sys_messages` 由 `SupportAgent#runLoopStep` 的 `saveNewMessages` 在后续轮次保存，也不在工具拒绝点；它可能在本轮晚些时候写入。

**为什么只看拦截器解释不了**：对“两个头都缺”和“令牌无效”，拦截器正常路径确实只是“不设置上下文并放行”，它不抛 401、不检查角色、不检查归属；但过期令牌会删令牌，依赖异常会直接失败，不能概括成“拦截器只读、始终放行”。四个拒绝分别落在切面（a、b）、Service（c）、Service 内部再由 Agent catch（d）。

**关键源码**：`interceptor/AuthTokenInterceptor.java#preHandle`；`aspect/PermissionAspect.java#checkPermission`；`exception/GlobalExceptionHandler.java#handleUnauthorized/#handleForbidden/#handleBusiness`；`service/impl/RideOrderServiceImpl.java#getOrderDetail/#assertCanViewOrder`；`service/impl/TicketServiceImpl.java#submitTicket`；`agentbase/tool/TicketTool.java#createTicket`；`agents/SupportAgent.java#handleToolCalls/#executeTool`；`controller/ChatController.java#chat`；`service/base/ChatRequestRunner.java#run`。

**对应新教材**：A2 §1～§2（身份/角色/归属分层）、§6.2（异常与可见性）；S1 §2.3/§3（工单归属与工具身份）；P1 §1.4（401/403 分支）、§3.6～§3.7（工单入口与工具失败）、§4.2（反向定位表）。

**重要前提与仍未验证**：令牌有效、账号状态正常、`@RequirePermission` 的 AOP 代理生效、`GlobalExceptionHandler` 正常映射；模型确实调用了 `createTicket`；工具回执是否被 `recordCalling` 成功保存、模型是否转述、SSE 是否送达，均未实测。

</details>

---

### Q2 09:59:59 的接单与 10:00:03 的领取（订单并发，★★★☆☆）

**虚构初态**

- 订单 `5004`：`order_status=10 CREATED`、`driver_id=null`、`is_deleted=0`、`updateTime=09:45:00.000`。
- ZSet `delay:order:timeout`：`member="5004"`、`score=10:00:00.000`。**Redis 的 score 是 epoch 毫秒数值**；本文表中的 `10:00:00.000` 只是把这个毫秒值按业务时区展示成时刻，不是 Redis 里存了日期字符串。
- 司机周师傅 `920002`、邵师傅 `920003` 都在线，心跳在 60 秒内持续有效；两人的 `driver:active` 都是 `__IDLE__`。
- `order:lock:5004` 空闲；`order:status:5004=10`（TTL 5 分钟）。
- `OrderTimeoutJob#pollTimeoutOrders` 每 5 秒 `fixedDelay` 轮询一次；`ORDER_LOCK_WAIT_SECONDS=3` 秒。

**执行交错（主线：接单先取得订单锁）**

```text
09:59:59.000  周师傅 POST /order/driver/accept {orderId:"5004"}，
              事务代理开启事务，进入 driverAcceptOrder 并由 withOrderLock 取得 order:lock:5004
09:59:59.050  tryGrab：司机行锁 → 查周师傅活跃单（无）→ driver:active=__IDLE__ →
              order:status=10 → GRAB_ORDER_ATOMIC 返回 1，占位写成 5004
09:59:59.080  DB 条件更新 where order_status=10 and driver_id is null → 1 行；
              now=09:59:59.080，driverAcceptTime 也取这个值
09:59:59.080  schedule(5004, 10m) 在事务内被调用，先算 deadline：
              09:59:59.080 + 10m = 10:09:59.080（epoch 毫秒的展示）
09:59:59.100  事务提交；afterCommit 才执行 ZADD，把同一 member 的 score 改成
              10:09:59.080，覆盖旧值 10:00:00.000
10:00:03.000  超时轮询 claimDue：ZRANGEBYSCORE 0..10:00:03.000 LIMIT ...
```

**让你预测**

1. `10:00:03.000` 的 `claimDue` 会不会返回 `5004`？为什么？
2. 换一个前提：接单事务已提交，无其他状态变更。本次回调未将新 score 写入 Redis，并且核对旧 score `10:00:00.000` 仍在。`10:00:03.000` 领取到 `5004` 后，超时线程会做什么？它会取消订单吗？
3. **第 3 问是独立时间线，不是把接单先锁和超时先锁拼在一起。** 请分别推演：
   - **A（接单先持锁）**：周师傅在 `09:59:59.000` 已取得 `order:lock:5004`，DB 更新 `10:00:00.050`、提交 `10:00:00.120`；超时在 `10:00:00.070` 才领取到 `5004`。超时线程能不能先拿到锁？拿到后读到什么？会不会取消？
   - **B（超时先持锁）**：周师傅的请求尚未取得订单锁（例如还在进入 `withOrderLock` 之前），超时在 `10:00:00.070` 先取得锁，重读 DB 仍是 `10` 且 `updateTime` 已超过 15 分钟，成功执行 `10→90` 并提交；周师傅随后取得锁，`tryGrab` 看到缓存 `90` 或仍是旧 `10`，分别会怎样？
   - **C（等待失败）**：超时线程持锁超过 3 秒，周师傅在 `withOrderLock` 等待超时，接口返回什么？有没有执行 `tryGrab`？
4. 如果周师傅的 DB 条件更新影响 0 行，接口返回什么？和 `tryGrab` 返回 `ORDER_NOT_GRABBABLE` 的 409 有什么不同？**0 行能说明 DB 仍然是 10 吗？**
5. 请把“事务内 `schedule` 算 deadline”“提交后 ZADD 实际执行”“旧待办已被 `claimDue` 领走”“同一 member 成功改期”“回调未执行”“回调执行但 Redis 响应丢失”分开描述。哪些状态能从异常/返回值推断，哪些不能？

**可观察信号**：HTTP 状态与 `Result.data`、`sys_ride_order` 行、`order:status:5004`、`delay:order:timeout` 的 score 与成员、`driver:active:920002`、超时日志。**下一步查哪里**：`OrderGrabService#tryGrab/#afterCommit/#rollbackGrab/#refreshOrderStatus`、`RedisScripts#GRAB_ORDER_ATOMIC/#CLAIM_DUE_ORDERS/#SYNC_ORDER_STATUS`、`RideOrderServiceImpl#driverAcceptOrder/#withOrderLock/#handleTimeoutOrderUnderLock/#handleAcceptTimeout/#handleArriveTimeout`、`OrderDelayQueue#schedule/#claimDue`、`OrderTimeoutJob#pollTimeoutOrders`。

<details>
<summary>参考答案与推导（Q2）</summary>

**1. 10:00:03 的 claimDue 不会返回 5004。** `schedule` 在事务内调用时先算 `deadline = System.currentTimeMillis() + delay`；本例是 `10:09:59.080` 的 epoch 毫秒。它把 ZADD 注册到 `afterCommit`，所以只有事务提交后（`.100`）才真正执行 `ZADD delay:order:timeout 10:09:59.080 5004`。`claimDue` 在 `10:00:03.000` 执行 `ZRANGEBYSCORE 0 10:00:03.000 LIMIT ...`；`5004` 的 score 是 `10:09:59.080`，不在窗口内，不会被读出，更不会被 `ZREM`。**前提**是 afterCommit 确实执行并且 ZADD 成功；下一问处理失败/未执行。

**2. 旧 score 仍在时，超时线程会重读 DB，但不会盲目取消。** **前提是接单已提交且没有其他推进**。`claimDue` 的 Lua 先 `ZRANGEBYSCORE` 再 `ZREM`；`5004` 被领走后，`OrderTimeoutJob` 调 `handleTimeoutOrder` → `withOrderLock` → `handleTimeoutOrderUnderLock`，在订单锁内重新 `selectOne` 读 `sys_ride_order`：

- 读到 `20 DRIVER_ACCEPTED`：走 `handleArriveTimeout`，锚点 `driverAcceptTime=09:59:59.080`；到 `10:00:03.000` 约 4 秒，不到 10 分钟 → `reschedule`，重新入队（有副作用，不是无操作）。
以下是改变第 2 问前提、让锁内实际读到其他状态时的独立分支。

- 读到 `10 CREATED`：锚点 `updateTime`（或预约时间）；本例 `09:45:00.000` 已超过 15 分钟 → `systemCancel` 条件更新 `10→90`；只有影响行数 > 0 才算取消成功。
- 读到 `50 FINISHED_WAIT_PAY`：锚点 `finishTime`，达到 30 分钟阈值且取消条件更新成功才取消；本例不适用。
- 读到 `40 IN_TRIP`：`handleTimeoutOrderUnderLock` 的 switch 没有这个分支，`default` 只记日志，本次不取消、不释放。

**“旧待办被领取”不等于“订单一定被取消”**：取消由锁内重读的状态与业务锚点决定。

**3. 三条独立时间线**

**A（接单先持锁，超时后到）**：周师傅在 `09:59:59.000` 取得 `order:lock:5004`，锁随事务持有到 `.120` 提交（`withOrderLock` 在 afterCompletion 才释放）。超时 `10:00:00.070` 领取到 `5004` 后调用 `handleTimeoutOrder`，`withOrderLock` 最多等 3 秒；在 `.120` 之前拿不到锁，因此**不可能先进入 `handleTimeoutOrderUnderLock`**。假设 `10:00:00.120` 提交后的回调执行完，并在等待上限内成功释放锁，超时线程随后拿到锁并重读到 `20`。按本分支的 `driverAcceptTime=10:00:00.050` 推算，业务到期为 `10:10:00.050`，未到期则 `reschedule`，ZADD 成功后才重新入队。接单成功，订单不取消。**这一条是主线，不能把超时写成先胜出。**

**B（超时先持锁，接单尚未取得锁）**：这是另一套初态：周师傅请求尚未执行到 `withOrderLock`，超时在 `10:00:00.070` 先取得 `order:lock:5004`，重读 DB 为 `10`，`updateTime=09:45:00.000` 已超过 15 分钟 → `systemCancel` 条件更新 `10→90`，提交；`syncOrderStatus(90)` 与 `cancel` 注册在 afterCommit。周师傅随后进入 `withOrderLock`，在 3 秒内拿到锁，执行 `tryGrab`：

- 若 `order:status:5004` 已变成 `90`：`GRAB_ORDER_ATOMIC` 的第一个条件不成立 → `-1` → `ORDER_NOT_GRABBABLE` → 409 `"订单已被其他司机接走或已取消"`。
- 若另行核对缓存仍是 `10`（例如回调未执行，或 Redis 命令确认没有生效）：Lua 可能返回 1；DB 条件更新 `where order_status=10` 影响 0 行 → `rollbackGrab` + `refreshOrderStatus` + `return false` → HTTP 200、`Result.data=false`。**0 行不说明 DB 仍是 10**；真实行是 90，0 行还可能来自 20/40/50/90、driver 已变或逻辑删除。

**C（等待失败）**：超时线程持锁超过 3 秒，周师傅的 `withOrderLock` 抛 `BusinessException(409,"订单正在处理中，请稍后重试")`；**没有执行 `tryGrab`**，没有 DB 更新，也没有写 Lua 占位。另一种“接单已拿到锁但 `tryGrab` 失败”是：`-1`/`-2` 直接 409；`tryGrab` 成功但 DB 0 行才是 200+false。四种结果不要合并。

**4. DB 0 行不是 409，也不能反推 DB 状态。** `driverAcceptOrder` 条件更新影响 0 行时执行 `rollbackGrab(driverId, orderId)`、`refreshOrderStatus(orderId)`、`return false`；控制器 `Result.ok(false)`，HTTP 200、`Result.data=false`。0 行只说明“没有一行同时满足 orderId、未删除、status=10、driver_id is null”，不能说明行仍是 10：它可能已是 20/40/50/90，driver 已非空，或被逻辑删除。**“事务已确认回滚”是另一个前提**：回滚后 DB 保持接单前状态（本例 10），但 0 行本身不能证明回滚；0 行也可能来自别的原因。`tryGrab` 返回 `ORDER_NOT_GRABBABLE` 时抛 409 `"订单已被其他司机接走或已取消"`；`DRIVER_BUSY` 抛 409 `"司机已有未结束订单，无法接单"`；锁等待超时也是 409 `"订单正在处理中，请稍后重试"`。409 与 200+false 是不同层，不能互相替代。

**5. 六个状态要分开**

| 说法 | 实际含义 / 需要的前提 | 不能推出 |
|---|---|---|
| 事务内 `schedule` 算 deadline | 在调用 `schedule` 的时刻用 `System.currentTimeMillis()` 算，尚未写 Redis | Redis 已经有新 score |
| 提交后回调发起 ZADD | afterCommit 调用了 Redis 写操作 | 命令已生效。若旧 member 已被领取，成功的 ZADD 会重新加入 |
| 旧待办已被 `claimDue` 领走 | Lua 已把 member 从 ZSet `ZREM` | 订单一定被取消；还要锁内重读 |
| 同一 member 成功改期 | ZADD 用同一 member 写入了新 score | 旧待办一定不存在；若旧 member 已被领走，ZADD 会重新加入 |
| 回调未执行 | 事务回滚、未注册、或提交后回调未运行 | Redis 一定没写；未执行与“执行了但失败”不同 |
| 回调执行但 Redis 响应丢失 | 命令可能已应用，也可能未应用 | 旧 score 一定还在/一定不在；只能标记未知 |

**补充：同司机抢两单**。`tryGrab` 先对 `sys_user` 司机行 `FOR UPDATE`，再查该司机未结束的活跃单，把 `driver:active` 修成 `__IDLE__` 或真实订单 id。第二个接单事务阻塞在司机行锁；第一个提交后，第二个看到活跃单 → `driver:active=第一单`，Lua 第二个条件不满足 → `-2` → `DRIVER_BUSY` 409。**缓存丢失不会绕过这条**，因为占位在每次 `tryGrab` 都会按 DB 修复。

**关键源码**：`service/base/OrderGrabService.java#tryGrab/#afterCommit/#rollbackGrab/#refreshOrderStatus`；`util/RedisScripts.java#GRAB_ORDER_ATOMIC/#CLAIM_DUE_ORDERS/#SYNC_ORDER_STATUS`；`service/impl/RideOrderServiceImpl.java#driverAcceptOrder/#withOrderLock`；`service/base/OrderDelayQueue.java#schedule/#claimDue`；`service/base/OrderTimeoutJob.java#pollTimeoutOrders`；`RideOrderServiceImpl#handleTimeoutOrderUnderLock/#handleAcceptTimeout/#handleArriveTimeout/#reschedule/#systemCancel`。

**对应新教材**：O2 §2.3（tryGrab 顺序）、§2.4（条件更新与提交后动作）、§3（两类并发）、§4（失败矩阵）；O4 §2.1～§2.3（ZSet 与领取/重排）、§3.2（轮询处理）、§4.3（领取后故障）；P1 §2.9（超时与接单竞争）。

**重要前提与仍未验证**：`ORDER_LOCK_WAIT_SECONDS=3`；轮询 tick、提交时刻与超时/接单的相对先后是虚构的；真实 Redisson 锁等待、MySQL 行锁、`FOR UPDATE` 计划、Redis 执行结果均未实测。**A、B、C 是互斥初态，不能拼成一条时间线。**

</details>

---

### Q3 10:00:00 的仪表盘（事务与缓存窗口，★★★☆☆）

**虚构初态**

- 2026-11-20 09:59:39.900，客服苏晴打开仪表盘，`GET /ticket/admin/statistics` 返回 `pendingAssignCount=7`，结果写入 Redis `ticket:statistics:20261120`，TTL 为 `20 + random(0..10)` 秒；本例取 20 秒，到 `09:59:59.900` 过期。
- 09:59:59.900，该 key 因 TTL 到点被 Redis 删除。
- 09:59:59.905，读者 R1 的 `getTicketStatistics` 未命中缓存，抢到重建锁（`ticket:statistics:lock:20261120`，TTL 5 秒），双检仍未命中。
- 09:59:59.910，R1 的 `queryTicketStatisticsFromDb` 开始执行。`pendingAssignCount` 这一条 `COUNT` 读到 7；其余三条 `COUNT` 在它之后分别执行，可能看到不同已提交版本。
- 林小雨的 `POST /ticket/submit` 在 09:59:59.910 之前已经进入事务；**10:00:00.000 提交**，新增一张 `PENDING_ASSIGN(0)` 工单（真实待分配数应为 8）。题设给的是“已进入事务，于 10:00:00.000 提交”，不是“请求和提交在同一瞬间”。
- 10:00:00.003，`evictTicketStatistics` 的 afterCommit 第一次删除 `ticket:statistics:20261120`（key 此刻不存在，删除是 no-op），并调用 `DelayedCacheEvictor#evictAfter(key, 500ms)`；标称在 `10:00:00.503` 执行。**题设假设延迟任务在 10:00:00.503 真的运行且删除成功**，另设失败分支。
- R1 可能在 10:00:00.200 或 10:00:00.600 把“7”写回缓存；下面分别算。

**让你预测**

1. 分别推演三个交错：
   - **A**：R1 在 `10:00:00.200` 写回 7；`10:00:00.503` 延迟删除成功；在 `10:00:00.400` 和 `10:00:00.600` 各读一次，各看到什么？
   - **B**：延迟删除在 `10:00:00.503` 先成功；R1 在 `10:00:00.600` 才写回 7；`10:00:00.700` 读一次，看到什么？什么时候恢复？
   - **C**：R1 在 `10:00:00.200` 写回 7；`10:00:00.503` 的延迟删除失败；`10:00:00.600` 读一次，看到什么？
2. 这个系统里“统计的真值”在哪一层？Redis 里的 JSON 是什么地位？四条 `COUNT` 是同一个事务快照吗？
3. 第一次删除抛 Redis 异常、第二次删除也失败，会留下什么？第二次删除被 `RejectedExecutionException` 拒绝呢？事务“已确认回滚”与“提交结果不明”分别怎样？
4. `ticket:statistics` 的 TTL 单次设置范围与上界各是多少？它能不能从提交时间推出“系统最迟多久收敛”？
5. 如果 R1 的 DB 查询发生在 `10:00:00.000` 之后，而不是之前，结论会变吗？“数据库读到 8”“下一次请求回填 8”“界面显示 8”分别需要什么前提？

**可观察信号**：`GET /ticket/admin/statistics` 的 `pendingAssignCount`、Redis `ticket:statistics:20261120` 的值与 TTL、`ticket:statistics:lock:20261120` 是否存在、`sys_ticket` 的行数、应用日志中的“工单统计缓存失效失败/延迟删除缓存失败/重建等待超时”。**下一步查哪里**：`TicketServiceImpl#getTicketStatistics/#getCachedTicketStatistics/#cacheTicketStatistics/#evictTicketStatistics/#queryTicketStatisticsFromDb`、`DelayedCacheEvictor#evictAfter`、`RedisLock#tryLock`、`RedisKeyConstants#ticketStatisticsKey`。

<details>
<summary>参考答案与推导（Q3）</summary>

**1. 三个交错**

- **A：先回填 7，再删除，再读。** `10:00:00.200` R1 写回 7；`10:00:00.400` 的读命中缓存，看到 7；`10:00:00.503` 延迟删除成功；`10:00:00.600` 的读未命中，`getTicketStatistics` 抢锁/查 DB，`pendingAssignCount` 读到 8，回填并返回 8。**“用户下一次请求看到 8”需要这次请求发生在删除之后**；`10:00:00.400` 的请求仍然看到 7。
- **B：先删除，再回填，再读。** `10:00:00.503` 删除成功，当时 key 可能已不存在；R1 在 `10:00:00.600` 才写回 7；`10:00:00.700` 的读命中 7。这个 7 的 TTL 从这个写入时刻重新算 20～30 秒，所以到约 `10:00:20.600`～`10:00:30.600` 才自然过期；下一次工单写入会触发新的双删，否则只能等 TTL。
- **C：先回填，删除失败，再读。** R1 在 `10:00:00.200` 写回 7；`10:00:00.503` 删除失败（只记 warn）；`10:00:00.600` 的读仍命中 7，直到 TTL 或下一次写入失效。**“延迟删除被调度”不等于“删除成功”**。

**为什么会有这个窗口**：`getTicketStatistics` 的读路径是“未命中 → 抢锁 → 双检 → 查 DB → 写缓存”，没有版本号，也不检查“我读 DB 的快照是否早于某次提交”。R1 在 `09:59:59.910` 读 DB 时写事务尚未提交，快照是 7；它写回缓存的时刻可以晚于提交与第一次删除。`DelayedCacheEvictor#evictAfter` 把第二次删除安排在调度约 500ms 后，实际执行可能更晚；任务实际运行、Redis 删除成功、以及读线程恰好在删除前/后写回，都是独立条件。

**2. 真值在 MySQL，不在 Redis。** `queryTicketStatisticsFromDb` 依次执行四条独立的 `selectCount`：待分配、处理中、今日创建、今日完成。它们不是同一个事务快照；在 `READ_COMMITTED` 下，每条 SQL 看到它执行时已提交的数据。Redis 的 `TicketDataVO` JSON 只是这四条查询结果的派生快照，缓存命中时直接返回，不再查 DB。**不要把“Redis 有值”当成“数据库已提交”，也不要把四条 COUNT 说成同一快照。**

**3. 删除失败、回滚与未知**

- 第一次删除抛异常：`evictTicketStatistics` 的 `try/catch` 只记 `warn`，然后**仍然**调用 `delayedCacheEvictor.evictAfter(cacheKey, 500ms)`。第一次失败不等于没有第二次。
- 第二次删除失败：`DelayedCacheEvictor` 的延迟任务只记 `warn`。若确认命令未生效且旧值已回填，该值可能保留至 TTL 到期或后续有效失效。若 `executor.schedule` 抛 `RejectedExecutionException`，只记“延迟删除任务未被接收”，根本没有第二次删除。
- **已确认回滚**：`submitTicket` 的事务回滚，afterCommit 不会执行；DB 没有新工单，旧缓存 7 与 DB 7 一致（前提是缓存本身来自已提交快照）。**提交成功但删缓存失败**是另一种残留。
- **提交结果不明**：例如提交后在返回响应时超时/断连。`afterCommit` 是否执行、删除是否发出、key 是否已删，都不能由“调用方没收到成功响应”推断；可能已提交并已删，也可能已提交但未删，也可能回滚。必须把“已确认回滚”和“未知”分开。
- 若 Redis `delete` 命令“发出去了但响应丢失”：`stringRedisTemplate.delete` 的返回值没有被检查，调用方只知道抛没抛异常；key 可能已删，也可能没删。这是未知态。

**4. 单次 TTL 是 20～30 秒，上界 30 秒，但不是收敛上界。** `TICKET_STATS_CACHE_BASE_SECONDS=20`、`TICKET_STATS_CACHE_JITTER_SECONDS=10`，`cacheTicketStatistics` 用 `20 + random.nextInt(11)` 秒；单次写入的范围是 20～30 秒（含 30），上界是 30 秒。**新写会重新开始 TTL**，所以不能用“提交时刻 + 30 秒”推出系统一定在某个时刻收敛；旧值可能在第二次删除之后写回，再活最多 30 秒；还可能被下一次写入再次刷新。

**5. 查询在提交之后**

`queryTicketStatisticsFromDb` 在 `10:00:00.000` 之后才执行，`pendingAssignCount` 读到的是 8；它写回 8，下一次读命中 8。三个说法要分开：

- **数据库读到 8**：需要这条 `COUNT` 在提交之后执行，且没有其他未提交/并发变更影响它。
- **下一次请求回填 8**：需要缓存未命中（或旧值已被删除/过期），这次请求进入持锁重建分支、实际读到 8，且写缓存成功。降级直查只返回数据库结果。
- **界面显示 8**：还需要这次请求发生在删除/过期之后，并且响应真的到达浏览器。

所以“旧值回填”的前提是：**R1 的 DB 快照早于提交，且写缓存晚于第一次/第二次删除（或删除失败）**。这两个时刻要分开写，不能只说“缓存过期了所以读到旧值”。

**补充：重建锁与降级**。R1 抢到 `ticket:statistics:lock:20261120`，`RedisLock#tryLock` 用 UUID 令牌 + 5 秒 TTL；拿不到锁的读线程 `sleep 60ms` 重试 3 次，仍未命中就降级直查 DB（`queryTicketStatisticsFromDb`），不会再写缓存。`getCachedTicketStatistics` 的 Redis `get` 没有 catch；Redis 读异常会直接冒泡，不是“自动降级”。这些分支与旧值回填是不同问题。

**关键源码**：`service/impl/TicketServiceImpl.java#getTicketStatistics`（缓存命中/重建/等待/降级）、`#evictTicketStatistics`（afterCommit + 第一次删除 + 500ms 调度）、`#cacheTicketStatistics`（TTL 20+random(0..10)）、`#queryTicketStatisticsFromDb`（四条独立 COUNT）；`service/base/DelayedCacheEvictor.java#evictAfter`；`service/base/RedisLock.java#tryLock/#unlock`；`constant/RedisKeyConstants.java#ticketStatisticsKey/#ticketStatisticsLockKey`。

**对应新教材**：S2 §5（统计缓存的命中、重建、降级与双删）、§5.5（500ms 双删）、§6.3（旧值回填时间线）；S3 §5.1～§5.3（双删的两条时间线与残留窗口）；F3 §3.5、§5.6（提交后回调）；P1 §2.8（守卫前后存储状态）。

**重要前提与仍未验证**：TTL 20 秒、R1 的调度延迟、延迟任务在 `.503` 真正运行且删除成功，都是本题假设；真实 Redis 命令响应丢失、`DelayedCacheEvictor` 执行器状态、MySQL `COUNT` 的隔离级别与实际读时点未实测。“20～30 秒”是单次 TTL 常量，不是生产收敛保证。

</details>

---

### Q4 同一张确认卡的正常恢复与独立反事实（确认令牌，★★★★☆）

**虚构初态**

- `chat:info:7f9c24e8-8b3d-4c1e-9a6f-2d5e8b1c4a70`：`quoteId=QUOTE-7F3A`、`quoteCreatedAt=T0=13:00:00.000`、`ReadyforConfirm=true`、`break=yes`、`pendingToolCall=call-9`；尚无 `confirmedQuote/ReadyForCreate`。
- `OrderAgent` 的历史以一条未闭合的 `notifyUser` 工具调用结束（这是合法的“末尾未闭合”）；`/r` 的 `chat:execution` 锁空闲，`canStartChat` 与 `assertOwned` 在题设下都通过。
- 时间单位毫秒；`CONFIRM`/`CONSUME` 的年龄条件是**严格大于** `900000`。

**主线：正常恢复（不是重放）**

```text
13:00:10.000  用户点“确认”：/r {confirmationId:QUOTE-7F3A, confirmed:true}
              CONFIRM：quoteId 匹配、年龄 10000 <= 900000、break=yes
              → 写 break=resuming、confirmationAccepted=yes、
                confirmedQuote=QUOTE-7F3A、ReadyForCreate=QUOTE-7F3A
              OrderAgent#resume：getPersisted 读 MySQL 历史 →
                补 notifyUser 的 ToolResponseMessage → save →
                HDEL break/pendingToolCall/confirmationAccepted →
                runLoopStep（此时 break 已不在）
13:00:20.000  模型调用 createOrder：
              ReadyForCreate 存在 → 组装 DTO →
              consume（年龄 20000 <= 900000，三字段相等）
              → HDEL ReadyForCreate/confirmedQuote → 创建订单 5004 → lockChat
13:00:30.000  模型再次调用 createOrder（重复调用，同一主线）
13:00:40.000  用户在另一个标签页再次 POST /r，同一 confirmationId、confirmed:true
```

**独立反事实**（每题只选一个，不要和主线拼成一条）

- **反事实 A（恢复在清字段前失败）**：13:00:10 的 CONFIRM 已写入许可；`OrderAgent#resume` 在 `getPersisted`/`save` 阶段失败，尚未执行 HDEL。历史形状是**非法的**：A2 有 `c-1,c-2`，T2 只回了 `c-1`，后面又出现 A3；或 T2 重复 `c-1`；或 T2 出现在 A2 之前。问：`break`、`pendingToolCall`、`confirmedQuote/ReadyForCreate` 留下什么？13:00:40 同决定 `/r` 与相反决定分别怎样？
- **反事实 B（年龄边界）**：模型在确认后卡住，直到 `13:15:00.000` 或 `13:15:00.001` 才调用 `createOrder`，`ReadyForCreate/confirmedQuote` 仍在。问：`consume` 分别返回什么？`isConfirmed` 还是 true 吗？
- **反事实 C（确认年龄边界）**：13:00:10 的 `/r` 改在 `T0+900000ms` 或 `T0+900001ms` 到达；`break=yes`、quote 匹配。问：CONFIRM 分别返回什么？900001ms 且 `break=resuming`、同决定时会不会因为进入 `resuming` 分支而返回 1？
- **反事实 D（报价被改写）**：13:00:05 用户重新询价，`OrderConfirmation#quoted` 删除了 `ReadyForCreate/confirmedQuote` 并写入新 `quoteId=QUOTE-NEW`。问：旧 `confirmationId=QUOTE-7F3A` 的 `/r`、旧 `createOrder` 各在哪一步失败？

**让你预测**

1. 13:00:30 的重复 `createOrder` 在哪一步被挡住？它有没有调用 Lua？**13:00:30 的 30000ms 是否超龄？** 同一条主线的 13:00:40 `/r` 会在哪一步失败？它的 `break` 还是 `resuming` 吗？
2. 反事实 B 中 `T0+900000ms` 与 `T0+900001ms` 的 `consume` 结果；失败时异常在哪一层被转成文本？`isConfirmed` 还是 true 吗？
3. 反事实 A 中恢复失败后，13:00:40 同决定 `/r` 与相反决定分别怎样？需要哪些前提（锁、`canStartChat`、归属、quote 匹配、字段未被改写）？
4. 反事实 C 中正好 900000ms 与 900001ms 的 CONFIRM 分支；为什么 900001ms 的同决定也可能返回 0？
5. 反事实 D 中旧 quote 的 `/r` 与 `createOrder` 各失败在哪一层？

**可观察信号**：`chat:info` 字段、`sys_ride_order` 行数、`ToolResponseMessage` 内容、SSE `confirm`/`error` 事件（尝试）、工具回执字符串。**下一步查哪里**：`OrderConfirmation#quoted/#begin/#confirm/#consume`、`OrderTool#createOrder`、`OrderAgent#resume/#executeTool`、`ChatServiceImpl#resume`、`ChatRequestRunner#run`、`AgentStreams#guard`。

<details>
<summary>参考答案与推导（Q4）</summary>

**主线：13:00:10 正常恢复后，`break` 已经被删除。** `OrderAgent#resume` 的顺序是：`break=="resuming"` 检查 → `messageMemory.getPersisted`（MySQL + `MessageWindow.tail`）→ 找 `pendingToolCall` → 构建 `notifyUser` 的 `ToolResponseMessage` → `answered` 检查（只扫描**当前读取窗口**里的 `ToolResponseMessage`）→ 未答则 `messageMemory.save` → `HDEL break/pendingToolCall/confirmationAccepted` → `runLoopStep`。所以 13:00:10 的 `resume` 在进入模型循环前就删了 `break`/`pendingToolCall`/`confirmationAccepted`；`confirmedQuote/ReadyForCreate` 仍保留，供 13:00:20 的 `createOrder` 使用。

**1. 13:00:30 的重复 `createOrder` 在 `ReadyForCreate` 检查处早退，没有调用 Lua。** 13:00:20 的 `consume` 成功后执行 `HDEL ReadyForCreate confirmedQuote`；13:00:30 的 `createOrder` 第一行是 `if (redisGetObj(chatInfoKey, "ReadyForCreate") == null) return "订单非可创建态";`，直接返回字符串，不进入 `consume`，没有 Lua、没有异常、没有 `Tool execution failed:` 前缀。**13:00:30 只有 30000ms，远小于 900000ms；它不是超龄失败**。这个回执是正常工具回执，`handleToolCalls` 接下来尝试记录辅助副本，在正常分支组装内存回执并进入下一次模型流。

**同一条主线的 13:00:40 `/r`**：`OrderAgent#resume` 在 13:00:10 已经 `HDEL break/pendingToolCall/confirmationAccepted`，所以 `break` 不是 `resuming`，而是 nil。**前提是上一轮的 `doFinally` 已经把 `chat:execution` 锁释放成功**；若 `unlockAsync` 未成功，13:00:40 的请求会先在 `ChatRequestRunner#run` 的 `acquired=false` 分支得到“会话正在处理中”，不会走到 `confirm`。在锁已释放、`canStartChat` 与 `assertOwned` 通过的前提下，`CONFIRM` 先比 `quoteId`（仍匹配 `QUOTE-7F3A`）、再看 `yes` 年龄（40000ms <= 900000），最后 `state ~= 'yes'`（nil ≠ yes）→ 返回 0 → `confirm` 抛 409 `"确认已失效或已被使用，请重新核对订单"`；后台 `AgentStreams#guard` 尝试 error 事件。**这不是“`resuming` 同决定重放”，而是正常恢复已清 `break` 后的一次无效 `/r`**。主线中订单已创建，但不因这次 `/r` 改变。

**2. 反事实 B：`consume` 的年龄边界**

`consume` 的第一行是：

```lua
if tonumber(ARGV[1]) - tonumber(redis.call('HGET', KEYS[1], 'quoteCreatedAt') or '0') > 900000 then return 0 end
```

- `T0+900000ms`：`900000 > 900000` 为假，继续检查三字段；若 `confirmedQuote==quote==ReadyForCreate`，HDEL 两字段并返回 1，随后 `rideOrderService.createOrder` 创建订单。
- `T0+900001ms`：`900001 > 900000` 为真，返回 0；`consume` 抛 `BusinessException(409,"尚未取得有效的用户确认，或本次确认已使用")`。异常发生在 `OrderAgent#executeTool` 的 `try` 内，被 catch 成 `Tool execution failed: 尚未取得有效的用户确认，或本次确认已使用`；**不是** `ChatServiceImpl#resume` 的 CONFIRM 异常，不经过 `AgentStreams#guard`。
- 失败时 `consume` 不删字段，所以 `isConfirmed` 仍为 true（`quoteId == confirmedQuote == ReadyForCreate`）；下一次 `createOrder` 会在同一年龄检查上再次失败，直到重新报价。失败在 `rideOrderService.createOrder` 之前，没有订单行。

**3. 反事实 A：恢复在清字段前失败**

触发 `getPersisted` 异常的合法反例：A2 的 `tool_calls=[c-1,c-2]`，T2 只回了 `c-1`，**后面又出现 A3**；`MessageWindow` 遍历到 A3 时 `pending={c-2}` 非空，抛 `IllegalStateException("会话中的工具记录不完整，请新建对话或联系管理员修复历史")`。重复 `c-1` 或 T2 在 A2 之前（孤儿应答）也会抛。**“末尾未闭合”本身不抛**（Q5 会单独证明），所以不要把 A 反事实写成“历史以未闭合 notifyUser 结束”。

失败点在 `HDEL` 之前，因此：

- `break` 仍是 `resuming`；
- `pendingToolCall` 仍是 `call-9`；
- `confirmationAccepted` 仍是 `yes`；
- `confirmedQuote/ReadyForCreate` 仍是 `QUOTE-7F3A`（接受分支）；
- 异常由 `AgentStreams#guard` 捕获，尝试发 error 事件；本轮没有进入模型，不会创建订单。

13:00:40 同决定 `/r` 的前提：`chat:execution` 锁已随上一轮终止而释放（`doFinally` 尝试 `unlockAsync`，成功与否未验证）、`canStartChat` 通过（prompt 被设为“确认”）、`assertOwned` 通过、`quoteId` 仍匹配 `QUOTE-7F3A`、年龄 40000ms 未超、`break` 未被其他操作改写。满足后：

- `CONFIRM` 先验 quote，再验年龄（`40000 <= 900000`），然后 `state == 'resuming'`；`confirmationAccepted` 仍是 `yes`，与本次 `yes` 相同 → **返回 1，不执行任何写命令**；`confirm` 不抛异常。
- `OrderAgent#resume` 再次 `getPersisted`；如果历史仍非法，再次抛同样的 `IllegalStateException`，仍然到不了 HDEL；`break` 保持 `resuming`。**这是“能重试”而不是“一定成功”**。只有历史被修复（例如删除非法记录/重建对话）后，才可能继续。

相反决定 `confirmed:false`：`state == 'resuming'`，但 `confirmationAccepted` 是 `yes`，与 `"no"` 不等 → `CONFIRM` 返回 0 → `confirm` 抛 409 `"确认已失效或已被使用，请重新核对订单"`；后台 `ChatServiceImpl#resume` 被 `AgentStreams#guard` 捕获，尝试 error 事件。**拒绝不会清掉已发出的 `confirmedQuote/ReadyForCreate`**；它们仍可能在后续被 `createOrder` 使用，除非重新报价/消费。若 `break` 已被删除，`state ~= 'yes'` 也会返回 0。

**清字段前的另一个具体失败例子**：`messageMemory.save` 先 `mysqlMemory.append`（MySQL 成功），再写 Redis/heap；若 Redis/heap 抛异常，`save` 抛出，`HDEL` 未执行。此时 `break/pendingToolCall/confirmationAccepted` 仍在；下一次同决定 `/r`：CONFIRM 返回 1；`resume` 的 `getPersisted` 从 MySQL 读到已保存的 `ToolResponseMessage`，`answered` 为 true，不再重复 save；随后 HDEL 并继续。若 MySQL append 就失败，`answered` 为 false，下一次会再 save。**`answered` 只看当前读取窗口，不是全历史幂等。**

**4. 反事实 C：CONFIRM 的 900000/900001 边界**

`CONFIRM` 的顺序是：先比 `quoteId`；若是 `yes`，再比年龄；然后才看 `state`：

```lua
local state = redis.call('HGET', KEYS[1], 'break')
if redis.call('HGET', KEYS[1], 'quoteId') ~= ARGV[1] then return 0 end
if ARGV[2] == 'yes' and tonumber(ARGV[3]) - tonumber(redis.call('HGET', KEYS[1], 'quoteCreatedAt') or '0') > 900000 then return 0 end
if state == 'resuming' then
    if redis.call('HGET', KEYS[1], 'confirmationAccepted') == ARGV[2] then return 1 end
    return 0
end
if state ~= 'yes' then return 0 end
-- 以下为写许可分支的节选
```

- `T0+900000ms`、`break=yes`：`900000 > 900000` 为假，继续；`state ~= 'resuming'`，`state == 'yes'`，于是写 `break=resuming`、`confirmationAccepted=yes`、`confirmedQuote/ReadyForCreate=QUOTE-7F3A`，返回 1。
- `T0+900001ms`、`break=yes`：年龄检查先返回 0；不会进入写分支。
- `T0+900001ms`、`break=resuming`、同决定 `yes`：**年龄检查仍然先执行**，`900001 > 900000` → 返回 0；**不会因为进入 `resuming` 分支而返回 1**。只有年龄未超（≤900000）且 `confirmationAccepted` 与本次决定相同，`resuming` 分支才返回 1。
- 相反决定 `no`：`CONFIRM` 不查年龄；若 `state=resuming` 且 `confirmationAccepted=yes`，比较 `"no"` 不等 → 0；若 `state=yes`，`no` 分支会删 `confirmedQuote/ReadyForCreate` 并返回 1。

**5. 反事实 D：报价被改写**

`OrderConfirmation#quoted` 先 `HDEL ReadyForCreate/confirmedQuote`，再写入新 `quoteId=QUOTE-NEW` 和新的 `quoteCreatedAt`，并设 `ReadyforConfirm=true`。它不会保留旧 `break` 的语义（旧 `break` 可能仍是 `yes`/`resuming`）。因此：

- 旧 `confirmationId=QUOTE-7F3A` 的 `/r`：`CONFIRM` 第一行 `quoteId != QUOTE-7F3A` → 0 → 409；不会进入年龄/状态分支。
- 旧 `createOrder`：`ReadyForCreate` 已被 `quoted` 删除 → 早退 `"订单非可创建态"`；即使字段还在，`consume` 的三字段比较也会因 `quoteId=QUOTE-NEW` 不等而返回 0。
- 旧 `CONSUME`：年龄可能未超，但 `quoteId != confirmedQuote/ReadyForCreate` → 0 → 工具文本。

**出口对照**

| 出口 | 触发点 | 是否调用 Lua | 异常/返回值 | 谁来接 |
|---|---|---|---|---|
| 早退 | `ReadyForCreate == null` | 否 | 字符串 `订单非可创建态` | 正常工具回执 |
| CONSUME 失败 | 年龄 >900000，或三字段不相等 | 是，CONSUME 返回 0 | `BusinessException(409)` | `OrderAgent#executeTool` → `Tool execution failed: ...` |
| CONFIRM 失败（`/r`） | quote 不匹配/`yes` 超龄/`resuming` 相反决定/`break` 非 yes | 是，CONFIRM 返回 0 | `BusinessException(409)` | `ChatServiceImpl#resume` 后台 → `AgentStreams#guard` → error 事件尝试 |
| 恢复异常 | `resume` 的 `getPersisted`/`save`/`HDEL` 之前 | 不涉及 CONSUME | `IllegalStateException` 等 | `AgentStreams#guard` → error 事件尝试；字段可能保留 |

**确认/拒绝两条恢复路径**：`ChatServiceImpl#resume` 在 `canStartChat`、`assertOwned` 之后调用 `confirm`，再把 feedback 交给 `OrderAgent#resume`。接受（yes）写 `confirmedQuote/ReadyForCreate`；拒绝（no）删除两者，但同样会补 `notifyUser` 的 `ToolResponseMessage`、HDEL 暂停字段并进入模型循环。**拒绝也有恢复补应答，不是“直接结束”。**

**关键源码**：`service/base/OrderConfirmation.java#quoted/#begin/#confirm/#consume`；`agentbase/tool/OrderTool.java#createOrder`；`agents/OrderAgent.java#resume/#executeTool`；`service/impl/ChatServiceImpl.java#resume`；`agents/AgentStreams.java#guard`；`service/base/ChatRequestRunner.java#run`；`agentbase/memory/MessageWindow.java#tail`。

**对应新教材**：C3 §4.2（CONFIRM 逐行）、§4.4（resuming 窗口）、§4.5（900000 与两个时钟）、§4.6（400/409 与流内呈现）、§5.3（resume 顺序）、§5.4（工具应答配对/失败窗口）、§6.2～§6.3（createOrder 前置与 CONSUME）；C4 §5.3（窗口边界）；P1 §2.4、§2.8、§4.2。

**重要前提与仍未验证**：`quoteCreatedAt` 存在且为数字；`break/pendingToolCall/confirmationAccepted` 的初值如题设；`confirm`/`consume` 脚本正常返回（不是运行时错误）；锁 `unlockAsync` 成功、`canStartChat`/归属通过、字段未被并发改写，都是反事实 A 重试的前提；SSE 是否送达、HTTP 是否已提交未实测。**不要用 `SecurityRepairRegressionTest#modelCannotGrantItselfCreationPermission` 证明本问**：该测试只断言 `markOrderReadyForCreate` 的“尚未”文本与无 Redis 交互，不测 `createOrder` 重放、`consume` 或 Lua。

</details>

---

### Q5 缺一条工具应答的历史（记忆批次配对，★★★★☆）

**虚构初态**

角色：林小雨（U1/U2/U3）、模型（A1/A3 的文本与 A2 的 `tool_calls`）、`OrderAgent`（执行工具、组装 `ToolResponseMessage` 并保存历史）。MySQL `sys_messages` 中某个 chatId 的消息，按 `id` 升序解析后如下（每条是一行 JSON 的解析结果）：

| 下标 | 消息 |
|---|---|
| 0 | `UserMessage`：U1 "帮我叫车" |
| 1 | `AssistantMessage`：A1 文本"好的" |
| 2 | `UserMessage`：U2 "从云栖小镇到萧山机场，快车" |
| 3 | `AssistantMessage`：A2，`tool_calls=[c-1 saveNewOrderParam, c-2 isNewOrderReady]`，无文本 |
| 4 | `ToolResponseMessage`：T2，`responses=[c-1 成功, c-2 成功]` |
| 5 | `AssistantMessage`：A3 文本"报价 30.60 元，请确认" |
| 6 | `UserMessage`：U3 "先等等" |

实际运行中，`OrderAgent` 调用 `messageMemory.get(userId, chatId, 100)`，`MessageMemory#get` 内部会用 `MessageWindow.tail(messages, 100)` 取窗口。为了手算，本题绕过这个 100 条参数，直接对上面的完整列表调用 `tail(history, limit)`；两者是同一个方法。本题只读源码，不需要数据库。

**让你预测**

1. `MessageWindow.tail(history, 3)` 返回哪些消息？返回条数是 3 吗？请写出起点下标的计算过程。
2. 把下标 4 的 T2 改成只含 `c-1`，历史在下标 4 结束（没有 A3/U3）：`tail(history, 2)` 返回什么？会不会抛异常？
3. 同样只含 `c-1`，但后面还有 A3 和 U3：`tail(history, 2)` 会怎样？异常文本是什么？
4. 若 T2 的 `responses` 是 `[c-1, c-1, c-2]`，或 T2 出现在 A2 之前，分别会怎样？
5. 若下标 2 的 U2 是一行损坏 JSON（`MessageParser#unparse` 抛异常），`unparseHistory` 会得到什么？`MessageWindow` 会抛异常吗？
6. 对 c-1、c-2 和（假设存在的）`notifyUser` 调用，`ToolResponseMemory#get` 各自可能返回什么？"内存回执、辅助副本、消息落库"分别指哪份数据？确认与拒绝两种恢复是否都会补 `notifyUser` 的应答？

**可观察信号**：`MessageWindow.tail` 的返回列表、`IllegalStateException("会话中的工具记录不完整，请新建对话或联系管理员修复历史")`、`chat:tool:v2:{chatId}:{callId}`、`sys_messages` 行、`ToolResponseMessage` 内容。**下一步查哪里**：`agentbase/memory/MessageWindow#tail`、`MessageParser#unparseHistory`、`MessageMemory#get/#getPersisted/#save`、`MysqlMemory#append`、`ToolResponseMemory#save/#get`、`OrderAgent#handleToolCalls/#resume`。

<details>
<summary>参考答案与推导（Q5）</summary>

**1. `tail(history, 3)` 返回下标 3～6 的 4 条消息，不是 3 条。** 逐条追踪：

```text
i=0 U1：pending 空，且不是 ToolResponse → boundary=0
i=1 A1：无 toolCalls → boundary=1
i=2 U2：→ boundary=2
i=3 A2：处理前 pending 空，且不是 ToolResponse → boundary=3；
        然后把 c-1,c-2 加入 pending
i=4 T2：pending 非空 → 不加 boundary；remove c-1,c-2 → pending 空
i=5 A3：→ boundary=5
i=6 U3：→ boundary=6

wanted = max(0, 7-3) = 4
boundaries = [0,1,2,3,5,6]
从前往后取 boundary <= 4：0→start=0，1→start=1，2→start=2，3→start=3，5>4 停止
tail = messages[3..6] = [A2, T2, A3, U3]，共 4 条
```

**关键**：A2 本身是 boundary，因为 boundary 的判定发生在"把它的 toolCalls 加入 pending"之前——此时 `pending` 还是空的。所以窗口从**批次的第一条 Assistant** 开始，而不是从它前面的用户消息开始。`limit` 是"目标条数"，不是"最多条数"：这里为了不把 A2/T2 拆开，返回了 4 条，比 `limit=3` 多 1 条。

**2. 只含 c-1、历史在下标 4 结束：允许，返回 [A2, T2] 2 条。** 遍历结束时 `pending={c-2}` 非空，但源码没有在循环后检查 `pending` 是否为空；注释明确"最后一个待人工确认的工具批次可以尚未结束，必须整体保留"。`wanted=max(0,5-2)=3`，boundaries=[0,1,2,3]，取 <=3 的最大 boundary=3 → `[A2,T2]`。**这个"允许未闭合"是通用的**：它不检查未闭合的是不是 `notifyUser`，只要它是最后一组且后面没有普通消息。

**3. 只含 c-1、后面还有 A3/U3：抛 `IllegalStateException`。** 遍历到 A3 时，`pending={c-2}` 非空，A3 是 `AssistantMessage`，进入 `if (!pending.isEmpty()) throw invalid()`；异常文本是 `"会话中的工具记录不完整，请新建对话或联系管理员修复历史"`。如果是 U3 先出现，走到 `else if (!pending.isEmpty()) throw invalid()`，同样抛。**原因**：一个未闭合的工具批次只能出现在历史末尾；中间插入普通消息或新的 assistant 消息，模型协议就不合法。

**4. 重复应答 / 无前置 assistant 的应答**

- `responses=[c-1, c-1, c-2]`：第一个 c-1 `pending.remove` 成功；第二个 c-1 `remove` 返回 false → 抛 `IllegalStateException`。**重复 id 不是"后一个覆盖前一个"**。
- T2 出现在 A2 之前（`pending` 为空）：`ToolResponseMessage` 不是 boundary；对 `c-1` 执行 `pending.remove` 返回 false → 抛。**没有前置 assistant 的工具应答不是"可忽略的孤儿"**。
- 未知 id（例如 `c-3`）：同样 `remove` 返回 false → 抛。

**5. 损坏 JSON 走 `MessageParser#unparseHistory`，可能被截断，不一定让 `MessageWindow` 抛。** `unparseHistory` 逐行 `unparse`：

```java
try { message = unparse(row); }
catch (RuntimeException e) { result.clear(); recovering = true; continue; }
if (message == null) { result.clear(); recovering = true; continue; }
if (recovering && !(message instanceof UserMessage)) continue;
recovering = false;
result.add(message);
```

下标 2 的 U2 损坏 → 之前已收集的 U1/A1 被 `clear()` 掉，`recovering=true`；下标 3 的 A2、4 的 T2、5 的 A3 都不是 `UserMessage`，被跳过；下标 6 的 U3 让 `recovering=false`，从 U3 开始重新收集。`unparseHistory` 返回 `[U3]`（或 U3 之后的完整轮次）。`MessageWindow.tail` 看到的是这个**被截断的合法历史**，不会因为原来的 A2/T2 缺失而抛异常。**这和"窗口配对错误"是两类损坏**：解析层可能静默截断，窗口层只在配对错误时抛。

**6. 三份数据要分开**

| 数据 | 位置 | 谁写 | c-1/c-2 | `notifyUser` |
|---|---|---|---|---|
| 内存回执 | `history` 里的 `ToolResponseMessage` | `OrderAgent#handleToolCalls` 把 `executeTool` 的返回值加入历史 | 有（完整文本） | 没有；`notifyUser` 在 `handleToolCalls` 里特判并 `return`，不调用 `executeTool` |
| 辅助副本（条件性） | `chat:tool:v2:{chatId}:{callId}`（Redis，24h）+ 表 `sys_chat_tool_repsonse`（源码拼写如此） | `ToolRepPointerTool#recordCalling` → `ToolResponseMemory#save` | **尝试写**；`recordCalling` 在 `executeTool` 之后调用，成功与否取决于 MySQL insert/Redis set | **没有**；同一批中被延迟的调用也没有 `recordCalling` |
| 消息落库（条件性） | `sys_messages` | `MessageMemory#save` → `MysqlMemory#append`（`@Transactional`） | 有，作为 `ToolResponseMessage` 的一行，但受 save 的 MySQL/cache 条件约束 | 用户**确认或拒绝**后由 `OrderAgent#resume` 补一条 `ToolResponseMessage`，`name` 硬编码为 `notifyUser` |

**“有”是条件性的，不是必然成功**：

- `ToolResponseMemory#save` 先 `mapper.insert`，再 `cache`。insert 失败时没有 MySQL 行、也没有 Redis key；insert 成功但 Redis 失败时 MySQL 行仍在，`get` 可以从 MySQL 回源。`recordCalling` 捕获异常只记 warn，所以工具回执在内存历史里，不代表辅助副本写成功。
- `MessageMemory#save` 先 `mysqlMemory.append`（MySQL），再 Redis/heap；MySQL append 抛异常时，本次不会继续写 Redis/heap。MySQL 明确回滚时没有新增行，提交结果未知时需核实。若 MySQL 已提交、随后缓存步骤抛异常，`save` 抛出时 MySQL 行仍可能已存在。`MysqlMemory#append` 是 `@Transactional`，但 `MessageParser#parse` 对 null/blank 会跳过该条——**“save 没抛”不等于“每条都落库”**。
- `notifyUser` 的应答由 `OrderAgent#resume` 构建；**接受与拒绝都会走 `resume`**（`ChatServiceImpl#resume` 先 `confirm` 再 `orderAgent.resume`），所以两种决定都会补这条 `ToolResponseMessage`，区别只是 `feedback` 文本与 `confirm` 是否写/删 `confirmedQuote/ReadyForCreate`。
- 同一批中 `notifyUser` 之后的工具调用不会执行：`handleToolCalls` 为它们生成“尚未执行：请等待用户确认后按需重新调用。”的 `ToolResponseMessage`，也**不调用** `recordCalling`；`notifyUser` 自己的应答留到恢复时补。
- `answered` 只扫描 `getPersisted` 返回的**当前窗口**，不是全历史；因此“窗口里没有”不等于“历史上从未保存”。

`getCalling` 对 c-1/c-2：先读 Redis key，未命中再按 `callId + chatId` 查 MySQL；`recordCalling` 成功时有值，失败/未执行时可能为 null。对 `notifyUser`：`recordCalling` 没有被调用，所以通常返回 null；`ToolRepPointerTool#getToolResponse` 返回 null 后，框架如何把 null 交给模型未实测。**`getCalling` 还先做 `chatManager.assertOwned`**，所以跨 chat 的 callId 即使知道也查不到（查询条件同时含 `chatId`）。

**补充：`MessageMemory#save` 的三层顺序**。`save` 先 `mysqlMemory.append`（MySQL），再 `redisMemory.appendIfVersionMatch`、`heapMemory.appendIfVersionMatch`；如果 Redis/heap 抛异常，会先 `heapMemory.clear(chatId)` 再抛。**"MySQL 已写、缓存失败"是可能的**，所以恢复必须用 `getPersisted` 直接读 MySQL；`answered` 只扫描当前窗口，不是全历史幂等。`MessageParser#parse` 对未知类型返回 null/blank 时，`MysqlMemory#append` 会跳过该条而不抛错——"save 没抛"不等于"每条都落库"。

**关键源码**：`agentbase/memory/MessageWindow.java#tail`；`MessageParser.java#unparseHistory`；`MessageMemory.java#get/#getPersisted/#save`；`MysqlMemory.java#append`；`ToolResponseMemory.java#save/#get`；`agentbase/tool/ToolRepPointerTool.java#recordCalling/#getToolResponse`；`agents/OrderAgent.java#handleToolCalls/#resume`。

**对应新教材**：C4 §5.2（坏记录恢复）、§5.3（`tail` 按批次边界扩展）、§5.4（`limit=100` 不等于全历史）、§7.1～§7.2（工具结果两份数据）、§7.3（`getCalling` 身份）、§8.4（历史损坏与工具配对）；C3 §5.3～§5.4（恢复补应答与失败窗口）。

**重要前提与仍未验证**：消息顺序按 `id` 升序；`unparse` 的异常类型如题设；`getCalling` 的 Redis/MySQL 读取结果、null 返回的框架行为、`recordCalling` 是否成功均未实测。不要用测试名代替窗口手算。

</details>

---

### Q6 第 2 秒关掉页面（SSE 断开，★★★★☆）

**虚构初态：先分清三种订阅起点**

- chatId `7f9c24e8-8b3d-4c1e-9a6f-2d5e8b1c4a70`，`chat:execution:{chatId}` 空闲。
- **S0（完全未订阅）**：控制器返回 `Flux` 后，外层（Spring 的 SSE 写出）从未订阅它。
- **S1（外层已订阅、只凑到 1 个订阅者）**：外层订阅已发生，`Flux.defer` 的 supplier 已执行并取得锁；但 `autoConnect(2)` 只看到内部 `events.subscribe(...)` 一个订阅者，另一个还没到。
- **S2（已接通）**：外层订阅 + 内部订阅凑齐 2 个，`autoConnect(2)` 连接上游，`doOnSubscribe` 启动后台，模型开始。
- 主场景取 **S2**：t=0s 客户端 A 发起 `POST /chat/v2/c/{chatId}`，请求体触发 `getEstRouteAndPrice`；t=1s 模型流返回一个工具调用，服务端工具监听器**尝试**发工具事件（前端可能显示为 `tool_start`）；工具本身耗时 8 秒；t=2s 客户端 A 关闭页面；t=3s 客户端 B 同账号、同 chatId 发起第二个 `/c`；t=9s 工具返回；t=10s 第二次模型流返回纯文本。
- `AgentStreams` 的单次模型流超时是 3 分钟；本轮没有整轮总超时。

**让你预测**

1. S2 下 t=2s 断开时，后台工具会停吗？`chat:execution` 锁会立刻释放吗？断开后的工具事件会怎样？
2. t=3s 的第二个请求，若异步获取锁的结果确实是 `false`，会得到什么？它有没有进入 `assertOwned`、分类或模型？如果 `acquisition.get(3s)` 超时或抛异常，结果有何不同？
3. t=9s 工具返回时，这一轮是否结束？随后发生什么？t=10s 纯文本、再次工具调用、`notifyUser`、模型流错误四条路径，分别在哪一步尝试 `complete`/`error`？
4. S0 与 S1 的区别是什么？S0 会不会取锁？S1 会不会启动后台、会不会执行内部 `doFinally`？各自能推断什么、不能推断什么？
5. 3 分钟是整轮上限，还是每次模型流的上限？工具执行和 `saveNewMessages` 是否各自有 3 分钟计时？超时后事件与锁怎样？

**可观察信号**：`chat:execution:{chatId}` 是否存在、后台线程日志、工具事件/`message` 事件、第二个请求的事件流、`sys_messages` 是否新增、模型调用次数。**下一步查哪里**：`ChatRequestRunner#run`、`AgentStreams#guard/#subscribe`、`ChatServiceImpl#chat/#resume`、`OrderAgent#runLoopStep/#handleToolCalls/#handleNotifyUser`、`Sinks.Many` 的 `tryEmit*` 返回值（源码未检查）。

<details>
<summary>参考答案与推导（Q6）</summary>

**1. S2 下断开不取消后台，也不立刻释放锁。** `ChatRequestRunner#run` 的关键结构（简化，省略 try/catch 细节）：

```java
return Flux.defer(() -> {
    Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
    var lock = redis.getLock("chat:execution:" + chatId);
    // tryLockAsync(0, -1) 后 get(3s)；失败返回单事件 error，异常返回 Flux.error
    chats.assertOwned(userId, chatId, !resume);
    Flux<AgentEvent> events = sink.asFlux()
        .doOnSubscribe(subscription -> executor.execute(() -> AgentStreams.guard(sink, () -> {
            if (resume) service.resume(...); else service.chat(...);
        })))
        .doFinally(signal -> lock.unlockAsync(owner))
        .publish().autoConnect(2);
    events.subscribe(ignored -> {}, ignored -> {});
    return events;
});
```

- `publish()` 把上游多播给两个订阅者；`autoConnect(2)` 在第二个订阅者到达时连接上游并触发 `doOnSubscribe`。
- `doFinally` 挂在上游，不是挂在某一个下游订阅者上。S2 下客户端 A 断开，取消的是 HTTP 订阅者；`publish()` 不会因为一个下游取消就断开上游，内部 `events.subscribe(...)` 仍在，后台继续，`doFinally` 不触发。
- 工具会继续跑，锁由后台任务持有；断开后的 `tryEmitNext` 对已断开的 HTTP 订阅者没有实际接收者，但内部订阅者仍在消费；`tryEmit*` 返回值未检查，**不能据此断言“事件送达”**。

**2. 第二个请求：`false` 才得到单事件流；超时/异常是另一条路。** `run` 每次重新执行 `tryLockAsync(0, -1)`，`waitTime=0` 表示“立即尝试”，`leaseTime=-1` 表示看门狗。随后 `acquisition.get(3, TimeUnit.SECONDS)` 等的是**异步获取结果**，不是把等待时间改成 3 秒：

- 获取结果 `false`：返回 `Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"))`；**没有**进入 `chats.assertOwned`、`ChatServiceImpl#chat`、分类或模型，也没有消耗确认状态。HTTP 状态取决于 SSE/响应是否已提交，源码不保证是 200 还是 409。
- `get(3s)` 超时：catch 里注册 `thenAccept(late -> { if (late) unlockAsync(owner); })`，并抛出 `TimeoutException`；外层 catch 返回 `Flux.error(timeout)`，不是单事件流。若获取在 3 秒后才成功，late 回调会尝试解锁，避免锁泄漏。
- `InterruptedException`：恢复中断标志并抛出，同样走 `Flux.error`；Redis/Redisson 异常也走 `Flux.error`。
- 获取结果确实为 false 时，服务端构造单事件流，实际送达取决于传输。等待获取结果超时则走 `Flux.error`。

**3. t=9s 工具返回不是终态。** `OrderAgent#handleToolCalls` 的正常路径是：`executeTool` → `toolRepPointerTool.recordCalling(...)` → 把 `ToolResponseMessage` 加入 `history` → **递归 `runLoopStep`**，发起下一次模型流。所以 t=9s 只是工具阶段结束，t=10s 的第二次模型流才是下一条路径：

- **纯文本、没有 toolCalls**：`runLoopStep` 的完成回调构造最终 assistant 文本，`saveNewMessages` → `sink.tryEmitComplete()`；这是成功终态。
- **再次返回 toolCalls**：继续 `handleToolCalls`，不会 `saveNewMessages`/`complete`；可能一直循环到 200 条阈值等限制。
- **`notifyUser`**：`handleToolCalls` 特判，先为同批后续调用写“尚未执行”的应答，再 `handleNotifyUser`：`begin` 写 `break=yes`、`saveNewMessages`、尝试发确认事件、`tryEmitComplete`；这是暂停终态，不是订单创建。
- **模型流错误/3 分钟超时**：`AgentStreams#subscribe` 的 failure 回调先 `guard(sink, () -> error.accept(failure))`，再 `sink.tryEmitError(failure)`；这是错误终态。`error` consumer 是否再发 `AgentEvent.error`、文案是什么，取决于 Agent 实现。
- **`saveNewMessages` 抛异常**：它发生在完成回调里，异常冒泡到 `subscribe` 的 failure 分支，走 `guard` + `tryEmitError`；不是“工具结束就成功”。

`tryEmitComplete`/`tryEmitError` 的返回值都没有检查：调用可能失败（sink 已终止/并发终止），所以“尝试 complete”不等于“sink 已进入终态”。只有 sink 真正终止，上游 `doFinally` 才执行 `lock.unlockAsync(owner)`；`unlockAsync` 是异步的，**调用不等于释放成功**，Redisson 客户端/网络异常时可能失败。工具返回本身没有 `doFinally`。

**4. S0 与 S1 不是一回事**

| 起点 | 是否取锁 | 是否连接上游/启动后台 | 内部 `doFinally` | 能推断什么 |
|---|---|---|---|---|
| S0 完全未订阅 | **否**；`Flux.defer` 的 supplier 根本没执行 | 否 | 不存在 | 没有锁、没有 sink、没有后台；不能把“客户端断开”当成“锁泄漏” |
| S1 外层已订阅、取得锁、只凑到 1 个订阅者 | **是**；锁在 supplier 里、连接前取得 | 否；`autoConnect(2)` 未凑齐，`doOnSubscribe` 未执行，后台不启动 | 否；上游未连接 | 可能出现“锁已持有、没有后台、没有内部 `doFinally`”的窗口；真实容器是否这样订阅未实测 |
| S2 已接通后断开 | 是 | 已连接；断开只取消 HTTP 订阅者 | 上游终态时才执行 | 后台继续、锁持有；事件是否送达未知 |

**S0 与 S1 的区分必须从“外层是否订阅、锁是否已取、是否已连接”开始**；只凭“客户端关闭页面”无法判断。S1 的“锁可能保留”是按源码语义推导的风险，不能写成生产必然；真实 Servlet/SSE 容器通常会在写响应时订阅，是否只凑到 1 个订阅者、客户端在订阅前断开时容器行为如何，未实测。

**5. 3 分钟是每次模型流的上限，不是整轮上限。** `AgentStreams#subscribe` 对每个 `source` 使用：

```java
source.takeUntilOther(Mono.delay(Duration.ofMinutes(3))
    .flatMap(ignored -> Mono.error(new TimeoutException("模型响应超时"))))
```

它约束“这一次模型订阅”从建立到结束的时间。每次后续模型流订阅会重新计时。工具执行和 `saveNewMessages` 没有各自的 3 分钟计时器，整轮也没有统一 deadline。超时后该模型流以 `TimeoutException` 结束，`subscribe` 的 failure 回调执行 `guard` + `tryEmitError`；sink 终止后 `doFinally` 尝试解锁。**不要把 3 分钟写成“整个请求最多 3 分钟”，也不要把工具/保存写成各自 3 分钟。**

**四个状态词的区别**

| 状态 | 含义 | 本例 |
|---|---|---|
| 已接通 | `autoConnect(2)` 凑齐两个订阅者，上游连接，`doOnSubscribe` 执行 | S2 的 t=0s |
| 订阅不足 | 只有 1 个订阅者，上游不连接，后台不启动，内部 `doFinally` 不执行 | S1 |
| 终态 | sink `tryEmitComplete`/`tryEmitError` 实际成功，上游终止，`doFinally` 执行 | t=10s 成功、notifyUser 暂停或错误 |
| 尝试发出 | `tryEmitNext`/`tryEmitComplete`/`tryEmitError` 被调用，但返回值和实际送达未检查 | 所有事件 |

**关键源码**：`service/base/ChatRequestRunner.java#run`；`agents/AgentStreams.java#guard/#subscribe`；`service/impl/ChatServiceImpl.java#chat/#resume/#canStartChat`；`agentbase/chatinfo/ChatManager.java#assertOwned/#lockChat`；`agents/OrderAgent.java#runLoopStep/#handleToolCalls/#handleNotifyUser`；`domain/dto/AgentEvent.java`。

**对应新教材**：C1 §2.2（unicast sink、`autoConnect(2)`、`doFinally`）、§3.2～§3.3（订阅/后台阶段）、§5.2～§5.4（并发、断开、只出现内部订阅）、§5.5（五个“结束”）、§6.2（事件不等于 HTTP 状态）、§7.1（单轮执行锁）、§7.5（超时层级）；C2 §6（工具循环）；P1 §2.4、§4.2。

**重要前提与仍未验证**：客户端断开发生在 S2 已接通之后；容器确实取消 HTTP 订阅者；内部订阅者未被取消；`unlockAsync` 的异步结果未检查；S0/S1 的容器订阅行为、Redisson 解锁结果、`tryEmit*` 返回值均未实测。

</details>

---

### Q7 13:56 的新答案（RAG 索引落后，★★★★☆）

**虚构初态**

- `sys_qa_info`：`groupId=301`，`answer=v1`（“司机接单超过 5 分钟后取消收违约金……”）。
- `sys_qa_es`：`elasticId=7001`、`groupId=301`、`question=q1`（“乘客取消订单违约金怎么算”）。
- ES 索引 `qa_knowledge_base`：文档 `_id=7001`，`groupId=301`、`question=q1`、`answer=v1` 的副本，已 refresh 可见。
- 13:55:00.000，管理员调用 `POST /rag/admin/qa/update-answer`，把 `sys_qa_info.answer` 改成 v2；同一事务内 `enqueue(301)`；13:55:01.000 提交。
- `QaIndexSynchronizer#synchronize` 每 5 秒 `fixedDelay` 执行，`claimBatch` 用 `FOR UPDATE SKIP LOCKED` 取任务；**题目不假设它在 13:55:06 一定领取/完成**。
- 13:56:00.000，林小雨问 q1。

**先分清三条独立时间线**

- **T-A（旧文档仍可见，重建尚未删旧/尚未 refresh）**：13:56 查询仍能召回 `_id=7001` 的旧文档；本次权威查询读到 `sys_qa_info.answer=v2`。
- **T-B（旧已删、新已写且 refresh 可见）**：13:56 查询召回的是新文档 `_id=7001`（同 id 重写）或同一 group 的新文档；本次权威查询读到 v2。
- **T-C（旧已删、新尚未可见/未被召回）**：13:56 查询没有该 group 的候选，或候选均未通过；结果可能是空或只有其他 group。
- **T-A 与 T-B 不能在同一次查询里同时用来解释“旧候选”和“已换新”**；先选一条时间线，再谈答案。

**让你预测**

1. 如果 13:56 的工具回执“标准回答”是 v2，这能证明 ES 重建已经完成吗？请分别给出 T-A 与 T-B 下“返回 v2”的前提；哪些条件二者都需要？
2. 如果管理员改的不是答案，而是把 `sys_qa_es.question` 从 q1 改成 q2（`update-question`），13:56 检索 q1 时，旧候选为什么可能被保留、也可能被剔除？关键取决于本次 `authoritative` 实际读到的新/旧映射文本吗？
3. 如果管理员在 13:55 删除了 `groupId=301`（`deleteQA`），13:56 旧 ES 文档可能还在；为什么结果可能是空，也可能是旧答案？删除事务的提交时点与本次映射/信息查询的读时点如何影响结论？
4. “写入/入队事务提交”“同步处理调用”“显式 refresh”“同步任务删行提交”“本次查询命中”各需要什么条件？哪一个不等于另一个？
5. 如果 13:56 返回空列表，你能直接判断是 bug 吗？应该先查哪几层？`defer` 的 30 秒从什么时候算、什么会重置它？

**可观察信号**：工具回执的“标准回答”、`sys_qa_info.answer`、`sys_qa_es.question`、ES 文档的 `question/answer/groupId`、`sys_qa_sync_job` 的 `next_attempt_at/version`、ES `_refresh` 后的可见性、`RagTool` 的空结果文案。**下一步查哪里**：`RagService#updateAnswer/#updateQuestion/#deleteQA/#searchAnswers/#authoritative`、`QaIndexSynchronizer#synchronize/#synchronizeGroup`、`QaSyncJobMapper#enqueue/#claimBatch/#defer`、`RagTool#searchKnowledgeBase`。

<details>
<summary>参考答案与推导（Q7）</summary>

**1. v2 不证明重建完成；“旧候选 + 本次回查”与“新候选 + 本次回查”是两条独立路径。** `authoritative` 对通过校验的候选执行 `doc.setAnswer(info.getAnswer())`；答案是**本次回查 `sys_qa_info` 读到的行**，不是 ES 文档里的 `answer` 副本。

- **T-A（旧候选返回 v2）** 需要：① 旧 ES 文档 `7001` 在本次查询仍可见（同步器尚未删旧，或删除尚未 refresh 可见）；② 旧文档至少被向量或 BM25 一路召回，进入 `WINDOW_SIZE=100` 窗口；③ RRF 后按 `groupId` 组内去重进入前五；④ `authoritative` 五条件通过：`sys_qa_es` 映射存在、`mapping.question == doc.question`（q1==q1）、`mapping.groupId == doc.groupId`（301）、`sys_qa_info` 存在、文档 id 可解析为 Long；⑤ 本次 `sys_qa_info` 查询实际读到 13:55:01 提交的 v2 行。
- **T-B（新候选返回 v2）** 需要：① 新文档已经写入；② refresh 后可见（或恰好被本次查询看到）；③ 新文档被召回、进入前五；④ 映射 question/groupId 与新文档一致；⑤ 本次回查读到 v2。
- **二者都需要**：本次权威查询读到 v2；候选被召回并进入前五；映射/信息通过校验。**“返回 v2”只证明本次回查读到 v2 与候选通过，不证明旧文档已删、新文档已写或 refresh 成功。** T-A 下根本不需要“换新可见”。

**2. 改问题（q1→q2）：旧候选是否保留，取决于本次 `authoritative` 读到的映射文本。** `updateQuestion` 只更新 `sys_qa_es.question`（映射表），不改 `sys_qa_info`；同一事务 `enqueue(301)`。13:56 检索 q1 时：

- 若本次 `authoritative` 读到的 `mapping.question=q2`，旧文档 `doc.question=q1` → `Objects.equals` 不成立 → 旧候选被 `continue` 剔除。
- 若本次 `authoritative` 的映射查询实际读到旧文本 q1（例如该查询的事务开始/读取早于提交，或更新尚未提交），则 `mapping.question == doc.question` 仍成立，旧候选可以通过；答案仍是 `sys_qa_info.answer`（本例未改答案）。
- 若新文档 q2 已写且可见，查询 q1 是否召回它取决于词面/向量相似度；即使召回，`mapping.question=q2 == doc.question=q2` 可以通过，返回 info 的答案。
- **整次空结果**要求所有候选都没通过（映射缺失、question 不等、info 缺失、id 不可解析等）；不能因为“改了问题”就断言必空。**关键不是“我改过”，而是“这一次查询实际读到的映射是哪一版”。**

**3. 删除 group：空或旧答案取决于本次映射/信息查询的读时点。** `deleteQA` 在同一事务里删除 `sys_qa_es` 映射、在“该 group 已无问题映射”时删除 `sys_qa_info`，并对受影响的 group `enqueue`；同步器之后才 `deleteByQuery(groupId=301)` 删 ES 旧文档。13:56 查询时：

- **若本次 `authoritative` 的映射查询实际观察到了已提交的删除**：按 ES 文档 id 查不到 `sys_qa_es` 行，`mappings` 为空或缺少该 id；`groups` 可能为空 → `return List.of()`；即使还有其他映射，候选也会因 `mapping == null` 被跳过。结果可能是空。
- **若本次查询实际读到旧映射/旧 `sys_qa_info`**（例如该查询的事务开始/读取早于删除提交，或删除尚未提交）：它仍可能看到旧映射和旧信息，旧 ES 文档也可能仍可见 → 旧候选通过，返回旧 answer。**“删库行”与“删 ES 文档”不是同一时刻；不能把 deleteQA 的调用当成查询一定看不到旧行。**
- 空结果还可能来自：旧文档已删但新文档不可见、候选未被召回、组内去重没进前五、其他候选也失败。只有**所有候选都没通过**才是整次空。

**4. 五个条件不是一回事**

| 说法 | 需要的条件 | 不等于 |
|---|---|---|
| 写入/入队事务提交 | `updateAnswer/updateQuestion/deleteQA` 的事务提交成功；`enqueue` 与业务写入同事务 | 任务被领取、索引已重建 |
| 同步处理调用 | `claimBatch` 领取到任务；`synchronizeGroup` 被调用 | 旧文档删除成功、新文档写入成功 |
| 显式 refresh | `synchronizeGroup` 末尾 `es.indices().refresh(...)` 被调用；源码未检查 refresh 响应中的分片失败，调用是否抛异常和分片结果应分别判断 | 所有分片成功、所有副本可见 |
| 同步任务删行提交 | `jobs.deleteById(groupId)` 执行且事务提交 | 文档一定可搜、本次查询一定命中；`info==null` 时也会尝试删任务 |
| 本次查询命中 | 查询词进入向量/BM25 的 100 窗口、RRF 排名靠前、组内去重进前五、`authoritative` 五条件通过、本次 MySQL 回查读到目标行 | 整次重建完成、答案一定是某一版本 |

`QaIndexSynchronizer#synchronizeGroup` 的顺序是：确认/创建索引 → `deleteByQuery(groupId)` → 检查 `timedOut()`/`failures()` → 读 `sys_qa_info`；**`info == null` 直接 return，不执行 refresh**；否则逐 question Embedding/index，最后调用 `refresh`（返回值未检查）。调用方随后 `jobs.deleteById(groupId)`：即使 `info == null` 导致没有 refresh，也会尝试删任务，最终删行取决于执行及提交结果。**“refresh 被调用”不等于“分片成功”；“任务删行”不等于“文档可搜”。**

`fixedDelay=5000` **不是**“5 秒内完成”的上界；批次最多 5 个任务、每个 group 可能多次 Embedding/ES 调用。任务失败时 `defer` 执行 `UPDATE ... next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND)`，30 秒从**数据库执行这条 SQL 的时刻**算；后续同一 group 的 `enqueue` 会把 `next_attempt_at` 重置为 `CURRENT_TIMESTAMP`，所以“上次失败后 30 秒”不是固定等待。`defer` 自身失败或事务回滚时，任务可能保持原时间/原行。

**5. 空列表不能直接判 bug。** 先按三层查：

- **召回层**：ES 里有没有候选？是否 refresh 可见？查询词与文档 question 是否匹配（分词/相似度）？是否进入 100 窗口？
- **融合层**：候选是否在 RRF 后进入组内去重的前五？是否被同 group 的其他问题挤掉？
- **权威层**：`sys_qa_es` 映射是否存在、question/groupId 是否与 doc 一致、`sys_qa_info` 是否存在、本次回查读到的是哪个版本？

只有这三层都排除了“无候选/被剔除”，才谈得上实现 bug。`RagTool` 的空结果文案是正常业务分支，不是异常。

**补充：答案-only 与 question-only 的差别**。`updateAnswer` 只改 `sys_qa_info.answer`，旧文档的 `question/groupId` 仍与映射一致，五条件可以通过，答案由回查覆盖；`updateQuestion` 改的是映射表的 `question`，旧文档的 `question` 不变，是否失败取决于本次读到的映射文本。**两条分支不能合并成“索引没同步好”。**

**P2 对照的边界**：L2-M1/M2 是外层事务模板/持锁测试，能补“写入/任务行”的事务边界，**不能补真实 ES 检索/回查**；L0-D 是源码推演，L1-2 是 mock 回查，L3-2 才是隔离环境下的真实检索冒烟。任一层都不能单独证明“整次重建完成”。

**关键源码**：`agentbase/rag/RagService.java#updateAnswer`（`@Transactional` + `sys_qa_info.answer` + `enqueue`）、`#updateQuestion`（`@Transactional` + `FOR UPDATE` + `sys_qa_es.question` + `enqueue`）、`#deleteQA`、`#searchAnswers`（Embedding/双路/RRF/组内去重前五）、`#authoritative`（五条件 + `doc.setAnswer`）；`agentbase/rag/QaIndexSynchronizer.java#synchronize/#synchronizeGroup`；`mapper/QaSyncJobMapper.java#enqueue/#claimBatch/#defer`；`agentbase/tool/RagTool.java#searchKnowledgeBase`。

**对应新教材**：R1 §3.6（`authoritative` 五条件与答案覆盖）、§5.3～§5.4（改答案/改问题的读取影响）；R2 §2.0～§2.3（写后异步重建、任务领取、`info == null`）、§3.2（`defer`）、§4.3（同步窗口与 refresh）；P1 §3.4～§3.5（旅程三的两条交错）、P1 自测 5。

**重要前提与仍未验证**：13:56 的检索时点、ES 分词/相似度、`refresh` 的实际可见性、`deleteByQuery` 的分片结果、`sys_qa_info`/映射的读时点均为虚构/推导；未连接真实 ES/MySQL/Embedding。测试名 `answerAlwaysComesFromCurrentDatabaseRecord` 只是 mock 下的一条断言，不是真实库的证明。

</details>

---

### Q8 缓存说 10，数据库说 20（跨模块综合，★★★★★）

**虚构初态**

- 订单 `5004`：MySQL `order_status=20 DRIVER_ACCEPTED`（**已接单，不是在途**）、`driver_id=920002`、`driver_accept_time=09:59:00.000`、`is_deleted=0`；`updateTime=09:59:00.000`。
- 09:59:00.100，周师傅接单事务的 `afterCommit` 调 `syncOrderStatus(5004, 20)`，但 Redis 调用失败（连接异常/超时），`OrderGrabService#afterCommit` 只记 `warn`，**`order:status:5004` 仍是旧值 `10`，TTL 5 分钟**。
- 09:59:00.100，同一次提交后的 `schedule(5004, 10m)` 回调也失败（或未执行），ZSet 中 `member=5004` 的 score 仍是旧的 `10:00:00.000`（epoch 毫秒）。
- 司机邵师傅 `920003` 空闲，`driver:active:920003=__IDLE__`；09:59:30.000 他调用 `POST /order/driver/accept {orderId:"5004"}`。
- `order:lock:5004` 空闲；`OrderTimeoutJob` 10:00:03.000 轮询。
- **反事实**：如果订单已是 `40 IN_TRIP`，`handleTimeoutOrderUnderLock` 的 switch 没有 40 分支，走 `default` 只记日志，不取消、不释放；不要把 20 叫“行程中”。

**让你预测**

1. 邵师傅的请求会走到 `tryGrab` 的哪一步？Lua 会返回什么？DB 条件更新会怎样？接口最终返回什么？**0 行能说明 DB 仍是 10 吗？**
2. 如果 `syncOrderStatus(20)` 成功、`order:status:5004=20`，邵师傅的结果会变成什么？DB 还会被更新吗？
3. 如果 `order:status` 缓存不存在，`tryGrab` 会怎么补？请分别假设 `setIfAbsent` 成功、`setIfAbsent` 失败且 key 被写成 `10`/`90`；结果与第 1、2 问哪一问相同？**不要用未经核实的主从延迟解释。**
4. `20→10` 的条件更新具体条件是什么？更新 0 行与更新 1 行后，`releaseDriver`、`refreshOrderStatus`、`orderGeoPool.add`、`schedule(15m)` 各做什么？哪一个失败会留下什么？
5. 10:00:03 的 `claimDue` 领到 `5004`（因为旧 score 未被覆盖）时，超时线程读到 `20`、`40`、`10`、`50` 分别会怎样？会不会把“已接单”取消？请给出两种 `20` 的结局。

**可观察信号**：邵师傅的 HTTP 状态与 `Result.data`、`order:status:5004`、`driver:active:920003`、`sys_ride_order` 的 `driver_id/order_status`、ZSet 的 score、超时日志。**下一步查哪里**：`OrderGrabService#tryGrab/#afterCommit/#rollbackGrab/#refreshOrderStatus/#syncOrderStatus/#releaseDriver`、`RedisScripts#GRAB_ORDER_ATOMIC/#SYNC_ORDER_STATUS/#RELEASE_LOCK_IF_MATCH`、`RideOrderServiceImpl#driverAcceptOrder`、`OrderDelayQueue#schedule/#claimDue`、`handleTimeoutOrderUnderLock/#handleArriveTimeout/#handleAcceptTimeout`。

<details>
<summary>参考答案与推导（Q8）</summary>

**1. 邵师傅会通过 Lua 预检，但被 DB 条件更新挡住，返回 `false`（HTTP 200）。** 逐步：

- `tryGrab` 先 `FOR UPDATE` 锁邵师傅的 `sys_user` 行（DRIVER/status=1/未删除），再查他是否有未结束的活跃单。邵师傅没有 → `driver:active:920003=__IDLE__`（5 分钟）。
- 读 `order:status:5004`：存在且为 `10`，所以**不**回源 DB（只有 `get(statusKey)==null` 才回源）。
- `GRAB_ORDER_ATOMIC`：`GET status == "10"` 成立，`GET driver:active == "__IDLE__"` 成立 → `SET driver:active:920003=5004 EX 300`，返回 1。
- DB 条件更新：`where order_id=5004 and is_deleted=0 and order_status=10 and driver_id is null`。真实行是 `status=20, driver_id=920002` → 影响 0 行。
- `updated <= 0`：`rollbackGrab(920003, 5004)`（`RELEASE_LOCK_IF_MATCH`，只在 `driver:active` 的值仍是 `5004` 时删除）、`refreshOrderStatus(5004)`（注册 afterCommit 删除 `order:status:5004`）、`return false`。
- 控制器 `Result.ok(false)`：HTTP 200、`Result{code:200,data:false}`。**不是 409**；409 来自 `tryGrab` 的 `DRIVER_BUSY`/`ORDER_NOT_GRABBABLE` 或订单锁等待超时。
- **0 行只说明“没有一行同时满足 orderId、未删除、status=10、driver_id is null”**；它不说明 DB 仍是 10。真实行可以已是 20/40/50/90，也可以 driver 已非空或被逻辑删除。**不能从 0 行推出 DB 状态。**

**关键点**：Redis 预检通过不等于接单成功；DB 条件更新的影响行数才是最终裁决。**“缓存说 10”只影响预检是否放行，不影响 DB 事实。**

**2. 缓存为 20 时，Lua 返回 -1，邵师傅得到 409，DB 不被更新。** `GRAB_ORDER_ATOMIC` 的第一个条件 `GET status == "10"` 不成立 → 返回 `-1`；`tryGrab` 返回 `ORDER_NOT_GRABBABLE`，`driverAcceptOrder` 抛 `BusinessException(409,"订单已被其他司机接走或已取消")`。DB 条件更新没有执行，`rollbackGrab` 也不需要；`driver:active` 被写成 `__IDLE__`，这是正常空闲占位。**这与第 1 问的 200+false 是两种不同结果**：一个是“预检就拒绝”，一个是“预检放行、DB 拒绝”。

**3. 缓存不存在：回源 + `setIfAbsent` 的结果要有题设**

`statusKey` 为空的路径：按 `order_id=5004 and is_deleted=0` 查 `sys_ride_order`，读到 20，然后 `setIfAbsent(order:status:5004, "20", 5m)`。`setIfAbsent` 的返回值没有被检查，所以后续 Lua 看到什么取决于**这次写入是否成功、以及是否有其他命令写入**：

- **假设没有并发写**：`setIfAbsent` 成功，key=20 → Lua 第一个条件失败 → `-1` → 409（同第 2 问）。
- **假设 `setIfAbsent` 失败，且 key 已被其他命令写成 10**：Lua 可能返回 1；DB 仍以 0 行拒绝 → 200+false（同第 1 问）。
- **假设 key 被写成 90**：Lua 返回 -1 → 409。
- **假设本次回源读到 10**（题设给定的陈旧读，不引入主从假设）：Lua 可能返回 1，DB 仍以 0 行拒绝 → 200+false；0 行仍不说明 DB 是 10。

**回源只补缓存，不改变 DB 事实**。不要用“主从延迟”解释固定题设；题目只需要“本次回源读到什么”与“`setIfAbsent` 是否成功/被谁改写”。

**4. `20→10` 的条件与副作用**

`handleArriveTimeout` 的条件更新是：

```sql
UPDATE sys_ride_order
SET driver_id = NULL, driver_accept_time = NULL,
    order_status = 10, update_time = now
WHERE order_id = 5004 AND is_deleted = 0
  AND order_status = 20
  AND driver_id = 920002
  AND driver_accept_time = 09:59:00.000
```

- **更新 0 行**：直接 `return`，**不会**执行 `releaseDriver`/`refreshOrderStatus`/`orderGeoPool.add`/`schedule`；订单可能已被推进到 40、已被取消或司机字段已变。0 行不是“订单仍是 20”。
- **更新 1 行**：DB 提交后，分别执行：
  - `releaseDriver(driverId, orderId)`：注册 afterCommit，执行 `RELEASE_LOCK_IF_MATCH` 删除司机占位；失败只记 warn。
  - `refreshOrderStatus(orderId)`：注册 afterCommit 删除 `order:status`；失败只记 warn，旧缓存可能保留。
  - `orderGeoPool.add(orderId, startLng, startLat)`：按项目约定这是**失效附近订单池**，不是把订单加回可搜池；失败则池状态可能陈旧。
  - `orderDelayQueue.schedule(orderId, 15m)`：注册 afterCommit ZADD 新 score；失败则没有新待办，只能等数据库补偿扫描。
- 这些副作用**不在同一个数据库事务内**，也不能因为 DB 更新成功就推断它们都成功；反过来，副作用失败不会回滚已提交的 `20→10`。`releaseDriver` 失败留下的 `driver:active` 占位，下一次 `tryGrab` 会按 DB 活跃单修复（本例没有活跃单，会修回 `__IDLE__`），所以不是永久 `DRIVER_BUSY`；`refresh` 失败则旧缓存可能继续让下一个司机误判。

**5. `claimDue` 领到旧 score：按锁内重读状态分流**

`claimDue` 只做 `ZRANGEBYSCORE + ZREM`；`handleTimeoutOrder` 在 `order:lock:5004` 内重读：

- **读到 `20 DRIVER_ACCEPTED`**：走 `handleArriveTimeout`。本例 `driverAcceptTime=09:59:00.000`，到 `10:00:03` 约 63 秒，不到 10 分钟 → `reschedule`（约 `10:09:00.000`）；**不取消**。若锚点已超过 10 分钟（例如改成 `09:50:00.000`），才走 `20→10` 回池；**也不是 `90` 取消**。
- **读到 `40 IN_TRIP`**：`handleTimeoutOrderUnderLock` 的 switch 没有 40，`default` 只记“状态已前进，无需超时处理”，**本次不取消、不释放、不改缓存**。
- **读到 `10 CREATED`**：走 `handleAcceptTimeout`，锚点 `updateTime`/预约时间；本例 `09:45` 已超过 15 分钟 → 条件 `10→90`；只有影响行数 > 0 才算取消。
- **读到 `50 FINISHED_WAIT_PAY`**：走 `handlePayTimeout`，锚点 `finishTime`；超过 30 分钟才 `50→90`。

**两种 `20` 的结局**：未到期是 `reschedule`，到期是 `20→10` 回池；**都不是取消**。题目问“会不会把已接单取消”时，答案应先说“不会仅因为 claimDue 领到就取消；40 更是直接跳过”。

**“改期覆盖”与“旧待办已领取”的区别**：如果接单事务的 `schedule` afterCommit 成功，ZADD 会把同一 member 的 score 改为 `10:09:00.000` 左右，`10:00:03` 的 claimDue 不会返回 5004；若确认新 score 在领取前尚未写入且旧 score 仍在，则可能领到旧待办。即使回调后来成功，也不能撤回已被领取的待办。**“领取”不等于“取消”**，还要看锁内重读的状态与锚点。`SYNC_ORDER_STATUS` 单调推进，后续较小的 `10` 不会覆盖已写入的 `20`；但题设的 20 同步失败，所以缓存仍可能是 10。

**关键源码**：`service/base/OrderGrabService.java#tryGrab/#afterCommit/#rollbackGrab/#releaseDriver/#refreshOrderStatus/#syncOrderStatus`；`util/RedisScripts.java#GRAB_ORDER_ATOMIC/#SYNC_ORDER_STATUS/#RELEASE_LOCK_IF_MATCH/#CLAIM_DUE_ORDERS`；`service/impl/RideOrderServiceImpl.java#driverAcceptOrder`；`service/base/OrderDelayQueue.java#schedule/#claimDue`；`service/base/OrderTimeoutJob.java#pollTimeoutOrders`；`RideOrderServiceImpl#handleTimeoutOrderUnderLock/#handleAcceptTimeout/#handleArriveTimeout/#handlePayTimeout/#reschedule/#systemCancel`。

**对应新教材**：O2 §2.3～§2.4（tryGrab/条件更新/afterCommit）、§4（失败矩阵）、§5.2（状态缓存单调推进）；O4 §2.1～§2.3、§3.2、§4.3；F3 §3.5、§5.6（提交后回调）；P1 §2.9；Q2 的独立时间线。

**重要前提与仍未验证**：`syncOrderStatus` 失败是题设；`setIfAbsent` 是否成功、是否有并发写、本次回源读到什么，都要在题设中给定；真实 Redisson/Redis 异常、afterCommit 是否执行、`RELEASE_LOCK_IF_MATCH` 结果、`SYNC_ORDER_STATUS` 单调比较未实测；MySQL 条件更新 0 行是源码可推导的，不是运行结果。

</details>

---

## 四、自评与错题定位：错在概念、顺序还是条件

做完题后不要只对"结论对不对"。用下面的三步定位：

1. **先分类**：你的答案错在"事实/概念"（不知道真值在哪）、"调用顺序"（知道有这一步，但放错位置）还是"条件遗漏"（知道顺序，但漏了前提/分支）。
2. **再定位**：按错题现象查下表，只读对应小节和源码方法，不重读整章。
3. **再复述**：合上书，用"谁调用、读什么、写什么、失败怎样"四句话复述该机制；能复述再回到题。

| 错题现象 | 归因 | 立刻重读 | 源码核对 |
|---|---|---|---|
| 把 401 说成拦截器返回 | 概念：拦截器只"认人"，切面才"拒人" | A2 §1～§2；P1 §1.4 | `AuthTokenInterceptor#preHandle`、`PermissionAspect#checkPermission` |
| 把工具 userId 当成模型参数 | 概念：ToolContext 由服务端注入 | C2 §8.1；S1 §2.3 | `SupportAgent#runLoopStep`、`TicketTool#createTicket` |
| 认为 Redis 预检决定接单 | 顺序：预检只是加速，DB 条件更新才是裁决 | O2 §2.3～§2.4；P1 §2.9 | `OrderGrabService#tryGrab`、`RideOrderServiceImpl#driverAcceptOrder` |
| 把 DB 0 行当 409 | 概念/返回层：false 是 200，409 来自 tryGrab/锁等待；**0 行不说明 DB 仍是 10** | O2 §4；P1 §4.2 | `driverAcceptOrder` 的 `updated <= 0` 分支 |
| 把 20 当“行程中”/把 40 当 20 | 状态码：20=DRIVER_ACCEPTED，40=IN_TRIP；40 在当前超时 switch 走 default | O4 §1、§3.2；P1 §2.9 | `handleTimeoutOrderUnderLock` 的 switch |
| 忘记 afterCommit | 条件遗漏：提交后回调可能失败，失败只记日志 | F3 §3.5、§5.6；S2 §5.5 | `OrderGrabService#afterCommit`、`TicketServiceImpl#evictTicketStatistics` |
| 认为双删能消除所有旧值 | 条件遗漏：500ms 之后回填的旧值仍在 | S3 §5.2～§5.3；S2 §6.3 | `DelayedCacheEvictor#evictAfter` |
| 把 `resume` 当写许可 | 顺序：许可是 CONFIRM Lua 写的，`resume` 只清暂停字段 | C3 §5.3 | `OrderAgent#resume`、CONFIRM Lua |
| 把 `resuming` 当正常残留 | 时序：正常恢复会 HDEL，残留只在异常/未完成窗口 | C3 §4.4、§5.3 | `OrderAgent#resume` 的 `HDEL break/pendingToolCall/confirmationAccepted` |
| 把 `>900000` 当 `>=900000` | 边界：正好 900000ms 不拒绝 | C3 §4.5 | `OrderConfirmation` 两段 Lua |
| 把 `limit` 当返回条数 | 条件：窗口按批次边界向前扩展，可能超过 limit | C4 §5.3～§5.4 | `MessageWindow#tail` |
| 把 SSE 断开当取消后台 | 概念/订阅：`publish().autoConnect(2)` 与 `doFinally` 的位置；3 分钟是每次模型流 | C1 §5.3～§5.4、§7.1、§7.5 | `ChatRequestRunner#run`、`AgentStreams#subscribe` |
| 把“新答案”当同步完成 | 概念：答案由本次 MySQL 回查覆盖；refresh 调用不等于分片成功 | R1 §3.6；R2 §2.3、§4.3 | `RagService#authoritative`、`QaIndexSynchronizer` |
| 把空结果当 bug | 条件：无候选/被剔除/映射缺失/本次回查读到旧版本都可能 | R1 §5.3～§5.4；R2 §2.3 | `authoritative` 的五条件、`RagTool` 的空结果文案 |
| 从测试名推断运行结果 | 证据级别：测试名不是断言，测试未运行 | P2 §二、§五 | 读 `assert/verify` 与 mock 设置 |

**一个可操作的自评标准**：

- **及格**：八题中至少 6 题的"第一失败点"和"可观察层"正确；Q2/Q3/Q8 至少各写出两种结局。
- **良好**：能对每题的"错误答案"说出它错在哪个条件，而不是只说"记错了"。
- **优秀**：能只用源码方法名和 Redis 字段，复述 Q4 的出口分支、Q5 的窗口计算、Q6 的四个订阅状态，不依赖本章原文。

---

## 五、一页速查：七个机制的真值与边界

| 主题 | 真值/权威 | 第一道守卫与关键写入 | 关键源码 | 最容易记错 |
|---|---|---|---|---|
| 身份归属 | Redis `auth:token:{token}` + MySQL `sys_user`；工具身份来自 `ToolContext` | 拦截器认人（写 ThreadLocal，过期时删令牌）；切面 401/403；Service/Controller 查归属；工具内 catch 转文本 | `AuthTokenInterceptor#preHandle`、`PermissionAspect#checkPermission`、`RideOrderServiceImpl#assertCanViewOrder`、`TicketTool#createTicket` | 把 401 归给拦截器；把模型参数当 userId |
| 订单并发 | MySQL `sys_ride_order.order_status` | `withOrderLock` → `tryGrab`（司机行锁/活跃单/状态缓存/Lua）→ DB 条件更新；`afterCommit` 同步缓存/ZADD/GEO | `OrderGrabService#tryGrab`、`RideOrderServiceImpl#driverAcceptOrder`、`RedisScripts.GRAB_ORDER_ATOMIC` | 把 Redis 预检当裁决；把 DB 0 行当 409 或当“DB 仍是 10”；把 20 当 40 |
| 事务与缓存 | MySQL 的四条独立 `COUNT` | 统计 key 单次 TTL 20～30s（上界 30s）；提交后第一次删除 + 500ms 第二次删除；重建锁 5s | `TicketServiceImpl#getTicketStatistics/#evictTicketStatistics`、`DelayedCacheEvictor` | 把 Redis 当权威；以为双删消灭所有窗口；把提交时刻 + 30s 当收敛上界 |
| 确认令牌 | Redis `chat:info:{chatId}` 的 `quoteId/confirmedQuote/ReadyForCreate/break` | `quoted` → `begin` → CONFIRM（写/删许可、`resuming`）→ `resume`（清暂停字段）→ CONSUME（HDEL 许可）→ createOrder | `OrderConfirmation` 两段 Lua、`OrderTool#createOrder`、`OrderAgent#resume` | 把 `resume` 当写许可；把 `resuming` 当正常残留；把 `>900000` 当 `>=`；忘了 CONFIRM 年龄先于 resuming |
| 记忆批次 | MySQL `sys_messages`（真值）；Heap/Redis 是缓存；工具结果另有辅助副本 | `MessageWindow` 按完整批次取窗口；未闭合只允许在末尾；坏记录在 parser 层可能截断 | `MessageWindow#tail`、`MessageParser#unparseHistory`、`ToolResponseMemory#save/#get`、`MessageMemory#save` | 把 `limit` 当条数上限；以为 `notifyUser` 也有辅助副本；以为 `recordCalling` 必然成功 |
| SSE 断开 | `chat:execution:{chatId}` 锁 + sink 的终态 | `tryLockAsync(0,-1)`；`publish().autoConnect(2)`；`doFinally` 在上游；3 分钟是每次模型流 | `ChatRequestRunner#run`、`AgentStreams#guard/#subscribe` | 断开=取消后台；事件=客户端一定收到；3 分钟=整轮上限 |
| RAG 索引 | MySQL `sys_qa_info.answer`（答案真值）；ES 只提供候选 | 双路召回→RRF→组内去重前五→`authoritative` 五条件→本次回填答案；同步任务异步重建 | `RagService#searchAnswers/#authoritative/#updateAnswer/#updateQuestion`、`QaIndexSynchronizer` | ES 的 answer 是用户看到的；新答案=同步完成；refresh 调用=分片成功；空=bug |

**七条一句话不变量**

1. 拦截器认人，切面拒人，业务查归属；工具身份只信 `ToolContext`。
2. Redis 预检可以拒绝，但不能批准；DB 条件更新的影响行数才是批准；0 行不能反推 DB 仍是 10。
3. 缓存不是真值；提交后失效可能失败，500ms 之后回填的旧值只能等单次 TTL（20～30 秒，上界 30 秒）或下一次写入。
4. `quoteId` 绑定一次报价；CONFIRM 写许可（年龄检查先于 `resuming`），`resume` 清暂停字段，CONSUME 删许可；三者不是同一件事。
5. `MessageWindow` 的 `limit` 是目标不是上限；未闭合批次只允许在末尾；坏记录可能被 parser 截断，不一定在窗口抛。
6. 已接通且内部订阅仍在时，HTTP 订阅取消不会取消后台任务。上游最终结束时才走本例的 `doFinally`；`tryEmit*` 只是尝试；3 分钟是每次模型流。
7. 最终答案是本次 MySQL 回查读到的行；ES 候选、任务快照、单次 refresh 调用都不能单独证明“整次同步完成”；refresh 调用不等于分片成功。

---

## 六、P2 回看：把"结论"再推一层

如果你在某道题上仍不放心，按 P2 的层级选卡；**不要为了"更真实"直接跳到 L3**，也不要拿 L2 的证据补 L1 的缺项。

| 你现在的疑问 | 先看 P2 哪张卡 | 这张卡能补什么 | 仍然缺什么 |
|---|---|---|---|
| 确认编号到底怎么绑定、消费一次 | L0-1、L2-R1 | 源码分支 + 真 Redis 顺序调用 | 并发 `/r`、事件送达、HTTP 状态 |
| 接单后旧超时待办会不会被错误领取 | L0-2、L2-R2 | 同一 member 改期覆盖的真实 ZSet 行为 | 订单锁、事务提交早晚、业务锚点 |
| RAG 批量写入失败会不会留下半套数据 | L2-M1 | 外层事务模板下三表一起回滚 | Spring 代理、真实 ES、并发同步器 |
| 持锁期间新任务会不会丢 | L2-M2 | 指定交错下任务最终保留 | 版本值、唯一等待原因、真实同步成功 |
| 旧候选为什么能返回新答案 | L0-D、L1-2、L3-2 | 四前提推演 → mock 断言 → 真实检索冒烟（需隔离数据集） | 任一层都不能单独证明整次重建完成 |
| 身份/价格覆盖有没有测试证据 | L1-1 | 两条断言的源码阅读 | 工单归属、Lua、consume 重放 |

P2 的完整实验卡、门控、清理与停止条件见[第 24 章 四层实验与清理](02-isolated-labs.md)。**本章不要求执行任何卡**；如果执行，按 P2 的一次性实例核对与收尾步骤做，不要用共享实例。

---

## 七、P4 导航

### 7.1 下一章的位置与入口

P4《术语、源码与 Key 索引》已写入 `06-practice-and-review/04-glossary-and-source-index.md`，状态为**已通过复审（P7-4 第二次复审，2026-11-21）**；本节只同步导航，不改动本章其余已通过正文。P4 提供四个入口：

- **术语**：中英文对应、首次出现章节、项目内实例；
- **类/方法**：按职责分组，避免只给文件名；
- **Redis key**：key 模板、类型、TTL、写入方/读取方、失败边界；
- **机制**：从现象反查章节与源码。

本章第五节的"一页速查"仍是速查，不替代 P4 的完整条目；P4 已通过复审，查阅时以 P4 为准。原两处措辞差异（订单表名简写与真实表名不一致）已在全书复核中统一为 `sys_ride_order`。

### 7.2 课程计划对照

| P3 课程计划的要求 | 本章位置 | 自检 |
|---|---|---|
| 能按间隔复习计划复述核心机制 | §1.1～§1.4 | 三轮任务、时长估计、验收标准齐全 |
| 完成覆盖七主题的综合题 | §3 Q1～Q8 | 身份、订单并发、缓存窗口、确认重放、记忆批次、SSE、RAG 全覆盖 |
| 至少两题横跨两个以上模块 | Q1、Q3、Q8 | Q1 跨鉴权/订单/工单/Agent；Q3 跨工单/缓存/事务；Q8 跨订单/缓存/超时 |
| 答案给推导而不是结论 | 每题折叠答案 | 守卫、状态、读写、异常传播、源码与章节、前提/未验证 |
| 首次学习/隔日/一周后三轮 | §1.1～§1.3 | 时长明确标为估计 |
| 时间不足时的最小组合 | §1.4 | 10/20/30/45 分钟四档 |
| 只读源码即可完成 | §1.5 | 七步路线，不需要中间件 |
| 与 P4 索引配合 | §7.1 | P4 已通过复审，§7.3 已给出链接 |
| 答错后回到具体章节与源码 | §4 | 15 行错题定位表 |

### 7.3 导航

- **上一章**：[第 24 章 四层实验与清理](02-isolated-labs.md)（P2，已通过复审）——本章 §1.6 的选卡与清理边界都来自它。
- **上一章的上一章**：[第 23 章 三条完整业务旅程](01-trace-three-journeys.md)（P1，已通过复审）——本章所有虚构人物、订单、工单与 chatId 的出处。
- **下一章**：[第 26 章 术语、源码与 Key 索引](04-glossary-and-source-index.md)（P4，已通过复审）——本章答错后，按它的术语、源码、key、现象四个入口定位。
- **相关专题**：[第 5 章 登录与会话](../01-auth/01-login-and-session.md)、[第 6 章 权限与防护](../01-auth/02-authorization-and-defense.md)、[第 8 章 抢单与并发](../02-orders/02-grab-and-concurrency.md)、[第 10 章 超时与恢复](../02-orders/04-timeouts-and-recovery.md)、[第 13 章 工单池与统计缓存](../03-support-and-cache/02-ticket-index-and-cache.md)、[第 14 章 缓存竞态](../03-support-and-cache/03-cache-races.md)、[第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)、[第 18 章 订单确认](../04-chat-and-agent/03-order-confirmation.md)、[第 19 章 三层记忆](../04-chat-and-agent/04-memory-and-tool-results.md)、[第 21 章 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md)、[第 22 章 索引同步与预算](../05-rag/02-index-sync-and-budget.md)。

### 7.4 本章源码核验入口（相对 `src/main/java/com/fancy/rideagenthub/`）

- **身份/归属**：`interceptor/AuthTokenInterceptor.java`、`aspect/PermissionAspect.java`、`exception/GlobalExceptionHandler.java`、`service/impl/RideOrderServiceImpl.java#getOrderDetail/#assertCanViewOrder`、`controller/TicketController.java#assertTicketReadable`、`service/impl/TicketServiceImpl.java#submitTicket`、`agentbase/tool/TicketTool.java#createTicket`、`agents/SupportAgent.java#handleToolCalls/#executeTool`。
- **订单/缓存/超时**：`service/base/OrderGrabService.java`、`service/impl/RideOrderServiceImpl.java#driverAcceptOrder/#withOrderLock/#handleTimeoutOrderUnderLock`、`service/base/OrderDelayQueue.java`、`service/base/OrderTimeoutJob.java`、`util/RedisScripts.java`、`service/impl/TicketServiceImpl.java#getTicketStatistics/#evictTicketStatistics`、`service/base/DelayedCacheEvictor.java`、`service/base/RedisLock.java`。
- **对话/确认/记忆/SSE**：`service/base/ChatRequestRunner.java`、`agents/AgentStreams.java`、`service/impl/ChatServiceImpl.java#chat/#resume`、`service/base/OrderConfirmation.java`、`agentbase/tool/OrderTool.java#createOrder`、`agents/OrderAgent.java#handleToolCalls/#resume/#executeTool`、`agentbase/memory/MessageWindow.java`、`MessageParser.java`、`MessageMemory.java`、`MysqlMemory.java`、`ToolResponseMemory.java`、`agentbase/tool/ToolRepPointerTool.java`。
- **RAG**：`agentbase/rag/RagService.java#searchAnswers/#authoritative/#updateAnswer/#updateQuestion/#deleteQA`、`agentbase/rag/QaIndexSynchronizer.java`、`mapper/QaSyncJobMapper.java`、`agentbase/tool/RagTool.java`。
- **测试源码（只读，未运行）**：`src/test/java/com/fancy/rideagenthub/SecurityRepairRegressionTest.java`、`SecurityRedisIntegrationTest.java`、`OrderDelayQueueIntegrationTest.java`、`RepairDatabaseIntegrationTest.java`、`src/test/java/com/fancy/rideagenthub/agentbase/rag/RagConsistencyRegressionTest.java`。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置、测试与 API 文档写完整仓库相对路径。定位优先用"类 + 方法"，行号会漂移。

**状态：已通过复审（P7-3，2026-11-20），2026-11-22 独立复核。** 见 [最终检查记录](../_meta/review-2026-11-22.md)。
