# 第 16 章 一次对话与事件流：从请求到 SSE 结束

> 用户林小雨（虚构，`userId=7000001`）在浏览器里打开一个新会话，输入“帮我查下天气”。她看到的是一行行出现的答复；服务器那边却要回答四个问题：**这是谁**、**这一轮能不能开始**、**后台任务在哪儿跑**、**流结束时到底释放了什么**。本章沿真实代码把这四件事串起来。
>
> 本章只讲“理解一次对话所需的最小串联”：**分类与工具循环**由 [C2](02-routing-and-tool-loop.md) 主讲，**人工确认与恢复**由 [C3](03-order-confirmation.md) 主讲，**三层记忆与回填**由 [C4](04-memory-and-tool-results.md) 主讲（均已通过复审）。
>
> **本章没有启动服务、运行测试、连接 Redis/MySQL/Redisson 或发送 HTTP/模型请求**。除明确标注“测试源码阅读”的地方，结论都来自仓库源码静态推导；任何示例事件与时序都是示意，不是实测输出。

## 学习目标

读完本章，你应该能：

1. 从 `/chat/v2/c/{id}` 与 `/chat/v2/r/{id}` 两个入口出发，说出身份、chatId、请求体分别来自哪里，以及 UUID 校验发生在 Flux 创建还是订阅阶段。
2. 解释 `ChatRequestRunner#run` 中 Redis 单轮执行锁、`Flux.defer`、`publish().autoConnect(2)`、后台订阅与 `doFinally` 的配合，能判断某时刻能否断言“锁已释放”。
3. 列出 `AgentEvent` 的真实 type、payload 来源与发送位置，并区分 SSE 事件、Flux 的 `onError`/`onComplete`、HTTP 状态与后台业务完成。
4. 分别说明单轮执行锁、`ChatManager` 的会话锁定状态与 `ChatRateLimiter` 的请求限流：保护对象、键、调用点、生命周期与失败边界。
5. 说明请求线程、`chatExecutor` 工作线程与 `ThreadLocal`/`ToolContext` 的身份传递边界，以及单次模型流超时、工具超时与“无整轮总时限”的含义。
6. 阅读相关测试的门控、真实/替身组件、断言与清理范围，区分源码事实、测试源码阅读、静态推导和实际运行。

## 前置知识与阅读时间

- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——`Flux`、SSE 与 `AgentEvent` 的基础（本章复用其版本证据）。
- 必读：[第 6 章 权限与防护](../01-auth/02-authorization-and-defense.md)——Token 如何进入服务端身份上下文、注解切面如何拒绝。
- 必读：[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)——锁令牌与释放条件，S4 已建立的口径。
- 相关：[第 5 章 登录与会话](../01-auth/01-login-and-session.md)、[第 13 章 工单池索引与统计缓存](../03-support-and-cache/02-ticket-index-and-cache.md)。
- 后续主讲：[C2 分类路由与工具循环](02-routing-and-tool-loop.md)、[C3 订单确认、恢复与消费](03-order-confirmation.md)、[C4 三层记忆与工具结果反查](04-memory-and-tool-results.md)（均已通过复审）。
- 阅读时间：约 90～110 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、API 文档与测试写完整仓库相对路径。

---

## 零、先记住十个事实

1. **两个流式入口**：`POST /chat/v2/c/{id}`（新对话或继续普通对话）与 `POST /chat/v2/r/{id}`（订单确认后的恢复），都返回 `Flux<AgentEvent>`，都调用同一个 `ChatRequestRunner#run(userId, id, chatParam, false/true)`。
2. 身份来自服务端：`ChatController#chat/#resume` 用 `UserTokenContext.getUserIdInString()` 取 `userId`，它是拦截器在请求线程放入 `ThreadLocal` 的；请求体 `ChatParamDTO` 只有 `prompt`、`confirmationId`、`confirmed` 三个字段。
3. **UUID 校验是同步的**：`UUID.fromString(chatId)` 在 `ChatRequestRunner#run` 中、`Flux.defer` 之前执行；`chatManager.assertOwned(...)` 在 `Flux.defer` 内部，属于订阅阶段。
4. 单轮执行锁是 Redisson `RLock`，key 为 `chat:execution:{chatId}`，`tryLockAsync(0, -1, SECONDS, owner)` 不等待、租期交看门狗；`get(3, SECONDS)` 只是等待异步结果的上限，不是整轮超时。
5. `Flux.defer` 里创建 **unicast sink**；`publish().autoConnect(2)` 要求两个订阅者：Runner 自己的内部订阅（第 1 个）与 HTTP 下游订阅（第 2 个）。只有连接建立时才执行 `doOnSubscribe` 并把任务提交给 `chatExecutor`；`doOnSubscribe` 运行在第 2 个订阅发生的线程，不保证是原请求线程。
6. `doFinally(signal -> lock.unlockAsync(owner))` 只在**连接建立后的上游**收到 complete/error/取消时触发。HTTP 下游断开不等于上游取消：内部订阅仍在，`autoConnect` 也不会因下游离开而断开连接；若两个订阅始终没有凑齐、上游从未连接，`doFinally` 没有机会运行。`unlockAsync` 只是发起解锁，不是成功确认。
7. **落库分三类**：请求到达时只做身份与 UUID 校验；首轮（历史为空）分类成功且非 DANGER 后，`ChatServiceImpl#chat` 调 `chatManager.initChat` → `ChatInfoService#insertChat` → `chatMapper.insert` 写 `sys_chat` 会话元数据；聊天消息由各 Agent 成功收尾的 `saveNewMessages` 写 MySQL 与缓存。提前结束、失败、确认暂停的保存情况不同，见 3.3 的保存表。
8. `AgentEvent` 只有五种 `type`：`message`、`tool_start`、`error`、`confirm`、`notify`；`confirm` 的工厂方法源码拼写是 `comfirm(...)`，payload 可能仍是 JSON 字符串。
9. **限流不是 HTTP 429**：`ChatRateLimiter#tryAcquire` 超限后，`canStartChat` 发 `notify` 事件并正常 `complete`；Redis 异常时放行。
10. 没有整轮总时限：`AgentStreams#subscribe` 给**每次模型流调用** 3 分钟，`ChatRateLimiter` / RAG / 天气等有各自的局部时限；Java 逻辑结束不等于底层网络一定停止。

---

## 一、学习地图与第一个虚构请求

### 1.1 一次对话要回答的四个问题

林小雨的浏览器向 `POST /chat/v2/c/9f1d...`（虚构 UUID）发送 `{"prompt":"帮我查下天气"}`。服务器要在一次请求里回答：

- **身份**：这个 `userId` 从哪里来？服务端是否信任请求体里声称的身份？（来自 `UserTokenContext`，不信任请求体）
- **准入**：这一轮是否允许开始？会话是否属于当前用户？是否正在被其他人处理？是否超过频率？（注解切面 → 单轮锁 → 归属 → 限流）
- **执行**：分类结果决定哪个 Agent 处理；Agent 在哪个线程运行，事件如何回到 HTTP 响应？（`chatExecutor` + sink + `Flux`）
- **收尾**：什么时候算“业务完成”？什么时候锁真正释放？客户端断开会改变什么？（sink 终态 + `doFinally`）

### 1.2 两个真实入口的区别

| 入口 | 方法 | 调用 `run(..., resume)` | 典型用途 | 与会话状态的关系 |
|---|---|---|---|---|
| `POST /chat/v2/c/{id}` | `ChatController#chat` | `false` | 首次发消息或继续普通对话 | `assertOwned(userId, id, true)`：**允许**不存在的 chatId（新会话） |
| `POST /chat/v2/r/{id}` | `ChatController#resume` | `true` | 订单确认后继续被暂停的流程 | `assertOwned(userId, id, false)`：会话必须已存在；`ChatServiceImpl#resume` 只做限流/归属/确认，然后走 `OrderAgent#resume`，**不经过分类缓存与 `isLocked` 检查**；`break` 不是在 `ChatServiceImpl#resume` 入口检查，而是由 `OrderAgent#resume` 要求它为 `resuming` |

`GET /chat/v2/restore`、`POST /chat/v2/restore`、`POST /chat/v2/lock/{id}`、`POST /chat/v2/newuuid` 是同一 Controller 中的同步 `Result` 接口，**不经过** `ChatRequestRunner` 的单轮执行锁。它们不是同一种“恢复”：`GET /restore` 只判断有无候选会话并给相关 key 续期 60 分钟；`POST /restore` 在候选成立时把 DB/Redis 的 `locked` 置为 `false` 并 `PERSIST`；`/lock/{id}` 则把 `locked` 置为 `true` 并设 TTL。`POST /r/{id}` 是订单确认后的继续执行，不是会话恢复。详见 7.2；本章不展开 C3 的确认协议。

### 1.3 身份与请求体来自哪里

```java
// controller/ChatController.java（真实节选）
@RequirePermission
@PostMapping("/c/{id}")
public Flux<AgentEvent> chat(@PathVariable String id, @RequestBody ChatParamDTO chatParam) {
    String userId = UserTokenContext.getUserIdInString();
    return requestRunner.run(userId, id, chatParam, false);
}
```

`AuthTokenInterceptor#preHandle` 从 `Authorization: Bearer ...` 或 `X-Auth-Token` 取 token，经 `TokenService#getToken` 校验 Redis token、session 版本与 DB 用户状态后，调用 `UserTokenContext.set(userToken)`；`@RequirePermission`（此处 value 为空，只要求已登录）由 `PermissionAspect#checkPermission` 在未登录时抛 401、角色不匹配时抛 403。拦截器在 `afterCompletion` 清理 ThreadLocal。这些失败发生在**流创建之前**，由全局异常处理器映射 HTTP 状态（A2 已讲）。

请求体 `ChatParamDTO` 是普通 Java Bean：`prompt`（用户文本）、`confirmationId`、`confirmed`（`Boolean`，没有 `@NotNull`）。`/r` 在 `confirmationId`/`confirmed` 都非空且 `prompt` 空白时，把 `prompt` 自动填成“确认”或“拒绝”；否则把用户文本原样拼接在固定反馈之后传给 `OrderAgent#resume`——这是不可信输入，不能把前端文字当作确认凭据（C3 主讲）。

---

## 二、最少概念：Flux、sink 与两种“锁”

### 2.1 从 HTTP 到 SSE（复用 F4）

`Flux<AgentEvent>` 是 Reactor 的异步事件流。Spring MVC 在检测到返回值是 `Flux` 时，可以按 `text/event-stream` 逐条写出事件；本章沿 F4 已核验的项目版本与配置理解这一点，`application.yaml` 中**没有** `sse.*`、`spring.mvc.async.*` 或专门线程池键。Controller 也没有显式声明 `produces`；无版本依据的框架默认行为（例如 `Accept` 头的具体协商细节）标为**未知**，不凭想象补规则。

SSE 事件里看到的 `{"type":"message","payload":"..."}` 是**应用数据**；它不等于 HTTP 200，也不等于后台业务已完成。反过来，Flux 的 `onComplete` / `onError` 是**流终态**，与业务是否提交数据库、锁是否已释放也不是同一件事。

### 2.2 unicast sink、`autoConnect(2)` 与 `doFinally`

用一个比喻：sink 是单读者信箱，后台任务往里投事件，HTTP 连接从信箱里取。`Sinks.many().unicast().onBackpressureBuffer()` 只允许一个下游消费者；`Flux.defer` 让每次订阅都创建一套新的信箱和锁，而不是多个请求共用。

`publish().autoConnect(2)` 的含义常被误读：这里有两个订阅者——Runner 先用 `events.subscribe(ignored -> {}, ignored -> {})` 建立内部订阅（第 1 个），HTTP 下游再订阅（第 2 个）；只有第二个订阅出现时连接才真正建立，`doOnSubscribe` 才执行并提交后台任务。这个内部订阅只是让 sink 先有一个“不消费内容”的订阅者，它本身不启动模型调用，也不代表业务线程；如果返回的外层 Flux 根本没有被订阅，`defer` 不会执行，也不会获取锁。只有外层订阅已触发 `defer`、但内层 HTTP 下游因取消或其他原因未能成为第二个订阅者时，才有计数停在 1 的可能；这种交错未实测。

`doFinally` 挂在连接后的上游：只有上游收到 **complete / error / cancel** 时才触发。连接尚未建立时没有可触发的上游；HTTP 下游单独取消也不会让 `autoConnect` 自动断开连接，因为内部订阅仍在。连接建立后，`doFinally` 运行在发出终态信号或因取消而终止连接的那个线程，不保证是 `chat-request-*`。这些是 Reactor 语义的静态推导，实际调度未实测。

### 2.3 三种容易混的“保护”

| 机制 | 保护对象 | 键 / 存储 | 生命周期 | 失败边界 |
|---|---|---|---|---|
| 单轮执行锁（Redisson `RLock`） | 同一 chatId 的**一轮后台执行** | `chat:execution:{chatId}`，随机 owner，看门狗续期 | 订阅阶段获取；连接后的流终态 `doFinally` 发起释放，异常路径有补偿尝试 | 获取超时/中断、迟到成功、响应丢失、连接未建立、进程退出都不能无条件推断已释放 |
| 会话锁定状态（`ChatManager`） | 这个会话是否“暂停 / 停用” | MySQL `sys_chat.locked` 与 Redis `chat:info:{chatId}` Hash 的 `locked` 字段，HSET+EXPIRE 3600 秒 | `/lock` 置 `true` + TTL；下单成功后 `OrderTool#createOrder` 也会自动 `lockChat`；`POST /restore` 置 `false` 并 `PERSIST`；`GET /restore` 只续期 60 分钟；`clearChat` 置 DB `locked=true` 但当前无对外接口 | Redis Hash 字段缺失时回退查 DB；不是“正在执行的请求”保护 |
| 请求限流（`ChatRateLimiter`） | 单位时间单个用户能发起多少轮 | `chat:ratelimit:{userId}` ZSet 滑动窗口，30 次 / 60 秒（默认配置） | 每次 `canStartChat` 尝试获取一个名额 | 超限发 `notify` 并结束流；Redis 异常放行，**不是 HTTP 429** |

---

## 三、正常一轮：端到端串联

### 3.1 同步阶段（Flux 创建之前）

1. `AuthTokenInterceptor` 在请求线程设置 `UserTokenContext`；`PermissionAspect` 检查 `@RequirePermission`。
2. `ChatController#chat` 同步读 `userId`，调用 `requestRunner.run(userId, id, chatParam, false)`。
3. `ChatRequestRunner#run` 先执行 `java.util.UUID.fromString(chatId)`。这个调用在 `Flux.defer` **之前**，非法 UUID 会在这里同步抛异常，Controller 还没拿到 Flux；若异常进入全局处理器，才会谈 HTTP 状态。

### 3.2 订阅阶段（`Flux.defer` 内）

`Flux.defer` 把后续动作推迟到下游订阅时执行：

1. 创建 unicast sink；
2. `redis.getLock("chat:execution:" + chatId)`，生成随机 `owner`；
3. `tryLockAsync(0, -1, TimeUnit.SECONDS, owner)`：等待 0（拿不到立即返回 false），租期 -1 表示交 Redisson 看门狗；
4. `acquisition.get(3, TimeUnit.SECONDS)` 最多等 **3 秒异步结果**——这是“等多久知道抢锁结果”，不是对话总超时；
5. 若 3 秒内没拿到结果，注册迟到成功时的补偿解锁 `acquisition.thenAccept(late -> { if (late) lock.unlockAsync(owner); })`，恢复中断标志并抛出；外层 catch 还会立即按 `isHeldByThread(owner)` 再尝试一次解锁，然后返回 `Flux.error`。两个解锁路径可能同时发生，且都只看发起、不看结果；
6. 若 `!acquired`，返回 `Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"))`，**本次不进入业务，也没有持锁**；
7. 拿到锁后调用 `chats.assertOwned(userId, chatId, !resume)`；归属失败进入 catch：若 `lock.isHeldByThread(owner)` 则 `unlockAsync(owner)`，并返回 `Flux.error(e)`。

注意：第 4～7 步都在 `Flux.defer` 内、但**尚未提交后台任务**；它们发生在订阅者（通常是 HTTP 层）订阅时，而不是 Controller 返回 Flux 的那一刻。订阅线程通常被 MVC 当作请求线程处理，但源码没有保证它一定是原请求线程。

### 3.3 后台阶段（从 `chatExecutor` 工作线程开始）

```java
// service/base/ChatRequestRunner.java#run（真实节选，保留锁与订阅关键路径）
Flux<AgentEvent> events = sink.asFlux().doOnSubscribe(subscription -> {
    try {
        executor.execute(() -> AgentStreams.guard(sink, () -> {
            if (resume) service.resume(chatId, request, sink, userId);
            else service.chat(chatId, request, sink, userId);
        }));
    } catch (RuntimeException failure) { sink.tryEmitError(failure); }
}).doFinally(signal -> lock.unlockAsync(owner)).publish().autoConnect(2);
events.subscribe(ignored -> {}, ignored -> {});
return events;
```

- `executor` 是 `@Qualifier("chatExecutor")` 的 `ThreadPoolTaskExecutor`，核心 8 / 最大 32 / 队列 64，线程名前缀 `chat-request-`，参数硬编码在 `config/AsyncWorkConfig.java`，没有 YAML 覆盖。
- `executor.execute(...)` 抛 `RuntimeException`（例如队列满被拒绝）时，Runner 调 `sink.tryEmitError(failure)`，但**没有显式调用 `unlockAsync`**；锁此时可能被 `doFinally` 在错误终态时释放，也可能因连接/终态条件未满足而留待 TTL/看门狗——不能无条件说“立即释放”。
- `AgentStreams.guard` 用 `try/catch (Exception)` 包住整个业务调用：捕获后先发 `AgentEvent.error("处理失败，请稍后重试")`，再 `tryEmitError(failure)`。`Error` 不属于 `Exception`，不在该捕获范围内。

`ChatServiceImpl#chat` 从 `chat-request-*` 线程开始，按以下顺序执行（节选见第七节源码导航）：

1. `chatManager.isLocked(id)`：会话被显式锁定 → 发 `notify` 并 `complete`，不继续；
2. `canStartChat`：`param` 为 null 或 prompt 空白 → 发 `error` 并 `complete`；限流超限 → 发 `notify` 并 `complete`；
3. `chatManager.assertOwned(userId, id, true)` 再次校验归属（Runner 里已经查过一次）；
4. 检查 Redis Hash 的 `break=yes` → 提醒先确认/拒绝并结束；
5. `memory.get(userId, id, 100)` 读历史；同时读出 Hash 的旧 `classification`（`classRedis`，C4 主讲记忆内部机制）；
6. `resolveClassification(...)` 查分类缓存/调用分类模型 → 发 `notify(classification)`；路由失败只发 `message` 并 `complete`；
7. `DANGER` → 发不支持提示并 `complete`；**此分支不调用 `initChat`**；
8. 若历史为空，`chatManager.initChat(userId, id, prompt)` → `ChatInfoService#insertChat`：`assertOwned`、`selectCount`、`chatMapper.insert` 写 `sys_chat`。这是**会话元数据**，不是聊天消息；已存在时 `insertChat` 返回 0；
9. **旧分类为 ORDER 的提前分支**：若 `classRedis` 等于 `"ORDER"`，先在新分类不是 ORDER 时发 `notify("当前处于下单流程，如果您想换个话题，请新建对话。")`，然后在已排除 `null` 与 `DANGER` 的前提下，无论新分类是 `ORDER / DAILY / SUPPORT / OTHER`，都调用 `orderAgent.invoke(...)` 并 `return`。这个分支**不写新的 `classification` 字段，也不进入下面的 `switch`**；因此即使新分类是 `DAILY / SUPPORT / OTHER`，本轮仍由 `OrderAgent` 处理，Hash 里的分类也保持旧值；
10. 其余情况才写 Hash 的 `classification` 字段，再按 `ORDER / DAILY / SUPPORT / OTHER` 进入 `switch`。`default` 分支在当前 `normalizeClassification` 只放行五种标签的前提下通常不可达，但代码仍保留“未知分类”提示并 `complete`。

**保存不是请求到达就发生，也不是所有终态都一样**。`ChatServiceImpl#chat` 自己从不调用 `messageMemory.save`；它只可能写 `sys_chat` 元数据与 Redis Hash。聊天消息由各 Agent 的完成回调在终态调用 `saveNewMessages`：

| 时点 / 终态 | `sys_chat` 会话元数据 | 聊天消息（MySQL + 条件写缓存） |
|---|---|---|
| 请求刚到、锁竞争、空 prompt、限流、`break=yes` | 不写 | 不写 |
| 分类失败（返回 `null`） | 不写 | 不写 |
| `DANGER` | 不写（在第 8 步之前返回） | 不写 |
| 分类成功且历史为空（非 DANGER） | 第 8 步插入；已存在则 0 | 尚未写 |
| 旧分类 ORDER 提前分支 | 首轮可能已在第 8 步写 | 由 `OrderAgent` 收尾时保存 |
| Agent 纯文本成功，或模型完成但 `finalMsg == null` | 已有 | `saveNewMessages` 保存从 `originalHistorySize` 起的新增消息；`finalMsg == null` 时通常只有用户/系统消息 |
| Agent 工具循环进行中 | 已有 | 中间轮只存在内存里的 `history`，尚未落库；最终纯文本收尾时一次性保存 |
| `OrderAgent#handleNotifyUser` 确认暂停 | 已有 | `begin` 先设置 `break/pendingToolCall`，随后 **`saveNewMessages`**，再发 `confirm`、`complete`；所以“暂停”不等于“消息没保存”，但保存发生在 `begin` 之后 |
| `OrderAgent#resume` | 已有 | 在前置检查通过且历史中没有对应应答时补存 `ToolResponseMessage`，再继续模型循环；后续成功时再保存新增消息 |
| 模型流错误回调 / `guard` 捕获 / 执行器拒绝 | 可能已在第 8 步写 | 该回调不保存；此前工具可能有外部副作用，但本轮消息可能不在 DB |
| 工具循环超过 200 条、`handleNotifyUser` 内保存/序列化异常 | 已有 | 走 error 事件 + `complete`/错误终态，不能断言整轮已保存 |

两点补充：

- `AgentStreams#subscribe` 给模型 Flux 套上 3 分钟超时、`doOnNext/doOnComplete` 回调和错误处理并订阅它；它不负责组装消息或保存。拼接 `AssistantMessage`、判断有无工具调用、保存、发 `confirm`/`complete` 都在各 Agent 传入的 `next/error/complete` 回调里（见 3.4）。
- `OrderAgent#handleNotifyUser` 的顺序是 `orderConfirmation.begin`（设置 `break=yes` 和 `pendingToolCall`）→ `saveNewMessages` → 再写一次 `break=yes` → `sink.tryEmitNext(confirm)` → `sink.tryEmitComplete`。如果 `begin` 之后保存或序列化抛异常，catch 会发 error 并 `complete`，但 Redis 里的暂停状态已经设置；这是需要 C3 继续核验的边界。

### 3.4 事件如何回到浏览器

Agent 在模型流的 `next` 回调里逐块调用 `sink.tryEmitNext(AgentEvent.message(content))`，同时把内容追加到 `contentBuffer`；工具开始时经工具监听器（`ToolContext` 里的 `TOOL_LISTENER`）发出 `AgentEvent.tool("文案")`。模型流完成后，由 **Agent 自己传入的 `complete` Runnable** 决定后续：

- 取到最后的 `AssistantMessage`：拼出完整内容；对于具备工具循环的 Agent，若没有工具调用，`saveNewMessages` 保存从原始历史长度起的新增消息，再 `tryEmitComplete`；
- 对于具备工具循环的 Agent，若有工具调用：执行工具、追加 `ToolResponseMessage`、递归进入下一轮 `runLoopStep`；中间轮不保存；
- 若 `finalMsg == null`：也调用 `saveNewMessages`（通常只有用户/系统消息），再 `tryEmitComplete`；
- `OrderAgent` 遇到 `notifyUser`：`handleNotifyUser` 先保存，再发 `confirm` 并 `complete`（见 3.3）。

模型流错误时，Agent 的 `error` 回调发 `AgentEvent.error(error.getMessage())`，随后 `tryEmitError(failure)`；`AgentStreams#subscribe` 的错误处理也会经 `guard` 调用这个回调。`AgentStreams#subscribe` 本身不拼接消息、不保存、不决定终态；它负责超时、订阅模型 Flux 并把信号接到这些回调上。

所有 `tryEmitNext / tryEmitComplete / tryEmitError` 都是**尝试**：返回值没有被检查，sink 可能已终止、已取消或没有下游；因此不能承诺“事件一定发送成功”或“客户端一定收到”。Spring MVC 把已成功写入 sink 的事件按 SSE 格式写给客户端；`Flux` 的 `onComplete` 是流信号，不是一条 `type=message` 的 SSE 数据；浏览器是否看到明确的结束标记还取决于框架与传输层，本章未实测。

---

## 四、源码导读：把主线放在一页

| 环节 | 源码位置 | 你必须记住的事实 |
|---|---|---|
| 身份 | `controller/ChatController.java#chat/#resume`；`interceptor/AuthTokenInterceptor.java#preHandle`；`security/UserTokenContext.java` | `userId` 来自 ThreadLocal；请求体不带身份 |
| UUID 校验 | `service/base/ChatRequestRunner.java#run` 的 `UUID.fromString(chatId)` | 在 `Flux.defer` **之前**，同步抛异常 |
| 单轮锁 | `ChatRequestRunner.java#run` 的 `tryLockAsync` / `acquisition.get` | `chat:execution:{chatId}`；不等待、看门狗；`get(3s)` 是异步结果上限；迟到成功补解锁 |
| 后台订阅 | `ChatRequestRunner.java#run` 的 `doOnSubscribe` / `publish().autoConnect(2)` / `events.subscribe` | `doOnSubscribe` 提任务；两个订阅者才连接；只出现内部订阅时后台不启动、`doFinally` 也没有机会运行 |
| 流终态解锁 | `ChatRequestRunner.java#run` 的 `doFinally` | 连接后的上游 complete/error/cancel 才调用 `unlockAsync(owner)`；异常/响应丢失不保证即时成功 |
| 业务守卫 | `service/impl/ChatServiceImpl.java#chat/#resume/#canStartChat` | 锁定状态、限流、归属、`break`、分类与路由都在后台调用中 |
| 会话元数据 | `service/impl/ChatServiceImpl.java#chat`；`agentbase/chatinfo/ChatManager.java#initChat`；`service/base/ChatInfoService.java#insertChat` | 首轮分类成功且非 DANGER 后才 `chatMapper.insert` 写 `sys_chat`；与聊天消息保存分开 |
| 模型流与超时 | `agents/AgentStreams.java#subscribe/#guard` | 每次模型流 3 分钟；`guard` 的 `Exception` → error 事件 + 错误终态；保存与拼接由 Agent 回调决定 |
| 事件结构 | `domain/dto/AgentEvent.java` | 五种 type；`comfirm` 工厂名拼写；payload 为 `Object` |
| 线程池 | `config/AsyncWorkConfig.java#chatExecutor` | core 8 / max 32 / queue 64；只有 Runner 显式使用 |
| 会话锁定 | `agentbase/chatinfo/ChatManager.java#assertOwned/#isLocked/#lockChat` | Redis Hash 字段 + DB 回退；`HASH_SET_WITH_EXPIRE` 带 3600 秒 TTL |
| 限流 | `service/base/ChatRateLimiter.java#tryAcquire` | `chat:ratelimit:{userId}` ZSet；超限不是 HTTP 429；Redis 异常放行 |
| 分类缓存 | `service/base/ClassificationCache.java` | `chat:classify:*`；6 小时；空结果不缓存（C2 主讲完整语义） |

---

## 五、竞争时间线与订阅边界

### 5.1 正常一轮（T1）

前提：会话未锁定、UUID 合法、T1 是唯一请求、Redis/DB/模型均正常，且 HTTP 下游确实订阅了返回的 Flux。

| 阶段 | T1 所在线程（按调用推导，未实测） | 动作与可见状态 |
|---|---|---|
| a0 | 请求线程 | 拦截器设置身份；`@RequirePermission` 放行；Controller 取 userId 并调 `run(...)` |
| a1 | 请求线程 | `UUID.fromString` 通过；返回 `Flux.defer(...)` 给 Spring MVC（**此刻尚未抢锁**） |
| a2 | 订阅发生的线程（通常是 MVC 请求处理线程，不保证） | `defer` 创建 sink；`tryLockAsync(0,-1,...)`；`get(3s)` 得到 true；`assertOwned` 通过；锁已持有 |
| a3 | 同一订阅线程 | 内部订阅成为第 1 个订阅者，但 `autoConnect(2)` 未满，上游未连接，后台任务**尚未提交** |
| a4 | 同一订阅线程 | HTTP 下游成为第 2 个订阅者 → 连接建立 → `doOnSubscribe` 执行 → `chatExecutor.execute(...)` |
| a5 | `chat-request-*` 线程 | `AgentStreams.guard` → `ChatServiceImpl#chat`：守卫、分类、`initChat`、路由；分类模型是同步 `call()`，阻塞在这条线程 |
| a6 | 模型客户端 / Reactor 回调线程（不保证是 `chat-request-*`） | Agent 的 `next` 回调发 `message`；工具调用与 `saveNewMessages` 也在完成回调链里执行 → 写 MySQL/条件写缓存 → `tryEmitComplete` |
| a7 | 发出 sink 终态的线程 | 上游 complete → `doFinally` → `unlockAsync(owner)` 发起；**发起不等于已确认释放** |

要点：**收到 Flux 返回 ≠ 抢到锁 ≠ 后台已开始 ≠ 会话元数据已写 ≠ 消息已落库 ≠ 锁已释放**。a2 的订阅线程与 a6/a7 的回调线程都不能仅凭源码断言为某一个固定线程；`chatExecutor` 只保证业务入口从 `chat-request-*` 开始，不保证模型信号、工具回调、保存和 `doFinally` 永远留在该线程。

### 5.2 同一 chat 的 T1/T2 并发

前提：T1 已建立连接、已持有 `chat:execution:{chatId}` 且尚未终态；T2 是合法同 chat 请求。

| 时刻 | T1 | T2 | 可见状态 |
|---|---|---|---|
| b0 | 订阅阶段抢锁成功，owner=A | — | lock 由 A 持有 |
| b1 | 后台开始跑，发若干 message/tool_start | 订阅阶段 `tryLockAsync` 返回 false | T2 的 `acquired=false` |
| b2 | 继续处理 | 得到 `Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"))`；HTTP 订阅后流 complete | T2 **没有**进入 `ChatServiceImpl`，也没有持锁；若 HTTP 未订阅，连这条事件也不会被消费 |
| b3 | 终态 → `doFinally(A)` | — | 解锁请求发出；是否成功、何时完成都未确认，新请求只是“可能”拿到 |

若 T2 的锁获取异步结果在 3 秒内没返回，Runner 会注册迟到成功补偿解锁并抛异常；只有迟到的结果确实为 true 时才 `unlockAsync(owner)`。catch 里的 `isHeldByThread(owner)` 只是当时的同步检查：它可能因异步获取恰好完成而返回 true 并再解锁一次，也可能在检查本身失败/返回 false 时留给迟到回调处理；两次 `unlockAsync` 的返回值都未观察。若 T1 的锁因进程崩溃/网络问题失效，T2 可能提前进入——这正是“应用层锁不提供无条件串行”的边界；DB 归属与业务守卫仍在。

### 5.3 客户端断开

前提：T1 **已经建立连接并进入后台执行**；浏览器关闭页面导致 HTTP 下游取消订阅。

- 内部订阅仍是第 1 个订阅者，`autoConnect` 建立的连接不因 HTTP 下游取消而自动断开；按源码与 Reactor 语义静态推导，上游不会因为这次取消而立即终止，后台任务可以继续（实际调度未实测）。
- 客户端断开本身**不触发**额外的取消业务逻辑；源码中没有“关闭页面即停止工具调用/释放锁”的保证。
- 只有当后台链把 complete/error 写入 sink，或连接被取消到 `doFinally` 时，才调用 `unlockAsync(owner)`；若后台卡在未受单次模型流超时覆盖的工具调用，锁可能继续被看门狗续期（前提是 Redisson 客户端与 Redis 仍正常工作，未实测）。
- 客户端已断开时，`tryEmitNext` 的失败只体现 sink 内部状态，用户不会收到后续事件；不能据此断言后台失败、消息未保存或锁已释放。
- 如果外层已订阅并取得锁，但取消等交错使内层第 2 个订阅未建立，后台没有启动；外层完全未订阅时也不会取锁。具体交错见 5.4，不能用上面的“继续运行”解释。

### 5.4 只出现内部订阅时（连接未建立）

只有外层 Flux 已被订阅才会执行 `defer`：Runner 在其中先获取锁，再执行 `events.subscribe(ignored -> {}, ignored -> {})` 建立内部订阅。若外层根本未订阅，则不会进入这里，也不会取锁。若外层 Flux 已被订阅并执行到内部订阅，但返回的 `events` 未能获得第 2 个订阅者（例如此过程中发生取消；具体交错未实测），会出现一个容易误判的边界：

- `autoConnect(2)` 计数停在 1，上游从未连接；
- `doOnSubscribe` 从未执行，`chatExecutor` 没有提交任务；
- sink 没有业务事件，也没有 complete/error 信号，`doFinally` 没有机会运行；
- 已获取的锁不会由这条路径释放；看门狗是否继续续期、锁最终何时失效，取决于 Redisson 客户端/连接是否存活，未实测，不能承诺“及时释放”；
- 也不能把这种情况说成“客户端断开后后台继续”——后台根本没开始。

**假设性对照**：如果把 `autoConnect(2)` 改成 `autoConnect(1)`，内部订阅本身就会触发连接、`doOnSubscribe` 和后台任务，HTTP 断开后内部订阅仍保持上游。这是另一个假设场景，只能用于理解订阅计数，不能用来解释当前代码在只出现内部订阅时的行为。

### 5.5 五个“结束”不要混

| 层 | 触发点 | 能说明什么 | 不能说明什么 |
|---|---|---|---|
| 业务函数返回 | `ChatServiceImpl#chat/#resume` 或 Agent 回调返回 | 这一次 Java 调用走到了某条分支 | 消息是否已保存、sink 是否已终止、客户端是否收到 |
| sink 终态尝试 | `sink.tryEmitComplete()` / `tryEmitError(...)` | 调用方**尝试**结束 sink；返回值未检查 | 一定成功、订阅者一定收到、HTTP 连接一定结束 |
| Flux 终态 | 连接后的上游收到 complete/error/cancel | 会触发 `doFinally`；下游收到对应信号（若仍订阅） | 业务数据已提交、锁已释放、浏览器看到一条 message |
| HTTP 响应结束 | MVC/传输层关闭 SSE 连接 | 客户端不再收到后续事件 | 后台任务一定停止、数据库一定已写入 |
| 锁释放 | `unlockAsync(owner)` 被调用 | 发起了一次异步解锁 | Redis 一定成功、锁一定已不存在、旧工作一定停止 |

`onComplete` / `onError` 属于 Flux 层；它们不是 SSE 的 `type=message` 事件，也不能替代“保存成功”或“解锁成功”的证据。

---

## 六、事件、HTTP 状态与失败边界

### 6.1 `AgentEvent` 的真实 type

`domain/dto/AgentEvent.java` 是 record，字段 `String type`、`Object payload`；工厂方法共五个：

| type | 工厂 | payload 来源与示例（**示意，非实测**） | 常见发送位置 |
|---|---|---|---|
| `message` | `message(String)` | 模型流的内容片段；路由失败/不支持命令/未知分类的文本 | 四个 Agent 的流回调；`ChatServiceImpl#chat` 的提前结束分支 |
| `tool_start` | `tool(String)` | 工具的**描述文案**（不是单纯工具名），由工具内 `ToolNotifySupport.notifyToolListener` 提供 | 各 Agent `executeTool` / `ToolContext` 监听器 |
| `error` | `error(String)` | 锁竞争提示、空 prompt、模型错误、guard 的通用提示、超限条数等 | `ChatRequestRunner`（锁失败）、`ChatServiceImpl`、`AgentStreams#guard` 与错误回调 |
| `confirm` | `comfirm(Object)`（源码拼写） | 含 `confirmationId` 等订单槽位的 JSON **字符串**，需要读取字段的客户端需再解析该字符串 | `OrderAgent#handleNotifyUser`（C3 主讲） |
| `notify` | `notify(String)` | 分类标签、会话锁定提醒、`break` 提醒、限流提示、旧 ORDER 分支的换话题提醒等 | `ChatServiceImpl#chat/#canStartChat` |

这些事件通过 `sink.tryEmitNext` 发送，返回值被忽略；`tryEmitComplete`/`tryEmitError` 同样只是尝试。表中“常见发送位置”是源码里的调用点，不代表该事件一定成功到达客户端。

### 6.2 事件不等于 HTTP 状态

- **流创建前的失败**（非法 UUID、未登录、无权限）在请求线程同步抛异常，若进入 `GlobalExceptionHandler` 才映射为 HTTP 状态；本章与前几章都未运行实测。
- **订阅期的失败**：锁竞争 → `Flux.just(AgentEvent.error(...))` 并 complete，这仍是一条**正常的数据流**，不是 `Flux.error`；锁获取异常/归属异常 → `Flux.error(e)`。这发生在响应 Subscription 建立前后，最终呈现依赖 MVC/Reactor 与响应是否已提交；不能简单说“HTTP 400/403”，也不能把 `Flux.error` 和 HTTP 状态画等号。
- **流开始后的失败**：业务异常被 `AgentStreams#guard` 转成 `error` 事件 + `tryEmitError(failure)`；模型流错误先发 `AgentEvent.error(error.getMessage())` 再错误终态。响应可能已经提交为 `text/event-stream`，此时通常无法再改 HTTP 状态。后台线程里被 `guard` 捕获的异常不会自动变成 Controller 的同步异常；是否会进入全局 handler 取决于框架对 `Flux` 错误的处理，未实测。事件和错误终态本身也都是尝试，客户端可能只看到连接结束。
- **业务完成**：`ChatServiceImpl#chat` 的提前结束分支会 `tryEmitComplete`，Agent 正常收尾也会 `tryEmitComplete`；`onComplete` 是流信号，不是一条 `type=message` 的 SSE 数据。消息是否持久化要看具体分支是否走到 `saveNewMessages`，不能由收到 complete 反推一定保存。

### 6.3 失败路径逐项核验

下表的“客户端可见”是源码与框架条件推导，**未实测**；后台流里抛出的异常不能承诺对应的 HTTP 状态。

| 场景 | 真实路径 | 客户端可见 | 源码位置 |
|---|---|---|---|
| 有效拒绝（`/r`，`confirmed=false`，编号匹配、状态为 `yes`） | CONFIRM Lua 返回 1：`break=resuming`、`confirmationAccepted=no`，并删除 `confirmedQuote/ReadyForCreate`；`ChatServiceImpl#resume` 生成“用户拒绝当前报价，禁止创建订单。”反馈，`OrderAgent#resume` 检查 `break=resuming` 和待处理调用；尚无对应应答时补存 `ToolResponse`，然后尝试继续模型循环 | **确认拒绝本身可成功**；后续仍受 pendingToolCall、持久化和模型等条件影响，事件与终态只是尝试，不能保证客户端收到 message 或正常结束 | `OrderConfirmation#confirm`、CONFIRM Lua、`ChatServiceImpl#resume`、`OrderAgent#resume` |
| 缺少确认编号或 `confirmed` | 只有 prompt 非空时才会走到 `confirm`；若 `confirmationId` 或 `confirmed` 为 null，`confirm` 抛 `BusinessException(400)`。若 prompt 为空：两个字段都非空时会被自动补成“确认/拒绝”并进入确认；否则 `canStartChat` 先发“消息内容不能为空”的 error 并正常结束 | 后台 `guard` 发通用 `error` 事件并 `tryEmitError`；HTTP 状态取决于响应是否已提交，不能承诺 400 | `ChatServiceImpl#resume/#canStartChat`、`OrderConfirmation#confirm` |
| 编号无效、报价过期（接受时超过 15 分钟）或状态不符 | CONFIRM Lua 返回 0 → `BusinessException(409)`；脚本在返回 0 前不写入状态，原来的 `break=yes` 通常保留，可用正确编号重试（仍需满足其他条件） | 后台 `guard` 发通用 `error` 事件并错误终态；不是同步 HTTP 409 | `OrderConfirmation#confirm`、CONFIRM Lua、`AgentStreams#guard` |
| 确认已成功但 `OrderAgent#resume` 前置条件不满足 | `break` 不是 `resuming` 或缺 `pendingToolCall`：直接发 error 事件并 `complete` | 正常结束的流上出现 error 事件，不是异常抛出 | `OrderAgent#resume` |
| 权限失败 | 切面在 Controller 前抛 `UnauthorizedException`/`ForbiddenException` | 进入全局 handler 时映射 401/403；流还没创建 | `PermissionAspect#checkPermission` |
| 归属失败 | Runner 与 ServiceImpl 两次 `assertOwned`；404（新会话不允许）、403（非本人）、410（历史已删） | 若发生在 Runner 订阅期 → `Flux.error(e)`；流已开始则由 guard 转通用错误 | `service/base/ChatInfoService.java#assertOwned` |
| 限流 | `canStartChat` 发 `notify` + `complete` | 正常结束的流，只有一条提示；**不是** HTTP 429，也不是 error 事件 | `ChatServiceImpl#canStartChat` |
| 模型/工具出错 | 模型流错误 → error 事件 + 错误终态；工具异常在 Agent 内 `log.error` 并返回文本给模型 | 前者可见 error；后者通常继续以 message 流呈现，不直接报错 | `AgentStreams#subscribe`、各 Agent `executeTool` |
| 客户端断开 | 已连接时内部订阅维持上游，后台可继续；连接未建立时后台未启动 | 客户端收不到后续事件；后台结果与锁释放不可从客户端观察 | `ChatRequestRunner#run` |
| 模型流超时 | 每次 `subscribe` 的 3 分钟 `TimeoutException("模型响应超时")` | error 事件 + 错误终态；Java 逻辑结束不等于底层请求已取消 | `AgentStreams#subscribe` |

---

## 七、保护对象、线程与超时

### 7.1 单轮执行锁的精确边界

`ChatRequestRunner#run` 中：

- key：`chat:execution:{chatId}`，owner 是 `ThreadLocalRandom` 随机 long；
- 获取：`tryLockAsync(0, -1, TimeUnit.SECONDS, owner)` 不等待、租期 -1（看门狗）；`get(3, SECONDS)` 只等异步结果；
- 主动释放点：① 异常 catch 中 `if (lock.isHeldByThread(owner)) lock.unlockAsync(owner)`；② `get(3s)` 超时/中断时注册的迟到成功补偿 `acquisition.thenAccept(late -> ...)`；③ 连接建立后 `doFinally(signal -> lock.unlockAsync(owner))`；
- 超时/中断的处理：内层 catch 先注册补偿再抛出；外层 catch 立即做一次持有者检查。异步获取可能在检查前、检查中或检查后才完成，因此补偿与 catch 可能都调用 `unlockAsync`；`isHeldByThread` 本身也可能失败。两次调用的返回值都未观察；
- 不释放的分支：`!acquired` 时本来没持锁；只有内部订阅、连接未建立时 `doFinally` 不会运行；`executor.execute` 被拒绝时只 `tryEmitError`，没有显式解锁；进程终止、Redis 响应丢失、`unlockAsync` 失败时结果未知；
- 与 S4 的口径一致：**发起解锁 ≠ 成功释放**，锁到期后旧工作仍可能与新持有者重叠。

### 7.2 会话状态、恢复与显式锁定

这里有四个不同操作，不能合并成一句“恢复时解锁并 PERSIST”：

| 操作 | 入口 | 实际动作 | 条件与失败边界 |
|---|---|---|---|
| 会话锁定状态 | `ChatManager#lockChat`、`ChatServiceImpl#chat` 开头 | `ChatInfoService#lockChat` 更新 DB `locked=true`，再用 `HASH_SET_WITH_EXPIRE` 原子设置 Redis `chat:info:{chatId}` 的 `locked=true` 与 3600 秒 TTL；`isLocked` 先读 Hash，字段缺失时回退查 DB | 只影响 `#chat`；`#resume` 不检查它。`/c` 命中后发 `notify` 并结束。它保护的是“会话暂停/不再接受普通聊天”，不是“当前有一轮正在运行” |
| `POST /chat/v2/lock/{id}` | `ChatController#lockChat` | `assertOwned(..., false)` 后调用 `lockChat` | 会话不存在 404、非本人 403、已清空 410；不经过单轮执行锁；DB/Redis 异常如何映射 HTTP 未实测 |
| `GET /chat/v2/restore` | `ChatController#getRestorableChat` → `ChatManager#getRestorableChat` | 只判断候选：`getLatestChatId` 取最近未清空会话；Redis `chat:info:{chatId}` 的 `classification` 必须为 `ORDER`，且 `OrderId` 字段为空。命中时只对该 key `expire` 60 分钟 | 返回 `Result<Boolean>`；不修改 `locked`、不写 DB、不 PERSIST、不返回历史；Redis 中分类缺失或已有 `OrderId` 时返回 false |
| `POST /chat/v2/restore` | `ChatController#restore` → `ChatManager#restoreChat` → `ChatInfoService#restoreChat` | 用同一候选判断；命中时 DB `locked=false`、Redis `locked=false`，并对 `chat:info` 执行 `PERSIST`；随后读取最近 100 条消息（按完整工具批次可能超过 100）返回 | 无候选时 Controller 抛 `BusinessException(404)`“没有可恢复的对话”；不经过单轮执行锁；清空历史后 `historyDeleted=true` 的会话不会成为候选 |

`POST /chat/v2/r/{id}` 不是“恢复会话”：它是订单确认后继续执行，走 `OrderConfirmation` 与 `OrderAgent#resume`，不改 `locked` 字段，也不会 PERSIST。

**历史清空**：`MessageMemory#clearChat` 先调 `ChatManager#retireHistory`（DB 置 `historyDeleted=true`、`locked=true`），再清 MySQL、Redis、Heap；原 chatId 之后会被 `assertOwned` 以 410 拒绝。但当前 `ChatController` **没有**对外暴露清空接口，`clearChat` 在 main/java 中没有其他调用方；教材不能把它写成已有端点。

**恢复候选的必要条件**可以概括为：用户有未清空的最近会话；该会话的 Redis `chat:info` 仍存在且 `classification=ORDER`；尚未写入 `OrderId`；Redis 未丢数据。任一条件不满足，`GET` 返回 false，`POST` 返回 404。恢复动作本身不校验 `locked` 当前值，也不保证随后 `/c` 能继续下单——那还要看 `break`、订单槽位与后续业务校验。

### 7.3 请求限流

`ChatRateLimiter#tryAcquire(userId)` 用 `chat:ratelimit:{userId}` ZSet 滑动窗口，Lua 以 Redis `TIME` 为准清窗外成员、计数、写唯一 UUID member 并设窗口 TTL；默认 `chat.guard.rate-limit-max=30`、`rate-limit-window-seconds=60`（`application.yaml` 的 `chat.guard.*`）。空 userId、非正配置、Redis 异常都放行；超限由 `canStartChat` 发 `notify` 并结束流。它与 A2 的 IP/聊天两套限流按不同维度与挂载点区分（A2 已讲）。

### 7.4 线程与身份上下文

- **请求线程**：拦截器设置/清理 `UserTokenContext`；Controller 同步取 `userId` 并创建 Flux。`Flux.defer` 的供应函数在**订阅发生时**执行，订阅线程通常是 MVC 的请求处理线程，但源码没有把“原请求线程”钉死；本教材不无条件断言。
- **`chat-request-*` 工作线程**：`doOnSubscribe` 通过 `chatExecutor.execute(...)` 提交，`AgentStreams.guard` 与 `ChatServiceImpl#chat/#resume` 的入口从这里开始。分类模型的 `.call()` 是同步调用，也占用这条线程。
- **模型流信号与工具回调**：`AgentStreams#subscribe` 在 `chat-request-*` 上调用 `openAiChatModel.stream(prompt)` 并订阅返回的 Flux；之后的 `onNext/onComplete/onError`、工具执行、`saveNewMessages`、`tryEmitComplete` 由模型客户端/Reactor 的回调线程触发，**没有**源码保证仍在 `chat-request-*`。`doFinally` 同样运行在发出终态/取消连接的那个线程。
- **HTTP 下游订阅线程**：不能仅凭 Controller 的调用线程推断；它由 MVC/Reactor 适配层决定，未实测。
- **`UserTokenContext` 是普通 `ThreadLocal`**，不是 `InheritableThreadLocal`，`AsyncWorkConfig` 也没有 `TaskDecorator`；后台线程不会自动继承身份。真实链路靠显式传参 `run(userId, ...)` → `service.chat/resume(..., userId)` → Agent 的 `ToolContext(USER_ID, CHAT_ID)`。
- 不要依赖 Reactor Context 或 ThreadLocal 自动传播；也不要假设用户关闭页面会中断后台线程。以上线程归属均为按调用链的静态推导，实际调度未实测。

### 7.5 超时的真实层级

| 层级 | 是否有明确时限 | 源码证据 | 取消边界 |
|---|---|---|---|
| 单轮获取锁的异步结果 | 3 秒 | `ChatRequestRunner#run` 的 `acquisition.get(3, SECONDS)` | 超时注册迟到成功补偿解锁并抛异常；不终止后台（尚未提交） |
| 单次模型流 | 3 分钟 | `AgentStreams#subscribe` 的 `Mono.delay(Duration.ofMinutes(3))` + `TimeoutException` | 每次 `runLoopStep` 重新计时；多轮工具循环可能累计远超 3 分钟 |
| RAG 检索工具 | 默认 8000ms | `agentbase/rag/RagService.java#searchAnswers`（`rag.search.timeout-ms:8000`） | 局部 deadline，失败返回/抛错由工具链决定 |
| 天气工具 | `qweather.timeout=5000`（配置默认） | `agentbase/qweather/config/QweatherConfig.java#qweatherRestClient` | HTTP 客户端连接/读取超时，不是整轮超时 |
| **整轮对话总时限** | **没有** | 全 main/java 未发现跨 chat/resume 的 deadline | Java 逻辑终止/Reactor 取消订阅不等于底层网络操作一定停止；卡在未覆盖的工具时锁可能长期持有 |

---

## 八、逐步轨迹

### 8.1 正常请求（虚构）

输入：`userId=7000001`，`chatId=9f1d...`（合法 UUID），`prompt="帮我查下天气"`。

1. （假设）Token 有效，`@RequirePermission` 放行；Controller 取 userId，调用 `run(..., false)`。
2. UUID 解析通过；返回 Flux；HTTP 下游订阅后，`defer` 内抢锁成功，`assertOwned(..., true)` 通过。
3. 第二个订阅触发连接和后台任务；`ChatServiceImpl#chat` 依次通过锁定状态、空 prompt、限流、`break`、历史读取。
4. （假设）分类缓存命中 `DAILY`；发 `notify("DAILY")`；历史为空 → `initChat` 写 `sys_chat` 会话元数据；写 Hash `classification=DAILY`。
5. （假设）`DailyAgent` 调用天气工具 → `tool_start` → 模型逐块返回 → `message` 事件。工具与模型回调不保证都在 `chat-request-*` 线程。
6. Agent 完成回调调 `saveNewMessages` → `messageMemory.save(...)`：MySQL 先落库，再条件写缓存（S4 已核验真实链）；发 `tryEmitComplete`。
7. `doFinally` 发起 `unlockAsync(owner)`。客户端看到的最后一条是流完成/连接结束，不是“锁已释放”通知；保存与解锁结果都不可由事件确认。

**不可观测项**：第 2 步锁获取的异步完成时点、第 3 步订阅线程、第 4 步分类是否真的命中缓存、第 6 步消息保存与第 7 步解锁的最终结果——只靠 SSE 事件无法确认。

### 8.2 被拒绝场景（限流或归属失败）

输入：同一用户在 60 秒内第 31 次请求。

1. 身份、UUID、抢锁、归属都通过后，后台进入 `canStartChat`。
2. `chatRateLimiter.tryAcquire(userId)` 返回 false；发 `AgentEvent.notify("消息发送过于频繁，请稍后重试。")` 并 `tryEmitComplete`。
3. 流正常结束，**没有** error 事件，也没有 HTTP 429；没有调用分类模型、没有 Agent、没有 `initChat`、没有消息落库。
4. `doFinally` 发起解锁；是否成功、何时完成仍取决于 Redis 与响应，不能把“正常终态”当成“必然释放”。

分叉：若第 2 步之前 `assertOwned` 已失败，Runner 在订阅期返回 `Flux.error(e)`，客户端看到的是错误流而不是上述 notify；两种拒绝的路径与可观察边界不同。

### 8.3 有效拒绝与无效确认（`/r`）

前置（虚构）：会话已在订单确认处暂停：`break=yes`、`pendingToolCall` 存在，暂停前消息已由 `handleNotifyUser` 保存；`quoteId=Q1`。

**有效拒绝**：`POST /r/{id}` 带 `{"confirmationId":"Q1","confirmed":false,"prompt":"太贵了"}`。

1. 抢锁、限流、归属通过；`resume` 先把空白 prompt 补成“拒绝”的逻辑不触发（这里 prompt 非空）。
2. `OrderConfirmation#confirm` 执行 CONFIRM Lua：`quoteId` 匹配、`confirmed=false`（不校验报价年龄）、状态为 `yes` → 返回 1；Redis 置 `break=resuming`、`confirmationAccepted=no`，删除 `confirmedQuote/ReadyForCreate`。
3. `ChatServiceImpl#resume` 生成 feedback：“用户拒绝当前报价，禁止创建订单。太贵了”，调用 `OrderAgent#resume`。
4. `OrderAgent#resume` 读到 `break=resuming` 与 `pendingToolCall`，用 `getPersisted` 读 MySQL 事实，补一条 `ToolResponseMessage`（若尚未存在），删除 `break/pendingToolCall/confirmationAccepted`，继续模型循环。
5. （假设）模型正常回复；成功收尾时保存新增消息并 `tryEmitComplete`。客户端看到正常事件序列，**不是** error。

**无效确认**：同一会话，带 `{"confirmationId":"OLD-ID","confirmed":true,"prompt":"确认"}`。

1. 抢锁、限流、归属通过；CONFIRM Lua 因 `quoteId` 不匹配返回 0。
2. `confirm` 抛 `BusinessException(409)`；脚本在返回 0 前没有改状态，`break=yes` 仍保留。
3. `AgentStreams.guard` 捕获后发通用 `error` 事件并 `tryEmitError`；客户端可能只看到错误事件或连接终止，HTTP 状态通常已无法改成 409（是否已提交未实测）。
4. 分叉：若缺 `confirmationId`/`confirmed` 且 prompt 非空 → 400；若 prompt 为空且字段缺失 → `canStartChat` 先发“消息内容不能为空”的 error 并正常结束，不会走到 `confirm`。

**不可观测项**：有效拒绝后模型的文本、消息保存结果、`break` 删除的真实时点；无效确认时客户端是否确实收到 error 事件、HTTP 状态是什么。

### 8.4 客户端断开（连接已建立）

输入：T1 已进入工具调用，用户关闭浏览器。

1. HTTP 下游取消；内部订阅仍在，`autoConnect(2)` 已建立的连接不因这次取消而自动断开（静态推导，未实测）。
2. 后台任务继续跑工具与模型流；`tool_start` / `message` 仍尝试写入 sink，但客户端不再接收，写入可能失败。
3. （假设）模型流在 3 分钟内返回，Agent 正常收尾并保存、`tryEmitComplete`；随后 `doFinally` 发起解锁，但成功与否不可见。
4. 分叉：若工具卡住且不在单次模型流超时覆盖内，后台可能长时间不终态，锁可能由看门狗续期（前提是 Redisson/Redis 正常），客户端侧看不到任何错误。
5. 另一个分叉：若断开发生在第 2 个订阅建立之前，后台根本没启动，锁可能被获取却没有任务，见 5.4。

**不可观测项**：客户端无法区分“后台已完成并释放锁”“后台仍在跑”“后台已异常但 sink 终态未到”“连接未建立、后台未启动”。

### 8.5 下单流程中换话题（旧 `classRedis=ORDER` 分支）

前置：会话历史非空，Redis `chat:info:{chatId}` 的 `classification=ORDER`；用户本轮输入“今天天气怎么样”。

1. `memory.get` 返回非空历史；`classRedis=ORDER`；分类模型（假设）返回 `DAILY`。
2. 发 `notify("DAILY")`；历史非空，因此不调用 `initChat`。
3. 进入旧分类提前分支：`classRedis=ORDER` 且新分类不是 ORDER → 发 `notify("当前处于下单流程，如果您想换个话题，请新建对话。")`；随后仍调用 `orderAgent.invoke(...)` 并 `return`。
4. 不写新的 `classification` 字段，也不执行 `switch`；Hash 里的分类仍是 ORDER。若 `OrderAgent` 成功收尾，消息照常保存；若失败，按 3.3 的失败分支处理。
5. 若新分类是 `DANGER`，第 7 步就结束，不会进入这个分支，也不会调用 `OrderAgent`。

---

## 九、测试阅读（未运行）

本章只读了测试源码，**没有运行任何测试**；未连接 Redis/MySQL/Redisson，未发 HTTP 或模型请求。

| 测试 | 门控、真实/替身 | 断言与写入/清理 | 没覆盖 |
|---|---|---|---|
| `ChatGuardRedisIntegrationTest` | `@EnabledIfSystemProperty(test.redis.port)`；`127.0.0.1:port` 真实 Redis + 真实 `ChatRateLimiter`/`ClassificationCache`/Lua | 40 并发只放行 7、ZCard=7、TTL>0；脚本返回 1/0/-1 并清窗外成员；分类 key 上下文/NUL 隔离与约 6 小时 TTL。写入 `chat:ratelimit:{随机 user}`、`chat:classify:*`；`@AfterEach` 只删除 `keys` 清单中的 key。**分类测试会把 `redis.keys("chat:classify:*")` 返回的实例上全部匹配 key 加入清单**，因此可能删掉同实例已有的分类缓存；清单加入前若断言失败、`keys` 未收集成功，或删除本身失败/进程终止，都不能保证已清理 | 真实 HTTP 流、后台线程、模型输出 |
| `ChatGuardRegressionTest` | 无门控；Mockito Redis/ValueOperations + HashMap 替身 | 分隔符与 null 不串键、禁用 TTL 不访问 Redis、空分类不缓存、Redis 异常降级、匿名/禁用限流不访问 Redis；无真实写入 | 真实 Redis 并发与网络 |
| `ChatServiceRegressionTest` | 无门控；真实 `ChatServiceImpl`，模型/缓存/限流/Agent/Memory/ChatManager 全 mock | 超限只发 `notify` 不读历史/分类/模型；缓存命中跳过模型；失败/空白标签不缓存不建会话；活跃 ORDER 不被 DANGER 改写；resume 也受限流。无真实 key/DB | 真实 Agent、SSE 序列化、实际落库 |
| `SecurityRepairRegressionTest` | 无门控；真实 `ChatInfoService`/`AgentStreams`/`ChatRequestRunner`，mapper/Redisson/ChatService/ChatManager 为 mock | 外部 chatId 403、已清空 410；完成回调异常进入错误终态；Runner 首事件不丢且**调用过** `unlockAsync`（未验证释放成功）；孤儿缓存不读。无真实 key/DB | 真实锁释放结果、客户端断开、只出现内部订阅的边界 |
| `CacheConsistencyRegressionTest` | 无门控；真实 `HeapMemory`，RedisMemory/MysqlMemory/ChatManager 为 mock | 租约失效、回填竞态、失败持久化不发布、清空顺序；`ChatManager` 仅替身 | 真实 Memory/DB |
| `RedisCacheRegressionTest` | `test.redis.port` 门控；真实 Redis，`MysqlMemory`/`ChatManager` 为 mock | 写 `chat:history:{chatId}`、版本 key、城市码 data+version、合成 `hash-*`/`lock-*`/`helper-lock-*` 等，`@AfterEach` 删除 `keys` 清单；暂存键 `chat:history:staging:{chatId}:{uuid}` 不在清单里，正常由 Lua `RENAME`/`DEL` 消费，异常路径尝试显式删除，1 分钟 TTL 只有在 `MULTI` 里的 `EXPIRE` 实际成功时才是兜底 | 对话主链、Agent、模型 |

**`ChatGuardRedisIntegrationTest` 的清理范围要特别小心**：`classificationCacheUsesTtlAndUnambiguousContextKeys` 里的 `redis.keys(CHAT_CLASSIFY_CACHE_PREFIX + "*")` 不是“只取本测试写入的那个 key”，而是把**该 Redis 实例上所有匹配前缀的 key** 加入 `keys`，随后 `@AfterEach` 统一 `delete`。因此它只适合一次性、专用、没有其他数据的 Redis；在共享实例上运行可能删掉别人的分类缓存，也可能因为预先存在的匹配 key 让 `assertEquals(1, cacheKeys.size())` 失败。若断言在 `keys.addAll` 之前失败，测试写入的 `chat:classify:*` 不在清单里；其 TTL 成功设置且未被改写时才可由约 6 小时过期兜底；若删除失败或进程被终止，同样不能保证已删除。两个限流测试写入的是随机 `chat:ratelimit:{user}`，按精确 key 清理，若过期时间成功设置且未被改写，可由窗口 TTL 兜底。

**`RedisCacheRegressionTest` 的整类影响**：每个测试在 `@BeforeEach` 生成随机 chatId 并登记 `chat:history:{chatId}` 与版本 key；测试还会创建 `amap:city_code:{随机城市}` 及其 version、`hash-*`、`lock-*`、`helper-lock-*`。清理只删 `keys` 清单，不是清库。暂存键不在清单中；`RedisMemory#overwriteIfLeaseValid` 先用 `MULTI` 执行 `RPUSH` + `EXPIRE`（1 分钟），再由 Lua 成功时 `RENAME`、租约失效时 `DEL`；catch 中再尽力删除。若 `EXPIRE` 没有实际生效（或进程在异常路径中退出），不能把“1 分钟 TTL”当作必然兜底。该测试也必须使用一次性专用 Redis；这与 S3/S4 已核验的“暂存键 TTL 是兜底、不是清理保证”口径一致。

**Python 评测脚本**：`src/test/llm_judge_eval.py` 不是 JUnit 测试，也不在 Maven 测试生命周期内。入口是 `python src/test/llm_judge_eval.py`，可加 `--cases`、`--out`、`--report-txt`、`--max-side-effect`、`--timeout`。它从 `SUT_BASE_URL`（默认 `http://localhost:8080`）向 `SUT_CHAT_PATH`（默认 `/chat/v2/c/{id}`）发 `POST`，`Accept: text/event-stream`，用 `SUT_TOKEN` 作为 Bearer token，按行解析 `data:` 后的 JSON 事件；读取 `--cases`（默认 `src/test/llm_judge_cases.jsonl`），可用 `--out` 写 JSON、`--report-txt`（默认 `src/test/llm_judge_report.txt`）写文本报告，`--max-side-effect` 控制跳过高风险用例，`--timeout` 是每次 HTTP 调用的超时（默认 180 秒）。命中 `confirm` 且用例配置 `hitl.on_confirm_user_reply` 时，它还会向 `/chat/v2/r/{id}` 发第二次请求。若配置了 `JUDGE_API_KEY`，它再把事件摘要发给 OpenAI 兼容的 judge 端点打分。

这个脚本**能尝试评测**黑盒可见的分类标签、工具启动文案、message/notify/error/confirm 事件序列、最终文本与主观 rubric；**依赖**真实服务、有效 token、真实模型/工具/Redis/MySQL，并可能创建会话、写入消息/订单/工单等业务数据、调用付费模型或 judge API、覆盖报告文件，因此只能在明确隔离的环境和授权下运行。它**不能证明**内部锁是否释放、消息是否落库、线程归属或断线后的后台行为；judge 分数也不是确定性断言。此外按源码静态阅读，`call_chat` 对 `/c` 与 `/r` 都只发送 `prompt`（以及 `ChatParamDTO` 未声明的 `model`），`/r` 不会带 `confirmationId`/`confirmed`，所以涉及人工确认的用例无法满足当前 `OrderConfirmation#confirm` 的参数要求，不能据此证明确认链正常。**本章未运行该脚本，也未发任何 HTTP/模型请求。**

在**已检索的 Java 测试类**中，没有 `ChatController` 或完整 chat/resume HTTP→模型→SSE 链路测试；这不等于 `src/test` 下没有其他评测入口——上面的 Python 脚本就是例外。若做隔离实验，只能按各测试自己的门控、在一次性专用 Redis/MySQL 上运行，并逐项核对上表的 key 与清理；本章不提供可直接运行的服务端命令，也未运行。

---

## 十、动手验证：只读追踪

**练习 A：定位校验阶段**

打开 `ChatRequestRunner#run`，指出哪些语句在 `Flux.defer` 之前、哪些在内部；若传 `"bad-id"`，异常在哪里抛？归属失败又在哪里变成 `Flux.error`？

<details>
<summary>练习 A 参考答案</summary>

`UUID.fromString(chatId)` 在 `Flux.defer` 之前，非法 ID 在 Controller 调用 `run()` 时就同步抛异常；`chats.assertOwned(...)` 在 `Flux.defer` 的供应函数内部，只有下游订阅后才执行。归属异常被外层 catch 捕获：先按 `lock.isHeldByThread(owner)` 尝试 `unlockAsync(owner)`，再返回 `Flux.error(e)`。两者所处阶段不同，一个在响应创建前，一个在订阅期；后者也不等于 HTTP 403。
</details>

**练习 B：数订阅者**

`publish().autoConnect(2)` 为什么需要两个订阅？**保留 `autoConnect(2)` 不变**，如果删掉 Runner 的 `events.subscribe(ignored -> {}, ignored -> {})`，只剩 HTTP 下游订阅，会发生什么？

<details>
<summary>练习 B 参考答案</summary>

`autoConnect(2)` 要等两个订阅者才连接上游。Runner 先 `events.subscribe(...)` 建立内部订阅（第 1 个），HTTP 下游是第 2 个；第二个出现时连接建立，`doOnSubscribe` 才提交后台任务。若删掉内部订阅且仍保留 `autoConnect(2)`，订阅计数最多只有 1，上游**根本不会连接**：`doOnSubscribe` 不执行、`chatExecutor` 不提交任务、sink 没有业务事件，`doFinally` 也没有机会运行；不能直接说“上游已运行，断开后取消”。只有假设把阈值改成 `autoConnect(1)`，才轮到“内部订阅单独触发连接、HTTP 断开后连接仍在”的另一种场景；两者不能混为一谈。实际 Reactor 调度未实测。
</details>

**练习 C：三种锁/状态**

单轮执行锁、会话 `locked` 状态、限流 ZSet 各保护什么？列出 key 与一次请求里的调用位置。

<details>
<summary>练习 C 参考答案</summary>

执行锁：`chat:execution:{chatId}`，`ChatRequestRunner#run` 订阅期获取，连接后的 `doFinally` 发起释放（不保证成功），保护同一 chat 的一轮后台执行；会话状态：DB `sys_chat.locked` 与 Redis `chat:info:{chatId}` 的 `locked` 字段，`ChatManager#lockChat/#isLocked`，`ChatServiceImpl#chat` 开头检查，`/r` 不检查；限流：`chat:ratelimit:{userId}` ZSet，`ChatRateLimiter#tryAcquire` 在 `canStartChat` 调用。三者互不替代。
</details>

**练习 D：锁真的释放了吗**

T1 的 `doFinally` 已触发、`unlockAsync(owner)` 已调用；能断言锁已释放吗？若 T2 此时抢锁成功，说明什么？

<details>
<summary>练习 D 参考答案</summary>

不能。`unlockAsync` 是异步调用，成功与否取决于 Redis 与响应；异常/响应丢失时结果未知。若获取锁时曾超时，迟到成功补偿和 catch 的 `isHeldByThread` 检查还可能各自尝试一次解锁，结果同样未观察。T2 抢锁成功只说明那一刻锁键可被获取——可能是 T1 已成功释放，也可能是租期失效、看门狗停止或被其他路径改动；不能反推 T1 的业务工作已经结束或落库完成。
</details>

**练习 E：这一轮到底保存了什么**

分别判断以下终态是否写了 `sys_chat`、是否保存聊天消息：① 首轮分类 `DANGER`；② 首轮分类 `DAILY` 但模型流报错；③ `OrderAgent` 发出 `confirm` 暂停；④ 旧 `classRedis=ORDER` 时新分类 `DAILY`；⑤ `executor.execute` 被拒绝。

<details>
<summary>练习 E 参考答案</summary>

① `DANGER` 在 `initChat` 之前返回：不写 `sys_chat`，不保存消息。② 首轮非 DANGER 会先 `initChat`，所以 `sys_chat` 可能已写；模型流错误回调只发 error/错误终态，不调用 `saveNewMessages`，本轮消息可能不在 DB。③ `handleNotifyUser` 先 `saveNewMessages`，再发 confirm/complete：元数据已有，暂停前消息已保存；若保存本身抛异常则另当别论。④ 旧分类分支不写新 classification、不走 switch，但仍调用 `OrderAgent`；首轮可能已在 `initChat` 写元数据，消息由 `OrderAgent` 成功收尾时保存。⑤ 执行器拒绝只 `tryEmitError`，没有业务调用、没有 `initChat`、没有消息保存，也没有显式解锁。
</details>

**练习 F：GET、POST restore 与 `/r` 的区别**

用户看到“恢复会话”按钮。`GET /chat/v2/restore`、`POST /chat/v2/restore`、`POST /chat/v2/r/{id}` 各做什么？哪些条件不满足会失败？

<details>
<summary>练习 F 参考答案</summary>

`GET` 只判断候选：最近未清空会话、Redis `chat:info` 的 `classification=ORDER`、没有 `OrderId`；命中只把该 key 续期 60 分钟，不返回历史、不解锁，返回布尔值。`POST` 用同一候选，命中时 DB/Redis `locked=false` 并 `PERSIST`，再读最近消息；无候选抛 404。`/r` 是订单确认后的继续执行，走 `OrderConfirmation` 与 `OrderAgent#resume`，不负责会话恢复。历史清空（`clearChat`）当前没有对外接口；HTTP 显式锁定是 `POST /chat/v2/lock/{id}`（置 `locked=true` 并带 60 分钟 TTL），此外下单成功后 `OrderTool#createOrder` 会自动调用 `ChatManager#lockChat`，这是“已下单会话”进入锁定态的真实业务来源。`ChatStatusTool#lockSession` 虽然带 `@Tool`，但没有注册进任何 Agent，属于“类存在但走不到”。候选依赖 Redis 未丢分类数据，Redis 缺失时 GET 返回 false、POST 返回 404。
</details>

---

## 十一、一页复习

- **入口**：`/c` 新/普通对话（allowNew=true），`/r` 订单确认恢复（allowNew=false，走 `OrderConfirmation` + `OrderAgent.resume`）；都返回 `Flux<AgentEvent>`。`/r` 不是“会话恢复”。
- **身份**：Token → `UserTokenContext`（ThreadLocal）→ Controller 显式取 userId → Runner/Service/Agent 显式传参；后台线程不自动继承。
- **阶段**：UUID 同步校验（创建期）→ 抢锁+归属（订阅期）→ 内部订阅 + HTTP 第二个订阅 → 连接、`doOnSubscribe`、提交 `chatExecutor` → 事件流 → 终态 `doFinally` 发起解锁。
- **订阅边界**：`autoConnect(2)` 未凑齐两个订阅时上游不连接，后台不启动，`doFinally` 也不会运行；此时“断开”不能解释成“取消上游”。
- **锁**：`chat:execution:{chatId}` 不等待、看门狗、`get(3s)` 不是总超时；超时补偿与 catch 可能重复尝试解锁；`doFinally` 发起解锁不等于成功释放。
- **会话元数据**：首轮分类成功且非 DANGER 后 `initChat` → `ChatInfoService#insertChat` → `chatMapper.insert` 写 `sys_chat`；DANGER、分类失败、限流等更早结束的分支不写。
- **聊天消息**：不是请求到达即落库；四个 Agent 在成功收尾的完成回调里 `saveNewMessages`。工具循环中间轮、模型错误回调不保存；`handleNotifyUser` 确认暂停**先保存再发 confirm**；`OrderAgent#resume` 会先补存工具应答。
- **会话状态与恢复**：`chat:info:{chatId}` 的 `locked` + DB 回退；`GET /restore` 只判断候选并续期 60 分钟，`POST /restore` 才 `locked=false` + `PERSIST`，`/lock` 置 `true` + TTL；`clearChat` 当前没有对外接口。
- **限流**：`chat:ratelimit:{userId}` 滑动窗口；超限发 `notify` 并 complete，不是 HTTP 429；Redis 异常放行。
- **事件**：`message` / `tool_start` / `error` / `confirm`（工厂名 `comfirm`）/ `notify`；payload 可为字符串或 JSON 字符串；所有发送都是 `tryEmit*` 尝试，不保证客户端收到。
- **旧 ORDER 分支**：`classRedis=ORDER` 时，新分类为 DAILY/SUPPORT/OTHER 会发换话题提醒并仍调用 `OrderAgent`；新分类为 ORDER 时不发换话题提醒，然后 `return`；不写新 `classification`、不进 `switch`。
- **超时**：模型流每次 3 分钟、RAG/天气局部超时；**没有整轮总时限**；Java 逻辑终止不等于网络停止。
- **断开**：连接已建立时内部订阅保持上游，后台可继续；连接未建立时后台未启动；客户端都看不到释放与落库结果。
- **测试**：只读未运行；两个 Redis 集成测试需 `test.redis.port` 且只能用于一次性专用实例；`ChatGuardRedisIntegrationTest` 的分类测试会按前缀收集并删除实例上全部匹配 key。Java 测试中没有完整 ChatController/SSE 链路；`src/test/llm_judge_eval.py` 是另需真实服务与凭据的评测脚本，本章未运行。

---

## 十二、自测与答案

**1. `POST /chat/v2/c/{id}` 与 `/r/{id}` 在归属校验和路由上有什么不同？**

<details>
<summary>参考答案</summary>

`/c` 调 `run(..., false)`，Runner 与 ServiceImpl 都传 `allowNew=true`，不存在的 chatId 可进入首次对话，并走完整分类/路由链；`/r` 传 `true`，`allowNew=false`，会话必须已存在。`ChatServiceImpl#resume` 只做限流、归属与确认反馈，然后调 `OrderAgent#resume`，不经过分类缓存与 `isLocked` 检查；`break` 由 `OrderAgent#resume` 在确认之后要求为 `resuming`。两者共用同一单轮执行锁。
</details>

**2. 非法 UUID 与归属失败分别在哪个阶段变成什么错误？**

<details>
<summary>参考答案</summary>

非法 UUID 在 `ChatRequestRunner#run` 的 `UUID.fromString(chatId)`、`Flux.defer` 之前同步抛 `IllegalArgumentException`，Controller 还没拿到 Flux；归属失败在 `Flux.defer` 内部，catch 中先按 `isHeldByThread(owner)` 尝试补偿解锁，再返回 `Flux.error(e)`。前者可能进入全局 HTTP 异常映射（未实测），后者是订阅期错误流，也不能保证对应 HTTP 403。
</details>

**3. `tryLockAsync(0, -1, ...)` 与 `get(3, SECONDS)` 分别控制什么？**

<details>
<summary>参考答案</summary>

前者：不等待锁，拿不到立即 false；租期 -1 表示交 Redisson 看门狗续期。后者只是等待异步获取结果的 3 秒上限，不是对话总超时。超时后会注册迟到成功补偿解锁并抛异常；若迟到的结果为 true 则 `unlockAsync(owner)`。
</details>

**4. 为什么 `publish().autoConnect(2)` 加内部订阅？**

<details>
<summary>参考答案</summary>

`autoConnect(2)` 需要两个订阅者才连接上游。Runner 先建内部订阅，HTTP 下游是第二个；第二个出现后才连接、执行 `doOnSubscribe`、提交后台任务。连接建立后，HTTP 断开时内部订阅仍在，`autoConnect` 也不会自动断开连接，所以后台可以继续到 sink 终态。若删掉内部订阅而仍用 `autoConnect(2)`，计数不足，上游根本不连接，不能用“断开后继续”解释；只有改成 `autoConnect(1)` 才是另一个场景。实际调度与取消行为未实测。
</details>

**5. 收到 Flux 返回、后台任务开始、锁释放是一回事吗？**

<details>
<summary>参考答案</summary>

不是。`run()` 返回 Flux 时还没抢锁；抢锁在订阅期；后台任务要等第二个订阅触发 `doOnSubscribe` 才提交；`doFinally` 在流终态时才发起 `unlockAsync`。收到返回不能推出后两步已发生，发起解锁也不能推出已成功释放。
</details>

**6. 限流超限会返回 HTTP 429 或 error 事件吗？**

<details>
<summary>参考答案</summary>

都不会。`canStartChat` 发 `AgentEvent.notify("消息发送过于频繁，请稍后重试。")` 后 `tryEmitComplete`，流正常结束；没有抛异常、没有 HTTP 状态映射。`ChatRateLimiter#tryAcquire` 在 Redis 异常、空 userId 或非正配置时放行。
</details>

**7. 五种 AgentEvent 分别是什么？`confirm` 的 payload 能直接用吗？**

<details>
<summary>参考答案</summary>

`message`、`tool_start`、`error`、`confirm`、`notify`。`confirm` 由 `AgentEvent.comfirm(Object)`（源码拼写）创建，payload 实际常是包含 `confirmationId` 的 JSON 字符串，需要先解析外层事件、再对 payload 做一次 JSON 解析（API 文档已说明，C3 主讲状态机）。
</details>

**8. 用户关闭页面后，后台锁会立刻释放吗？**

<details>
<summary>参考答案</summary>

分情况：连接已建立时，内部订阅保持上游，后台通常继续；只有 sink 终态触发 `doFinally` 才发起解锁，且不保证成功。若后台卡在未受单次模型流超时覆盖的工具调用，锁可能被看门狗续期（前提是 Redisson/Redis 正常）。若外层已订阅并获得锁，但内层第二个订阅未能建立，后台未启动、上游 `doFinally` 不运行，锁可能被获取却没有任务；若外层根本没有订阅，则不会取锁。具体断开交错未实测。客户端断开本身不触发额外的取消业务逻辑。
</details>

**9. 一次对话有没有总超时？模型流 3 分钟意味着整轮最多 3 分钟吗？**

<details>
<summary>参考答案</summary>

没有整轮总时限。`AgentStreams#subscribe` 的 3 分钟是**每次模型流调用**的超时，每次 `runLoopStep` 都重新计时；工具循环可能多轮，累计远超 3 分钟。另有 RAG 默认 8000ms、天气配置 5000ms 等局部超时，`ChatRequestRunner` 的 3 秒只是锁结果等待。Java 逻辑终止不等于底层网络停止。
</details>

**10. 下面哪项是源码事实？（A）后台线程自动继承 `UserTokenContext`；（B）`ChatManager#isLocked` 与执行锁是同一把；（C）限流超限返回 HTTP 429；（D）UUID 校验在 `Flux.defer` 之前。**

<details>
<summary>参考答案</summary>

D。A 错：UserTokenContext 是普通 ThreadLocal，无 TaskDecorator，身份靠显式参数与 ToolContext 传递；B 错：会话状态锁是 Redis Hash/DB 字段，执行锁是 Redisson `chat:execution:*`；C 错：超限是 notify + complete；D 对：`UUID.fromString` 在 `Flux.defer` 之前同步执行。
</details>

**11. 用户点“拒绝”和提交一个无效确认编号，分别会发生什么？**

<details>
<summary>参考答案</summary>

有效拒绝：`confirmationId` 匹配、`confirmed=false`，CONFIRM Lua 不校验报价年龄，返回 1，置 `break=resuming`、`confirmationAccepted=no`、删除创建权限；`ChatServiceImpl#resume` 生成拒绝 feedback，`OrderAgent#resume` 在检查通过后，仅在历史中尚无对应应答时补存，再继续模型循环。拒绝本身是有效确认，后续仍可能失败或不产生可见 message。无效编号/状态不符/接受已过期：CONFIRM 返回 0，`confirm` 抛 `BusinessException(409)`，被后台 `guard` 转成通用 error 事件和错误终态；脚本返回 0 前不改状态。缺字段则可能先遇到 400（prompt 非空）或“消息内容不能为空”（prompt 为空且字段缺失）。
</details>

**12. `GET /chat/v2/restore`、`POST /chat/v2/restore` 与 `POST /chat/v2/lock/{id}` 的副作用分别是什么？**

<details>
<summary>参考答案</summary>

`GET` 只判断候选（最近未清空会话、Redis `classification=ORDER`、无 `OrderId`），命中时把 `chat:info` 续期 60 分钟，返回布尔值；不写 DB、不解锁。`POST` 用同一候选，命中时 DB/Redis `locked=false` 并 `PERSIST`，再读消息；无候选 404。`/lock` 先 `assertOwned(false)`，再把 DB/Redis `locked` 设为 true，并给 Redis key 设 3600 秒 TTL。`POST /r/{id}` 是订单确认恢复，不改这些字段；`clearChat` 虽有方法但当前没有对外接口。
</details>

**13. 会话旧分类是 ORDER，用户突然问天气，代码会怎么写分类、走哪个 Agent、消息是否保存？**

<details>
<summary>参考答案</summary>

`memory.get` 读出非空历史与 `classRedis=ORDER`；分类模型假设返回 `DAILY`，先发 `notify("DAILY")`。历史非空，不调用 `initChat`。随后进入旧 ORDER 提前分支：因为新分类不是 ORDER，发换话题提醒，仍调用 `orderAgent.invoke(...)` 并 `return`；不写新的 `classification`，不进入 `switch`。消息是否保存取决于 `OrderAgent` 是否成功收尾：纯文本成功会 `saveNewMessages`，模型错误/提前结束则不能保证。若分类是 DANGER，则在分支前结束，不调用 OrderAgent。
</details>

---

## 十三、常见误区与导航

### 13.1 常见误区

- 把“Controller 返回了 Flux”当成“后台已经在跑”：后台在第二个订阅触发连接后才提交；若只有内部订阅，后台根本不启动。
- 把 `doFinally` 调用当成“锁已成功释放”：它是异步发起，结果未知；连接未建立时它不会运行。
- 把会话 `locked` 状态、单轮执行锁和限流 ZSet 混为一谈：保护对象、key、生命周期都不同。
- 以为关闭页面会立即停止工具调用或释放锁：源码没有这个保证；还要区分连接是否已建立。
- 把 SSE `error` 事件、Flux `onError`、HTTP 状态和业务失败画等号：它们处在不同层级，流开始后 HTTP 状态通常无法修改。
- 把限流提示当 HTTP 429：实际是 `notify` 事件 + 正常结束。
- 把用户消息当成立即落库：真实保存在 Agent 收尾时的 `saveNewMessages`；但确认暂停会先保存再发 confirm，不能反过来说“暂停一定没保存”。
- 把“用户拒绝”当成“确认失败”：有效 `confirmed=false` 是成功记录拒绝；400/409 是缺参数或编号/状态无效。
- 把 `GET /restore` 当成分解锁：它只续期 60 分钟；`POST /restore` 才 `locked=false` + `PERSIST`。
- 把 `POST /r/{id}` 当成会话恢复：它是订单确认后的继续执行。
- 把旧 `classRedis=ORDER` 分支当成会写新分类：它直接调用 `OrderAgent` 并 `return`，不写 `classification`、不进 `switch`。
- 把 `tryEmitNext/Complete/Error` 当成必然成功：返回值未检查，客户端可能收不到。
- 把线程本地身份当自动传播：后台靠显式参数与 ToolContext；模型回调/保存也不保证在 `chat-request-*`。
- 把 3 分钟模型流超时当整轮总时限：没有全局 deadline。
- 把测试源码断言说成“测试通过”：本章没有运行任何测试。

### 13.2 导航与源码入口

- **上一章**：[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)——锁令牌与释放条件的通用口径。
- **下一章**：[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)——分类缓存、四类 Agent 与工具循环。
- **相关专题**：[C3 订单确认、恢复与消费](03-order-confirmation.md)、[C4 三层记忆与工具结果反查](04-memory-and-tool-results.md)；本章只提供最小必要上下文。
- **已通过的前置**：[第 4 章](../00-foundations/04-redis-flux-and-sse-basics.md)、[第 6 章](../01-auth/02-authorization-and-defense.md)。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；API 文档、配置与测试写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 流式入口与身份 | `controller/ChatController.java#chat/#resume`；`interceptor/AuthTokenInterceptor.java#preHandle`；`security/UserTokenContext.java`；`aspect/PermissionAspect.java#checkPermission` |
| 请求编排与锁 | `service/base/ChatRequestRunner.java#run`；`config/AsyncWorkConfig.java#chatExecutor`；`util/RedisScripts.java#RELEASE_LOCK_IF_MATCH`（对照 S4） |
| 业务守卫与路由 | `service/impl/ChatServiceImpl.java#chat/#resume/#canStartChat/#resolveClassification`；`agentbase/tool/TicketTool.java`（C2 展开） |
| 会话元数据与消息保存 | `agentbase/chatinfo/ChatManager.java#initChat`；`service/base/ChatInfoService.java#insertChat`；四个 Agent 的 `invoke/#resume/#runLoopStep/saveNewMessages`；`OrderAgent#handleNotifyUser` |
| Agent 流与超时 | `agents/AgentStreams.java#subscribe/#guard`；`agents/DailyAgent.java`、`OrderAgent.java`、`SupportAgent.java`、`FallbackAgent.java` 的 `runLoopStep`/`invoke`/`executeTool` |
| 事件与 DTO | `domain/dto/AgentEvent.java`；`domain/dto/ChatParamDTO.java`；`src/main/resources/api/chat-security.md` |
| 会话归属、恢复与锁定 | `agentbase/chatinfo/ChatManager.java#assertOwned/#isLocked/#lockChat/#getRestorableChat/#restoreChat`；`service/base/ChatInfoService.java#assertOwned/#lockChat/#checkLock/#restoreChat/#retireHistory`；`constant/RedisKeyConstants.java#chatInfoKey` |
| 限流与分类缓存 | `service/base/ChatRateLimiter.java#tryAcquire`；`service/base/ClassificationCache.java`；`util/RedisScripts.java#SLIDING_WINDOW_RATE_LIMIT`；`src/main/resources/application.yaml`（`chat.guard.*`） |
| 天气/检索局部超时 | `agentbase/qweather/config/QweatherConfig.java#qweatherRestClient`；`agentbase/rag/RagService.java#searchAnswers` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/ChatGuardRedisIntegrationTest.java`、`ChatGuardRegressionTest.java`、`ChatServiceRegressionTest.java`、`SecurityRepairRegressionTest.java`、`CacheConsistencyRegressionTest.java`、`RedisCacheRegressionTest.java`；另有非 JUnit 的 `src/test/llm_judge_eval.py` 与 `src/test/llm_judge_cases.jsonl` |
