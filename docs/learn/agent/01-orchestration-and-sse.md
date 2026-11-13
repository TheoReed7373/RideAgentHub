# Agent 编排与 SSE 生命周期：一次对话请求经过哪些关卡

> **学习目标**：把「用户点发送」到「流式输出结束」之间的真实调用链串起来，理解为什么需要单轮执行锁、后台订阅、统一异常出口与 3 分钟超时，并能在面试里解释「客户端断开为什么不提前放锁」。
> **代码基准**：当前工作区 `RideAgentHub` / `com.fancy.rideagenthub`；路径相对 `src/main/java/com/fancy/rideagenthub/`。
> **主要源码**：`service/base/ChatRequestRunner.java`、`agents/AgentStreams.java`、`service/impl/ChatServiceImpl.java`、`agents/` 下 4 个 Agent、`agentbase/tool/ToolNotifySupport.java`、`config/ChatProperties.java`、`config/AsyncWorkConfig.java`、`domain/dto/AgentEvent.java`。

---

## 一、业务背景与问题定义

一次对话不是「把 prompt 丢给模型、把答案返回」这么简单，它同时涉及：会话归属、限流、意图分类、历史读取、工具调用循环、订单确认断点、消息持久化、以及向前端持续推送的 SSE 流。任何一环出错都可能让**流永远不结束**、**同一会话两轮请求交叉写历史**、或**模型悄悄挂住连接**。

如果没有这些保护，一次对话请求会暴露四类问题：异步任务抛出异常后静默丢弃、SSE 流永不结束、锁的生命周期被客户端连接绑架、同一会话两轮请求交叉写历史。当前实现要同时解决四件事：

1. **同一会话一次只跑一轮**（单轮执行锁，与「对话锁定」是两个 key、两种语义）；
2. **流必须有确定的终止条件**（单次模型流 3 分钟超时 + 统一异常出口 + 新增消息数超过 200 的保护阈值 + `sink.tryEmitComplete()`；注意整轮 SSE **没有**总时长上限）；
3. **客户端断开不应中断后台业务、也不应提前放锁**（后台订阅）；
4. **模型不能自授权限、不能编造数据**（提示词即流程约束 + 服务端确认状态机）。

---

## 二、架构设计与模块关系

```mermaid
flowchart LR
    U[前端<br/>Flux 流式响应] --> RLI[RequestRateLimitInterceptor<br/>仅 /auth/**, /order/estimate]
    RLI --> ATI[AuthTokenInterceptor<br/>→ TokenServiceImpl.getToken]
    ATI --> PA[PermissionAspect<br/>@RequirePermission 只校验登录/角色]
    PA --> CC[ChatController<br/>/chat/v2/* 6 端点]
    CC --> CR[ChatRequestRunner.run]
    CR --> LK[("Redisson RLock<br/>chat:execution:{chatId}<br/>看门狗, 无显式 TTL")]
    CR --> OW[ChatManager.assertOwned<br/>三参, 锁内做归属校验]
    OW --> CS[ChatServiceImpl]
    CS --> RL[ChatRateLimiter 限流]
    CS --> CCACHE[ClassificationCache]
    CS --> MM[MessageMemory 三层记忆]
    CS --> AG[Order/Daily/Support/Fallback Agent]
    AG --> TOOLS[agentbase/tool/* 16 类]
    TOOLS --> TN[ToolNotifySupport<br/>Consumer 回调 + 异常隔离]
    AG --> SUB[AgentStreams.subscribe<br/>单次模型流 3 分钟]
    CR --> GD[AgentStreams.guard<br/>包住 chatExecutor 任务]
    GD --> SINK[Sinks.Many<br/>tryEmitNext / tryEmitError]
    SINK --> U
    CR --> DF[doFinally → unlockAsync(owner)]
```

| 模块 | 职责 | 关键事实 |
|---|---|---|
| `ChatController` | 6 个 `/chat/v2` 映射 | 只做参数接收，把执行委托给 `ChatRequestRunner` |
| `ChatRequestRunner` | 单轮执行锁、格式校验、后台订阅、解锁 | 使用 `chatExecutor` 线程池 |
| `ChatServiceImpl` | 分类、守卫、分发、恢复确认 | `resolveClassification()` 内联，无独立 `Classifier` 类 |
| 4 个 Agent | 领域工具循环 | 均 `internalToolExecutionEnabled(false)`；**仅 Daily/Order/Support 三个**有 `history.size() - originalHistorySize > 200` 上限，`FallbackAgent` 无工具、无循环、单轮流式回复 |
| `AgentStreams` | 两个静态方法 | `guard(Sinks.Many<AgentEvent>, Runnable)` 包住同步动作；`subscribe(Flux<ChatResponse>, …)` 内用 `takeUntilOther(Mono.delay(3min))` 给**单次模型流**设上限 |
| `AsyncWorkConfig` | 双线程池隔离 | `ragExecutor` 4/8/32（`rag-search-`）、`chatExecutor` 8/32/64（`chat-request-`）；**无 `@EnableAsync`、全库无 `@Async`、未配置拒绝策略**（默认 AbortPolicy），靠 `@Qualifier` / `@Resource(name=...)` 显式注入 |
| `ToolNotifySupport` | 工具事件回调 | `Consumer<String>` + try/catch 异常隔离；39 处通知调用点 / 14 个类，6 处注入 `AgentEvent.tool` 回调，`agentbase/tool/` 共 16 个类 |

---

## 三、完整执行流程（端到端调用链）

以 `POST /chat/v2/c/{chatId}` 为例：

1. **入口**：`ChatController` 的两个端点返回 `Flux<AgentEvent>`，由 Spring MVC 按协商流式输出（**源码没有 `produces = text/event-stream` 声明**，项目依赖是 `spring-boot-starter-web` Servlet 栈，不是 WebFlux），委托 `ChatRequestRunner.run(...)`。
2. **格式校验**：`UUID.fromString(chatId)` 在 `Flux.defer` 外只做**格式校验**（不要求来自 `/chat/v2/newuuid`）。
3. **单轮锁**：`redissonClient.getLock("chat:execution:" + chatId)`，调用 **`tryLockAsync(0, -1, TimeUnit.SECONDS, owner)`** —— `waitTime=0` 不排队、`leaseTime=-1` 走 Redisson 看门狗续期（**无显式 TTL**）、`owner` 是随机持有者令牌；随后 `acquisition.get(3, TimeUnit.SECONDS)` 的 **3 秒只是等待 Future 的上限**，不是抢锁超时、更不是锁过期时间。超时用 `thenAccept` 补偿解锁，抢锁失败发 `error` 事件并结束流。
4. **锁内归属校验**：`chats.assertOwned(userId, chatId, !resume)`（**三参**，第三参 `allowNew`：`resume` 时为 `false`，禁止在恢复链路上新建会话）；会话不存在 404、**非本人 403**、停用 410。
5. **后台订阅**：`sink.asFlux().doOnSubscribe(...).doFinally(signal -> lock.unlockAsync(owner)).publish().autoConnect(2)`，并在 `:61` 主动 `events.subscribe(...)` 构成第一个订阅者（另一个是前端）。业务在 `doOnSubscribe` 里提交到 `chatExecutor`，因此前端断开既不取消执行、也不提前放锁。
6. **前置守卫**（普通 chat 先检查 isLocked，再调 `ChatServiceImpl.canStartChat`）：参数非空（空 prompt 不消耗限流配额，以 error 事件结束）→ `ChatRateLimiter.tryAcquire`（30 次/60 秒，Redis 异常放行）。
7. **状态检查**：`assertOwned` → `break == yes` 时提示「请先确认或拒绝当前订单」并结束流 → `memory.get(userId, chatId, 100)` → 分类。
8. **分类**：`resolveClassification()` 先查 `ClassificationCache`（`v2` 长度前缀指纹、6h），未命中调分类模型 `qwen3-max-preview`，结果 `normalizeClassification()` 归一化到 5 个标签；异常或无效分类返回 null，上游发路由失败消息并结束；DANGER 直接拦截；只有通过这两关，旧 classification 为 ORDER 才粘滞路由给 OrderAgent。
9. **Agent 工具循环**：Daily/Order/Support 三个 Agent 手动循环（`history.size() - originalHistorySize > 200` 上限）逐轮调用工具、把结果写回上下文；`internalToolExecutionEnabled(false)` 表示由项目自己驱动。`FallbackAgent` 不注册工具、无循环。
10. **事件流**：`AgentEvent` 共 5 类（`message` / `tool_start` / `notify` / `confirm` / `error`，源码工厂方法名是 `AgentEvent.tool`、`AgentEvent.comfirm`）；工具事件来自 `ToolNotifySupport` 的回调，回调异常被隔离。
11. **收尾**：`AgentStreams.guard(sink, action)` 是 **`void` 方法**，捕获同步动作异常后 `tryEmitNext(error)` + `tryEmitError`；`subscribe(...)` 内的 `takeUntilOther(Mono.delay(3min).flatMap(Mono.error(TimeoutException)))` 给**单次模型流调用**设上限（每次工具循环重新 `subscribe`，计时重置，整轮 SSE 无总时长上限）；解锁在 `ChatRequestRunner` 的 `doFinally`。

`POST /chat/v2/r/{id}` 走同一个 `run(resume=true)`，区别在第 8 步之后：`ChatServiceImpl.resume` 先调 `OrderConfirmation.confirm(confirmationId, confirmed)` 做授权，再调 `orderAgent.resume(sink, id, userId, feedback + chatParam.getPrompt())` —— **用户 `prompt` 被原样拼接在固定 feedback 之后**（空 prompt 时自动填「确认/拒绝」），并未切断注入面；真正的授权边界在服务端 `CONFIRM`/`CONSUME` 两段 Lua，而不是「不转发用户原话」。

---

## 四、核心源码解析

**`service/base/ChatRequestRunner.java`（单轮锁与后台订阅，节选）**

```java
Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
var lock = redis.getLock("chat:execution:" + chatId);
long owner = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
var acquisition = lock.tryLockAsync(0, -1, TimeUnit.SECONDS, owner).toCompletableFuture();
boolean acquired = acquisition.get(3, TimeUnit.SECONDS);   // 3s 是 Future 等待上限
if (!acquired) return Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"));
chats.assertOwned(userId, chatId, !resume);                // 三参，锁内校验
Flux<AgentEvent> events = sink.asFlux()
    .doOnSubscribe(s -> executor.execute(() -> AgentStreams.guard(sink, () -> { /* service.chat/resume */ })))
    .doFinally(signal -> lock.unlockAsync(owner))
    .publish().autoConnect(2);
events.subscribe(ignored -> {}, ignored -> {});            // 第一个订阅者 = 后台
return events;                                            // 第二个订阅者 = 前端
```

要点：`autoConnect(2)` 的「2」由 `:61` 的后台订阅与前端订阅凑齐；`unicast()` + `onBackpressureBuffer()` 意味着事件只面向单一订阅者缓冲，`tryEmitNext` / `tryEmitError` 都是非阻塞发射。解锁在 `doFinally`，**不是完成回调**：超时/异常/正常完成都会走到它。

**`agents/AgentStreams.java`（两个静态方法，签名与职责不同）**

```java
public static void subscribe(Flux<ChatResponse> source, Sinks.Many<AgentEvent> sink,
        Consumer<ChatResponse> next, Consumer<Throwable> error, Runnable complete) {
    source.takeUntilOther(Mono.delay(Duration.ofMinutes(3))
            .flatMap(ignored -> Mono.error(new TimeoutException("模型响应超时"))))
        .doOnNext(next).doOnComplete(complete)
        .subscribe(ignored -> {}, failure -> {
            guard(sink, () -> error.accept(failure));
            sink.tryEmitError(failure);
        });
}

public static void guard(Sinks.Many<AgentEvent> sink, Runnable action) {
    try { action.run(); }
    catch (Exception failure) {
        sink.tryEmitNext(AgentEvent.error("处理失败，请稍后重试"));
        sink.tryEmitError(failure);
    }
}
```

`guard` **返回 `void`、不接收也不返回 `Flux`**，它只包住一段同步动作；3 分钟 `takeUntilOther` 在 `subscribe()` 内，靠延时后 `Mono.error(TimeoutException)` 走 error 分支，而不是 `onErrorResume` 转成一条普通消息。由于每个 Agent 每次工具循环都重新调用 `subscribe`（`DailyAgent:119`、`OrderAgent:191`、`SupportAgent:116`、`FallbackAgent:70`），**3 分钟是单次模型流调用上限，整轮 SSE 没有总时长上限**；真正的整轮终止条件是 新增消息数超过 200 的保护阈值、`break/confirm` 断点与 `sink.tryEmitComplete()`。

**`config/ChatProperties.java`（提示词即流程约束）**：`ORDER_AGENT_SYS_PROMPT` 规定「槽位 → `isNewOrderReady` → `getEstRouteAndPrice` → `notifyUser` → `markOrderReadyForCreate` → `createOrder`」的流程 A–D，并强制「依赖历史工具结果必须 `getCalling` 反查」；`DAILY_AGENT_SYS_PROMPT` 有「不得编造」三规则；`SUPPORT_AGENT_SYS_PROMPT` 规定查询重写、Grounding、取消 SOP 与安全升级 SOP。**提示词提供模型流程指导；真正强制执行的权限门禁在服务端代码和 Lua 中**：模型不能自报价格、不能凭记忆编造工具结果、不能自授创建权限（创建权限由 `OrderConfirmation.CONSUME` 一次性消费）。

**`agentbase/tool/ToolNotifySupport.java`**（`:16-34`）：从 `ToolContext` 取出 `Consumer<String>`，`try/catch` 包住 `listener.accept(message)`，失败只 `log.warn` 不外抛。全项目 `notifyToolListener` 有 **39 处调用点 / 14 个类**；真正把回调创建成 `AgentEvent.tool`（`type="tool_start"`）的只有 **6 处**（`DailyAgent:104,170`、`OrderAgent:176,242`、`SupportAgent:101,167`），由 Agent 在工具循环里注入 `TOOL_LISTENER`。`ToolNotifySupport` 本身**不负责**转事件，工具类共 16 个（`ChatStatusTool` 带 `@Tool` 但未注册、`FallbackAgent` 无 toolListener）。

---

## 五、底层原理

- **冷流 / 热流与 Sinks 结构**：`Flux` 默认冷流，每个订阅者都会重新触发一次执行。真实结构是 `Sinks.many().unicast().onBackpressureBuffer()`（`ChatRequestRunner.java:32`）+ `publish().autoConnect(2)`（`:60`），两个订阅者分别是 `:61` 主动建立的后台订阅与前端订阅。业务在 `doOnSubscribe` 里提交到 `chatExecutor`，`tryEmitNext` / `tryEmitError` / `tryEmitComplete` 都是**非阻塞**发射，适合在回调线程里结束流。unicast 意味着事件只面向单一订阅者缓冲，前端断开后事件由后台订阅继续消费。
- **SSE 的真实载体**：项目依赖是 `spring-boot-starter-web`（Servlet 栈，**不是 WebFlux**），`ChatController` 两个端点只返回 `Flux<AgentEvent>`、**没有 `produces = text/event-stream` 声明**，靠 Spring MVC 的返回值协商与 `Accept` 头流式输出。Reactor 只作为流编排 API 使用；把它写成「Spring WebFlux 逐事件序列化」会误导技术栈判断。
- **锁的生命周期与风险**：单轮锁用 Redisson 看门狗且**无显式 TTL**（`tryLockAsync(0, -1, ...)`），释放只放在 `doFinally`。业务流被后台订阅接管后，客户端断开不会触发 `cancel`，锁会一直持到流终止 —— 这保护了「同一会话历史写入」的串行性，但也意味着**若业务流因缺陷永不终止且 `doFinally` 未触发，锁没有 TTL 兜底**。新增消息数超过 200 的保护阈值、`break/confirm` 断点与 `sink.tryEmitComplete()` 因此不只是逻辑收尾，还是死锁兜底。
- **两种「取消」不是一回事**：`takeUntilOther(Mono.delay(3min))` 在 Reactor 层向上游发 `cancel` 并改走 `Mono.error(TimeoutException)` 的 error 分支；RAG 篇的 `Future.cancel(true)` 标记 Future 取消，并不保证中断执行线程，对阻塞 HTTP 调用没有中断点。两者都**不能物理终止底层模型/ES 请求**，但机制不同，不能写成「同理」。
- **ThreadLocal 清理**：鉴权链把用户身份放进 `security/UserTokenContext`，`AuthTokenInterceptor.afterCompletion` 负责清理；异步执行时若跨线程，必须在提交任务前把身份取出，不能依赖 ThreadLocal 传递（见 03 篇）。

---

### 5.1 配置来源与常量类分工

| 配置 | 载体 | 是否被 `application.yaml` 外置 |
|---|---|---|
| System Prompt / 分类提示词 / ORDER 流程 A–D | `config/ChatProperties.java` —— **无 `@Component`、无 `@ConfigurationProperties`、无 `@Value` 的纯静态常量类**，靠 4 个 Agent 与 `ChatServiceImpl` 共 6 处静态导入消费 | 否，改提示词必须改代码 |
| 分类缓存 TTL 21600s、指纹长度 32、限流 30 次/60 秒 | `@ConfigurationProperties(prefix = "chat.guard")` 的 `config/ChatGuardProperties.java`，由 `ClassificationCache` / `ChatRateLimiter` 注入 | **是**（`application.yaml:76-81`） |
| 入口层限流 120 次/60 秒 | `RequestRateLimitInterceptor.java:22` 硬编码 | 否 |
| 3 分钟模型流超时 | `AgentStreams.java:15` 代码常量 | 否 |
| 8 秒 RAG 预算 / 5 秒同步间隔 | `@Value("${rag.search.timeout-ms:8000}")` / `@Scheduled(fixedDelayString="${rag.sync.interval-ms:5000}")` | 有默认值但 yaml 未显式配置 |
| 线程池 4/8/32、8/32/64 | `config/AsyncWorkConfig.java` 普通 `@Bean`，**无 `@EnableAsync`、无 `@Async`、未配置拒绝策略** | 否 |

把两个 `Chat*Properties` 混为一类是常见误读：前者只是提示词常量，后者才是可运行时调整的配置 Bean。队列满时 `executor.execute` 抛 `RejectedExecutionException`，由 `ChatRequestRunner.java:59` 的 `catch (RuntimeException)` 转成 `tryEmitError`；RAG 侧 `supplyAsync` 被拒则直接进异常分支，不会返回降级结果。

### 5.2 错误发生的两个时机与状态码边界

| 时机 | 例子 | 对外表现 |
|---|---|---|
| 控制器调用或 Flux.defer 阶段 | UUID 校验在 defer 外同步执行；锁等待与归属校验在 defer 内 | UUID 参数错误可映射 400；defer 内异常返回 Flux.error。HTTP 能否映射依赖 MVC 处理时机，不保证均为业务状态码 |
| 流开始发送后 | 模型流超时、工具异常、`guard` 捕获的同步动作异常、`chatExecutor` 拒绝任务 | guard 会发通用 AgentEvent.error 再终止；线程池拒绝只 tryEmitError；空 prompt 发 error 后正常 complete，限流发 notify 后 complete，**不能**再改 HTTP 状态码 |

`resume` 还多一层差异：`assertOwned(userId, chatId, !resume)` 的第三参 `allowNew=false`，恢复链路**不允许新建会话**；而 `@RequirePermission` 在切面层只校验登录与角色，会话归属是在 Runner 的单轮锁内校验的 —— 两个层次不能混写。

---

### 5.3 超时术语统一声明（覆盖六～八章的简写）

六章表格「超时 = 代码常量 3 分钟」、七章「模型 3 分钟无响应」、八-2「超时流有 3 分钟上限」、八-6「3 分钟超时未外置」四处均应读作 **单次模型流调用上限**：计时在 `AgentStreams.subscribe()` 内，每次工具循环重新调用即重置。整轮 SSE 的终止条件是 新增消息数超过 200 的保护阈值、`break/confirm` 断点与 `sink.tryEmitComplete()`；单轮锁因看门狗无显式 TTL 只能靠 `doFinally` 释放，因此「**整轮无硬超时**」是需要在面试里主动说明的真实边界。「3 分钟常量未外置」这一条仍成立。

---

## 六、设计决策与方案对比

| 决策 | 当前方案 | 备选与取舍 |
|---|---|---|
| 单轮互斥 | `chat:execution:{chatId}` Redisson 锁 | 用 DB 行锁更重；用「对话锁定」的 `locked` 字段不行 —— 那是业务状态，不是执行互斥 |
| 客户端断开 | 后台订阅，继续跑完并 `doFinally` 解锁 | 绑客户端连接可省资源，但刷新页面会造成两轮并发写历史 |
| 异常出口 | `AgentStreams.guard` 统一转 error 并结束 sink | 各处 `try/catch` 易漏；裸 `CompletableFuture.runAsync` 会静默丢异常、导致 SSE 永不结束 |
| 超时 | 代码常量 3 分钟 | 可外置到配置中心（**可选优化，当前未实现**）；RAG 侧 8 秒是 `@Value` 默认值 |
| 线程池 | `ragExecutor` / `chatExecutor` 隔离 | 共用一个池时，RAG 慢检索会饿死对话请求；隔离是稳定性优先 |
| 工具执行 | 手动循环 + `internalToolExecutionEnabled(false)` | 框架自动执行更省代码，但无法插入「确认断点」「事件通知」「上下文压缩」 |

**面试常见误解**：`break` 状态（HITL）外置在 Redis：begin 写 yes，CONFIRM Lua 写 resuming，OrderAgent.resume 补应答后删除，不是 Agent 内存里的变量；单轮执行锁与「订单创建后锁定会话」完全无关。

---

## 七、完整示例

| 场景 | 输入 | 中间状态 | 输出 |
|---|---|---|---|
| 正常一轮 | `{"prompt":"我要打车"}` | 单轮锁持有 → 分类 `ORDER` → 工具循环 → 写历史 | `message` 事件流，最后 `complete`，`doFinally` 解锁 |
| 等待确认期间追加聊天 | `POST /c/{id}` | `break == yes` | `notify("请先确认或拒绝当前订单，再继续对话")` + 结束流 |
| 客户端断开 | 中途关页面 | 后台订阅继续执行，锁仍持有 | 历史照常落库；流结束后解锁 |
| 模型超时 | 模型 3 分钟无响应 | `takeUntilOther` 触发 | `error` 事件 + 结束流 + 解锁 |
| 工具回调异常 | `ToolNotifySupport` 抛异常 | 被隔离 | 业务工具继续，`tool_start` 事件可能缺失，但不影响结果 |
| 空 prompt | `{"prompt":""}` | 不消耗限流配额 | `error` 事件 + 结束流 |

---

## 八、常见问题与面试延伸

1. **为什么 `autoConnect(2)` 而不是 `subscribe()` 一次？** —— 需要前端与后台两个订阅者共享同一次执行；直接 `subscribe()` 会让冷流被触发两次或让执行绑死在客户端连接上。
2. **单轮锁会不会长期占用？** —— 正常终态由 doFinally 解锁；单次模型流有 3 分钟上限，但分类同步调用、工具调用及整轮 SSE 没有统一硬超时，业务不终止时看门狗仍可能持续续期。
3. **分类为什么不用独立 `Classifier` 类？** —— 分类只是一段提示词 + 缓存 + 归一化，内联在 `ChatServiceImpl.resolveClassification()` 更直接；`CLASSIFIER_*` 常量与 `ClassificationCache` 存在，但没有同名类/Bean。
4. **DANGER 能不能被绕过？** —— `ChatServiceRegressionTest` 断言 DANGER 不能绕过 ORDER 流程守卫、resume 也受限流约束、ORDER 会话粘滞生效。
5. **提示词算不算「实现」？** —— 在这个项目里算：它约束工具调用顺序、强制 `getCalling` 反查、禁止编造；但**服务端**仍用 `RidePricing` 重算价格、`OrderConfirmation` 管创建权限，不依赖模型自觉。
6. **还缺什么？** —— 3 分钟超时未外置、缺少可观测性（trace / token / tool latency）、RAG 物理取消依赖底层客户端；这些都是**可选优化**，不要在面试里说成已有能力。

> 关联：[02 RAG 一致性与预算](02-rag-consistency-and-budget.md)、[03 安全边界](03-security-and-boundaries.md)、[`../redis/12-chat-lock-atomic.md`](../redis/12-chat-lock-atomic.md)、[`../redis/25-agent-hitl-state.md`](../redis/25-agent-hitl-state.md)。
