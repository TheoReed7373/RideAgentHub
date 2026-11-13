# RAG 最终一致性与 8 秒检索预算：接口成功 ≠ 立刻可检索

> **学习目标**：解释「管理员改了知识库，为什么检索结果可能仍旧/查不到」，理解事务内持久任务表、`FOR UPDATE SKIP LOCKED` 领取、双路检索 + RRF、共享 deadline 与权威库核对的完整链路。
> **代码基准**：`src/main/java/com/fancy/rideagenthub/agentbase/rag/`（只有 `RagService.java`、`QaIndexSynchronizer.java`）、`mapper/QaSyncJobMapper.java`（**在 `mapper/` 包，不在 `service/` 或 `agentbase/rag/`**）、`domain/entity/QaSyncJob.java`、`controller/RagController.java`、`config/AsyncWorkConfig.java`、`src/main/resources/api/rag-consistency.md`。
> **测试依据**：`RagConsistencyRegressionTest`、`RepairDatabaseIntegrationTest`（静态断言核对，未实机运行外部中间件）。

---

## 一、业务背景与问题定义

知识库问答有**两个存储**：MySQL（`sys_qa_info` 答案、`sys_qa_es` 问题映射）是权威源，Elasticsearch（`qa_knowledge_base`）是检索索引。管理员在 `RagController` 上新增/改答案/改问题/删除后，接口返回成功只代表 **DB 事务提交了**，ES 索引还没重建。

如果写入路径直接同步双写（DB + ES），会遇到：ES 不可用导致业务写入失败；ES 成功但 DB 回滚造成幽灵文档；并发改同一 `groupId` 时旧任务覆盖新版本。当前选的是**事务内入队 + 后台异步重建 + 检索时以 DB 为准**的最终一致性方案。可检索性因此有几个明确的边界：

- 接口成功 ≠ 立刻可检索（默认 5 秒扫描一次）；
- 改「问题」到索引同步完成之间可能暂时检索不到；
- 已删除记录 / 失败新增残留的 ES 文档**不能独立作为有效答案**返回。

---

## 二、架构设计与数据流

```mermaid
flowchart LR
    ADM[ADMIN 接口<br/>RagController 7 端点] --> RS[RagService 写路径<br/>@Transactional]
    RS --> DB[(MySQL<br/>sys_qa_info / sys_qa_es)]
    RS --> JOB[(qa_sync_job 持久任务表)]
    JOB --> SYNC[QaIndexSynchronizer<br/>@Scheduled 5s]
    SYNC --> ES[(ES qa_knowledge_base<br/>1024 维 dense_vector + text)]
    Q[用户提问] --> RS2[RagService.searchAnswers<br/>8 秒共享 deadline]
    RS2 --> EMB[DashScope Embedding]
    RS2 --> V[向量 knn 窗口 100]
    RS2 --> B[BM25 窗口 100]
    V --> RRF[RRF K=60 → group 去重 Top5]
    B --> RRF
    RRF --> AUTH[authoritative(): 按 DB 最新 answer 覆盖]
    DB --> AUTH
    AUTH --> OUT[最终知识条目]
```

索引结构（`QaIndexSynchronizer.synchronizeGroup` 首次创建时）：`groupId`(keyword)、`question`(text)、`answer`(text, `index(false)`)、`questionVector`(dense_vector, `dims=1024`, `index=true`)。向量模型为 `text-embedding-v4`、1024 维（`application.yaml`）。

---

## 三、完整执行流程

### 3.1 写入路径（事务内只写 DB + 任务）

`RagService.addQA / addQAs / deleteQA / updateAnswer / updateQuestion` 全部 `@Transactional`：改 `sys_qa_info` / `sys_qa_es`，然后 `syncJobs.enqueue(groupId)` 往 `qa_sync_job` 写一行持久任务；**不调用 ES**。`updateQuestion` 额外 `selectOne(...).last("FOR UPDATE")` 锁住问题行，防止并发改写导致向量与文本错配。

**为什么把任务表放进同一个 DB 事务**：DB 回滚时任务也回滚，绝不会出现「DB 没改、ES 却重建」；DB 提交后任务一定可见，不需要额外的可靠消息通道。这是「用数据库事务边界换消息可靠性」的经典做法。

### 3.2 索引重建（领取 → 重嵌 → 提交/延期）

`QaIndexSynchronizer.synchronize()` 用 `@Scheduled(fixedDelayString = "${rag.sync.interval-ms:5000}")` 每 5 秒扫一次，`@Transactional(isolation = READ_COMMITTED)` 内 `jobs.claimBatch()` 通过 `FOR UPDATE SKIP LOCKED LIMIT 5` 领取任务。`SKIP LOCKED` 让多实例并发领取时互不阻塞：被别人锁住的行直接跳过，而不是排队等待。

`synchronizeGroup(groupId)`：

1. 索引不存在则创建（`resource_already_exists_exception` 并发创建会被吞掉）；
2. `deleteByQuery` 删除该 `groupId` 的所有旧文档，并校验 `timedOut()`/`failures()`；
3. 从 DB 读当前 `QaInfo` 与该组所有 `QaElasticMap` 问题，逐条 `embedding.embed(question)` 重嵌并 `es.index(...)`；
4. `es.indices().refresh(...)` 让新文档立刻可见；
5. 成功后 `jobs.deleteById(groupId)`；**任何异常都 `jobs.defer(groupId)`**（约 30 秒后再可领取）并 `log.warn`，不阻塞后续正常任务。

### 3.3 检索路径（8 秒共享预算 + 双路 + RRF + 权威库）

`RagService.searchAnswers`：

```java
long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, searchTimeoutMillis)); // 默认 8000
var embeddingFuture = CompletableFuture.supplyAsync(() -> embeddingModel.embed(userQuery), ragExecutor);
float[] vectorArray = embeddingFuture.get(remaining(deadline), TimeUnit.NANOSECONDS);
var vectorFuture = CompletableFuture.supplyAsync(() -> searchVectorOnly(queryVector, 100), ragExecutor);
var bm25Future   = CompletableFuture.supplyAsync(() -> searchBm25Only(userQuery, 100), ragExecutor);
CompletableFuture.allOf(vectorFuture, bm25Future).get(remaining(deadline), TimeUnit.NANOSECONDS);
```

- **共享 deadline**：向量化、向量检索、BM25 三个 Future 都用同一个 `remaining(deadline)`，不是各自 8 秒；超时后 `finally` 对未完成 Future `cancel(true)`（**逻辑取消**）。
- **双路**：`knn` on `questionVector`（`k=100`、`numCandidates=200`）与 `match` on `question`（`size=100`），`source` 只取 `id/groupId/question/answer`。
- **RRF**：`score = 1.0 / (60 + i + 1)`，`K=60`、窗口 100，按分降序后按 `groupId` 手动 collapse 去重，取到 5 条停止。
- **`authoritative()`**：用 ES 文档 id 反查 `QaElasticMap`，校验 `question` 与 `groupId` **完全一致**，再用当前 `QaInfo.answer` 覆盖 `doc.setAnswer(...)`；无映射 / 问题已变 / 组已删除 → 丢弃该条。

最后一步是整个设计的「安全网」：即使 ES 索引滞后或残留旧文档，**答案内容永远来自当前 DB**。

### 3.4 `version` 递增与 `SKIP LOCKED` 的并发领取语义（补充）

`QaSyncJobMapper.enqueue()` 用 `INSERT ... ON DUPLICATE KEY UPDATE version = version + 1`（`:9-11`）：同一 `groupId` 被连续改写时**不新增任务行，只把版本号 +1**；`claimBatch()` 用 `LIMIT 5 FOR UPDATE SKIP LOCKED`（`:12-14`）领取并返回当时的 `version`。重建完成时删除任务以该版本号为准，因此「慢的那次重建」即使后完成，也不会把「快的新版本」任务删掉 —— 这是任务表能替代 MQ 而不丢更新的关键。多实例下 `SKIP LOCKED` 让被别人锁住的行直接跳过，互不阻塞；失败任务由 `defer()` 写 `DATE_ADD(NOW(), INTERVAL 30 SECOND)` 的 `next_attempt_at`（`:15-16`），下次扫描只取到期的行，避免坏任务饿死正常任务。

### 3.5 超时路径的完整状态流（补充）

`remaining(deadline)` 在任一阶段超限时抛 `TimeoutException`（`RagService.java:389-393`）；`finally` 对未完成的 `CompletableFuture` 调 `cancel(true)`（`:330-331,385`）。这条路径**不会**返回「部分检索结果」或执行 `authoritative()`，而是让 `searchAnswers` 直接抛异常 —— 因为只融合了一路的结果在 RRF 下没有统计意义，宁可失败也不返回偏斜答案。`cancel(true)` 只能中断线程标志，已提交到 `ragExecutor` 的阻塞 ES / Embedding HTTP 调用没有中断点，**物理上仍在占用线程**；若队列持续被打满，`supplyAsync` 会抛 `RejectedExecutionException`（`AsyncWorkConfig` 未配置拒绝策略，默认 AbortPolicy），这是 8 秒预算之外的第二个容量边界。

---

## 四、核心源码解析

| 位置 | 关键点 |
|---|---|
| `agentbase/rag/RagService.java` `@Value("${rag.search.timeout-ms:8000}")` | 8 秒预算是**代码默认值**，未在 `application.yaml` 外置 |
| `RagService.addQA` / `deleteQA` / `updateAnswer` | `@Transactional`，只写 DB + `syncJobs.enqueue(groupId)` |
| `RagService.updateQuestion` | `FOR UPDATE` 行锁，必须重嵌向量 |
| `QaSyncJobMapper.claimBatch()` | `FOR UPDATE SKIP LOCKED LIMIT 5` |
| `QaIndexSynchronizer.synchronize()` | `fixedDelayString` 5s、`jobs.defer()` 失败重试、异常只 warn |
| `RagService.searchVectorOnly` / `searchBm25Only` | 窗口 100、`source` 精简 |
| `RagService.authoritative()` | DB 最新答案覆盖、校验问题文本与 groupId |
| `config/AsyncWorkConfig.ragExecutor()` | core 4 / max 8 / queue 32 / `rag-search-` |
| `controller/RagController.java` | 7 个端点全部 `@RequirePermission({"ADMIN"})` |

7 个管理端点：`POST /rag/qa/page`、`/rag/qa/add`、`/rag/qa/add-batch`、`/rag/admin/qa/delete`、`/rag/admin/qa/update-answer`、`/rag/admin/qa/update-question`、`GET /rag/search?question=...`。

---

## 五、底层原理与设计取舍

| 机制 | 原理 | 优势 | 局限 |
|---|---|---|---|
| 事务内持久任务表 | DB 事务边界 = 任务可见性边界 | 不需要 MQ；回滚自动撤销任务 | 靠轮询，默认 5 秒延迟 |
| `SKIP LOCKED` | 跳过已被锁的行 | 多实例并行领取、不互相阻塞 | 失败任务需 `defer` 才会被再次领取 |
| 8 秒共享 deadline | `nanoTime` 单点计时，各阶段 `get(remaining)` | 避免「三段各 8 秒」把总耗时放大到 24 秒 | `cancel(true)` 只是逻辑中断，底层 HTTP 客户端可能仍占用线程 |
| RRF | 倒数排名融合，不依赖两路分数可比 | 无需归一化分数，鲁棒 | `K=60`、窗口 100 是经验值 |
| 权威库核对 | 以 DB 当前行覆盖 ES 答案 | 索引滞后不产生错误答案 | 每次检索多一次 DB 查询 |

**方案对比**：① 同步双写（简单但 ES 故障会拖垮写入、回滚不一致）；② MQ / binlog CDC（解耦更彻底，需额外组件与幂等消费）；③ 定时全量重建（无任务表，但浪费且延迟更高）。当前选 ① 的可靠性 + ③ 的简单性折中，适合管理端低频写入的客服知识库。**纯理论**的「用 ES 作为唯一数据源」在这里不成立：答案正确性由 DB 负责。

---

## 六、完整示例

管理员调 `POST /rag/admin/qa/update-answer` 把「退费规则」答案改成 A2：

1. 事务内更新 `sys_qa_info.answer=A2`，`qa_sync_job` 增加该 `groupId`，接口返回 `Result.ok()`；
2. 5 秒内若有用户提问，双路检索可能命中旧 ES 文档（答案 A1、旧向量）；
3. `authoritative()` 反查 DB，校验问题文本一致后用 **A2** 覆盖 → 用户看到新答案，即使索引还没重建；
4. `QaIndexSynchronizer` 领取任务：`deleteByQuery` 旧文档 → 重嵌 → `refresh` → 删任务；
5. 若 ES 宕机，`defer` 保留任务，30 秒后重试，`RagConsistencyRegressionTest` 断言「ES 不可用仍入队、失败文档不成答案」。

边界：改的是 `question` 时，旧问题文档被 `authoritative()` 的「问题完全一致」校验丢弃，因此**从改题到索引重建完成之间可能暂时检索不到该题** —— 这是有意的取舍（宁可查不到，也不返回错答案）。

---

## 七、常见问题与面试延伸

1. **「接口成功」能不能直接对前端承诺「立刻可检索」？** 不能。接口只保证 DB 提交 + 任务入队。
2. **为什么不用 `CompletableFuture` 各给 8 秒？** 共享 deadline 才能保证端到端预算；各段独立计时会累积超时。
3. **`cancel(true)` 能中断 ES / Embedding 的 HTTP 请求吗？** 不能保证，只是不再等待结果；这是**逻辑取消**（`api/rag-consistency.md` 已声明）。
4. **RRF 的 K 为什么取 60？** 论文经验值，压低头部单路结果的支配力；本项目窗口 100、Top5 按 `groupId` 去重。
5. **为什么 `answer` 字段 `index(false)`？** 答案不参与检索，只作展示，且最终由 DB 覆盖；省索引空间。
6. **运维要调超时/间隔怎么办？** `rag.search.timeout-ms`、`rag.sync.interval-ms` 是 `@Value`/`@Scheduled` 默认值，线程池参数写在 `AsyncWorkConfig`，均**未在 yaml 外置**；外置到配置中心是可选优化（当前未实现）。
7. **和 Redis 笔记的关系？** 25 篇 Redis 笔记不覆盖 RAG；`api/rag-consistency.md` 只有 3 段契约，本篇补齐原理与链路。

> 关联：[01 编排与 SSE](01-orchestration-and-sse.md)、[03 安全边界](03-security-and-boundaries.md)、[`src/main/resources/api/rag-consistency.md`](../../../src/main/resources/api/rag-consistency.md)。
