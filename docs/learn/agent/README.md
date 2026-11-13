# Agent 学习笔记

本目录用于整理本项目 AI Agent 层（`src/main/java/com/fancy/rideagenthub/agentbase/`、`agents/`）的学习笔记，与 [`../redis/`](../redis/README.md) 平行。原抬头写的 `com/fancy/taxiagent/` 是历史快照，当前包名已改为 `com.fancy.rideagenthub`。

> **代码版本**：以下主题按当前工作区（`a8f4e33` 安全与一致性改造之后）的真实代码归类。25 篇 Redis 笔记聚焦数据结构与并发原语，Agent 编排 / RAG / 安全边界这三块知识空白由本目录三篇新笔记补齐。

## 已完成的笔记

| 文档 | 解决的问题 | 核心代码 | 状态 |
|---|---|---|---|
| [01 编排与 SSE 生命周期](01-orchestration-and-sse.md) | 一次对话请求从 HTTP 到流式输出经过哪些关卡；客户端断开为何不提前放锁；模型挂起时流如何结束 | `service/base/ChatRequestRunner.java`、`agents/AgentStreams.java`、4 个 Agent、`agentbase/tool/ToolNotifySupport.java`、`config/ChatProperties.java`、`config/AsyncWorkConfig.java` | 已完成 |
| [02 RAG 最终一致性与 8 秒预算](02-rag-consistency-and-budget.md) | 管理接口写入成功后为何不能立刻检索到；双路检索如何在共享预算内融合；答案如何以 DB 为准 | `agentbase/rag/RagService.java`、`agentbase/rag/QaIndexSynchronizer.java`、`mapper/QaSyncJobMapper.java`（实体在 `domain/entity/QaSyncJob.java`） | 已完成 |
| [03 安全边界与状态映射](03-security-and-boundaries.md) | Token 如何变成 `@RequirePermission` 的授权；改密为何使旧令牌失效；支付为何默认拒绝；异常如何映射到 HTTP 状态 | `interceptor/`、`security/`、`aspect/`、`config/PaymentConfig.java`、`exception/GlobalExceptionHandler.java`、`src/main/resources/sql/migrations/` | 已完成 |

Redis 侧的对应笔记见 [`../redis/README.md`](../redis/README.md)（25 篇 + 阅读约定 + 术语规范）。

## 计划覆盖的主题

> 下表把「**当前代码已经实现**」与「**只有骨架、0 调用方**」分开列，避免把设计草案学成现有能力。学习顺序建议：先 01（编排与 SSE）→ 02（RAG）→ 03（安全边界），再回看 Redis 系列中与之互链的 09/12/19/20/22/25 篇。

| 主题 | 代码落点 | 现状 | 对应笔记 |
|---|---|---|---|
| 四类 Agent 的职责划分与路由 | `agents/DailyAgent`、`OrderAgent`、`SupportAgent`、`FallbackAgent`；`service/impl/ChatServiceImpl.resolveClassification()`（**内联分类**，无 `Classifier` 类） | 已实现（`CLASSIFIER_*` 提示词 + `ClassificationCache` 缓存） | [01](01-orchestration-and-sse.md)、[redis/21](../redis/21-llm-classification-cache.md) |
| 工具调用（Function Calling）注册与手动循环 | `agentbase/tool/`；4 个 Agent 均 `internalToolExecutionEnabled(false)`，但**只有 Daily/Order/Support 三个**有 `history.size() - originalHistorySize > 200` 的循环上限；`FallbackAgent` 无工具、无循环、单轮直接流式回复 | 已实现 | [01](01-orchestration-and-sse.md) |
| 工具事件通知链路 | `agentbase/tool/ToolNotifySupport.java` 的 `Consumer<String>` 回调与异常隔离；`notifyToolListener` 调用点共 **39 处 / 14 个类**，真正注入 `AgentEvent.tool` 回调的只有 **6 处**（Daily/Order/Support 各 2），`agentbase/tool/` 共 16 个类 | 已实现 | [01](01-orchestration-and-sse.md) |
| 记忆系统的分层存储 | `agentbase/memory/`：**Heap(L1) → Redis(L2) → MySQL(L3)**；工具结果另有 `ToolResponseMemory`（Redis + MySQL 两级） | 已实现；MongoDB 只存订单路线轨迹，ES 只服务 RAG 知识库 | [redis/19](../redis/19-multi-level-cache.md)、[redis/20](../redis/20-tool-response-cache.md) |
| 消息解析、消息窗口与 Advisor 机制 | `agentbase/memory/MessageParser`（TOOL 消息保存全文）、`MessageWindow.tail`（工具调用批次配对） | 已实现；`MemoryAdvisor` 返回 `null`、`ESMemory` 为空类，**均 0 调用方** | [redis/19](../redis/19-multi-level-cache.md)、[redis/20](../redis/20-tool-response-cache.md) |
| RAG 检索与索引一致性 | `agentbase/rag/`（`qa_knowledge_base` 向量 + BM25 + RRF） | 已实现（物理取消为部分实现：逻辑 `cancel(true)`） | [02](02-rag-consistency-and-budget.md) |
| 外部 API 接入（高德 / 和风天气） | `agentbase/amap/`、`agentbase/qweather/` | 已实现；`agentbase.amap` 的 String 字段用 `StringOrEmptyArrayToNullDeserializer` | 待补充（不影响主线） |
| SSE 生命周期、单轮执行锁、双线程池 | `ChatRequestRunner`、`AgentStreams`、`config/AsyncWorkConfig.java` | 已实现 | [01](01-orchestration-and-sse.md)、[redis/12](../redis/12-chat-lock-atomic.md) |
| 人工确认状态机与 `confirmationId` | `service/base/OrderConfirmation.java`（`CONFIRM`/`CONSUME` 两段 Lua）、`OrderAgent.resume()` | 已实现（15 分钟报价有效期、一次性消费、幂等重放） | [01](01-orchestration-and-sse.md)、[redis/11](../redis/11-two-phase-confirm-token.md)、[redis/25](../redis/25-agent-hitl-state.md) |
| 提示词即流程约束 | `config/ChatProperties.java`（ORDER 流程 A–D、`getCalling` 强制反查、DAILY 不得编造、SUPPORT 查询重写/Grounding/SOP） | 已实现 | [01](01-orchestration-and-sse.md) |
| 鉴权链、会话版本、限流、支付门禁、异常映射 | `interceptor/`、`security/`、`aspect/`、`config/PaymentConfig.java`、`exception/`、`sql/` | 已实现；**支付渠道核验为部分实现**（仅接口 + 默认 503） | [03](03-security-and-boundaries.md) |
| 多级 Advisor / ES 记忆扩展点 | `MemoryAdvisor`、`ESMemory` | 📐 **设计中（骨架）**：空实现、0 调用方，不要当作现有能力 | 待补充 |
