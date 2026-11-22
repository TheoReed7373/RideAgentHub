# 第 22 章 RAG 索引同步与检索预算：从“写成功”到“能搜到”

> 虚构管理员在后台点“保存”，把一条规则的答案从“提前 30 天”改成“提前 15 天”。接口返回成功，数据库里已经是新答案。用户几乎在同一秒发来的提问**不一定**要等搜索引擎重建：若问题文本没变，旧 ES 候选仍可能进入结果，再由 `authoritative` 用本次回查读到的 MySQL 行把答案覆盖成新值——这里“可能”不是“一定”，因为该候选仍要靠本轮召回、排名和五条校验。若改的是问题或向量，旧索引表示就可能失效，需要后台任务领取、删旧、重建、刷新之后，新索引候选才可能命中。可见“写成功→搜到”不是一条固定流水线：要返回新答案和要让新索引候选可见，是两条前提不同的路径。
>
> 本章承接第 21 章 R1 的检索链，专门讲**写后异步重建**与**一次检索的共享时间预算**。R1 已经讲清“搜到之后怎么核对、为什么 answer 以 MySQL 为准”；这里讲“索引是什么时候、在什么条件下被改写的”，以及“超时和取消究竟管到哪一步”。两章合起来，才能回答“写接口成功为什么不等于马上能搜到”。
>
> **本章没有启动服务、运行测试或脚本、连接 MySQL/Elasticsearch/Redis、调用 Embedding/模型/HTTP、读取凭据。** 除明确标注“测试源码阅读”的地方，结论都是对当前仓库源码的静态推导；所有 ID、时间、耗时、榜单与异常时序均为教学示意，不是实测输出。真实部署下的并发、锁等待、ES 刷新与网络超时表现列为未知。

## 学习目标

读完本章，你应该能：

1. 从一次管理员改答案出发，画出“MySQL 写入 → 同一事务入队 → 事务提交 → 定时调度 → 领取批次 → 删除旧 ES 文档 → 读权威行 → Embedding → 写 ES → refresh → 删除任务 → 搜索 → 回查”的完整时间线，并说清每一步由谁触发。
2. 明确区分六种“成功/状态”：接口返回成功、事务提交成功、任务被领取、ES 写入已发送、索引对搜索可见、某次查询命中；对每一种写出它的前提与失败后可在哪里观察到什么。
3. 解释 `claimBatch` 的 `FOR UPDATE SKIP LOCKED`、`LIMIT 5`、事务边界与行锁释放时机；追踪 `version` 的写入、领取与后续使用，说明源码中**没有**显式版本比较，以及真正防止“新入队任务丢失”的机制是什么。
4. 按真实顺序复述 `synchronizeGroup`：建索引判存、删除旧文档、读 `QaInfo`、`info == null` 直接 return、逐问题 Embedding 与写入、末尾 `refresh`；说明部分成功后抛异常、失败 `defer`、进程崩溃或响应丢失时哪些外部操作不能推断。
5. 逐阶段说明一次 `searchAnswers` 的共享 deadline：`supplyAsync` 提交、执行器排队、Embedding 等待、两路 ES 任务、`allOf`、结果提取、RRF 融合与 MySQL 回查，哪些阶段受 deadline 直接限制、哪些不受；给出带数字的条件化例子，区分 `remaining()` 直接抛超时与 timed `get` 等待超时；说明 `CompletableFuture.cancel(true)` 的 `mayInterruptIfRunning` 不控制中断，不能推断底层 HTTP 被停止。
6. 逐类阅读两个直接相关测试，说出门控、真实资源与 mock、具体断言、测试数据与清理，以及它们不能证明什么。

## 前置知识与阅读时间

- **硬前置**：[第 21 章 RAG 检索与答案溯源](01-retrieval-and-grounding.md)（R1）。你必须先知道三张 `sys_qa_*` 表与 ES 文档字段、`searchAnswers` 的执行顺序、`authoritative` 的五条校验、`RRF_K=60` 与 `WINDOW_SIZE=100`。本章不会重讲 RRF 手算，只引用其结论。
- 更底层的前置（已由 R1 覆盖）：[第 3 章 数据存在哪，事务管到哪](../00-foundations/03-data-and-transactions.md)（F3）——`@Transactional` 的代理边界、行锁、提交后才可见。
- 可选回看：[第 20 章 外部工具与提示词边界](../04-chat-and-agent/05-external-tools-and-prompts.md)（C5）——`RagTool` 的调用位置；[第 13 章 工单池索引与统计缓存](../03-support-and-cache/02-ticket-index-and-cache.md)（S2）——另一种“索引落后于数据”的对照；[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)（S4）——本章任务行的“领取即占有”与那里讲的回填版本不是同一个东西。
- 估计阅读时间：90～110 分钟（含两条并发时间线推导、四组练习与十道自测）。

## 一、业务场景与三处状态的责任

### 1.1 一次改动落在哪里

沿用 R1 的虚构数据：一组答案 `group_id=90001`，两个问题 `elastic_id=91001/91002`，答案原本是“最早 30 天”。管理员调用 `POST /rag/admin/qa/update-answer`，把答案改成“最早 15 天”。源码里这一步做了什么（`agentbase/rag/RagService.java#updateAnswer`，源码节选，仅调整换行）：

```java
@Transactional
public void updateAnswer(RagQAUpdateAnswerDTO dto) {
    if (dto == null || dto.getGroupId() == null || dto.getAnswer() == null || dto.getAnswer().isBlank())
        throw new IllegalArgumentException("答案组和答案不能为空");
    Long groupId = Long.valueOf(dto.getGroupId().trim());
    if (qaInfoMapper.update(null, new LambdaUpdateWrapper<QaInfo>().eq(QaInfo::getGroupId, groupId)
            .set(QaInfo::getAnswer, dto.getAnswer().trim())) != 1) throw new IllegalArgumentException("答案组不存在");
    syncJobs.enqueue(groupId);
}
```

三处状态各自负责一件事：

| 位置 | 谁写 | 存什么 | 责任 | 写了之后立刻发生什么 |
|---|---|---|---|---|
| MySQL `sys_qa_info` | 管理员写接口（同一 `@Transactional`） | `group_id` + **权威答案** | 答案真值；`authoritative` 从这里取值 | 事务提交后其他事务可见；**不直接改 ES** |
| MySQL `sys_qa_sync_job` | 同一事务里的 `syncJobs.enqueue(groupId)` | `group_id`（主键）、`version`、`next_attempt_at` | 一条持久“待重建”任务，不是答案数据 | 提交后任务变为“到期可领取”；**不直接改 ES** |
| ES `qa_knowledge_base` | 定时同步器 `QaIndexSynchronizer` | 文档的 `question`、`questionVector`、answer 冗余副本 | 候选来源，供 BM25/kNN 召回 | 只有同步器被调度、领取并执行后才变化 |

**源码里的一个注释陷阱**：`updateAnswer` 和 `updateQuestion` 的方法 Javadoc 写着 “ES: update-by-query 批量更新” / “ES: update 单文档部分更新（question + questionVector）”。但两个方法的**方法体没有任何 ES 调用**，只更新 MySQL 然后 `enqueue`。按源码写：当前实现是“写 MySQL + 入队 + 后台异步重建”，不是“同步更新 ES”。注释描述的是另一种设计，不能当行为依据。

### 1.2 六种“成功”不是一回事

这是本章最重要的区分。每一行都给出前提与失败后可观察状态：

| 名称 | 谁产生 | 前提 | 失败/未达成时可观察状态 |
|---|---|---|---|
| **接口返回成功** | `RagController` 的 `Result.ok()` | 无外层事务的正常路径下，被代理的 service 方法正常返回并完成提交后才有 ok；提交失败会抛异常而不是返回 ok | 如果确认业务事务已回滚，这次库内改动未生效。HTTP 响应失败或丢失时，单凭客户端结果无法判断事务状态 |
| **事务提交成功** | Spring 事务代理在 service 方法返回时（若有外层事务则在外层边界） | 方法体没有抛出未捕获异常，数据库提交未失败 | 明确失败/回滚时，MySQL 里 `sys_qa_info`/`sys_qa_es` 与 `sys_qa_sync_job` 的改动一起撤销；ES 不受此事务影响。提交结果不明时不能断言已回滚 |
| **任务已领取** | `QaIndexSynchronizer#synchronize` 调 `jobs.claimBatch()` | 任务 `next_attempt_at <= CURRENT_TIMESTAMP`；未被 `LIMIT 5` 挤出；未被其他事务的行锁挡住（`SKIP LOCKED` 会跳过） | 任务行仍在表里、仍到期；下一次调度可能领到；DB 行锁竞争可在数据库侧观察到，源码不记录业务日志 |
| **ES 写入已发送** | `synchronizeGroup` 中 `es.index(...)` / `es.deleteByQuery(...)` 调用返回 | Embedding 成功、前面步骤无异常 | 若抛异常进入 `catch` 并成功 `defer`、事务提交成功，任务保留、下次可领时间按执行时数据库时间 +30 秒；`defer` 失败或提交失败/未知时不能套用此结论。ES 可能已经部分生效（见 §4.2） |
| **索引对搜索可见** | `es.indices().refresh(...)` 及 ES 自身刷新 | `info != null` 且循环中没有异常才能执行到 `refresh`；`info == null` 时直接 return，**不执行 refresh**；`refresh` 未检查响应分片失败 | 文档可能已写入但尚未进入搜索结果；删除的旧文档也可能仍被召回；实际可见性依赖 ES 外部环境 |
| **某次查询命中** | `searchAnswers` 的 `match`/`kNN` + 排名 + `authoritative` | 候选（旧文档或重建后的文档均可）在可见索引里、进入任一路前 100、通过按组去重与 Top5、并通过五条数据库校验；**答案取本次 `qaInfoMapper.selectList` 读到的行** | 返回空或更少组；不能由“索引已写”推出“这次一定命中” |

一句话记忆：**接口成功只覆盖前两行**；但“返回新答案”不要求走完后面四行——仅改答案时旧候选 + 本次回查就可能做到；新索引候选的可见性取决于后台重建和 ES 刷新。本次查询能否命中还取决于召回、排名和回查。

### 1.3 本章术语，第一次出现就地解释

- **持久任务（durable job）**：任务写在 MySQL 表 `sys_qa_sync_job` 里，而不是进程内存队列。进程重启后任务还在。
- **领取（claim）**：`SELECT ... FOR UPDATE SKIP LOCKED` 把到期的任务行锁住并读出来；拿到行就等于“这一批由我处理”，别的同步实例会跳过被锁的行。
- **`FOR UPDATE SKIP LOCKED`**：MySQL 的锁定读。`FOR UPDATE` 给选中的行加排他锁；`SKIP LOCKED` 让查询直接跳过已被别人锁住的行，而不是排队等待。它解决“多个消费者抢同一批任务”，不解决业务数据一致性。
- **`defer`（延后）**：SQL 把任务的 `next_attempt_at` 改成**执行该 SQL 时数据库 `CURRENT_TIMESTAMP` + 30 秒**；任务**不被删除**，在新到期时间之前不可领取，且如果批次提交时已过该时点，提交后可立即到期。它可被后续同组 `enqueue` 重置，也从不算“从提交时刻起再等 30 秒”。
- **`refresh`**：ES 的近实时刷新调用。源码调用它但未检查响应分片失败；它不是事务提交，能否让文档进入可搜索状态取决于 ES 集群与外部环境；代码路径绕过时可见性更没有保证。
- **deadline（截止时间）**：`searchAnswers` 入口算出的一个绝对时间点；后续两处 `get` 用 `remaining(deadline)` 换算成剩余等待时间。不是每个子任务各有一份预算。
- **`allOf`**：`CompletableFuture.allOf(a, b)` 返回一个在 **a 和 b 都完成**（正常或异常）后才完成的 Future。
- **逻辑取消**：本项目调用 `CompletableFuture.cancel(true)`。对 `CompletableFuture` 而言，`mayInterruptIfRunning` 这个参数**不用于控制中断**（API 文档明确说明该参数无效果）；调用的可确认效果是 Future 自身进入“已取消/已完成”状态。至于已经运行的 supplier 是否继续、底层 ES/Embedding HTTP 是否停止，源码与 API 都不能证明；排队但尚未开始的任务是否最终执行，还取决于执行器与取消时的竞争。不要把它写成“尝试中断工作线程”。

## 二、端到端时间线：从改答案到搜到

### 2.0 源码导读主线（按执行顺序）

本章只有一条主线，按下面顺序打开源码就能跟完全程；每处只看列出的关键方法，不必读整类：

1. `controller/RagController.java#updateAnswer` → `agentbase/rag/RagService.java#updateAnswer`：`@Transactional`、MySQL `update`、`syncJobs.enqueue(groupId)`（§1.1）。
2. `mapper/QaSyncJobMapper.java#enqueue/#claimBatch/#defer` 与 `src/main/resources/sql/init.sql` 的 `sys_qa_sync_job` DDL：任务怎么产生、怎么被领取、怎么被延后（§3.1）。
3. `agentbase/rag/QaIndexSynchronizer.java#synchronize`：`@Scheduled`/`@Transactional`、`claimBatch`、循环里的 `synchronizeGroup`/`deleteById`/`defer`（§3.2～§3.5）。
4. `agentbase/rag/QaIndexSynchronizer.java#synchronizeGroup`：建索引判存、删旧文档、`info == null` 早退、逐条 Embedding/`es.index`、末尾 `refresh`（§4.1）。
5. `agentbase/rag/RagService.java#searchAnswers/#remaining/#authoritative` 与 `config/AsyncWorkConfig.java#ragExecutor`：预算起点、两处带超时的 `get`、融合与回查、线程池（§5）。

§2.1 先按时间线把这条主线走完一次，§3～§5 再分别拆开领取/并发、同步内部与预算。

### 2.1 时间线 T1：写、提交、调度、重建、再检索（源码静态推导）

这是一条**完整重建路径**的示例，不是“返回新答案”的必经顺序；§2.3 会给出另一条“旧候选返回新答案”的独立时间线。

前提（每一条都是条件，不是默认事实）：管理员请求经过令牌与 `@RequirePermission({"ADMIN"})`；`updateAnswer` 的 `update` 影响 1 行；写事务提交成功；`rag.sync.interval-ms` 取注解默认 5000ms 且未被外部配置覆盖；某次 `synchronize` 调用开始；任务未被 `LIMIT 5` 与行锁挡住；Embedding、ES 写入、`refresh` 都成功；用户查询的 `match`/`kNN` 与排名把文档列入候选。

| 时刻 | 触发者 | 动作 | 此刻可观察状态 |
|---|---|---|---|
| t0 | 管理员 HTTP | 请求进入 `RagController#updateAnswer` → `RagService#updateAnswer`（代理开启事务） | MySQL 事务进行中，外部还看不到 |
| t1 | Service | `update sys_qa_info set answer='最早15天' where group_id=90001`，检查影响行数 = 1 | 事务内答案已改，未提交 |
| t2 | Service | `syncJobs.enqueue(90001)`：`INSERT ... ON DUPLICATE KEY UPDATE version=version+1, next_attempt_at=CURRENT_TIMESTAMP` | 事务内已有任务行；`next_attempt_at` 因 DDL 默认或 upsert 为当前时间 |
| t3 | 事务代理 | service 方法返回，**提交** | MySQL 新答案与新/更新后的任务对其他事务可见；**本次写方法没有直接调用 ES**，但不排除先前或并发的同步任务正在操作 ES；接口此时才可能返回成功 |
| t4 | 定时调度 | 上一次 `synchronize` 结束后又过了 `fixedDelay`（默认 5000ms），调度器发起下一次 `synchronize()` | 调度线程调用被代理的方法，`@Transactional(READ_COMMITTED)` 开启新事务；**t4 − t3 没有上界** |
| t5 | `synchronize` | `jobs.claimBatch()`：`SELECT ... WHERE next_attempt_at<=NOW() ORDER BY next_attempt_at, group_id LIMIT 5 FOR UPDATE SKIP LOCKED` | 该行被加排他锁并进入本事务的批次；锁保持到 t11 事务结束才释放 |
| t6 | `synchronizeGroup` | 索引不存在则 `create`；`deleteByQuery` 按 `groupId=90001` 删旧文档，检查 `timedOut()`/`failures()` | ES 旧文档删除请求已执行；此时可能尚未对搜索可见 |
| t7 | `synchronizeGroup` | `infos.selectOne(group_id=90001)` 读到新答案；`info != null` 继续 | 读到的答案来自 t3 已提交的 MySQL 行 |
| t8 | `synchronizeGroup` | 对 `sys_qa_es` 的两条映射逐条：`elasticId.toString()` 当文档 id，`embedding.embed(question)`，`es.index(...)` 写入 question 与向量 | ES 里两份新文档已写入；**尚未 refresh** |
| t9 | `synchronizeGroup` | `es.indices().refresh(qa_knowledge_base)` | 客户端调用未抛异常；文档是否对搜索可见还取决于 ES 分片响应与外部环境（未实测） |
| t10 | `synchronize` | `jobs.deleteById(90001)` | 本事务内任务行被标记删除，还没提交 |
| t11 | 事务代理 | `synchronize()` 返回，**提交** | 若提交成功，任务行删除落库；t5 起持有的行锁此时才释放 |
| t12 | 用户请求 | “订单可以提前多久预约”进入 `searchAnswers` | 与 R1 相同的检索链开始，预算从方法入口起算 |
| t13 | 检索 | Embedding → 两路 ES → `allOf` → RRF → 按组去重 → Top5 → `authoritative` | 候选通过五条校验，答案取**本次** `sys_qa_info` 查询读到的行（新答案） |
| t14 | 呈现 | `RagController#search` 用 `Result.ok(results)` 封装文档列表，或 `RagTool` 拼字符串交回模型 | 用户看到什么还取决于 SSE/HTTP 层；本章不保证具体事件送达 |

注意 t4 的措辞：“上一次调用结束后又过了 `fixedDelay`”。`@Scheduled(fixedDelayString = "${rag.sync.interval-ms:5000}")` 的语义是**上一次调用结束到下一次调用开始之间的延迟**，不是“入队后 5 秒内可搜到”。t3 到 t4 之间可能已经有别的批次在跑、可能锁竞争、可能失败延后，源码没有给上界。还要注意：t12～t14 可以在 t11 **之前**发生——用户搜索不等待同步事务提交；如果 t9 刷新已经使新文档可见，查询可以命中新文档；如果重建还没发生，旧候选也可能经回查返回新答案（见 §2.3）。

### 2.2 正常之外：每一步失败后在哪里看

| 失败点 | 源码行为 | 可观察状态（均需限定提交结果） |
|---|---|---|
| t1/t2 抛未捕获异常（如影响行数 ≠ 1） | 异常经事务代理传播，事务按回滚标记处理 | 若确认回滚：MySQL 答案与任务行都不变；HTTP 不会返回成功（异常进入 MVC 时按 `GlobalExceptionHandler` 映射，未实测）。若提交响应丢失，服务端最终状态未知，不能由客户端没收到推断回滚 |
| t3 提交明确失败 / 提交响应丢失 | 明确失败时代理抛异常；响应丢失时结果未知 | 明确失败：不能说“接口成功”，库内改动不生效。响应丢失：客户端没收到成功不等于事务未提交；需回查 `sys_qa_info` 才能判断 |
| t5 任务未到期 / 被 `LIMIT 5` 挤出 / 被锁跳过 | `claimBatch` 不返回该行 | 任务行仍在 `sys_qa_sync_job`，`next_attempt_at` 不变；下一次调度可能领到 |
| t6 `deleteByQuery` 超时或有失败分片 | 抛 `IllegalStateException("旧索引删除未完成")` → `catch` 内 `defer` | 若 `defer` 与事务提交都成功：任务下次可领时间被推后约 30 秒；旧文档可能部分被删；ES 状态未知 |
| t7 `info == null`（组已被删） | `synchronizeGroup` **直接 return**（本次不执行 refresh）；调用方继续执行 `deleteById` | 旧文档删除请求可能已生效；**后面的 refresh 不执行**。任务最终被删以 `deleteById` 成功且事务提交成功为前提；若提交失败/结果未知，不能断言任务已删 |
| t8 Embedding 或 `es.index` 抛异常 | 被 `synchronize` 的 `catch` 捕获 → 尝试 `defer` | 若 `defer` 与提交成功：任务保留并延后；`defer` 自身失败可逃逸并回滚本批事务。ES 可能已有部分新文档（见 §4.2） |
| t9 `refresh` 抛异常 | 同上 → 尝试 `defer` | 若 `defer` 与提交成功：任务保留并延后；文档可能已写入但可见性未知 |
| t13 检索失败或超时 | `RuntimeException("RRF Search failed")` | 本组候选不返回；与 ES 索引状态无关 |

### 2.3 两条独立时间线：旧候选返回新答案 vs 新索引候选可见

“返回新答案”和“新索引候选可见”不是同一件事，也不是固定先后。下面两条时间线相互独立，前提不同，都**不承诺一定发生**。

**时间线 A：只改答案，旧 ES 候选返回新答案（不要求索引重建）**

前提：`updateAnswer` 只改 `sys_qa_info.answer`，`sys_qa_es.question` 未变；写事务已提交且本次 `authoritative` 的 `qaInfoMapper.selectList` 读到已提交的新行；旧 ES 文档仍在索引里且对搜索可见；该文档进入 `match`/`kNN` 的前 100、通过按组去重与 Top5，并通过五条校验。

| 时刻 | 动作 | 可见状态 |
|---|---|---|
| t0 | `updateAnswer`：更新答案、入队、事务提交 | MySQL 已是新答案；ES 文档仍是旧 answer 副本（且 `answer` 不参与检索） |
| t1 | **同步尚未被领取/未执行**（任务存在与否都不影响这条时间线） | 旧 question/向量文档仍在；用户查询可能用它召回 |
| t2 | `searchAnswers`：旧文档进入候选 | 答案字段起初是 ES 里的旧副本 |
| t3 | `authoritative` 校验通过，`doc.setAnswer(info.getAnswer())` | 答案被**本次** MySQL 查询读到的新行覆盖；用户读到新答案 |
| t4（可能更晚，也可能更早发生） | 同步器若稍后重建，answer 副本才变成新值；但在 A 中它不是返回新答案的原因 | 同步是否发生、何时发生不影响 t3 的结论 |

关键：`authoritative` 只按 `mapping.question == doc.question`、`groupId` 一致、映射与答案组存在来校验，**不比较 answer 副本**；所以旧候选完全可能返回新答案。**反过来不能写**“旧候选一定命中”（召回/排名是条件），也不能写“回查一定读到新行”（两条查询无本方法显式快照，读到较早行时可能返回旧值）。依据：`RagService#updateAnswer/#searchAnswers/#authoritative`（R1 §3.6 已逐条核验）。

**时间线 B：问题/向量改动后，新索引候选可见（要求重建链路成功）**

前提：`updateQuestion` 改了 `sys_qa_es.question` 并提交；任务到期且被 `claimBatch` 领到；`synchronizeGroup` 删旧、Embedding、写 ES、`refresh` 都未抛异常；若要求任务被清理，还需 `deleteById` 和同步事务提交成功；新文档对搜索可见、进入本次前 100、通过去重/Top5 与五条校验。

| 时刻 | 动作 | 可见状态 |
|---|---|---|
| t0 | `updateQuestion`：事务内 `FOR UPDATE` 查映射、更新 question、入队、提交 | MySQL 问题已变；ES 仍是旧 question + 旧向量 |
| t1 | 用户此时搜索：旧文档未必进入候选；若进入且本次回查看到了新 `mapping.question`，`authoritative` 因文本不等丢弃它 | 可能空或更少组；不是 A 那种“旧候选返回新答案”的场景 |
| t2 | 某次 `synchronize` 领取任务（`fixedDelay` 之后，受批次/锁影响），`deleteByQuery` 删旧、读新 question、Embedding、`es.index` 写新文档 | 文档已写入；尚未确认可见 |
| t3 | `refresh` 调用返回且无异常；实际可搜索可见性仍取决于 ES 分片响应与外部环境 | 新问题**可能**命中，不保证 |
| t4 | `deleteById` → 同步事务提交 | 任务删除以提交成功为前提；此后新索引候选仍要靠每次查询的召回、排名与回查才能命中 |

两条时间线各自都有自己的时钟。A 无需等重建，B 的搜索可以发生在刷新已生效而同步事务尚未提交的窗口；结论取决于该次查询实际读到的索引与 MySQL 行，而不是“改答案→重建→搜到”这条想象中的单一顺序。依据：`RagService#updateAnswer/#updateQuestion/#authoritative`、`QaIndexSynchronizer#synchronize/#synchronizeGroup`。

## 三、同步任务：SQL、领取与并发

### 3.1 三条 SQL 与 DDL（源码节选，未改写 SQL）

`src/main/java/com/fancy/rideagenthub/mapper/QaSyncJobMapper.java`：

```java
@Insert("INSERT INTO sys_qa_sync_job(group_id, version) VALUES(#{groupId}, 1) " +
        "ON DUPLICATE KEY UPDATE version = version + 1, next_attempt_at = CURRENT_TIMESTAMP")
void enqueue(@Param("groupId") Long groupId);

@Select("SELECT group_id, version FROM sys_qa_sync_job WHERE next_attempt_at <= CURRENT_TIMESTAMP " +
        "ORDER BY next_attempt_at, group_id LIMIT 5 FOR UPDATE SKIP LOCKED")
java.util.List<QaSyncJob> claimBatch();

@Update("UPDATE sys_qa_sync_job SET next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND) WHERE group_id = #{groupId}")
void defer(@Param("groupId") Long groupId);
```

`src/main/resources/sql/init.sql` 的对应 DDL：

```sql
CREATE TABLE IF NOT EXISTS sys_qa_sync_job (
    group_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_qa_sync_due(next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

实体 `domain/entity/QaSyncJob.java` 只有两个字段：`groupId`（`@TableId(type=IdType.INPUT)`，对应主键）与 `version`（`Long`）。**实体里没有 `nextAttemptAt` 字段**，`claimBatch` 的 `SELECT` 也只取 `group_id, version`；`next_attempt_at` 只在 SQL 的 `WHERE` 与 `UPDATE` 里出现。第一次 `enqueue` 只给 `group_id` 与 `version=1`，`next_attempt_at` 由 DDL 默认值 `CURRENT_TIMESTAMP` 填成“当前时间”，所以新任务立刻到期；重复 `enqueue` 走 `ON DUPLICATE KEY UPDATE`，把 `version+1` 并把 `next_attempt_at` 重置为当前时间——**重复入队会把一个已延后的任务重新变成立即可领取**。

### 3.2 `claimBatch` 的事务、行锁与释放时机

`agentbase/rag/QaIndexSynchronizer.java#synchronize`（源码节选，保留事务与循环全部语句）：

```java
@Scheduled(fixedDelayString = "${rag.sync.interval-ms:5000}")
@Transactional(isolation = Isolation.READ_COMMITTED)
public void synchronize() {
    List<QaSyncJob> batch = jobs.claimBatch();
    for (QaSyncJob job : batch) {
        try {
            synchronizeGroup(job.getGroupId());
            jobs.deleteById(job.getGroupId());
        } catch (Exception failure) {
            jobs.defer(job.getGroupId());
            log.warn("知识库索引同步失败，保留任务以便重试: groupId={}", job.getGroupId(), failure);
        }
    }
}
```

逐条解释：

1. **事务由代理开启**。调度器调用的是 Spring 容器里的 Bean 方法，`@Transactional` 生效；事务隔离级别明确写 `READ_COMMITTED`。`synchronizeGroup` 是同类里的包级方法，通过 `this.synchronizeGroup(...)` 自调用，没有独立代理，它本身也没有事务注解——它运行在 `synchronize()` 的同一个事务里。
2. **`FOR UPDATE` 锁住选中的任务行**。`claimBatch` 在事务中执行，行锁随事务持有；循环里还做了 Embedding 与 ES 网络调用，**锁一直保持到 `synchronize()` 返回、事务提交/回滚才释放**。因此在锁持续持有且没有超时/死锁的前提下，同组的 `enqueue` 会在这条任务行上等待（见 §3.4）；等待是否成功结束还取决于数据库与事务结果，不能写成“必然阻塞然后必然成功”。
3. **`SKIP LOCKED` 跳过别人的行**。另一个同步事务已经锁住的行不会被本次查询选中，也不会让本次查询等待；因此两个同步者不会领到同一组任务。这不是“谁先谁后”的公平队列，只是“谁锁到谁处理，没锁到的跳过”。
4. **`LIMIT 5` 是每批次上限**。一次 `synchronize()` 最多处理 5 组；第 6 组及以后要等下一次调度。某一组处理失败时，`catch` 内若 `defer` 正常返回，循环会继续下一组；但这不是无条件的“批次隔离”——`defer` 自身抛异常会逃逸出 `catch`，整个方法失败并可能让事务回滚，ES 外部操作不会随事务回滚。
5. **删除与延后的条件**：`synchronizeGroup` 正常返回才调用 `deleteById`；`synchronizeGroup` 或 **`deleteById` 自身**抛出的 `Exception` 都会进入 `catch` 并尝试 `defer`。`claimBatch()` 在 `try` 外，它抛异常时整个方法直接失败，没有任何 `defer`；`defer` 位于 `catch` 内，它自身失败会逃逸。注意：`deleteById` 在 try 内（不是“在 catch 外不受保护”），它是“先尝试删除，删除失败就转为延后”，而不是“删除失败就中断全批”。

关于“几个同步者会同时跑”：`RideAgentHubApplication` 标注了 `@EnableScheduling`，但仓库没有配置调度线程池大小；单 JVM 内默认是否只有一个调度线程、是否存在多实例部署，源码与本章均未核验。`SKIP LOCKED` 保证的是**数据库行层面**不会有两个事务同时锁住同一任务行，与线程/实例数量无关。

### 3.3 `version` 到底做了什么：没有显式版本比较

这是本章最容易被“设计意图”带偏的地方。把 `version` 从生到死追一遍：

| 阶段 | 源码 | 对 version 做了什么 |
|---|---|---|
| 建表 | `init.sql` / 迁移脚本 | `version BIGINT NOT NULL`，无默认值；迁移脚本给存量数据填 1 |
| 首次入队 | `enqueue` 的 `VALUES(...,1)` | 写 1 |
| 重复入队 | `enqueue` 的 `ON DUPLICATE KEY UPDATE version = version + 1` | 加 1 |
| 领取 | `claimBatch` 的 `SELECT group_id, version` | 查出来，映射进 `QaSyncJob.version` |
| 处理 | `synchronize()` 只用 `job.getGroupId()` | **完全没有读取 `job.getVersion()`** |
| 成功/失败 | `deleteById(group_id)` / `defer(group_id)` | 不按版本删除，也不按版本延后 |

全仓 `src` 精确检索 `getVersion(`、`version = version + 1` 只命中 mapper SQL 与迁移脚本；没有任何 Java 代码把领取到的版本和任务行当前版本做比较，也没有 `WHERE version = ?` 的条件更新。类注释写着“锁定任务行直至 ES 写完，阻止旧任务覆盖新版本”，但按源码可证明的机制是：

- **特定前提下任务不丢**：同步事务持有任务行锁期间，同组 `enqueue` 需要等锁；**在 S（同步）与 W（写入）都成功提交、没有其他并发写者、等待没有超时/死锁的前提下**，S 删除或延后之后，W 的 `enqueue` 会留下一条可领取的任务行（§3.4 时间线与 `RepairDatabaseIntegrationTest#newMutationAfterLockedSyncCannotLoseItsJob` 的库层断言，测试只覆盖该交错）。W 异常/回滚，或提交结果不明，都不能保证留下新任务。
- **不是版本比较**：`version` 只是被递增、被查出、被忽略。它**本身**不能阻止旧任务覆盖新数据；如果有人绕过 `enqueue` 直接改 `sys_qa_info`/`sys_qa_es`，任务行和版本都不会变化，同步器下一次仍按当前库内容重建——这恰好会读到新数据，但这是“重读当前库”的效果，不是版本守卫。

所以不要写“版本号保证旧任务不会覆盖新值”。准确说法是：**当前实现在 S/W 均成功提交等前提下依靠行锁 + 提交后重新入队来避免新任务丢失；`version` 字段没有参与任何裁决。**

### 3.4 并发时间线 C1：同步持锁期间，同组又来一次写入（条件化）

初始条件：任务行 `(group_id=90001, version=5, next_attempt_at=过去某时刻)` 已存在；同步事务 S 与管理员写事务 W 各用一个数据库连接；隔离级别 `READ_COMMITTED`。

**前提（缺一不可，否则下面结论不成立）**：S 与 W 都最终**成功提交**；没有其他写者同时改同一组；W 的锁等待没有超时、没有死锁；S/W 的语句交错如图。若 W 异常/回滚、S 回滚，或锁等待超时，则“留下新任务”不再有保证。

| 时刻 | S（同步） | W（管理员改问题） | 可观察/可证明 |
|---|---|---|---|
| t0 | `claimBatch()` 锁住 90001 行，进入批次 | — | 该行被 S 排他锁持有，直到 S 结束事务 |
| t1 | 开始 `deleteByQuery`/读数据 | `updateQuestion`：`FOR UPDATE` 查映射、更新 `sys_qa_es.question`，然后调 `enqueue(90001)` | W 的问题更新不受 S 的任务行锁影响（锁的是 `sys_qa_sync_job`，不是 `sys_qa_es`）；W 的 `enqueue` 要 upsert 同一任务行，在锁仍持有时**等待** |
| t2 | 依次 Embedding、写 ES、refresh | 仍在等锁（前提：未超时/未死锁） | S 此时读到的 `QaElasticMap.question` 是 W 未提交前的旧问题——这是该交错下的**推演假设**，`READ_COMMITTED` 下每个语句看已提交数据，源码没有固定两事务的语句顺序 |
| t3a（S 删除成功分支） | `deleteById(90001)` → 提交，释放锁 | 等待恢复：`enqueue` 发现行已被删 → **插入新行 `version=1, next_attempt_at=NOW()`**；W 提交 | S/W 都提交后：任务表有 90001 一行且立即到期（库层测试覆盖该交错） |
| t3b（S defer 分支） | `synchronizeGroup` 或 `deleteById` 抛异常 → `catch` 内 `defer(90001)` → 提交，释放锁 | 等待恢复：原行仍在（version 5）→ upsert 使 **`version=6`** 且 `next_attempt_at=CURRENT_TIMESTAMP`；W 提交 | S/W 都提交后：任务表仍有该行，版本 6，立即可到期；`defer` 的 30 秒延后被这次 `enqueue` 重置 |
| t4 | — | — | 下一次调度才可能取到新任务；在那之前，用新问题搜到的旧 ES 文档可能因 `authoritative` 的 question 文本不等被丢弃（R1 的 T3） |

**可证明（在上述前提下）**：同一任务行不会被两个事务同时持有；删除分支留下 `version=1` 的新行，延后分支留下 `version=6` 的 upsert 行；两者都在 W 成功提交后才到期。**推导/前提**：t2 读到旧问题取决于语句交错；“`enqueue` 等待”不等于“必然等到”，超时/死锁/回滚都会改变结果。**无法证明**：t4 的具体时间、中间某次查询一定返回什么。本次 S 把旧 question 写进 ES 后，用户用**新问题**搜索时旧文档未必进入候选；若用**旧问题**查到它，`authoritative` 在本次回查看到新 `mapping.question` 时会丢弃该候选（R1 已讲）。依据：`QaSyncJobMapper#enqueue/#claimBatch/#defer`、`QaIndexSynchronizer#synchronize`、`RepairDatabaseIntegrationTest#newMutationAfterLockedSyncCannotLoseItsJob`（仅覆盖 t3a 类交错）。

### 3.5 并发时间线 C2：两个同步者 + 响应丢失（条件化）

初始条件：两个同步调用 S1、S2（不同调度线程或不同实例，同一数据库）；任务行 `(90001, version=5, 到期)` 与 `(90002, version=2, 到期)` 都存在；S1 先执行 `claimBatch`。

**前提**：S1 的 `defer(90001)` 与 `deleteById(90002)` 都成功且 S1 事务最终提交；没有后续 `enqueue(90001)` 重置到期时间；进程未在事务中途崩溃。`defer` 失败、提交失败或崩溃时下面多条结论不成立。

| 时刻 | S1 | S2 | 说明 |
|---|---|---|---|
| t0 | `claimBatch()` 锁住 90001、90002（都在 `LIMIT 5` 内） | 稍后执行 `claimBatch()` | S2 的查询按 `SKIP LOCKED` 跳过两行，返回空；**可证明**：同一任务行不会被两个事务同时领取 |
| t1 | 对 90001：`deleteByQuery` 成功；`info != null`；第 1 条问题 `es.index` 已发送但**响应丢失**，客户端看到异常 | 空闲 | 外部 ES 是否已应用写入，源码无法判断 |
| t2 | `catch` → `defer(90001)`：SQL 用执行时数据库 `CURRENT_TIMESTAMP + 30秒` 写 `next_attempt_at`，任务保留；继续处理 90002 成功 → `deleteById(90002)`；若事务提交成功才释放两行锁 | — | **前提成立时**：90001 保留、90002 任务删除；一个组失败后循环能继续，是因为 `defer` 正常返回，不是无条件隔离 |
| t3 | — | 下一次 `claimBatch` | `next_attempt_at` 约从 t2 的 SQL 执行时点起 +30 秒，**不是从提交时刻起算**；若批次在 t2 后又跑很久，提交后剩余等待可能不足 30 秒；后续同组 `enqueue` 可把它重置为立即可领。`version` 仍是 5（`defer` 不改版本） |
| t4 | 若之后重领，`synchronizeGroup(90001)` 先再次 `deleteByQuery` | — | **可证明**：重试从整组删除开始，不是“接着上次续写”；**不可证明**：t1 那次写入是否生效、删除的旧文档此刻是否对搜索可见 |

**可证明（前提成立时）**：领取互斥；`defer` 成功时任务保留并延后；重试从删除整组开始。**无法证明**：响应丢失那次 ES 操作是否生效；`defer` 自身失败或事务回滚后任务状态；“30 秒后一定重试成功”“最终一定与 MySQL 一致”。`defer` 只改 `next_attempt_at`，不补偿 ES，也不保证下一次成功。依据：`QaSyncJobMapper#defer`、`QaIndexSynchronizer#synchronize/#synchronizeGroup`。

## 四、`synchronizeGroup` 的真实顺序与可见性

### 4.1 源码逐行顺序

`agentbase/rag/QaIndexSynchronizer.java#synchronizeGroup`（源码节选：保留全部执行语句与条件，删去 import 与字段声明）：

```java
void synchronizeGroup(Long groupId) throws java.io.IOException {
    if (!es.indices().exists(e -> e.index("qa_knowledge_base")).value()) {
        try {
            es.indices().create(c -> c.index("qa_knowledge_base").mappings(m -> m
                    .properties("groupId", p -> p.keyword(k -> k))
                    .properties("question", p -> p.text(t -> t))
                    .properties("answer", p -> p.text(t -> t.index(false)))
                    .properties("questionVector", p -> p.denseVector(v -> v.dims(1024).index(true)))));
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException race) {
            if (!"resource_already_exists_exception".equals(race.error().type())) throw race;
        }
    }
    var deleted = es.deleteByQuery(d -> d.index("qa_knowledge_base")
            .query(q -> q.term(t -> t.field("groupId").value(groupId.toString()))));
    if (deleted.timedOut() || !deleted.failures().isEmpty()) throw new IllegalStateException("旧索引删除未完成");
    QaInfo info = infos.selectOne(new LambdaQueryWrapper<QaInfo>().eq(QaInfo::getGroupId, groupId));
    if (info == null) return;
    for (QaElasticMap question : questions.selectList(new LambdaQueryWrapper<QaElasticMap>()
            .eq(QaElasticMap::getGroupId, groupId))) {
        QaDocument document = new QaDocument();
        document.setId(question.getElasticId().toString());
        document.setGroupId(groupId.toString());
        document.setQuestion(question.getQuestion());
        document.setAnswer(info.getAnswer());
        float[] vector = embedding.embed(question.getQuestion());
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        document.setQuestionVector(values);
        es.index(i -> i.index("qa_knowledge_base").id(document.getId()).document(document));
    }
    es.indices().refresh(r -> r.index("qa_knowledge_base"));
}
```

真实顺序（每一步都可能抛异常，除非另有说明）：

1. **判存 + 建索引**：索引不存在才 `create`，映射里 `groupId=keyword`、`question=text`、`answer=text(index=false)`、`questionVector=dense_vector(dims=1024, index=true)`。并发建索引遇到 `resource_already_exists_exception` 被吞掉，其他 `ElasticsearchException` 原样抛出。这里没有声明 `ik_smart` 分词或 `cosine` 相似度；注解里的声明是否生效未知（R1 §6.3 已记）。
2. **删旧文档**：`deleteByQuery` 按 `groupId` 精确删除整组旧文档；`timedOut()` 或 `failures()` 非空就抛 `IllegalStateException`。注意它检查的是“有没有失败/超时”，不检查“删了多少条”，也不保证删除对搜索立刻可见。
3. **读权威行**：`infos.selectOne(groupId)` 取当前答案。
4. **`info == null` 直接 return**：这是必须记住的分支。组已被删除时，第 2 步的删除请求可能已生效，但**后面的 `refresh` 不执行**。`info == null` 只证明“本次不执行 refresh 且 synchronizeGroup 正常返回”，调用方因此会尝试 `deleteById`；任务最终是否被删，还要以 `deleteById` 成功且事务最终提交成功为前提，崩溃或提交失败/结果不明时不能断言任务已删，也就不能断言“不会为已删组重试”。代价是这次删除的可见性只能依赖 ES 自身刷新或其他调用，源码没有保证。
5. **逐问题重建**：对每个 `sys_qa_es` 映射，文档 id 取 `elasticId.toString()`，`question` 与当刻向量来自同一条映射文本，`answer` 取第 3 步读到的 `info` 快照。`embedding.embed` 是同步调用，发生在同步事务持有任务行锁期间（§3.2）。
6. **末尾 `refresh`**：只有 `info != null` 且前面没异常才执行到。`es.index` 的返回结果没有逐条检查；源码能确认的只是“调用未抛异常”。`deleteByQuery` 检查了 `timedOut()`/`failures()`，而 `refresh` 调用未检查响应里的分片失败。因此“不抛异常”不能直接等同于“所有分片写入成功并立即可搜索”：实际可搜索可见性还取决于 ES 集群状态、分片刷新与外部环境，本章未实测。

### 4.2 部分成功、崩溃与响应丢失：不能推断什么

- **部分成功后抛异常**：例如第 2 步删除成功、第 5 步写了第 1 条文档后第 2 条 Embedding 失败。ES 不在 MySQL 事务里，`catch` 只调用 `defer`，**不会回滚已经发生的 ES 写入**。若 `defer` 成功且事务提交成功，任务保留并在到期后可能重试；重试从第 2 步“删整组”开始，把部分写入清掉重建。若一直失败，ES 可能长期处于“旧文档已删、新文档不全”的状态；用户查询可能返回更少组或空。`defer` 失败、提交失败或进程崩溃时任务状态不同，不能套用“30 秒后重试”。
- **进程崩溃，分两种阶段**：① 崩溃发生在事务**提交前**：MySQL 事务回滚，`deleteById`/`defer` 都不生效，任务行保持领取前的值（通常已到期），重启后可能重新领取；② 崩溃发生在**已提交或提交结果不明**：事务是否已落库无法从崩溃本身判断，任务可能被删、可能仍保留，需回查 `sys_qa_sync_job`。这两种情况下，已经发给 ES 的 `deleteByQuery`/`index`/`refresh` 都不受 MySQL 事务回滚影响，是否生效、生效到什么程度是独立问题。
- **响应丢失**：调用方看到异常，走 `catch` 并尝试 `defer`；但 ES 可能已经应用了该操作。源码没有基于响应内容判断“是否真的写了”，也没有幂等标识；`es.index` 用的是文档 id，重复写同一个 id 是覆盖语义（ES 通用行为），但“这一次到底写没写”仍不能从异常反推。若 `defer` 本身失败或事务回滚，任务也不会按“30 秒后重试”演进。
- **不能保证无条件最终一致**：只有在“任务最终被领到、`synchronizeGroup` 完整成功、`refresh` 调用未抛异常且实际可搜索可见、之后没有新的并发写入或失败重试”的条件下，ES 才趋于与 MySQL 一致。持续失败、任务被锁/批次长期挤掉、`info == null` 分支跳过刷新、或写库绕过 `enqueue`，都会打破这个条件。R1 的 `authoritative` 能挡住一部分旧候选，但那是**读取侧的有限校验**，不是索引收敛保证。

### 4.3 `fixedDelay` 不是“入队到可搜”的上界

`fixedDelayString="${rag.sync.interval-ms:5000}"` 是 Java 注解默认值；`application.yaml` 没有显式配置 `rag.sync.interval-ms`（只检索到订单超时相关的 `poll-interval-ms: 5000`，那是另一套机制）。外部配置中心是否覆盖默认值未知。即便真是 5000ms，它的含义也是：

> 本次 `synchronize()` **执行结束**之后，再等待约 5000ms，才发起下一次调度。

要让**新索引候选**进入某次结果，通常需要经过：当前正在执行的同步调用结束 → `fixedDelay` 等待 → 任务到期判断 → `claimBatch` 的 `LIMIT 5` 与锁竞争 → 建索引/删除/读库/Embedding/写 ES/refresh 的实际耗时 → 该次查询的窗口、排名与 `authoritative`。其中任一项都能把它拉长到远超 5000ms：批次里前面 4 组失败且每组都要做 Embedding 与 ES 调用、同组任务被别的实例锁住（`SKIP LOCKED` 直接跳过）、失败 `defer` 后 `next_attempt_at` 被推后约 30 秒（从 SQL 执行时算，还可能被同组新入队重置）、执行器或调度线程被占满。源码没有任何一处计算或承诺“入队后最多 N 毫秒可搜”。

但要注意：若只是**改答案**、问题与向量未变，旧索引候选可能已经能返回新答案（§2.3 时间线 A），完全不必等待上面的重建链路。所以“入队→可搜”的耗时只对“新索引候选可见”这条路径有意义；对“返回新答案”没有固定下界，也没有上界。

## 五、检索预算：一个 deadline 管到底

### 5.1 起点与逐阶段是否受 deadline 直接限制

`agentbase/rag/RagService.java#searchAnswers` 的预算起点（源码节选，保留原始表达式）：

```java
public List<QaDocument> searchAnswers(String userQuery) {
    if (userQuery == null || userQuery.isBlank()) return List.of();
    List<CompletableFuture<?>> tasks = new ArrayList<>();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, searchTimeoutMillis));
```

`searchTimeoutMillis` 由 `@Value("${rag.search.timeout-ms:8000}")` 注入，8000 是 Java 默认值；`application.yaml` 没有该键。下表按真实执行顺序逐个阶段说明“deadline 是否直接限制它”：

| 阶段 | 源码动作 | deadline 是否直接限制 | 依据与后果 |
|---|---|---|---|
| 1 入口与空检查 | `null`/`isBlank()` → `List.of()` | 否 | 预算还没开始计时 |
| 2 计算 deadline | `System.nanoTime() + max(1, timeout)` | — | 绝对时间点，只算一次 |
| 3 Embedding 任务提交 | `supplyAsync(() -> embeddingModel.embed(userQuery), executor)` | 否（提交本身无超时） | 但提交耗时与排队耗时**消耗同一个 deadline**；提交点被拒绝则直接抛 `RejectedExecutionException` |
| 4 执行器排队/等待 | 任务在 `ragExecutor` 队列里等线程 | 否（无单独超时） | 等待时间计入后续 `remaining`，可能把预算耗光 |
| 5 Embedding 等待 | `embeddingFuture.get(remaining(deadline), NANOSECONDS)` | **是** | 两种可能：`remaining(deadline)` 在调用时剩余 ≤0 直接抛 `TimeoutException("知识库检索超时")`；若剩余为正，则把该剩余时长传给 `get`，等待到点仍未完成由 `get` 抛 `TimeoutException`。两者都在外层 catch 汇成 `RuntimeException("RRF Search failed")` |
| 6 两路 ES 任务提交 | 两次 `supplyAsync`，分别调 `searchVectorOnly`/`searchBm25Only` | 否（提交本身无超时） | 同样消耗共享预算；任一提交被拒绝走外层 catch |
| 7 两路排队/执行 | ES 客户端调用在线程里跑 | 否（无逐路超时） | 网络/ES 自身耗时不受本项目 deadline 直接控制，但会吃掉后续等待额度 |
| 8 汇总等待 | `CompletableFuture.allOf(vectorFuture, bm25Future).get(remaining(deadline), NANOSECONDS)` | **是** | `get` 在两次提交**之后**立即调用（不是等排队完成后才调用）。剩余 ≤0 → `remaining()` 直接抛；剩余为正 → `get` 最多等到该时点。只有两路都完成 `allOf` 才完成；`finally` 对未完成者 `cancel(true)` |
| 9 结果提取 | `vectorFuture.get()` / `bm25Future.get()`（无超时参数） | 否 | 能执行到此处说明 `allOf` 已正常完成，两个 future 应已完成；不是“再给一份预算” |
| 10 RRF 融合 | 两个 `for` 循环、`scoreMap.merge` | 否 | 纯 CPU 遍历，没有 `remaining()` 检查 |
| 11 排序/按组去重/Top5 | `sort` + collapse + `break` | 否 | 同上 |
| 12 MySQL 回查 | `authoritative` 两条 `selectList` | 否 | **不在 deadline 内**；R1 已说明它无本方法显式事务 |

结论：**只有第 5、8 两步的 `get(remaining(deadline), ...)` 受 deadline 直接约束**；第 3/4/6/7 步的时间会挤占同一份预算，第 9～12 步不在预算内。不能写成“每个阶段各有 8000ms”。

### 5.2 带数字的条件化示例

以下全部是**假设**，不是实测；用来说明“共享”到底共享什么。假设 `searchTimeoutMillis=8000`（Java 默认值），一次查询从 t0 进入 `searchAnswers` 并把 deadline 定为 t0+8000ms。注意代码顺序：**先 `supplyAsync` 提交，紧接着调用 `get(remaining(deadline), ...)`**；`get` 不是在排队结束之后才调用的。`remaining(deadline)` 在**调用那一刻**计算剩余，若为正就把它作为 `get` 的等待时长；此后等到点仍未完成，是 `get` 抛超时，不是 `remaining()` 抛。

**情形 A：预算够用**

1. t0：计算 `deadline = t0+8000ms`。
2. t0+0～t0+5ms：Embedding 提交成功；**紧接着在 t0+5ms 调用** `get(remaining(deadline))`，此时 `remaining = 7995ms`（为正），于是 `get` 最多等 7995ms。任务进入 `ragExecutor` 队列等 100ms。
3. t0+5～t0+105ms 排队，t0+105～t0+2105ms Embedding 执行 2000ms；future 在 t0+2105ms 完成，`get` 正常返回。此时距 deadline 还剩 `8000−2105 = 5895ms`。
4. t0+2110～t0+2115ms：向量、BM25 两路提交成功；**紧接着在 t0+2115ms 调用** `allOf.get(remaining(deadline))`，`remaining = 8000−2115 = 5885ms`（为正），`get` 最多等 5885ms。向量路排队 100ms、BM25 路排队 200ms。
5. 向量路 ES 调用 2500ms（t0+2215～t0+4715），BM25 路调用 4000ms（t0+2315～t0+6315）。两路都完成时 `allOf` 完成，`get` 在 t0+6315ms 正常返回，距 deadline 还剩 `8000−6315 = 1685ms`。
6. RRF 融合 + 去重 + `authoritative` 两条 MySQL 查询**不受 deadline 约束**：若共花 600ms，t0+6915ms 返回，仍在 8000ms 内；若共花 2000ms，则 t0+8315ms 返回——**总耗时超过 deadline，但这后段没有这项 deadline 检查，单凭耗时不会触发该超时**。这说明“deadline 不覆盖融合与回查”与“整次请求不超过 8 秒”是两回事。

**情形 B：一路慢导致整次超时**

把第 5 步 BM25 的 ES 调用改成 6000ms（t0+2315～t0+8315）。`allOf.get` 已在 t0+2115 用 `remaining = 5885ms` 开始等待；到 t0+8000ms 等待到点而两路未全完成，**由 timed `get` 抛 `TimeoutException`**——此处 `remaining(deadline)` 在 t0+2115 返回的是正数 5885ms，它自己没有抛异常。异常经外层 catch 包成 `RuntimeException("RRF Search failed")`；内层 `finally` 对未完成的 BM25 future 调 `cancel(true)`。向量路即使早已算完，结果也不会被提取，RRF 与回查都不会执行。**没有“用向量路结果兜底”的分支。**

**情形 C：一路先失败，另一路还在跑**

向量任务在 t0+3000 抛异常并完成，BM25 任务要到 t0+7000 才完成。`allOf` 要等**两路都完成**：t0+3000 时它还没完成，`get` 不会把向量异常交给调用方；到 t0+7000 两路都完成后，**`get` 抛 `ExecutionException`**（含向量路的异常）→ 同一个外层 catch。若 BM25 拖到 t0+9000，则 `allOf.get` 在 t0+8000 因等待到点抛 `TimeoutException`，调用方看到的是超时而不是那一路的异常。**这正是“一路失败而另一路未完成时可能先超时”的含义。**

三个情形共同说明：三个 `supplyAsync`（Embedding、向量、BM25）**不是各拿 8000ms**，而是从同一个 deadline 里扣时间；排队时间也从这份预算里扣。

### 5.3 `allOf` 与 `cancel(true)` 的边界

`RagService#searchAnswers` 的两处 `cancel(true)`（源码节选）：

```java
try {
    CompletableFuture.allOf(vectorFuture, bm25Future).get(remaining(deadline), java.util.concurrent.TimeUnit.NANOSECONDS);
} finally {
    if (!vectorFuture.isDone()) vectorFuture.cancel(true);
    if (!bm25Future.isDone()) bm25Future.cancel(true);
}
...
} catch (Exception e) {
    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    throw new RuntimeException("RRF Search failed", e);
} finally {
    tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
}
```

- 内层 `finally` 在等待结束时对未完成的检索 future 调用 `cancel(true)`；外层 `finally` 对所有未完成任务（含 Embedding）再调用一次。
- 对 `CompletableFuture` 而言，`cancel(true)` 的参数 `mayInterruptIfRunning` **不控制中断**：若取消赢过正常或异常完成，Future 转入“已取消/已完成”状态（`isDone()` 为真）。已经运行的 supplier 是否继续执行、底层 ES/Embedding HTTP 是否被关闭，源码与 API 都不能证明；排队但尚未开始的任务是否真的开始执行，还受执行器状态与取消时机竞争影响。**逻辑取消 ≠ 物理取消**，不能写“尝试中断工作线程”或“超时后底层查询已经停止”。
- `TimeoutException` 由等待线程上的 timed `get`（或调用时已无剩余的 `remaining()`）抛出，随后被包成 `RuntimeException("RRF Search failed")`；若 `get` 抛的是 `InterruptedException`，外层 catch 恢复的是当前等待线程的中断标志，`finally` 的 `cancel(true)` 只是对 Future 状态做尽力而为的处置。
- `allOf` 的异常路径很窄：它在两路都完成后才把其中一路的 `ExecutionException` 交给 `get`；两路都成功但其中 `future.get()` 之后的操作出错，也会进外层 catch。没有逐路降级分支。

### 5.4 `ragExecutor`：配置、饱和与拒绝

`config/AsyncWorkConfig.java` 中 `ragExecutor` 的 Java 硬编码：

```java
@Bean("ragExecutor")
public ThreadPoolTaskExecutor ragExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(32);
    executor.setThreadNamePrefix("rag-search-");
    return executor;
}
```

`RagService` 用 `@Resource(name="ragExecutor") Executor executor` 注入它，三次 `supplyAsync` 都复用这一个池。逐条说明影响：

- **排队**：核心线程 4 个，队列容量 32。前 4 个任务占满核心线程后，新任务进队列；排队时间计入共享 deadline（§5.1 第 4/7 步），不单独计时。高并发下“提交很快、真正开始很晚”，预算在队列里就被吃掉。
- **扩容与拒绝**：队列满后，按 JDK `ThreadPoolExecutor` 的通用规则会继续创建线程到 `maxPoolSize=8`；再满则拒绝。`AsyncWorkConfig` **没有显式设置拒绝策略**，按 `ThreadPoolTaskExecutor`/JDK 默认应抛 `RejectedExecutionException`（框架默认，本仓库未运行验证）；提交点抛出的这个异常进入 `searchAnswers` 外层 catch，变成 `RuntimeException("RRF Search failed")`。注意这是**提交失败**，不是“检索跑了 8 秒超时”，也不是某路 ES 失败。
- **不与其他池共用**：`chatExecutor`（8/32/64）是另一个 Bean，与 RAG 检索隔离；不要把它和 `ragExecutor` 的数字混用。
- **同步器不用这个池**：`QaIndexSynchronizer` 的 Embedding 是在调度线程里直接同步调用的，不经过 `ragExecutor`。所以“检索线程池饱和”与“同步任务领取不到”是两件不同的事。

严格限定实际异常路径：源码中能确定的只有“提交点 `RejectedExecutionException`”“`remaining()` 调用时已无剩余的 `TimeoutException`”“timed `get` 等待到点的 `TimeoutException`”“`get` 的 `ExecutionException`/`InterruptedException`”“ES 辅助方法把 `IOException` 包成的 `RuntimeException`”，它们统一进外层 catch 变成 `RuntimeException("RRF Search failed", e)`。至于 HTTP 是否映射为 500、`RagTool` 是否返回“知识库暂时不可用。”、客户端是否收到事件，R1 §2、§5.6 已给出条件，本章不重复断言。

## 六、设计取舍、能保证什么、不能保证什么

| 结论 | 级别与前提 |
|---|---|
| 正常路径写接口成功 → 事务已提交；**本次写方法不直接操作 ES** | 源码事实（写方法只写库 + `enqueue`，`verifyNoInteractions(es)` 是测试断言）；提交响应丢失时结果未知；“本次不碰 ES”不等于并发同步器没在改 ES；未运行 |
| 仅改答案时旧 ES 候选可能返回新答案，不必等重建 | 源码事实（`authoritative` 用本次 MySQL 行覆盖答案）+ 静态推导；以旧候选进入结果且本次回查读到新行为前提，不保证一定命中 |
| 同一任务行不会被两个同步事务同时领取 | 源码事实（`FOR UPDATE SKIP LOCKED` + 事务锁持有至提交）；多实例实际并发未实测 |
| 同步期间同组新入队在 S/W 均成功提交等前提下不会因本次操作丢任务 | 源码 + `RepairDatabaseIntegrationTest` 的库层断言支持；只覆盖特定交错，依赖 InnoDB 行锁、upsert、无超时/死锁/回滚；删除分支 version=1、延后分支 version=6 |
| `version` 参与旧任务覆盖裁决 | **不成立**：全仓无读取/比较，`defer` 也不改版本 |
| 失败任务保留且约 30 秒后可能可领取 | `defer` 写入基于执行时 DB 时间 +30 秒，且要 `defer` 与事务都成功；可被后续 `enqueue` 重置，长批次可使提交后等待不足 30 秒；不保证一定运行/成功 |
| `info == null` 时本次 `refresh` 不执行 | 源码事实（直接 `return`）；任务最终被删以 `deleteById` 成功 + 提交成功为前提；删除可见性无保证 |
| ES 与 MySQL 最终一定一致 | **不成立**：持续失败、崩溃、响应丢失、绕过 `enqueue` 的写入都能打破；`authoritative` 只是读取侧有限校验 |
| `fixedDelay=5000ms` 是入队到可搜的上界 | **不成立**：它是上次执行结束到下次调度的延迟；且返回新答案本就不必走重建链路 |
| 一次检索的每个阶段各有 8000ms | **不成立**：只有两处 `get(remaining(deadline), ...)` 受同一个绝对 deadline 限制；要区分 `remaining()` 直接抛与 timed `get` 抛 |
| `Future.cancel(true)` 的 `mayInterruptIfRunning` 会中断工作线程 / 停止底层 HTTP | **不成立**：该参数对 `CompletableFuture` 不控制中断；Future 状态变化不证明底层请求停止 |
| `ragExecutor` 队列满时的具体拒绝表现 | 配置未显式设置拒绝策略；框架默认行为未在本仓库实测 |

## 七、动手验证：四组纯静态练习

> 全部是只读源码追踪，不启动服务、不连中间件、不发请求。“预期观察”是按源码推导的结果，不是运行输出。

### 练习 A：同步成功与可见（写路径到搜索）

**初始条件**：一组问答已存在；管理员改答案；当前无同步任务。

**步骤**：
1. 打开 `agentbase/rag/RagService.java#updateAnswer`，圈出 `@Transactional`、`update(...)`、`syncJobs.enqueue(...)` 三处，确认没有 `esClient`。
2. 打开 `mapper/QaSyncJobMapper.java#enqueue`，写下首次插入时 `version` 与 `next_attempt_at` 各是多少。
3. 打开 `QaIndexSynchronizer#synchronize` 与 `#synchronizeGroup`，按 §4.1 的六步写出执行顺序，标出哪一步之后调用方才 `deleteById`。
4. 打开 `searchAnswers` 与 `authoritative`，回答：若用户搜索发生在同步器领取之前、问题文本未变，`doc.setAnswer(info.getAnswer())` 用的是哪个答案？这次返回新答案是否要求索引重建？

**推导与答案**：首次 `enqueue` 写 `version=1`，`next_attempt_at` 取 DDL 默认 `CURRENT_TIMESTAMP`（立即到期）；事务提交后任务才可见。完整重建链路是 `claimBatch` 领取 → 删旧 → 读 `info` → Embedding/写 ES → `refresh` → `synchronizeGroup` 返回 → `deleteById` → 事务提交成功。接口成功、任务已领、ES 已写、索引可见、某次查询命中是不同时间点的状态。但**改答案不必然等待这条链路**：旧 ES 候选若进入结果，`authoritative` 用本次 MySQL 查询读到的新行覆盖答案即可返回新值（前提：候选命中且回查看见新行；不保证一定发生）。即使已 `refresh`，某次查询仍可能因窗口/排名/回查不命中。

**失败解释**：若你写出“返回新答案必须先重建索引/同步事务提交”，把两条独立路径混成一条（§2.3）；若写“updateAnswer 直接更新 ES 副本”，回看方法体与 Javadoc 的差别（§1.1）。

### 练习 B：同组并发与失败重试

**初始条件**：任务 `(90001, v=5, 已到期)`；同步事务 S 持有行锁；管理员事务 W 要改同组问题。**前提**：S/W 都成功提交、无其他写者、锁等待未超时/未死锁。

**步骤**：
1. 在 `claimBatch` 的 SQL 上标出 `FOR UPDATE`、`SKIP LOCKED`、`LIMIT 5` 各自影响什么。
2. 按 §3.4 的 t0～t4 写出 W 的 `enqueue` 何时等待、何时恢复。
3. 分别写出两条分支：S 的 `deleteById` 成功 → W 的 upsert 插入新行；S 在第 2 条文档 Embedding 抛异常 → `catch` 调 `defer` → W 的 upsert 更新原行。
4. 两个分支最终 `version` 各是多少？谁改变了它？若 W 回滚呢？

**推导与答案**：S 删除成功分支：W 的等待在 S 提交后恢复，行已被删 → 插入 `version=1, next_attempt_at=NOW()`。S defer 分支：`catch` 调 `defer`（只改 `next_attempt_at`，基于执行时 DB 时间 +30 秒，不改版本，此时仍是 5）；W 恢复后对原行 upsert → `version` 从 5 递增为 **6**，并把 `next_attempt_at` 重置为当前时间。因此“只有 enqueue 会改 version”是对的，但最终值取决于 S 删除还是 defer。重试从“删整组”开始，不是续写。若 W 异常/回滚，不能保证留下新任务；若锁等待超时/死锁，两条分支都不成立。

**失败解释**：若写“defer 后最终 version 仍是 5”，漏了 W 的 upsert 再 +1；若写“版本号递增所以旧任务不会覆盖新数据”，把设计意图当成源码事实——全仓没有版本比较（§3.3）。

### 练习 C：共享预算与队列

**初始条件**：`searchTimeoutMillis=8000`（假设未被覆盖）；三个 `supplyAsync` 共用一个 `ragExecutor`（core 4 / queue 32 / max 8）。

**步骤**：
1. 在 `searchAnswers` 里找出所有 `remaining(deadline)` 调用点，写出它们分别嵌在哪个 `get` 里。
2. 按 §5.2 情形 B，把 BM25 路改成 6000ms，推算 `allOf.get` 在 t0+8000 会发生什么、向量路结果用不用得上。
3. 假设 Embedding 任务因队列满在**提交点**被拒绝，写出异常类型与最终包装结果。
4. 回答：`authoritative` 的两条 MySQL 查询花 600ms，会不会触发超时？

**推导与答案**：只有 Embedding 的 `get` 与两路 `allOf.get` 两处用 `remaining`，且都在提交后立即调用。按 §5.2 情形 B 的时点，`remaining()` 在 t0+2115 返回正数 5885ms，**超时来自 timed `get` 在 t0+8000 等待到点**，外层包成 `RuntimeException("RRF Search failed")`，向量路结果不被提取。提交点被拒绝抛 `RejectedExecutionException`，也进同一个外层 catch；它不是“跑了 8 秒超时”。`authoritative` 不在 deadline 内，600ms 不触发超时；若后段耗时足够长，总耗时可超过 deadline 而不报错。

**失败解释**：若写“融合和回查也各限时 8 秒”，把共享 deadline 误当成每阶段独立预算（§5.1）；若写“BM25 拖过 deadline 时 `remaining()` 抛超时”，混淆了两种 TimeoutException 来源。

### 练习 D：取消与观察边界

**初始条件**：两路 ES 任务已提交，向量路在 t0+3000 抛异常结束，BM25 路还在跑。

**步骤**：
1. 写出 `allOf` 在 t0+3000 是否完成、`get` 什么时候拿到异常。
2. 找出内层与外层 `finally` 的两处 `cancel(true)`，说明各自在哪些路径上执行。
3. 回答：能否从“调用了 `cancel(true)`”推出“底层 ES HTTP 已停止”？能否从“任务被 `defer`”推出“ES 什么都没写”？

**推导与答案**：`allOf` 要等两路都完成，t0+3000 时未完成；若 BM25 到 t0+7000 才结束，`get` 在 t0+7000 抛 `ExecutionException`，若 BM25 拖过 t0+8000 则 timed `get` 先抛 `TimeoutException`。两处 `cancel(true)` 的可确认效果只是把 Future 置为已取消/已完成；对 `CompletableFuture`，`mayInterruptIfRunning` 不控制中断，不证明执行线程、底层 ES HTTP 停止，排队任务是否真正执行也取决于竞争。`defer` 只改变任务的可领取时间（且需成功提交，30 秒从执行时算并可被 enqueue 重置），ES 可能已部分写入；两者都不能作为“外部操作未发生”的证据。

**失败解释**：若写“一路失败另一路立刻接管”，把 `allOf` 当成“任一 future 失败立即抛出”；若写“cancel(true) 尝试中断线程”，把 `CompletableFuture` 与其他 Future 实现混为一谈（§5.3、R1 §5.6）。

## 八、逐测试阅读卡

> 全部是**测试源码阅读，未执行**。读测试只说明作者写了什么断言，不等于行为已验证；本章不要求读者运行它们。

### 8.1 `src/test/java/com/fancy/rideagenthub/agentbase/rag/RagConsistencyRegressionTest.java`

- **门控**：无 `@EnabledIf*`，普通 JUnit 5。
- **真实资源与替身**：`QaInfoMapper`/`QaElasticMapMapper`/`QaSyncJobMapper`/`ElasticsearchClient`/`EmbeddingModel` 全是 Mockito `mock`；被测 `RagService` 与 `QaIndexSynchronizer` 是 `new` 出来的真实对象（后者用 `spy`）；`@BeforeAll` 手工 `TableInfoHelper.initTableInfo`，`@BeforeEach` 反射注入 `syncJobs` 与 `new SnowflakeIdWorker(1,1)`；无 `@AfterEach`、无数据库/ES 临时资源。
- **与本轮主题直接相关的断言**：

| 方法 | 具体断言 | 覆盖与不覆盖 |
|---|---|---|
| `addWritesDatabaseAndDurableJobWithoutCallingElasticsearch` | 两次 insert + `enqueue(anyLong())` + `verifyNoInteractions(es)` | 覆盖“写路径不碰 ES”；不验证真实 SQL/事务 |
| `updateIsStoredAndQueuedEvenWhenElasticsearchIsUnavailable` | 改答案入队且 `verifyNoInteractions(es)` | 只测 `updateAnswer`；不测 ES 真不可用 |
| `failedIndexSynchronizationRetainsJob` | `spy` 同步器，`doThrow(IOException)` 替掉 `synchronizeGroup(1L)`；断言 `never().deleteById(anyLong())` 且 `defer(1L)` | 覆盖“异常 → 保留任务 + defer”；`synchronizeGroup` 被替身，**不测真实 ES、SQL、事务代理或 `@Scheduled`** |
| `completedIndexSynchronizationAcknowledgesJob` | `doNothing()` 替掉 `synchronizeGroup(1L)`；断言 `deleteById(1L)` | 覆盖“成功 → 删任务”；同上 |
| `embeddingSharesTheOverallSearchDeadline` | 50ms 预算 + latch 卡住 embedding；1 秒内 `assertThrows(RuntimeException.class, searchAnswers("q"))` 且 `verifyNoInteractions(es)`；finally 释放 latch 并 `shutdownNow` | 覆盖“Embedding 等待共享总预算”；不测 8000ms 默认值、两路 `allOf`、真实网络 |

- **测试数据与清理**：全 mock，无真实表/索引；`embeddingShares...` 自建单线程执行器并在 `finally` 关闭；其余无临时资源。
- **不能证明**：真实 `claimBatch` 的 SQL 与行锁、事务代理、`version` 比较（该方法本就不存在）、ES 写入/刷新、多实例并发、真实超时时长、客户端可见性。`worker.synchronize()` 是直接在 `spy` 对象上调用，**不经过 Spring 代理**，因此这两个测试没有覆盖 `@Transactional` 语义。

### 8.2 `src/test/java/com/fancy/rideagenthub/RepairDatabaseIntegrationTest.java`

- **门控**：类级 `@EnabledIfSystemProperty(named="test.mysql.port", matches="\\d+")`，未设置则整类跳过。
- **真实资源与替身**：真实 MySQL（仅测试端口）+ 真实 MyBatis mapper（`SqlSessionTemplate` + `MybatisSqlSessionFactoryBean`）；`EmbeddingModel`、`ElasticsearchClient` 是 mock；`RagService` 为真实 `new` + 反射注入。
- **临时资源与清理**：`@BeforeAll` 建 `ride_repair_<uuid32>` 独立 schema 并执行 `sql/init.sql`；`@BeforeEach` 只在该 schema 内 `DELETE` 七张表（含三张 `sys_qa_*`）；`@AfterAll` 仅在 schema 名匹配 `ride_repair_[0-9a-f]{32}` 时 `DROP DATABASE`。不碰应用库。
- **与本轮主题直接相关的断言**：

| 方法 | 具体断言 | 覆盖与不覆盖 |
|---|---|---|
| `failedBatchRollsBackBothQaDataAndSyncJobs` | 批量中混入非法 DTO 抛 `IllegalArgumentException` 后，三张 `sys_qa_*` 表均 0 行 | 覆盖“同一事务回滚同时撤销问答与任务”；事务由 `TransactionTemplate` 驱动，**不是**service 上的 `@Transactional` 代理；不测真实 ES |
| `newMutationAfterLockedSyncCannotLoseItsJob` | 先用 `jobs.enqueue(1L)` 造任务；reader 事务 `SELECT ... FOR UPDATE` 持锁后，writer 的 `enqueue(1L)` 对应的 future 在 100ms 内未完成（`assertThrows(TimeoutException.class, writer.get(100ms))`）；放行后 reader `deleteById`、writer 成功，最终该组任务仍为 1 行 | 覆盖**这一特定交错**下“持锁期间 writer 未在 100ms 内完成、放行后任务仍在”的库层结果；“future 未完成”单独不能证明阻塞原因一定是数据库锁（也可能是线程调度等），也不测 `version` 防覆盖（无此逻辑）、`claimBatch`/`defer` 的异常、同步器代码与 ES；未运行 |

- **不能证明**：真实 ES/Embedding 端到端同步、`@Scheduled` 实际调度、多实例并发、`defer` 后 30 秒的重试结果、真实网络超时与取消。
- **注意**：两个测试都没有断言“版本比较”，因为源码没有；也没有用真实 Elasticsearch 验证索引内容或可见性。

## 九、一页复习

- **责任划分**：MySQL 存权威答案与任务，ES 存可检索副本与向量，任务表只记“哪组要重建、何时可领”。写接口只碰前两者中的 MySQL；不碰 ES 不代表并发同步器没在改 ES。
- **两种“搜到”**：只改答案且问题未变时，旧 ES 候选 + `authoritative` 本次回查就可能返回新答案，不必等重建；要让新索引候选可见，才要等领取、重建、刷新、查询与回查都配合。两者都不保证一定命中。
- **六种状态**：正常写路径先提交事务，再返回接口成功。同步器随后可领取任务并尝试写入，索引可见性与查询命中还要分别判断。正常路径接口成功意味着提交成功；提交响应丢失时结果未知。
- **领取**：`claimBatch` = `WHERE next_attempt_at<=NOW() ORDER BY ... LIMIT 5 FOR UPDATE SKIP LOCKED`；事务 `@Transactional(READ_COMMITTED)`，锁保持到 `synchronize()` 结束才释放；`LIMIT 5` 是每批上限。`deleteById` 在 try 内、`defer` 在 catch 内且自身失败可逃逸，批次不是无条件隔离。
- **版本真相**：`enqueue` 递增、`claimBatch` 查出、**无人使用**；没有 `WHERE version=?`，`defer` 不改版本。S/W 均成功提交等前提下靠行锁 + 提交后重新入队避免任务丢失：S 删除则 W 插入 version=1，S defer 则 W upsert 到 version=6。
- **同步顺序**：判存/建索引 → `deleteByQuery`（检查超时/失败）→ `selectOne(QaInfo)` → **`info==null` 直接 return，不 refresh** → 逐条 Embedding + `es.index` → `refresh` → 成功 `deleteById` / 抛异常则尝试 `defer(+30s)`；任务最终删除/保留都以事务提交成功为前提。
- **一致边界**：ES 不在 MySQL 事务内；部分成功、崩溃（提交前 vs 提交结果不明）、响应丢失后，哪些 ES 操作生效未知；持续失败不会无条件收敛。`authoritative` 是读取侧有限校验，不是索引一致性保证。
- **预算**：deadline 从 `searchAnswers` 入口起算，只有 Embedding 的 `get(remaining)` 与两路 `allOf.get(remaining)` 受它直接限制；`get` 在提交后立即调用；要区分 `remaining()` 调用时已无剩余的 TimeoutException 与 timed `get` 等待到点的 TimeoutException；提交/排队消耗额度，融合与 MySQL 回查不受限；`allOf` 等全部完成；`cancel(true)` 的 `mayInterruptIfRunning` 不控制中断，不证明底层请求停止。
- **线程池**：`ragExecutor` = 4/8/32，三个 `supplyAsync` 共用；同步器的 Embedding 不走它；拒绝策略未在 `AsyncWorkConfig` 显式设置，按框架默认处理，未实测。
- **配置**：`rag.sync.interval-ms:5000` 与 `rag.search.timeout-ms:8000` 都是 Java 注解/`@Value` 默认值，`application.yaml` 未显式配置，外部覆盖未知；`fixedDelay` 是上次执行结束到下次调度的延迟，不是入队可搜上界。
- **证据**：本章全部为源码静态推导与测试源码阅读，实际运行：无。

## 十、常见误区

1. **“接口返回成功，所以搜索已经能用了”**——错。接口成功只覆盖 MySQL 与任务入队已提交；是否命中还看候选、排名与回查；改答案时旧候选可能够用，改问题时通常要等重建。
2. **“返回新答案必须先重建索引、等同步事务提交”**——错。旧候选 + `authoritative` 本次回查就可能返回新答案（前提是候选命中且回查看见新行）；搜索可以发生在同步事务提交前。
3. **“`fixedDelay=5000ms` 意味着最多 5 秒后可搜”**——错。它是两次调度之间的延迟，还受批次、锁、失败延后与实际耗时影响，源码没有上界。
4. **“版本号防止旧任务覆盖新答案”**——错。`version` 只被递增和查出，没有任何比较；S/W 均提交等前提下靠行锁 + 重新入队避免任务丢失。
5. **“`defer` 会回滚已写的 ES 文档 / 30 秒后一定重试”**——错。`defer` 只改 `next_attempt_at`（执行时 DB 时间 +30 秒，可被 enqueue 重置）；ES 不在 MySQL 事务里；且要 `defer` 与事务都成功。
6. **“删除组后同步器会刷新索引”**——不一定。`info == null` 时 `synchronizeGroup` 直接 `return`，后面的 `refresh` 不执行；任务最终被删还需 `deleteById` 与提交成功。
7. **“`allOf` 在任一路失败时立刻抛错，另一路可以接管”**——错。`allOf` 等两路都完成；一路先失败另一路未完时，可能先到 deadline。
8. **“三个 `supplyAsync` 各有 8000ms / `remaining()` 一定在超时点抛异常”**——错。它们共享一个 deadline，`get` 提交后立即调用；剩余为正时是 timed `get` 等待到点抛 TimeoutException。
9. **“`cancel(true)` 尝试中断线程、已经终止底层 HTTP”**——错。对 `CompletableFuture`，`mayInterruptIfRunning` 不控制中断；Future 状态变化不证明底层请求停止。
10. **“测试里 `worker.synchronize()` 跑过一遍，说明 `@Transactional` 生效了”**——错。测试直接调用 `new`/`spy` 出来的对象，不经过 Spring 代理；且 `synchronizeGroup` 被 `doThrow`/`doNothing` 替身。
11. **“读过测试就是跑过测试”**——错。本章所有测试均为源码阅读，实际运行：无。

## 十一、自测与答案

> 先自己写答案，再展开对照。每题都要求给推导或源码位置，不只给结论。

<details>
<summary>1. 管理员只把答案从“30 天”改成“15 天”（问题文本未变）。请分别写两条时间线：(A) 旧 ES 候选就在这时返回新答案；(B) 新索引候选被搜索命中。重建与同步提交是不是 A 的必要条件？</summary>

**A（旧候选返回新答案，不要求重建）**：前提——更新已提交；旧 ES 文档仍在且对搜索可见；该文档进入 `match`/`kNN` 前 100、通过按组去重与 Top5；`authoritative` 五条校验通过，且本次 `qaInfoMapper.selectList` 读到已提交的新行。时间线：t0 `updateAnswer` 更新 MySQL 答案 + 入队 + 提交 → t1（同步可能尚未领取）用户 `searchAnswers` 召回旧 question 文档 → t2 `authoritative` 用**本次** MySQL 读到的新行 `doc.setAnswer(info.getAnswer())` → t3 返回新答案。同步重建既不是 t3 的前提，也不需要等同步事务提交。但“旧候选一定进入结果”“回查一定读到新行”都不成立：召回/排名是条件，两条查询无本方法显式快照。

**B（新索引候选可见）**：前提——任务到期并被领取；`synchronizeGroup` 删旧、读新行、Embedding、写 ES、`refresh` 都未抛异常且实际可搜索可见；若要求任务被清理还需 `deleteById` 与事务提交成功；本次查询仍要满足召回、排名与五条校验。时间线：t0 改答案并提交 → t1 `fixedDelay` 后某次调度 `claimBatch` → t2 删旧/重建/`refresh` → t3（可能早于同步提交）用户搜索命中新文档 → t4 `deleteById` + 提交。B 中“返回新答案”仍由 `authoritative` 本次回查决定，不是由新 ES answer 副本决定（answer 不参与检索）。依据：`RagService#updateAnswer/#searchAnswers/#authoritative`、`QaIndexSynchronizer#synchronize/#synchronizeGroup`、正文 §2.3。
</details>

<details>
<summary>2. 当前 `RagController` 调用带 `@Transactional` 的代理 service。正常成功路径下“接口返回成功”与“事务提交”是什么关系？提交失败、提交响应丢失、外层事务三个前提各改变什么结论？“写接口不直接操作 ES”等于“并发同步器没在改 ES”吗？</summary>

**正常成功路径**：控制器调用代理 service 方法；方法正常返回时代理完成提交；控制器随后 `Result.ok()`。因此在“无外层事务、提交成功、响应正常送达”的前提下，接口成功意味着该 service 事务已提交。它不表示 ES 已更新、任务已领取、索引可见或某次查询命中。

**前提变化**：① 提交明确失败 → 代理抛异常，控制器不会返回 ok，库内改动不生效；② 提交响应丢失 → 客户端没收到 ok，但服务端最终状态未知，不能断言回滚；③ 若调用方本身处于外层事务（当前 Controller 路径没有），service 只加入外层事务，提交发生在外层边界，方法返回不等于提交。

**“本次写方法不碰 ES” ≠ “此刻没有 ES 操作”**：写方法体确实没有 ES 调用（`verifyNoInteractions(es)` 是 mock 测试里的断言），但独立的定时同步器可能正在对同一索引做 `deleteByQuery`/`index`/`refresh`；两者不是同一事务，也不能由写接口结果推断同步器状态。依据：`RagController`、`RagService` 写方法、`QaIndexSynchronizer#synchronize`、正文 §1.1/§1.2。
</details>

<details>
<summary>3. `FOR UPDATE SKIP LOCKED` 和 `LIMIT 5` 各解决什么问题？行锁什么时候释放？</summary>

`FOR UPDATE` 给领取到的任务行加排他锁，使同一行不会被另一个同步事务同时领到；`SKIP LOCKED` 让查询跳过已被锁住的行而不等待，多个同步者因此可以并行处理不同组；`LIMIT 5` 限制一次 `synchronize()` 最多处理 5 组，避免单批无限增长。锁在 `synchronize()` 的 `@Transactional(READ_COMMITTED)` 事务里持有，循环中还做了 Embedding 与 ES 调用，**直到方法返回、事务提交或回滚才释放**，不是每条任务处理完就释放。依据：`QaSyncJobMapper#claimBatch`、`QaIndexSynchronizer#synchronize`。
</details>

<details>
<summary>4. 追踪 `version` 的一生，说明它能不能阻止旧任务覆盖新数据。</summary>

`init.sql` 定义 `version BIGINT NOT NULL`；`enqueue` 首次写 1，重复键时 `version=version+1` 并把 `next_attempt_at` 重置为当前时间；`claimBatch` 的 `SELECT group_id, version` 把它映射进 `QaSyncJob`；此后 `synchronize()` 只用 `job.getGroupId()`，**没有任何 `getVersion()`、没有 `WHERE version=?`**；成功 `deleteById(group_id)`、失败 `defer(group_id)` 都不按版本操作。所以 `version` 不参与裁决，不能阻止旧任务覆盖新数据。真正防止“新入队丢失”的是任务行锁 + 提交后重新入队。`RepairDatabaseIntegrationTest#newMutationAfterLockedSyncCannotLoseItsJob` 只断言了库层前提，没有断言版本比较。依据：`QaSyncJobMapper`、`QaIndexSynchronizer`、`init.sql`、全仓 `getVersion` 检索。
</details>

<details>
<summary>5. 同步事务锁住任务行（初始 version=5）时，管理员又改同组问题并 `enqueue`。在 S/W 均成功提交、无其他写者、锁等待无超时/死锁的前提下，分别写 S 删除成功与 S defer 两条分支的最终 version 与到期时间；哪些结论在无前提时不成立？</summary>

**S 删除分支**：S 的 `deleteById` 成功并提交、释放行锁 → W 的 upsert 发现行已不存在 → 插入新行 `version=1, next_attempt_at=NOW()`；W 提交后才对其他事务可见并立即可领。**S defer 分支**：`synchronizeGroup` 或 `deleteById` 抛异常 → `catch` 调 `defer`，SQL 用执行时数据库 `CURRENT_TIMESTAMP + 30 秒` 写 `next_attempt_at`，不改版本（仍 5）→ W 的 upsert 对原行 `version+1` 变成 **6**，并把 `next_attempt_at` 重置为当前时间；W 提交后立即可领，defer 的 30 秒被覆盖。

**去掉前提后不成立**：`enqueue` 等待不等于“必然等到”——锁等待可能超时/死锁；W 异常或回滚会撤销它自己的 upsert，不保证留下新任务；S 回滚则删除/defer 不生效；提交响应丢失时最终状态未知。库层测试只断言了“持锁 → 放行 → reader 删除 → writer 成功”这一特定交错，且 100ms future 未完成本身不单独证明阻塞原因一定是数据库锁。ES 在这期间读到旧值属推演，取决于语句交错；中间查询命中与否、重试时间都不可断言。依据：`QaSyncJobMapper`、`QaIndexSynchronizer`、`RepairDatabaseIntegrationTest`、正文 §3.4。
</details>

<details>
<summary>6. `synchronizeGroup` 里 `info == null` 会发生什么？后面的 `refresh` 还执行吗？任务会怎样？</summary>

`deleteByQuery` 先删旧文档；随后 `infos.selectOne(groupId)` 返回 null（该组已被删），方法**直接 `return`**，所以后面的逐条 Embedding/写 ES 与 `es.indices().refresh(...)` **都不执行**。`info == null` 只证明“本次不执行 refresh 且 synchronizeGroup 正常返回”。调用方因此**尝试** `deleteById(groupId)`；任务最终被删以 `deleteById` 成功且事务提交成功为前提——若提交失败、崩溃或结果不明，不能断言任务已删、也不能断言“不会为已删组重试”。旧文档删除请求可能已生效，但这一次没有显式刷新，可见性依赖 ES 自身刷新或其他调用；`refresh` 即便被调用，其响应是否含分片失败源码也未检查。依据：`QaIndexSynchronizer#synchronizeGroup/#synchronize`。
</details>

<details>
<summary>7. 一次同步在第 2 条文档的 Embedding 处抛异常，之前已删旧文档、已写第 1 条文档。ES 会回滚吗？任务处于什么状态？</summary>

不会回滚。ES 不在 MySQL 事务内；`synchronizeGroup` 或 `deleteById` 抛出的 `Exception` 进入 `catch`，**尝试** `defer(groupId)`：若 `defer` 与事务都提交成功，任务保留、`next_attempt_at` 按执行时数据库时间 +30 秒、`version` 不变，循环才继续下一组；若 `defer` 自身失败则逃逸，整批可能回滚（ES 写入不会随之回滚）。ES 可能处于“旧文档已删、第 1 条新文档已写、第 2 条没写”的状态。之后若任务到期、被重新领取且 `synchronizeGroup` 完整成功，重试会从 `deleteByQuery` 删整组开始重建，覆盖部分写入；但“30 秒后一定重试成功”不成立——30 秒只是可能到期，可被后续 enqueue 重置，也可能持续失败。若进程在提交前崩溃，任务保持领取前状态；提交结果不明时任务状态需回查。依据：`QaIndexSynchronizer#synchronize/#synchronizeGroup`、`QaSyncJobMapper#defer`。
</details>

<details>
<summary>8. `allOf(vectorFuture, bm25Future).get(remaining(deadline))` 中，向量路在 3 秒抛异常、BM25 路 7 秒才完成，调用方什么时候、拿到什么错误？如果 BM25 拖到 9 秒呢？</summary>

`allOf` 要等两路都完成。BM25 7 秒完成时，`get` 在 7 秒左右抛 `ExecutionException`（含向量路异常），外层 catch 包成 `RuntimeException("RRF Search failed")`；不会在 3 秒“立刻失败”。若 BM25 拖到 9 秒，deadline 是 8 秒：假设时间从检索 deadline 的起点计且两路提交时预算仍有剩余，`allOf.get` 用当时的剩余预算开始等待（已经扣掉 Embedding 和提交耗时）。约到第 8 秒时由 **timed `get` 等待到点**抛 `TimeoutException`——不是 `remaining()` 在 8 秒抛，它在调用时返回的是正数。同样包成 `RuntimeException("RRF Search failed")`，内层 `finally` 对未完成的 BM25 future 调 `cancel(true)`。若调用 `get` 时剩余已经 ≤0，才是 `remaining()` 直接抛 `TimeoutException("知识库检索超时")`。两种超时都没有“用另一路结果兜底”；`CompletableFuture.cancel(true)` 的 `mayInterruptIfRunning` 不控制中断，不证明底层请求停止。依据：`RagService#searchAnswers/#remaining`、正文 §5.2/§5.3。
</details>

<details>
<summary>9. 三个 `supplyAsync` 和 `ragExecutor` 的关系是什么？队列满时会怎样？为什么说这不是“每个阶段 8 秒”？</summary>

Embedding、向量、BM25 三个 `supplyAsync` 共用 `@Resource(name="ragExecutor")` 注入的同一个执行器（core 4 / max 8 / queue 32，硬编码在 `AsyncWorkConfig`）。任务提交后排队等待，排队时间计入从方法入口算起的同一个绝对 deadline；**`get(remaining)` 在提交后立刻调用，不是在排队完成后**，所以队列里的时间会消耗它传给 `get` 的等待额度。提交点若被拒绝（队列满且线程到上限，按框架默认抛 `RejectedExecutionException`）会直接进外层 catch 变成 `RuntimeException("RRF Search failed")`。只有 Embedding 的 `get(remaining)` 与两路 `allOf.get(remaining)` 用这个 deadline；RRF 融合与 `authoritative` 不受它限制，故总耗时可超过 8 秒而不报错。所以不存在“每阶段各 8 秒”。同步器的 Embedding 不走这个池，是调度线程直接调用。依据：`AsyncWorkConfig`、`RagService#searchAnswers`、`QaIndexSynchronizer#synchronizeGroup`。
</details>

<details>
<summary>10. 两个相关测试各能证明什么、不能证明什么？请指出一个“测试名与断言不完全一致”或“容易误读”的地方。</summary>

`RagConsistencyRegressionTest` 无门控、全 mock；`failedIndexSynchronizationRetainsJob` 用 `spy`+`doThrow(IOException)` 替掉 `synchronizeGroup`，断言“不删任务且 `defer(1L)`”，`completedIndexSynchronizationAcknowledgesJob` 断言成功删任务；`embeddingSharesTheOverallSearchDeadline` 用 50ms 预算验证 Embedding 等待共享总预算。它不能证明真实 SQL、行锁、`@Transactional`、ES 写入或刷新——尤其 `worker.synchronize()` 是直接调用 `new`/`spy` 对象，不经过 Spring 代理。`RepairDatabaseIntegrationTest` 有 `@EnabledIfSystemProperty("test.mysql.port")` 门控，用真实 MySQL 临时 schema 与真实 mapper：`failedBatchRollsBackBothQaDataAndSyncJobs` 断言三表回滚为 0 行；`newMutationAfterLockedSyncCannotLoseItsJob` 断言持锁且未放行时 writer 的 Future 在 100ms 内未完成、放行后任务仍为 1 行。后者验证的是库层前提，不是 `version` 比较或完整同步器。容易误读处：`embeddingSharesTheOverallSearchDeadline` 的预算是测试里反射设置的 50ms，不是 8000ms 默认值；`failedOrDeletedDocumentsAreNeverServedFromEsAlone` 的名字讲“ES 单独召回”，实际断言是对无映射文档 `authoritative(...).isEmpty()`（细节见 R1 §8.1）。两者都**未运行**。依据：两个测试源码逐方法阅读。
</details>

## 十二、源码核验入口与导航

- **本章主讲源码**（相对 `src/main/java/com/fancy/rideagenthub/`）：`agentbase/rag/QaIndexSynchronizer.java`（`synchronize`、`synchronizeGroup`）；`mapper/QaSyncJobMapper.java`（`enqueue`、`claimBatch`、`defer`）；`agentbase/rag/RagService.java`（`searchAnswers`、`remaining`、`authoritative` 与五个写方法）；`config/AsyncWorkConfig.java`（`ragExecutor`）；`domain/entity/QaSyncJob.java`；`RideAgentHubApplication.java`（`@EnableScheduling`）。
- **配置、SQL 与 API 文档**（完整仓库相对路径）：`src/main/resources/sql/init.sql`、`src/main/resources/sql/migrations/20261113_security_consistency.sql`（任务表 DDL 与存量入队）；`src/main/resources/application.yaml`（仅确认 `rag.*` 未显式配置，不输出密钥）；`src/main/resources/api/rag-consistency.md`（接口侧口径）。
- **测试源码**：见 §八两张阅读卡，全部只读、**未运行**。
- **上一章**：[第 21 章 RAG 检索与答案溯源](01-retrieval-and-grounding.md)（R1）——检索链与答案溯源；本章不重复 RRF 手算。
- **下一章**：[第 23 章 三条完整业务旅程](../06-practice-and-review/01-trace-three-journeys.md)（P1，已通过复审）——客服咨询旅程会再次经过这里的索引同步与预算边界。
- **相关专题**：[第 13 章 工单池索引与统计缓存](../03-support-and-cache/02-ticket-index-and-cache.md)（另一种索引落后与回退）、[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)（锁令牌与回填版本的对照）、[第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)（一致性思路对照）。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、测试与 API 文档写完整仓库相对路径。定位优先用“类 + 方法”，行号会漂移。

**状态：已通过复审（P6-2，2026-11-19），并于 2026-11-22 独立复核。** R2-R1～R5 已静态复核；本次复核删除了一处重复段落并核对全部时点算术。检查范围与结论见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)。
