# RideAgentHub 项目教材（新）

这是一套从业务场景出发、逐步进入源码的中文教材，面向"会写 Java、刚接触 Spring Boot、但没读过这个项目"的读者。

教材不按技术名词组织，也不做面试问答。每一章的目标是：读完你能自己说清一件事怎么做成、代码在哪、失败了会怎样。

> 26 章均已通过章节审查；2026-11-22 又做了一次独立全量复核（逐章对照当前源码、复算示例、检查链接与先修），结论与遗留项见[最终检查记录](_meta/review-2026-11-22.md)。

## 一、读之前需要什么

**必须**：

- 能读懂 Java 类、接口、注解、泛型、`record`、`List/Map` 的基本用法；
- 知道 Spring Boot 里有 `@RestController`、`@Service`、依赖注入这类东西（不需要会写，能看懂即可）；
- 会用 Git 查看某个文件的历史，能在 IDE 里"跳转到定义"。

**不要求**：

- 不需要懂 Redis、Elasticsearch、MongoDB、消息队列；
- 不需要懂大模型、Agent、RAG、向量检索；
- 不需要能把项目跑起来（第 1、2 章都不需要）。

前两章假设你只有上面这些基础。后续章节会在第一次使用某个概念时就地解释，并在需要时指向"先读哪一章"。

## 二、这套教材想让你获得什么

学完之后，你应该能：

1. **讲清业务**：这个系统有哪几类角色，一次下单、一次工单、一次对话分别是怎么走完的。
2. **追踪源码**：给一个 HTTP 接口或一句用户消息，能从入口一路找到落库的位置，并说出中间经过了谁。
3. **解释机制**：为什么需要令牌、锁、缓存、确认令牌、索引同步；它们各自保证了什么、没保证什么。
4. **区分事实**：哪些是代码已经实现的、哪些只是提示词约束、哪些是占位类或未接入的能力。
5. **独立复习**：不靠聊天记录，用每章的一页复习和自测题恢复记忆。

这套教材**不**承诺：

- 不承诺所有外部服务都能跑通（地图、天气、模型、邮件、支付都依赖你自己的凭据）；
- 不承诺"照抄代码就能上线"，它是学习材料，不是部署手册；
- 不把"读过源码"写成"运行验证过"。

## 三、怎么学

每章正文按固定顺序展开，读某一章时按这个顺序看：

```text
学习目标 → 前置知识 → 业务场景 → 最少概念 → 端到端正常流程
→ 源码导读（按执行顺序）→ 失败与边界 → 设计取舍
→ 动手验证（只读练习 / 隔离实验）→ 一页复习 → 自测与答案
```

三个使用建议：

1. **先走完一次正常流程，再看细节**。第 1 章尤其如此：先知道"谁调用了谁"，再关心"为什么加锁"。
2. **自测题先自己写答案，再展开对照**。答案是折叠的，直接看会失去检验作用。
3. **每章末尾的"一页复习"要能独立读懂**。如果离开前后章就看不懂，说明那一章还没写到位，可以记下来。

阅读时间按"能读懂并做完练习"估算，不是浏览时间。第 1 章约 60～80 分钟，第 2 章约 70～90 分钟。

下面所有路径都可直接点击。**路线顺序是教学安排，不等于第四节目录表的排列顺序**：目录把 [第 10 章 超时与恢复](02-orders/04-timeouts-and-recovery.md)（O4）放在 [第 11 章 计价与支付](02-orders/05-pricing-and-payment.md)（O5）之前，课程设计把 O5 排在 O4 之前（先认识计价与支付，再学超时兜底），但**硬前置表并未限定 O5 与 O4 的相对次序**——两者都只依赖已列出的前置章。不要用"按目录顺序读"代替下列顺序，也不要把这条教学安排误读成硬依赖。

### 路线 A：初学（完整顺序）

[第 1 章 业务地图](00-foundations/01-project-map.md) → [第 2 章 请求路径与配置](00-foundations/02-request-path-and-config.md) → [第 3 章 数据存在哪，事务管到哪](00-foundations/03-data-and-transactions.md) → [第 4 章 Redis 与流式响应基础](00-foundations/04-redis-flux-and-sse-basics.md) → [第 5 章 登录与会话](01-auth/01-login-and-session.md) → [第 6 章 权限、归属与防护](01-auth/02-authorization-and-defense.md) → [第 7 章 订单生命周期](02-orders/01-order-lifecycle.md) → [第 8 章 抢单与并发](02-orders/02-grab-and-concurrency.md) → [第 9 章 位置与发现](02-orders/03-location-and-discovery.md) → **[第 11 章 计价与支付](02-orders/05-pricing-and-payment.md)** → **[第 10 章 超时与恢复](02-orders/04-timeouts-and-recovery.md)** → [第 12 章 工单生命周期与权限](03-support-and-cache/01-ticket-lifecycle.md) → [第 13 章 工单池索引与统计缓存](03-support-and-cache/02-ticket-index-and-cache.md) → [第 14 章 缓存问题与并发窗口](03-support-and-cache/03-cache-races.md) → [第 15 章 锁、版本租约与唯一 ID](03-support-and-cache/04-locks-leases-and-ids.md) → [第 16 章 一次对话与事件流](04-chat-and-agent/01-chat-round-and-sse.md) → [第 17 章 分类路由与工具循环](04-chat-and-agent/02-routing-and-tool-loop.md) → [第 18 章 订单确认、恢复与消费](04-chat-and-agent/03-order-confirmation.md) → [第 19 章 三层记忆与工具结果反查](04-chat-and-agent/04-memory-and-tool-results.md) → [第 20 章 外部工具与提示词边界](04-chat-and-agent/05-external-tools-and-prompts.md) → [第 21 章 RAG 检索与答案溯源](05-rag/01-retrieval-and-grounding.md) → [第 22 章 RAG 索引同步与检索预算](05-rag/02-index-sync-and-budget.md) → [第 23 章 三条完整业务旅程](06-practice-and-review/01-trace-three-journeys.md) → [第 24 章 四层实验与清理](06-practice-and-review/02-isolated-labs.md) → [第 25 章 间隔复习与综合题](06-practice-and-review/03-review-and-self-test.md) → [第 26 章 术语、源码与 Key 索引](06-practice-and-review/04-glossary-and-source-index.md)。

这条完整初学路线是 **24 章**（第 1～24 章）。[第 25 章 间隔复习与综合题](06-practice-and-review/03-review-and-self-test.md) 与 [第 26 章 术语、源码与 Key 索引](06-practice-and-review/04-glossary-and-source-index.md) 是**复习与索引拓展**，完成上面 24 章后再用，不混入初学主线（第 25 章本身还依赖 P1、可选 P2，第 26 章可随时反查）。每阶段结束只做该阶段章节的自测题，答错率超过 1/3 就重读该章"源码导读"。阶段划分、时长估计和可检查成果见[课程设计 §六路线 A](_meta/curriculum.md#路线-a初学建议-1012-周每周-46-小时)。

### 路线 B：复习

只读各章"一页复习"，再做[第 25 章综合自测](06-practice-and-review/03-review-and-self-test.md)；答错的题按错题定位表回到对应章的"源码导读"，再用[第 26 章索引](06-practice-and-review/04-glossary-and-source-index.md)从 key / 类名反查章节。不要求重读全书。具体四步见[课程设计 §六路线 B](_meta/curriculum.md#路线-b复习建议-23-天--每周-1-小时)。

### 路线 C：五条专项

公共基础 **B**（可点击）：[第 1 章](00-foundations/01-project-map.md) → [第 2 章](00-foundations/02-request-path-and-config.md) → [第 3 章](00-foundations/03-data-and-transactions.md) → [第 4 章](00-foundations/04-redis-flux-and-sse-basics.md) → [第 5 章](01-auth/01-login-and-session.md) → [第 6 章](01-auth/02-authorization-and-defense.md) → [第 7 章](02-orders/01-order-lifecycle.md)。B 是展开记号，不是新增章节；已经学过的章节只需确认学习目标和自测。

- **Redis 与并发**：B → [第 8 章 抢单与并发](02-orders/02-grab-and-concurrency.md) → [第 12 章 工单生命周期](03-support-and-cache/01-ticket-lifecycle.md) → [第 13 章 工单池索引与统计缓存](03-support-and-cache/02-ticket-index-and-cache.md) → [第 14 章 缓存问题与并发窗口](03-support-and-cache/03-cache-races.md) → [第 15 章 锁、版本租约与唯一 ID](03-support-and-cache/04-locks-leases-and-ids.md) → [第 9 章 位置与发现](02-orders/03-location-and-discovery.md) → [第 10 章 超时与恢复](02-orders/04-timeouts-and-recovery.md)。
- **订单业务**：B → [第 8 章 抢单与并发](02-orders/02-grab-and-concurrency.md) → [第 9 章 位置与发现](02-orders/03-location-and-discovery.md) → [第 10 章 超时与恢复](02-orders/04-timeouts-and-recovery.md) → [第 11 章 计价与支付](02-orders/05-pricing-and-payment.md)。
- **Agent 编排**：B → [第 8 章 抢单与并发](02-orders/02-grab-and-concurrency.md) → [第 9 章 位置与发现](02-orders/03-location-and-discovery.md) → [第 11 章 计价与支付](02-orders/05-pricing-and-payment.md) → [第 12 章 工单生命周期](03-support-and-cache/01-ticket-lifecycle.md) → [第 13 章 工单池索引与统计缓存](03-support-and-cache/02-ticket-index-and-cache.md) → [第 14 章 缓存问题与并发窗口](03-support-and-cache/03-cache-races.md) → [第 15 章 锁、版本租约与唯一 ID](03-support-and-cache/04-locks-leases-and-ids.md) → [第 16 章 一次对话与事件流](04-chat-and-agent/01-chat-round-and-sse.md) → [第 17 章 分类路由与工具循环](04-chat-and-agent/02-routing-and-tool-loop.md) → [第 18 章 订单确认、恢复与消费](04-chat-and-agent/03-order-confirmation.md) → [第 19 章 三层记忆与工具结果反查](04-chat-and-agent/04-memory-and-tool-results.md) → [第 20 章 外部工具与提示词边界](04-chat-and-agent/05-external-tools-and-prompts.md)。其中 [第 15 章](03-support-and-cache/04-locks-leases-and-ids.md) 是 [第 16 章](04-chat-and-agent/01-chat-round-and-sse.md) 与 [第 19 章](04-chat-and-agent/04-memory-and-tool-results.md) 的硬前置，[第 17 章](04-chat-and-agent/02-routing-and-tool-loop.md) 和 [第 11 章](02-orders/05-pricing-and-payment.md) 都在 [第 18 章](04-chat-and-agent/03-order-confirmation.md) 之前。
- **RAG**：[第 1 章](00-foundations/01-project-map.md) → [第 2 章](00-foundations/02-request-path-and-config.md) → [第 3 章 数据存在哪，事务管到哪](00-foundations/03-data-and-transactions.md) → [第 21 章 RAG 检索与答案溯源](05-rag/01-retrieval-and-grounding.md) → [第 22 章 RAG 索引同步与检索预算](05-rag/02-index-sync-and-budget.md)。[第 20 章 外部工具与提示词边界](04-chat-and-agent/05-external-tools-and-prompts.md) 只是 `RagTool` 调用位置的可选拓展，不是硬前置。
- **安全边界（含 Agent 确认）**：[第 1 章](00-foundations/01-project-map.md) → [第 2 章](00-foundations/02-request-path-and-config.md) → [第 3 章](00-foundations/03-data-and-transactions.md) → [第 4 章](00-foundations/04-redis-flux-and-sse-basics.md) → [第 5 章](01-auth/01-login-and-session.md) → [第 6 章](01-auth/02-authorization-and-defense.md) → [第 7 章](02-orders/01-order-lifecycle.md) → [第 8 章](02-orders/02-grab-and-concurrency.md) → [第 11 章](02-orders/05-pricing-and-payment.md) → [第 12 章](03-support-and-cache/01-ticket-lifecycle.md) → [第 13 章](03-support-and-cache/02-ticket-index-and-cache.md) → [第 14 章](03-support-and-cache/03-cache-races.md) → [第 15 章](03-support-and-cache/04-locks-leases-and-ids.md) → [第 16 章](04-chat-and-agent/01-chat-round-and-sse.md) → [第 17 章](04-chat-and-agent/02-routing-and-tool-loop.md) → [第 18 章](04-chat-and-agent/03-order-confirmation.md)。

**硬前置不能跳过**。例如直接读[第 8 章 抢单与并发](02-orders/02-grab-and-concurrency.md)，会因为没有事务、条件更新和 Redis 基础而停在表面。用已有能力替代整章阅读有明确条件：逐条检查该章全部学习目标，完成该章自测且正确率至少 5/6，并且身份来源、事务边界、数据恢复等关键约束题全部正确；任一项不满足就按初学路线补读。这条规则对上表所有章节一致。[第 23 章 P1](06-practice-and-review/01-trace-three-journeys.md) 的前置段已把 [第 17 章](04-chat-and-agent/02-routing-and-tool-loop.md)（C2）从"选读"改为**可替代前置**：C2 仍是 [第 18 章](04-chat-and-agent/03-order-confirmation.md)（C3）的硬前置，因此只有在通过 C2 自测、具备 C2 学习目标所述能力时才能替代整章阅读，不是无条件跳过。课程设计原文的章节顺序与阶段成果见[_meta/curriculum.md §六](_meta/curriculum.md#六三种阅读路线)。

## 四、目录与进度

### 已完成

| 章节 | 内容 | 状态 |
|---|---|---|
| [00-foundations/01-project-map.md](00-foundations/01-project-map.md) | 业务地图：角色、入口、一次真实下单调用链、AI 如何调用已有能力、存储职责与恢复方式 | 已通过 P0 复审（2026-11-14） |
| [00-foundations/02-request-path-and-config.md](00-foundations/02-request-path-and-config.md) | 请求路径与配置：URL 到方法、参数绑定、DTO/Entity/VO/Result、注入、HTTP 与业务码、配置来源、序列化 | 已通过 P1-1 复审（2026-11-14） |
| [00-foundations/03-data-and-transactions.md](00-foundations/03-data-and-transactions.md) | 数据关系与事务：主键与业务 ID、无外键的关联、当前创建订单链无事务、接单事务、条件更新与并发、提交后回调、跨库补偿边界 | 已通过 P1-2 复审（2026-11-14） |
| [00-foundations/04-redis-flux-and-sse-basics.md](00-foundations/04-redis-flux-and-sse-basics.md) | Redis 与流式响应基础：六类数据结构、TTL/成员心跳/恢复边界、Lua 原子范围、Servlet 中的 Flux/SSE、单轮执行锁与终态 | 已通过 P1-3 复审（2026-11-14） |
| [01-auth/01-login-and-session.md](01-auth/01-login-and-session.md) | 登录与会话：验证码五 key 与两种消费模式、注册/登录/重置链路、令牌签发与固定 TTL、拦截器与 ThreadLocal、会话版本与凭据指纹双栅栏、改密/注销后的旧会话 | 已通过 P2-1 复审（2026-11-14） |
| [01-auth/02-authorization-and-defense.md](01-auth/02-authorization-and-defense.md) | 权限、归属与防护：身份/角色/归属分层、端点拒绝层对照、订单/工单/会话越权案例、IP 限流与聊天限流对比、异常与流式边界、用户名布隆现状 | 已通过 P2-2 复审（2026-11-14） |
| [02-orders/01-order-lifecycle.md](02-orders/01-order-lifecycle.md) | 订单生命周期：报价/创建的价格来源、七状态与真实迁移、接单/到达/开始/结束、取消与违约金、支付门禁、事务/锁/Redis/Mongo 副作用、HTTP 与 Agent 下单对比 | 已通过 P3-1 复审（2026-11-15） |
| [02-orders/02-grab-and-concurrency.md](02-orders/02-grab-and-concurrency.md) | 抢单与并发：订单锁/司机行锁/Redis 占位/Lua/DB 条件更新的真实顺序、同单与同司机竞态时间线、缓存陈旧与失败矩阵、测试覆盖 | 已通过 P3-2 复审（2026-11-15） |
| [02-orders/03-location-and-discovery.md](02-orders/03-location-and-discovery.md) | 位置与发现：司机 GEO/成员级心跳/下线与过期清理、附近订单池失效/重建/回表校验、乘客位置 Hash、GEO 与 Hash 故障边界 | 已通过 P3-3 复审（2026-11-15） |
| [02-orders/04-timeouts-and-recovery.md](02-orders/04-timeouts-and-recovery.md) | 超时与恢复：ZSet 待办/领取/重排、无人接单/未到达/未支付三条迁移、数据库补偿范围、竞争与故障时间线、测试覆盖 | 已通过 P3-4 复审（2026-11-15） |
| [02-orders/05-pricing-and-payment.md](02-orders/05-pricing-and-payment.md) | 计价与支付：服务端公式与手算、预估/创建/改目的地/结束行程的金额来源、支付门禁与默认 503、取消费与字段归属、测试覆盖 | 已通过 P3-5 复审（2026-11-15） |
| [03-support-and-cache/01-ticket-lifecycle.md](03-support-and-cache/01-ticket-lifecycle.md) | 工单生命周期与权限：0～4 状态机、发起人/处理人/角色、可选订单归属、INCR 编号、条件更新与行锁、提交后缓存失效、聊天结果与无退款边界、测试覆盖 | 已通过 P4-1 复审（2026-11-15） |
| [03-support-and-cache/02-ticket-index-and-cache.md](03-support-and-cache/02-ticket-index-and-cache.md) | 工单池索引与统计缓存：score 编码与手算、ZSet/ready/版本/重建锁、整页回退与回表校验、统计缓存重建/等待/双删/降级、四类测试阅读与隔离实验边界 | 已通过 P4-2 再次复审（2026-11-16） |
| [03-support-and-cache/03-cache-races.md](03-support-and-cache/03-cache-races.md) | 缓存问题与并发窗口：穿透/击穿/雪崩判据、城市码空值哨兵/墓碑/版本、用户名布隆只写不读、统计互斥锁与 500ms 双删、竞争时间线与测试覆盖 | 已通过复审（P4-3，2026-11-16） |
| [03-support-and-cache/04-locks-leases-and-ids.md](03-support-and-cache/04-locks-leases-and-ids.md) | 锁、版本租约与唯一 ID：UUID 令牌锁/Redisson RLock/回填版本对照、订单锁事务回调、工单号与雪花 ID、节点租约与竞争时间线 | 已通过复审（P4-4，2026-11-16） |
| [04-chat-and-agent/01-chat-round-and-sse.md](04-chat-and-agent/01-chat-round-and-sse.md) | 一次对话与事件流：身份/入口、UUID 与归属校验、单轮执行锁与订阅边界、会话元数据与消息保存、五种事件、确认拒绝/无效确认、恢复语义、超时与断开、测试阅读 | 已通过复审（P5-1，2026-11-17） |
| [04-chat-and-agent/02-routing-and-tool-loop.md](04-chat-and-agent/02-routing-and-tool-loop.md) | 分类路由与工具循环：分类上下文/缓存指纹/TTL、规范化与 DANGER/旧 ORDER 分支、四类 Agent 与提示词、工具注册/分派、工具回执、循环终止、身份与安全边界、测试阅读 | 已通过复审（P5-2，2026-11-17） |
| [04-chat-and-agent/03-order-confirmation.md](04-chat-and-agent/03-order-confirmation.md) | 订单确认、恢复与消费：报价→暂停→确认→恢复→一次性消费→创建；`quoteId=confirmationId`、`begin` 的真实检查、`CONFIRM/CONSUME` Lua、`/r` 真实阶段、`isConfirmed`/`markOrderReadyForCreate`、创建重算与直接 API 边界、测试阅读；§十五 客服取消预检 | 已通过复审（P5-3，2026-11-17）；§十五 已通过 2026-11-22 复核 |
| [04-chat-and-agent/04-memory-and-tool-results.md](04-chat-and-agent/04-memory-and-tool-results.md) | 三层记忆与工具结果反查：Heap/Redis/MySQL 读写顺序、`getPersisted`、版本租约与 Lua 回填、消息窗口与坏记录、`ToolResponseMemory/getCalling`、清空/恢复与逐测试阅读卡 | 已通过复审（P5-4，2026-11-17） |
| [04-chat-and-agent/05-external-tools-and-prompts.md](04-chat-and-agent/05-external-tools-and-prompts.md) | 外部工具与提示词边界：工具注册/手写分派/服务端授权四层、地理编码与路径规划/天气两条链、三个反序列化器、重试与失败可见性、提示词约束 vs 代码守卫、城市码缓存接线、时间线、逐测试阅读卡 | 已通过复审（P5-5，2026-11-18） |
| [05-rag/01-retrieval-and-grounding.md](05-rag/01-retrieval-and-grounding.md) | RAG 检索与答案溯源：问答数据模型、双路检索与 RRF 手算、回 MySQL 校验、删除/改答案/改问题的读取影响、失败与预算边界 | 已通过复审（P6-1，2026-11-18） |
| [05-rag/02-index-sync-and-budget.md](05-rag/02-index-sync-and-budget.md) | RAG 索引同步与检索预算：写后异步重建、任务领取/失败延后、`version` 无显式比较、共享 deadline 与逻辑取消 | 已通过复审（P6-2，2026-11-19） |
| [06-practice-and-review/01-trace-three-journeys.md](06-practice-and-review/01-trace-three-journeys.md) | 三条完整业务旅程：登录与受保护入口、Agent 订车（确认/恢复/消费/接单/超时竞争）、客服咨询（双路检索回查、改答案/改问题交错、可选工单）与跨旅程反向定位表 | 已通过复审（P7-1，2026-11-20） |
| [06-practice-and-review/02-isolated-labs.md](06-practice-and-review/02-isolated-labs.md) | 四层实验与清理：纯阅读 / 已有测试 / 隔离中间件 / 真实外部服务四层路线，统一实验卡模板、一次性实例身份确认与收尾、门控与结果六分类、跨层对照与 6 道自测 | 已通过复审（P7-2，2026-11-20） |
| [06-practice-and-review/03-review-and-self-test.md](06-practice-and-review/03-review-and-self-test.md) | 间隔复习与综合题：首次/隔日/一周后三轮计划、只读源码路线、八道跨主题综合题与折叠答案、错题定位、一页速查与 P2/P4 导航；已按 P3-R1～R5 返工 | 已通过复审（P7-3，2026-11-20） |
| [术语、源码与 Key 索引](06-practice-and-review/04-glossary-and-source-index.md) | 术语、源码、Redis Key 和按现象反查四个入口，含五道索引练习与 28 个旧专题入口 | 已通过第二次复审（P7-4，2026-11-21） |

### 全书复核

**26 章已全部完成正文、导航、先修链与复习入口；2026-11-22 的独立复核逐章对照了当前源码。** 检查范围、修改清单、旧文错误与剩余未知见[最终检查记录](_meta/review-2026-11-22.md)；逐章事实与源码指向见[证据台账](_meta/evidence.md)。

## 五、约定

- **路径基准**：Java 类在正文中使用简写，基准是 `src/main/java/com/fancy/rideagenthub/`（例如 `controller/RideOrderController.java` 指 `src/main/java/com/fancy/rideagenthub/controller/RideOrderController.java`）；SQL、配置和资源文件写完整仓库相对路径（例如 `src/main/resources/sql/init.sql`）。代码块注释里给的是完整路径。定位优先给"类 + 方法"，因为行号会漂移。
- **状态标注**：涉及能力时使用四种说法——"当前已实现"、"部分实现"、"占位/未接入"、"仅提示词约束"。看到"设计意图"时，它不等于已实现。
- **证据级别**：写"未实测"的地方就是没有运行过；写"静态阅读"表示只读了代码。教材不会把读代码说成跑通过。
- **命令环境**：如无特别说明，命令按 Windows PowerShell 书写。
- **安全**：所有实验都限定在一次性实例或明确隔离的数据集，不给 `FLUSHALL`、清库这类命令。

## 六、和旧资料的关系

`docs/learn/` 下的 25 篇 Redis 专题与 3 篇 Agent 专题仍然保留，它们偏"单点机制 + 面试视角"，可以作为拓展阅读。

新教材不是旧文的改写：业务主线、分层基础、订单生命周期、计价与支付、工单业务、对话事件序列、RAG 数据模型等内容旧文没有，需要新写；旧文已有的机制推导，新教材会在对应章节给出完整解释，不要求你先读旧文。

如果你只有时间读一套，读新教材；如果某一章想看得更深，再去 `docs/learn/` 找对应专题（对应关系记录在 `_meta/coverage.md`，属于协作记录，不是必读）。
