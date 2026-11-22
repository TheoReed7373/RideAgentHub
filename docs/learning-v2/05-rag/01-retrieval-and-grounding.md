# 第 21 章 RAG 检索与答案溯源：从一条问答到可核对的答案

> 虚构管理员在后台录入一条规则：“预约订单可以提前多久下单？”——同一个答案挂着两个问法。几分钟后，虚构乘客林小雨在客服会话里问“订单可以提前多久预约”。系统并没有去翻提示词，也没有把模型凭记忆写的话当答案：它把问题变成向量、和关键词检索各出一份榜单，用 RRF 合成候选，再回到 MySQL 查“这条候选对应的映射还在吗、那次查询读到的答案是什么”，最后把核对过的文本交给模型组织语言。
>
> 本章只讲**检索与答案溯源**：一条问答在 MySQL 与 ES 里各是什么、两路检索怎样合并、为什么“搜到”不等于“可返回”。索引任务如何领取、重试、超时预算与 `Future.cancel` 属于第 22 章 R2，这里只交代读懂检索所必需的最小边界，不提前下结论。
>
> **本章没有启动服务、运行测试或脚本、连接 Redis/MySQL/Elasticsearch、调用 Embedding/模型/HTTP，也没有读取凭据。** 除明确标注“测试源码阅读”的地方，结论都是源码静态推导；所有 ID、问题文本、向量、分数与时间线均为虚构示意，不是实测输出。

## 学习目标

读完本章，你应该能：

1. 用虚构的一条问答，画出它在 `sys_qa_info`、`sys_qa_es`、`sys_qa_sync_job` 与 ES 索引 `qa_knowledge_base` 里的真实字段，说清 `groupId`、`elasticId`、ES 文档 `id` 各自是什么，以及为什么“答案”只以 MySQL 为准。
2. 分别走通管理员 HTTP 搜索与客服 Agent 工具两条入口：说出各自的身份来源、参数、是否经过模型、返回值的真正来源，并指出为什么工具描述里的“必须调用”不是授权。
3. 按 `RagService#searchAnswers` 的真实顺序逐步复述：空检查、预算起点、Embedding、BM25/向量两路并行、RRF 融合、按组去重、Top5、回 MySQL 校验；给定三条候选能手算分数、排序与淘汰结果。
4. 对“改答案但未同步”“改问题但未同步”“删除后仍被召回”“单路失败/超时”四种情形，写出条件明确的时间线，区分源码保证、静态推导与未知，不把有限校验说成强一致。
5. 解释 BM25、向量 Embedding、RRF 各自解决什么、代价是什么，并用源码证明项目怎么用；能说明 `config/chat-es-setting.json` 为什么不能当作当前启用的索引配置。
6. 逐类阅读 `RagConsistencyRegressionTest` 与 `RepairDatabaseIntegrationTest`，说出门控、真实组件与替身、每条断言覆盖什么、清理范围与未覆盖点。

## 前置知识与阅读时间

- **硬前置**：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)（F3）。你需要知道 `@Transactional` 的边界、事务回滚会撤销库内写入、以及“提交后才可见”的基本含义。
- **推荐前置**：[第 20 章 外部工具与提示词边界](../04-chat-and-agent/05-external-tools-and-prompts.md)（C5）。本章会从 `RagTool` 的调用位置讲起，但会完整重述检索链，不读 C5 也能跟上；只是会少一环“模型为什么请求这个工具”。
- 需要时回看：[第 17 章 分类路由与工具循环](../04-chat-and-agent/02-routing-and-tool-loop.md)（C2，工具注册/手写分派）、[第 19 章 三层记忆与工具结果反查](../04-chat-and-agent/04-memory-and-tool-results.md)（C4，工具回执）、[第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)（S3，缓存一致性思路的对照）。它们都不是硬前置。
- 估计阅读时间：75～95 分钟（含手算与四组练习；不含 R2）。

## 一、业务场景与最少概念

### 1.1 一条规则、两个问法、三处 ID

虚构管理员录入一组问答：

- 答案：`乘客最早可在出发前 30 天、最晚在出发前 15 分钟预约用车。`
- 问题 1：`订单可以提前多久预约`
- 问题 2：`最多能提前几天叫车`

这三个文本不是三行等价数据。按当前源码，它们落成：

| 位置 | 行/字段 | 虚构值 | 由谁产生 | 作用 |
|---|---|---|---|---|
| MySQL `sys_qa_info` | `id`（自增） | `8001` | 数据库 | 该行主键，检索链**不用它** |
| MySQL `sys_qa_info` | `group_id`（雪花 ID） | `90001` | `SnowflakeIdWorker#nextId()` | 一组答案的标识；一个 group 一个答案 |
| MySQL `sys_qa_info` | `answer` | 上面的答案全文 | 管理员 DTO | **权威答案** |
| MySQL `sys_qa_es` | `id`（自增） | `7001`、`7002` | 数据库 | 映射表行主键，检索链不用它 |
| MySQL `sys_qa_es` | `elastic_id`（雪花 ID） | `91001`、`91002` | `SnowflakeIdWorker#nextId()` | 每个问题一个；**同时是 ES 文档 id** |
| MySQL `sys_qa_es` | `group_id` | `90001`、`90001` | 同上 | 把两个问题归到同一答案 |
| MySQL `sys_qa_es` | `question` | 两个问题文本 | 管理员 DTO | 关键词检索与回查校验的基准文本 |
| MySQL `sys_qa_sync_job` | `group_id` / `version` / `next_attempt_at` | `90001` / `1` / 当前时间 | `syncJobs.enqueue` | 待同步任务，不是答案数据 |
| ES `qa_knowledge_base` | 文档 `_id` | `"91001"`、`"91002"` | 同步器用 `elasticId.toString()` | 文档 id；RRF 的 key |
| ES 文档 | `groupId` / `question` / `questionVector` / `answer` | 见 §1.2 | 同步器写入 | 检索候选字段 |

**关键关系**：一条 `sys_qa_info` 对多条 `sys_qa_es`（1:N，靠 `group_id` 关联），每条 `sys_qa_es` 的 `elastic_id` 对应一个 ES 文档。注意实体里 `sys_qa_es` 的表名是 `sys_qa_es`，不是“qa_elastic_map”——后者只是 Java 实体 `QaElasticMap` 的名字；三张表的 DDL 在 `src/main/resources/sql/init.sql`，均没有 `status` 列。

> 上面的短整数是**教学虚构**。真实 `SnowflakeIdWorker#nextId()` 拼出的十进制位数随`(当前毫秒 − 2025-01-01 epoch)`、5 位机房号、5 位工作号与 12 位序列变化，随时间和节点配置而不同，源码没有“恒定 19 位”的保证；示例短值只为手算可读，不代表真实生成值。另外，`group_id` / `elastic_id` 的“一组一答案、一问题一文档”是代码的**业务生成约定**：`init.sql` 里 `sys_qa_info.group_id`、`sys_qa_es.elastic_id` 与 `sys_qa_es.group_id` 都没有唯一约束（只有自增 `id` 是主键，`sys_qa_sync_job.group_id` 是主键），重复行会触发 `authoritative` 中 `Collectors.toMap` 的异常路径（§5.7）。

### 1.2 ES 文档里实际存什么

`domain/entity/QaDocument.java`（源码节选：删去 import 与字段注释，保留类/字段注解及字段声明，未改写注解参数）：

```java
@Data
@Document(indexName = "qa_knowledge_base")
public class QaDocument {
    @Id
    private String id;
    @Field(type = FieldType.Keyword)
    private String groupId;
    @Field(type = FieldType.Text, analyzer = "ik_smart", searchAnalyzer = "ik_smart")
    private String question;
    @Field(type = FieldType.Dense_Vector, dims = 1024, index = true, similarity = "cosine")
    private List<Float> questionVector;
    @Field(type = FieldType.Text, index = false)
    private String answer;
}
```

`QaIndexSynchronizer#synchronizeGroup` 写入时（`agentbase/rag/QaIndexSynchronizer.java`）：`id = elasticId.toString()`、`groupId = groupId.toString()`、`question` 取 `sys_qa_es.question`、`answer` 取当时 `sys_qa_info.answer` 的副本、`questionVector` 是对**写入那一刻的问题文本**做 Embedding 的 1024 维数组。回查只比较 `question` 文本是否相等，**不校验向量是否由该文本生成**（§3.6）。

所以：

- ES 里检索的是 `question`（BM25）和 `questionVector`（kNN）；`answer` 被写入但 `index=false`，**不参与检索**。
- 两路查询的 `_source` 只包含 `id`、`groupId`、`question`、`answer`，**不取 `questionVector`**：因此 HTTP 搜索返回的 `QaDocument` 里向量字段为 null（静态推导）。
- ES 中的 `answer` 只是同步那一刻的冗余副本；`RagService#authoritative` 会用 MySQL 当前答案覆盖它。也就是说，ES 是**候选来源**，MySQL 是**答案真值**。

索引实际由 `QaIndexSynchronizer#synchronizeGroup` 用 ES Java Client 编程式创建：维度硬编码 `1024`，`answer` 显式 `index(false)`，但没有在该创建代码里声明 `ik_smart` 分词器或 `cosine` 相似度。`QaDocument` 注解写了这两项，可注解是否被这条编程式建索引路径消费，源码无法证明——**标未知**，不要从注解推断集群真实 mapping（见 §6.3）。

### 1.3 四种“结果”不是一回事

初学者最容易把下面四件事当成同一个“答案”，本章反复区分：

| 名称 | 产生者 | 内容 | 可不可信 |
|---|---|---|---|
| **ES 候选** | 两路 ES 检索 + RRF | `QaDocument`（可能含旧 question、旧 answer 副本） | 只是候选，未校验前不能当答案 |
| **权威答案** | MySQL `sys_qa_info.answer` | 该次查询读到的答案行 | 源码认定的真值；只有候选通过校验且本次查询观察到该行时，`authoritative` 才把它覆盖到候选上 |
| **工具回执** | `RagTool#searchKnowledgeBase` | 拼好的“参考问题 / 标准回答”字符串 | 文本，会作为工具消息回到模型 |
| **模型回答 / HTTP 响应** | 模型生成或 `Result` 包装 | 给用户看的自然语言或 JSON | 不是检索结果本身；客户端收到什么还看 SSE/HTTP 层 |

`RagTool` 返回的字符串经过模型再加工；`GET /rag/search` 直接把 `List<QaDocument>` 塞进 `Result.ok()`。**不能由方法返回了字符串，推断客户端一定收到某个事件。**

### 1.4 先用一个直观例子理解三个术语

- **关键词 / BM25**：BM25 是经典的相关性打分，按词在文档里出现频率、在全体文档里有多稀有等统计给文档排序。你可以把它想成“按字典查词”：问句里“预约”“提前”都命中的问题得分高。本项目用 ES `match` 查询 `question` 字段，取前 100 名。
- **向量 / Embedding**：Embedding 把文本变成一组固定长度的数字（本项目 1024 维）。语义相近的两句话，数字也靠近；于是“最多能提前几天叫车”即使没有“订单”二字，也可能排在“订单可以提前多久预约”附近。本项目用 ES kNN 查 `questionVector`，`k=100`、`numCandidates=200`。
- **RRF（Reciprocal Rank Fusion，倒数排名融合）**：BM25 的分数和向量距离不是同一种量纲，直接相加没有意义。RRF 只看名次：某文档在第 `r` 名得 `1/(k+r)`，两路名次分数相加即可。本项目 `k=60`。

代价也要一起记：关键词检索对用词敏感、对同义改写弱；向量检索能抓语义但依赖模型质量、维度与索引参数，且“近”不等于“对”；RRF 便宜但只用名次，丢掉了两路原有的分差信息。

## 二、两条入口：身份、参数与结果来源

### 2.1 入口 A：管理员 HTTP 搜索

`controller/RagController.java` 共 7 个端点，全部标注 `@RequirePermission({"ADMIN"})`，其中检索是：

```java
@RequirePermission({"ADMIN"})
@GetMapping("/search")
public Result search(@RequestParam String question) {
    List<QaDocument> results = ragService.searchAnswers(question);
    return Result.ok(results);
}
```

- **身份**：Controller 方法没有用户参数。切面 `PermissionAspect` 从 `UserTokenContext`（ThreadLocal）取 `UserToken`，由 `AuthTokenInterceptor` 从请求令牌写入；未登录 401，角色不匹配 403。
- **参数**：只有 `String question`；**没有 trim、没有规范化**，也没有 DTO。
- **是否经过模型**：不经过。直接调 `RagService#searchAnswers`。
- **结果来源**：`Result.ok(List<QaDocument>)`；通过的候选由 `authoritative` 用该次 MySQL 查询读到的答案行覆盖（不是无条件“发出响应时的最新值”，见 §3.6）。若 `searchAnswers` 抛 `RuntimeException("RRF Search failed")`，只有该异常进入 MVC 的 `GlobalExceptionHandler` 时，才按其兜底 `@ExceptionHandler(Exception.class)` 静态推导为 HTTP 500 + `Result`“服务器内部错误”（**未实测**）；异常在异步/工具路径中不自动获得 HTTP 状态。

### 2.2 入口 B：客服 Agent 工具

`agents/SupportAgent.java` 注册与手写分派（源码节选：用 `...` 省略了与本工具无关的其他 `case` 分支，保留的语句未改写）：

```java
List<ToolCallback> callbacks = List.of(ToolCallbacks.from(
    orderSearchTool, ticketTool, toolRepPointerTool, ragTool));
...
case "searchKnowledgeBase" -> {
    String queryStr = null;
    if (rootNode.hasNonNull("queryStr")) {
        queryStr = rootNode.get("queryStr").asText();
    } else if (rootNode.hasNonNull("query")) {
        queryStr = rootNode.get("query").asText();
    } else if (rootNode.hasNonNull("question")) {
        queryStr = rootNode.get("question").asText();
    }
    if (queryStr == null || queryStr.isBlank()) {
        return "Missing required argument: queryStr";
    }
    return ragTool.searchKnowledgeBase(queryStr, toolContext);
}
```

`agentbase/tool/RagTool.java`（源码节选：省略了两行日志语句，保留 `try` / `catch` 边界与全部返回文本；下方的 `catch` 只包住 `try` 内的 `RuntimeException`）：

```java
@Tool(description = "知识库搜索引擎。当用户询问关于系统操作、业务规则或常见问题时，必须调用此工具获取准确信息。请勿凭空编造答案。")
public String searchKnowledgeBase(@ToolParam(description = "重写后的查询语句。...") String queryStr,
                                  ToolContext toolContext) {
    ToolNotifySupport.notifyToolListener(toolContext, "正在调用知识库搜索……" + queryStr);
    try {
        List<QaDocument> docs = ragService.searchAnswers(queryStr);
        if (docs.isEmpty()) return "知识库未找到有效的相关信息，请明确告知用户并按需转人工处理。";
        StringBuilder sb = new StringBuilder();
        sb.append("找到以下相关知识库条目：\n");
        for (int i = 0; i < docs.size(); i++) {
            QaDocument doc = docs.get(i);
            sb.append(String.format("--- 条目 %d ---\n", i + 1));
            sb.append("参考问题：").append(doc.getQuestion()).append("\n");
            sb.append("标准回答：").append(doc.getAnswer()).append("\n");
        }
        return sb.toString();
    } catch (RuntimeException e) {
        return "知识库暂时不可用。";
    }
}
```

- **身份**：会话归属由 `ChatController`/`ChatRequestRunner` 在更上游用 `UserTokenContext` + `assertOwned` 校验（C1）；`RagTool` 收到的 `ToolContext` 含 `toolListener`/`userId`/`chatId`，但 `searchKnowledgeBase` 只用 listener 发一条工具事件，**不按 userId 过滤知识库**。
- **参数**：`queryStr` 来自模型的工具调用参数，兼容 `queryStr`/`query`/`question` 三个别名；`ToolParam` 要求模型“重写成脱离上下文的完整问题”，这只是**提示词层的请求**，源码里 `searchAnswers` 对内容不做改写。
- **是否经过模型**：是。用户消息先进模型，模型请求工具，服务端执行，工具字符串作为 `ToolResponseMessage` 回填，模型再生成给用户的文字。只有工具内部这一段不经过模型。
- **结果来源**：`doc.getQuestion()` 与 `doc.getAnswer()` 来自已核对的 `QaDocument`；再被拼成文本。返回的字符串作为 `ToolResponseMessage` 交给模型后，模型是否继续调用工具、是否把文字发成 SSE 事件，源码都不保证。**提示词里的“必须调用”“请勿凭空编造”不是授权，也不是校验**；真正的身份与权限来自令牌、切面和上游的归属检查，真正的答案来自 MySQL（且以本次回查读到的行为准）。

### 2.3 两条入口对照

| 维度 | `GET /rag/search` | SupportAgent → `RagTool` |
|---|---|---|
| 权限 | `@RequirePermission({"ADMIN"})` | 会话归属校验在对话入口；知识库本身无逐用户过滤 |
| 参数 | `@RequestParam String question` | 模型生成的 `queryStr`（三个别名） |
| 经过模型 | 否 | 是（工具前后各一次模型参与） |
| 直接返回 | `Result<List<QaDocument>>` | 字符串（`ToolResponseMessage` → 模型 → SSE） |
| 空结果 | `[]` | 固定文本“知识库未找到有效的相关信息……” |
| 异常 | 异常进入 MVC 时兜底 500（静态推导，未实测） | 仅 `try` 内 `RuntimeException` → 固定文本“知识库暂时不可用。”；`try` 外异常或上层工具分派异常走其他分支，模型/客户端是否收到不保证 |

全仓只有 `SupportAgent` 注入并注册 `RagTool`；`OrderAgent`、`DailyAgent`、`FallbackAgent` 都没有。

## 三、源码导读：`searchAnswers` 的真实执行顺序

主线只有一条：`RagService#searchAnswers`。下面按源码顺序拆六段。代码块分两种：**“源码节选”** 保留关键语句、但可能删去日志/注释或以注释代替另一段等价循环，删减处在各小节标题或代码内显式标出；**教学示例数据**（§3.8 的虚构榜单、手算结果）不是 ES/Embedding 输出。不要把改写过的节选当成未删改的源码原文。

### 3.1 第 0 步：空检查与预算起点

```java
public List<QaDocument> searchAnswers(String userQuery) {
    if (userQuery == null || userQuery.isBlank()) return List.of();
    List<CompletableFuture<?>> tasks = new ArrayList<>();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            Math.max(1, searchTimeoutMillis));
```

- 只检查 `null`/`isBlank()`，**没有 trim、没有查询改写**。空白字符串直接返回空列表，连 Embedding 都不做。
- `searchTimeoutMillis` 由 `@Value("${rag.search.timeout-ms:8000}")` 注入：**8000ms 是注解里的 Java 默认值**，不是 `application.yaml` 显式配置的值（`application.yaml` 没有这个键），也不是源码能保证的真实运行时值——外部配置中心或环境变量若提供该键会覆盖默认值，本章未核验。`Math.max(1, ...)` 把下限兜到 1ms。`remaining(deadline)` 在剩余纳秒 ≤0 时抛 `TimeoutException("知识库检索超时")`。
- 这个 deadline 从方法入口开始计时，只约束随后两次带超时的 `Future#get` 等待；提交任务与执行器排队消耗的时间会挤占剩余额度，但源码没有给“提交/排队”单独设超时。RRF 融合与 MySQL 核对发生在这些等待之后，**不受 deadline 约束**（见 §5.6）。

### 3.2 第 1 步：Embedding 串行等待

```java
var embeddingFuture = CompletableFuture.supplyAsync(() -> embeddingModel.embed(userQuery), executor);
tasks.add(embeddingFuture);
float[] vectorArray = embeddingFuture.get(remaining(deadline), java.util.concurrent.TimeUnit.NANOSECONDS);
List<Float> queryVector = vectorToList(vectorArray);
```

Embedding 在两路检索**之前**同步等结果，共享同一个 deadline。`EmbeddingModel` 由构造器 `@Qualifier("dashscopeEmbeddingModel")` 注入；项目源码中没有显式声明这个 Bean，名称依赖 Spring AI 自动配置（版本与 Bean 是否能被命中，源码无法证明，标未知）。`vectorToList` 只是 `float[] → List<Float>`，空数组变 `Collections.emptyList()`。`application.yaml` **显式写了** `spring.ai.dashscope.embedding.options.model: text-embedding-v4` 与 `dimensions: 1024`（这是配置文件的值，不是 Java 默认值，也不保证实际发给模型服务后返回的维度恰好是 1024），与实体声明的 1024 维一致；这里只记键名，不涉及密钥。

### 3.3 第 2 步：BM25 与向量两路并行

```java
CompletableFuture<List<Hit<QaDocument>>> vectorFuture = CompletableFuture.supplyAsync(() ->
        searchVectorOnly(queryVector, WINDOW_SIZE), executor);
tasks.add(vectorFuture);
CompletableFuture<List<Hit<QaDocument>>> bm25Future = CompletableFuture.supplyAsync(() ->
        searchBm25Only(userQuery, WINDOW_SIZE), executor);
tasks.add(bm25Future);
try {
    CompletableFuture.allOf(vectorFuture, bm25Future).get(remaining(deadline), java.util.concurrent.TimeUnit.NANOSECONDS);
} finally {
    if (!vectorFuture.isDone()) vectorFuture.cancel(true);
    if (!bm25Future.isDone()) bm25Future.cancel(true);
}
List<Hit<QaDocument>> vectorHits = vectorFuture.get();
List<Hit<QaDocument>> bm25Hits = bm25Future.get();
```

`WINDOW_SIZE = 100`。两个私有辅助方法的真实查询：

| 方法 | ES 查询 | 关键参数 | `_source` |
|---|---|---|---|
| `searchVectorOnly` | `knn` on `questionVector` | `k=100`、`numCandidates=200`（`size*2`） | `id`、`groupId`、`question`、`answer` |
| `searchBm25Only` | `match` on `question` | `query=userQuery`、`size=100` | 同上 |

两者都查索引 `qa_knowledge_base`，都把 `IOException` 包装为 `RuntimeException`。**没有“某路失败就用另一路”的降级分支**：`CompletableFuture.allOf(vectorFuture, bm25Future)` 要等**两路都完成**（正常或异常）才完成；若一路提前失败而另一路仍在运行，`allOf(...).get(...)` 不会立刻把那一路的异常抛给调用方，可能先由超时到点。两路都完成后，异常才由 `get` 抛出并进入外层 catch（§3.7）。内层 `finally` 与外层 `finally` 都会尝试 `cancel(true)` 未完成的任务；这是 Future 层的**逻辑取消**，不能证明底层 Embedding/ES HTTP 请求已停止。

### 3.4 第 3 步：RRF 融合（源码节选：只展开向量路的循环；BM25 路用注释代替，公式与向量路相同）

```java
Map<String, Double> scoreMap = new HashMap<>();      // docId -> RRF Score
Map<String, QaDocument> docMap = new HashMap<>();    // docId -> Doc
for (int i = 0; i < vectorHits.size(); i++) {
    Hit<QaDocument> hit = vectorHits.get(i);
    String docId = hit.id();
    double score = 1.0 / (RRF_K + (i + 1));
    scoreMap.merge(docId, score, Double::sum);
    docMap.putIfAbsent(docId, hit.source());
}
// BM25 路同样循环，公式相同
```

- `RRF_K = 60`；`i` 从 0 开始，名次是 `i+1`，所以**排名从第 1 名起算**，第 1 名得 `1/61 ≈ 0.016393`。
- 融合 key 是 `hit.id()`（ES 文档 `_id`），不是 `QaInfo.id`；文档对象取自 `hit.source()`。同一 key 在两路都出现时分数相加；`docMap.putIfAbsent` 让先放进去的非 null 文档对象胜出。
- **两个 ID 在回查时并未被比较**：正常同步器写 `_id = document.id = elasticId.toString()`，此时二者一致；但异常或历史文档若 `hit.id()` 与 source 里的 `id` 不同，`authoritative` 只用 `QaDocument#getId()` 转 `Long` 去查库（§3.6），源码没有校验两者相等。
- 若某次 `hit.source()` 为 null，`HashMap.putIfAbsent` 仍允许同一 `hit.id()` 的另一路非 null source 补入；只有两路合并后 `docMap.get(id)` 仍为 null，去重循环才跳过该候选。若 source 解析本身导致 ES 客户端调用抛异常，则走检索异常路径，不能一概当作 null 跳过；真实异常包装未实测。
- 分数只用于排序，**不写回 `QaDocument`，也不返回给调用方**。

### 3.5 第 4 步：排序、按组去重、Top5

```java
List<Map.Entry<String, Double>> sortedDocs = new ArrayList<>(scoreMap.entrySet());
sortedDocs.sort(Map.Entry.<String, Double>comparingByValue().reversed());
List<QaDocument> finalResults = new ArrayList<>();
Set<String> seenGroupIds = new HashSet<>();
for (Map.Entry<String, Double> entry : sortedDocs) {
    QaDocument doc = docMap.get(entry.getKey());
    if (doc == null || doc.getGroupId() == null) continue;
    if (!seenGroupIds.contains(doc.getGroupId())) {
        finalResults.add(doc);
        seenGroupIds.add(doc.getGroupId());
    }
    if (finalResults.size() >= 5) break;
}
return authoritative(finalResults);
```

- 去重维度是 `groupId`，**不是文档 id**：同一答案下的多个问法，只保留分数最高的那条文档。
- `doc == null` 或 `groupId == null` 的候选在这里被跳过。
- 取够 5 个不同 group 就停；随后 `authoritative` 若淘汰了某些候选，**不会从排名 6 以后补足**，也**不会回退使用同一 group 里分数较低、已在去重时被跳过的候选**——去重发生在校验之前，被跳过的对象没有第二次机会（源码事实）。
- `HashMap` 的迭代顺序不保证；`List.sort` 是稳定排序，所以分数相同时先后取决于 `scoreMap` 的迭代次序，源码无法预测谁先被保留（§5.7）。

### 3.6 第 5 步：回 MySQL 核对

`agentbase/rag/RagService.java#authoritative` 源码节选（仅调整换行，未删减语句）：

```java
List<QaDocument> authoritative(List<QaDocument> candidates) {
    List<Long> ids = new ArrayList<>();
    for (QaDocument doc : candidates) {
        try { ids.add(Long.valueOf(doc.getId())); } catch (RuntimeException ignored) { }
    }
    if (ids.isEmpty()) return List.of();
    Map<Long, QaElasticMap> mappings = qaElasticMapMapper.selectList(
            new LambdaQueryWrapper<QaElasticMap>().in(QaElasticMap::getElasticId, ids))
            .stream().collect(Collectors.toMap(QaElasticMap::getElasticId, q -> q));
    Set<Long> groups = mappings.values().stream().map(QaElasticMap::getGroupId).collect(Collectors.toSet());
    if (groups.isEmpty()) return List.of();
    Map<Long, QaInfo> infos = qaInfoMapper.selectList(
            new LambdaQueryWrapper<QaInfo>().in(QaInfo::getGroupId, groups))
            .stream().collect(Collectors.toMap(QaInfo::getGroupId, q -> q));
    List<QaDocument> result = new ArrayList<>();
    for (QaDocument doc : candidates) {
        QaElasticMap mapping;
        try { mapping = mappings.get(Long.valueOf(doc.getId())); } catch (RuntimeException e) { continue; }
        if (mapping == null || !Objects.equals(mapping.getQuestion(), doc.getQuestion())
                || !Objects.equals(String.valueOf(mapping.getGroupId()), doc.getGroupId())) continue;
        QaInfo info = infos.get(mapping.getGroupId());
        if (info == null) continue;
        doc.setAnswer(info.getAnswer());
        result.add(doc);
    }
    return result;
}
```

逐条核对条件（全部是源码事实）：

1. `doc.getId()` 必须能转成 `Long`；否则既进不了查询列表，也会在循环里被跳过。
2. 按 `elastic_id IN (...)` 查 `sys_qa_es`；**映射不存在** → 丢弃（删除后的旧文档就是这样被挡住的）。
3. `mapping.question` 必须**等于** ES 文档的 `question`；改了问题但索引没更新 → 丢弃。
4. `String.valueOf(mapping.groupId)` 必须等于文档的 `groupId` 字符串；不等 → 丢弃。
5. 按 `group_id IN (...)` 查 `sys_qa_info`；**答案组不存在** → 丢弃。
6. 通过后 `doc.setAnswer(info.getAnswer())`：**答案取该次 `qaInfoMapper.selectList` 返回行的 `answer`**，ES 里的 answer 副本被覆盖。只有更新已提交、候选进入本轮校验、且这次答案查询确实读到更新后的行，返回值才反映那次更新；若查询读到较早行、候选被前几步丢弃，或并发提交发生在读取之后，本次返回不一定是发出响应时的最新值。

`authoritative` 对 `sys_qa_es` 与 `sys_qa_info` 各发**一条独立查询**，本方法没有 `@Transactional` 或显式快照：两条查询不保证同一时点。它只检查候选对应的那几行，不扫描全表；`Objects.equals(mapping.getQuestion(), doc.getQuestion())` 只比较文本相等，**不校验 `questionVector` 是否由该文本生成**；“删除/改问题后旧候选被丢弃”同样以本次回查能看到相关更新、且映射与索引的 ID/文本按上述假设一致为前提。这不是“全库状态强一致”校验，也不保证 ES 与 MySQL 同步完成。

### 3.7 第 6 步：异常路径

```java
} catch (Exception e) {
    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    throw new RuntimeException("RRF Search failed", e);
} finally {
    tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
}
```

`TimeoutException`、`ExecutionException`（含两路 ES 异常）、`IOException` 包装后的 `RuntimeException` 都汇入这里，统一变成 `RuntimeException("RRF Search failed", e)`；中断标志会被恢复。`finally` 对所有未完成任务调用 `cancel(true)`——这是**逻辑取消**，`cancel(true)` 是否真正中断底层 Embedding/ES HTTP 请求，源码无法证明（R2 展开）。

### 3.8 手算例子：三条候选的排序与淘汰

沿用 §1.1 的虚构数据。假设查询是 `订单可以提前多久预约`，两路返回（**虚构榜单，不是 ES 输出**）：

| 文档 id | 问题 | group | 向量榜名次 | BM25 榜名次 |
|---|---|---|---|---|
| `91001` | 订单可以提前多久预约 | `90001` | 2 | 1 |
| `91002` | 最多能提前几天叫车 | `90001` | 1 | 3 |
| `91003` | 预约单提前取消收费吗 | `90002` | 3 | — |
| `91005` | 工单提交有效期多久 | `90003` | — | 2 |

按 `score = 1/(60+rank)`，保留 6 位小数：

- `91001` = `1/62 + 1/61` = `0.016129 + 0.016393` = **0.032522**
- `91002` = `1/61 + 1/63` = `0.016393 + 0.015873` = **0.032266**
- `91005` = `0 + 1/62` = **0.016129**
- `91003` = `1/63 + 0` = **0.015873**

降序：`91001 > 91002 > 91005 > 91003`。按 `groupId` 去重：`91001` 先被保留（`90001`），`91002` 因同组被跳过；`91005`、`91003` 各属新组被保留。`finalResults = [91001, 91005, 91003]`，只有 3 组，未触发 Top5 的 `break`。随后 `authoritative` 假设三行的 `sys_qa_es`、`sys_qa_info` 都在且 question 一致，返回 3 条文档，答案分别被替换为三个 group 在该次 MySQL 查询中读到的值。

注意两个反直觉点：向量榜第 1 名的 `91002` 因为与 `91001` 同组而**没有进入结果**；BM25 榜第 1 名未必赢过“两路都不差”的文档——名次相加才决定顺序。

## 四、身份、参数与“提示词不是授权”

把三件事分开写，避免常见误解：

1. **谁能调用**：HTTP 搜索靠 `@RequirePermission({"ADMIN"})` + `UserTokenContext`；Agent 路径靠对话入口的令牌与 `assertOwned`。`RagTool` 的 `@Tool` 描述里写“必须调用”，那是给模型看的行为指引，**不是权限检查**。
2. **搜什么**：HTTP 的 `question` 来自 `@RequestParam`；Agent 的 `queryStr` 来自模型工具参数，三个别名由 `executeTool` 兼容。两者都直接进 `searchAnswers`，源码没有把用户文本当授权凭证，也没有按用户身份过滤知识库——这是共享知识库，不是用户私有数据。
3. **拿到什么**：候选来自 ES，答案来自 MySQL，文本由 `RagTool` 拼接；模型可能再改写。用户文本、工具回执与模型文字三者不可混为一谈。

`ToolContext` 里的 `userId`/`chatId` 由服务端注入（C5 已核验），用于工具事件与回执反查；`searchKnowledgeBase` 只把 listener 用于 `ToolNotifySupport`，没有把 `userId` 传进检索。

## 五、失败、过期与并发边界

### 5.1 写后到可搜：R1 只需知道的最小事实

- `RagService#addQA`、`addQAs`、`updateAnswer`、`updateQuestion`、`deleteQA` 都标注 `@Transactional`；它们**只写 MySQL**，随后 `syncJobs.enqueue(groupId)`，不直接操作 ES（`RagConsistencyRegressionTest` 的 `verifyNoInteractions(es)` 断言了新增与改答案这两条）。
- `syncJobs.enqueue` 是 `INSERT ... ON DUPLICATE KEY UPDATE version = version + 1, next_attempt_at = CURRENT_TIMESTAMP`（`mapper/QaSyncJobMapper.java`）：重复入队会把任务重新置为“立即可领取”并递增版本。
- `QaIndexSynchronizer#synchronize` 是独立的 `@Scheduled(fixedDelayString = "${rag.sync.interval-ms:5000}")` + `@Transactional(READ_COMMITTED)` 任务。`fixedDelay` 的含义是**上一次调用结束后，再延迟约 5000ms 才发起下一次调度**，不是“任务入队后最多 5 秒可搜”的上界。每次调用先 `jobs.claimBatch()`：`SELECT ... WHERE next_attempt_at <= CURRENT_TIMESTAMP ORDER BY next_attempt_at, group_id LIMIT 5 FOR UPDATE SKIP LOCKED`，一次最多领 5 组并跳过被其他事务锁住的行；随后逐组 `synchronizeGroup`，成功 `deleteById`，失败 `defer`（把 `next_attempt_at` 延后 30 秒）并保留任务。
- 因此**接口返回成功只表示 MySQL 数据与该次入队已提交，不表示 ES 已更新**。5000ms 与 30 秒分别是注解默认值与 SQL 常量，`application.yaml` 未显式配置 `rag.sync.interval-ms`；真实运行时值还可能被外部配置覆盖，源码无法证明。
- 从提交到“某次查询能搜到”，至少要依次满足：任务到期（`next_attempt_at <= 当前时间`）、被 `claimBatch` 领到（未被 5 组上限和锁挡住）、`synchronizeGroup` 的 Embedding 与 ES 写入没失败、写入后的 `refresh` 被执行（`info == null` 时直接 return，见 §5.5）、并且本次 `match`/`kNN` 与排名把该文档列入返回。任一步不满足都可能延后或搜不到；源码没有给出可见时延上界。同步器自身的实际运行状态也无法仅凭代码确定。
- 任务领取、重试、版本、预算与 `Future.cancel` 的完整推导留给第 22 章 R2；这里只使用上述最小事实。

### 5.2 时间线 T1：正常召回（静态推导）

前提：任务在 t0 入队；某次 `synchronize` 调用结束后又过了至少一次 `fixedDelay` 的调度才领取它；`claimBatch` 未被 5 组上限或锁挡住；Embedding、ES 写入与 `refresh` 都成功；本次查询的 `match`/`kNN` 与排名把两个文档列入返回。

| 时刻 | 动作 | 可见状态 |
|---|---|---|
| t0 | 管理员 `POST /rag/qa/add`，事务内 insert `sys_qa_info` + 两条 `sys_qa_es` + `enqueue(90001)` | MySQL 有数据；ES 还没有；HTTP 已可返回成功 |
| t1（上述条件成立后的某次调度，**不是 t0+5s 的上界**） | 同步器领取任务，删除该 group 旧文档、Embedding 两条问题、写 ES、`refresh` | 这两个文档才可能进入后续检索的候选；能否命中仍取决于 query 与排名 |
| t2 | 林小雨在客服会话问“订单可以提前多久预约”；模型请求 `searchKnowledgeBase` | 工具参数 `queryStr` 由模型给出 |
| t3 | `searchAnswers`：Embedding → 两路 ES → RRF → 按组去重 → `authoritative` | 候选被核对，答案取本次 MySQL 查询读到的行 |
| t4 | `RagTool` 拼字符串 → `ToolResponseMessage` → 模型生成文字 → SSE | 用户看到的文字；**是否送达客户端由 SSE 层决定，本章不保证** |

### 5.3 时间线 T2：答案改了，索引还没跟上（静态推导）

前提：`updateAnswer` 已提交；本次搜索的候选仍来自旧 ES 副本；并且这次 `authoritative` 的 `qaInfoMapper.selectList` 读到了更新后的 `sys_qa_info` 行。

| 时刻 | 动作 | 用户读到什么 |
|---|---|---|
| t0 | 管理员 `updateAnswer`，`sys_qa_info.answer` 从“旧答案”改成“新答案”，入队 | 已提交的 MySQL 是新答案；ES 文档里的 answer 副本仍是旧答案 |
| t1（同步前）搜索 | ES 仍返回 `91001` 候选；`authoritative` 通过后用本次查询读到的 `info.getAnswer()` 覆盖 | **在上述前提成立时读到新答案**；若查询读到较早答案行且其他校验通过，可能返回旧答案；若候选被丢弃，则不返回该候选 |
| t2 | 同步器重建该 group 文档，answer 副本也变成新答案 | 候选与副本一致，但答案仍以 MySQL 该次查询为准 |

结论：在“answer 不参与检索、真值在 MySQL”且本次回查看到新行的条件下，答案副本落后不会造成返回旧答案；风险在问题文本与向量落后（T3），不在答案副本本身。`authoritative` 两条查询各自独立、无本方法显式事务，不能推断返回值一定是发出响应时的最新值。

### 5.4 时间线 T3：问题改了，索引还没跟上（静态推导）

前提：`updateQuestion` 事务内 `FOR UPDATE` 查到映射并 `update` 成功；本次同步尚未完成；`authoritative` 按文本比较 `mapping.question` 与 ES 文档 `question`。

| 时刻 | 动作 | 用户读到什么 |
|---|---|---|
| t0 | 管理员 `updateQuestion` 把 `91001` 的问题改成“预约用车最早什么时候”，事务内 `FOR UPDATE` 当前行、update、`enqueue` | MySQL question 已变；ES 仍是旧 question + 旧向量 |
| t1（同步前）用**新问法**搜索 | ES 里该文档的问题表示仍是旧的；`match`/`kNN` 与排名是否把它列入本次结果不确定 | 可能返回空或更少的组，也可能命中；不能断言一定搜不到 |
| t1（同步前）用**旧问法**搜到该文档 | 若本次回查看到更新后的 `mapping.question`，`authoritative` 因文本不等丢弃该候选 | 该候选被丢弃；若回查观察到更新前的行、或文本恰好相等，就不会因这个条件丢弃 |
| t2 | 同步器删除旧文档、按新 question 重新 Embedding 并写入、`refresh` | 新问法**可能**命中（取决于实际索引、query 与排名）；旧问法仍**可能**经 `match`（分词命中共用词）或语义 kNN 命中这份新文档，不能断言“旧问法不再命中” |

这与 `src/main/resources/api/rag-consistency.md` 的口径一致：修改问题到索引同步完成之间可能暂时检索不到该问题。注意这**不是强一致保证**：本方法无全局快照，请求若先读到快照、并发提交随后发生，本次可能观察旧数据；相同的问题文本也不证明 `questionVector` 一定由该文本生成。

### 5.5 时间线 T4：删除后仍被召回（静态推导）

前提：删除已提交；ES 文档尚未被同步器清除；本次回查看不到对应映射（或文本条件不成立）。

| 时刻 | 动作 | 结果 |
|---|---|---|
| t0 | 管理员按 `groupId=90001` 删除；事务内删 `sys_qa_es` 两行，因该组已无映射再删 `sys_qa_info`，并 `enqueue` | MySQL 两份数据都消失；ES 文档还在 |
| t1（同步前）搜索 | ES 返回 `91001` 候选；`authoritative` 按 `elastic_id` 查不到映射 | 在上述前提成立时 `mapping == null` → 丢弃；若回查看到的是删除前的行，则不会因该条件丢弃 |
| t2 | 同步器按 `groupId` `deleteByQuery` 清掉旧文档；`QaInfo` 为 null 时 `synchronizeGroup` 在 `info == null` 处直接 `return`，**后面的 `refresh` 不执行** | 旧文档已被提交删除，但这次调用没有显式刷新，删除的可见性还取决于 ES 自身刷新与其他调用。只有同步成功、任务被确认、且没有并发新写入或失败重试时，ES 与 MySQL 才趋于一致；不承诺无条件收敛 |

若只删某个 `questionId` 而保留同组其他问题：`authoritative` 只丢弃被删的文档，保留的问法仍可返回；同步器会删掉整组文档再按剩余映射重建。删除路径先 `enqueue` 再判断剩余映射是否为 0；若删除任务失败被 `defer`、后续又有人写入同组数据，ES 可能维持旧文档或被新版本重建，细节属于 R2 的同步边界。

### 5.6 时间线 T5：单路失败、超时与空结果（静态推导）

先分清阶段：`supplyAsync(...)` **提交**时若 `ragExecutor` 拒绝任务，会在提交点抛出；任务在**队列等待**时没有单独超时，但占用从方法入口起算的 deadline；**Embedding future 的 `get(remaining(deadline))`** 与**两路 `allOf(...).get(remaining(deadline))`** 是两处受截止时间约束的等待。两路都提交后 `allOf` 要等所有 future 完成：一路提前失败而另一路未完成时不会立即失败，等待可能先到 deadline；完成后异常才被包装为 `RuntimeException("RRF Search failed")`。随后的 RRF 融合与 `authoritative` 两次 MySQL 查询**不受**该 deadline 约束。

| 情形 | 源码分支 | 调用方看到的 |
|---|---|---|
| 提交被拒绝（队列满等） | `supplyAsync` 在提交点抛 `RejectedExecutionException` → 外层 catch → `RuntimeException("RRF Search failed")`；`ragExecutor` 的拒绝策略未在 `AsyncWorkConfig` 显式设置 | Agent：`RagTool` 的 `try` 内 `RuntimeException` → “知识库暂时不可用。”；HTTP：异常进入 MVC 时兜底 500（未实测） |
| Embedding 失败或超过 deadline | `embeddingFuture.get(remaining(...))` 抛异常 → 外层 catch → `RuntimeException("RRF Search failed")` | 同上 |
| 向量路异常、BM25 正常 | 两路都完成后 `allOf(...).get()` 抛 `ExecutionException` → 同一个 catch；若 BM25 仍在运行，等待可能先到 deadline | **整次失败，没有 BM25 兜底**；`finally` 尝试 `cancel(true)` 未完成任务，不证明底层 HTTP 停止 |
| BM25 路异常、向量正常 | 同上 | **整次失败，没有向量兜底** |
| 两路都成功但无命中 | `scoreMap` 为空 → `finalResults` 为空 → `authoritative` 因 `ids.isEmpty()` 返回 `List.of()` | Agent: “知识库未找到有效的相关信息……”；HTTP: `[]` |
| 超过 deadline | `remaining()` 抛 `TimeoutException` → `RuntimeException("RRF Search failed")`；内层与外层 `finally` 都尝试 `cancel(true)` 未完成任务 | 同上；底层 HTTP 是否被物理取消**未知** |
| 候选的 `doc.getId()` 非数字 | `authoritative` 转换失败 → 跳过 | 该条不返回 |

`RagTool` 的固定文本只覆盖它自己 `try` 内捕获的 `RuntimeException`；`ToolNotifySupport.notifyToolListener` 在 `try` 之前，`SupportAgent#executeTool` 另有 `catch (Exception)` 返回 `"Tool execution failed: ..."`。模型拿到字符串后是否继续、工具事件是否送达、HTTP 异常是否映射为 500，都不是无条件的。8000ms 是 `@Value("${rag.search.timeout-ms:8000}")` 的 Java 默认值，不是整个请求的硬超时，真实运行时值未核验。

### 5.7 分数相同、重复 group 与空分支

- **分数相同**：`sortedDocs.sort(...reversed())` 是稳定排序，但 `scoreMap` 是 `HashMap`，迭代顺序不保证。并列文档的先后不可由源码预测；若它们同属一个 group，哪个被保留、哪个被 collapse 跳过也就不确定。不要编造“按 id 排序”的假规则。
- **重复 group**：只保留分数最高的那条文档，其余 `continue`；去重发生在 MySQL 校验之前，因此被跳过的同组候选不会再获得“校验通过”的机会。
- **空分支汇总**：空白 query → `List.of()`；无命中 → `List.of()`；候选 id 全部非法 → `List.of()`；映射/答案组缺失 → 逐条丢弃。返回空不等于“知识库没有答案”，只说明这些候选没通过当前条件。
- **额外的失败面（静态推导）**：`authoritative` 用 `Collectors.toMap` 且没有合并函数；若数据库里出现重复 `elastic_id` 或重复 `group_id` 行，会抛 `IllegalStateException` 并被外层包装为 `RuntimeException("RRF Search failed")`。`init.sql` 对这两列没有唯一约束，重复只能由异常数据或并发未互斥的写入产生，测试未覆盖。
- **null 与 ID 边界**：文档 `groupId == null` 在去重循环就被跳过；若数据库映射行的 `group_id` 为 null，`String.valueOf(null)` 会得到字符串 `"null"`，只可能与文档 `groupId` 恰好是 `"null"` 时相等，DDL 声明该列 `NOT NULL`，正常路径不出现。回查用 `QaDocument#getId()` 转 `Long`，不比较 ES `hit.id()`；异常文档的两个 ID 不一致时源码不会发现。

### 5.8 证据分级表

| 结论 | 级别与前提 |
|---|---|
| ES 候选与 MySQL 答案不是一回事；answer 不参与检索 | 源码事实（`QaDocument` 注解、同步器、`authoritative`） |
| RRF 公式、K=60、每路窗口 100、Top5、按组去重 | 源码事实（`searchAnswers`） |
| 改问题未同步时旧候选可能被丢弃；删除未同步时旧文档可能不返回 | 静态推导，以本次回查看到对应更新、ID/文本按假设一致为前提；未实测 |
| 改答案后读到新值 | 静态推导，以更新已提交、候选进入校验且本次答案查询读到新行为前提；未实测 |
| 同步后旧问法一定不命中 / 新问法一定进入结果 | 不成立；命中取决于实际索引、query、`match`/kNN 与排名 |
| `fixedDelay=5000ms` 是入队到可搜的上界 | 不成立；它是上次调用完成后的下次调度延迟，还受锁、批次、耗时、失败重试与运行状态影响 |
| 单路失败整次失败、无降级 | 源码事实（`allOf` 后无逐路兜底分支，统一进外层 catch）；一路失败另一路未完时可能先到 deadline，未实测运行时 |
| 并列分数的先后 | 未知（`HashMap` 迭代顺序不保证，源码无业务排序规则） |
| `cancel(true)` 是否中断底层 HTTP、ik_smart/cosine 是否真的生效、Bean 来源、真实 mapping | 未知 |
| 测试断言覆盖写库/入队/核对/任务保留与超时分享 | 测试源码阅读；**未执行** |

## 六、设计取舍与剩余边界

### 6.1 三种检索手段的取舍（都有源码证据）

| 手段 | 解决什么 | 代价 | 项目证据 |
|---|---|---|---|
| 关键词 / BM25 | 精确词项命中、可解释 | 同义改写弱；依赖分词 | `searchBm25Only`：`match` on `question`，`size=100` |
| 向量 / Embedding | 语义相近、支持改写 | 依赖模型与维度；近≠对；有延迟与成本 | `searchVectorOnly`：`knn` on `questionVector`，`k=100`、`numCandidates=200`；`dashscopeEmbeddingModel` |
| RRF | 不比较异质量纲，只按名次融合 | 丢掉分差；并列时先后不可控 | `RRF_K=60`、两路同样的 `1/(k+rank)`、`merge(Double::sum)` |

“两路各取前 100 再融合”是一个窗口限制：它控制成本，但**本次未进入任一路前 100 的文档不参与本次融合**。这不评价该文档真实有多相关，也不保证进入窗口的固定名次——`match` 与 `kNN` 的排名由 ES 数据与查询决定。Top5 之后不补位进一步限制了召回。当前实现没有可配置这些窗口的键。

### 6.2 为什么要回权威库核对

ES 文档是异步重建的副本，可能比 MySQL 旧；而 `authoritative` 只做五件事：id 可解析、映射存在、question 一致、groupId 一致、答案组存在，然后覆盖答案。它**没有**校验向量是否对应当前问题、没有全表扫描、没有跨库事务。因此正确表述是“候选经过有限条件核对后返回”，不是“ES 与 MySQL 强一致”。

### 6.3 遗留 N-Gram 文件不是当前配置

`src/main/resources/config/chat-es-setting.json` 定义了 `my_ngram_analyzer`（`min_gram=1`、`max_gram=2`）和 `full_text_content` 等聊天字段。全仓 `src` 检索 `chat-es-setting` / `my_ngram` 只有这个 JSON 自身命中，**没有代码或配置引用它**；它描述的是聊天全文检索设想，与 `qa_knowledge_base` 无关。不要用它推断知识库索引用了 N-Gram，也不要从 `QaDocument` 的注解推断集群真实 mapping——真实 mapping 只能由建索引代码或运维侧的 ES 状态证明，后者本章未核验。

### 6.4 预算与取消的最小边界（R2 再展开）

- 唯一超时键 `rag.search.timeout-ms` 的 8000ms 是 `@Value` 的 Java 默认值；`application.yaml` 没有显式配置，真实运行时值可能被外部配置覆盖，源码不能证明。它覆盖 Embedding 等待与两路 `allOf` 等待，不覆盖提交/排队之外的融合与 MySQL 核对。
- `ragExecutor` 4/8/32 是 `AsyncWorkConfig` 的 Java 硬编码；`rag.sync.interval-ms` 默认 5000ms 来自 `@Scheduled` 注解，且是“上一次调用完成后到下次调度”的延迟。两者都不在 `application.yaml` 中显式配置；前者改代码才能改，后者可被配置覆盖。
- 两路都提交后 `allOf` 等所有 future 完成；一路失败另一路未完时可能先到 deadline。`cancel(true)` 是逻辑取消；底层 HTTP/Embedding 是否停止未知。
- 任务领取、失败延后 30 秒、版本字段与旧任务覆盖的完整语义、以及预算如何在子任务间分配，属于第 22 章 R2，本章不下结论。

## 七、动手验证：四组只读追踪练习

> 全部是**只读源码追踪**，不启动服务、不连中间件、不发请求。每组的“预期观察”是按源码推导的结果，不是运行输出；做完后按“失败解释”回查。

### 练习 A：一条问答落到哪里（写路径）

1. 打开 `src/main/resources/sql/init.sql`，找到三张 `sys_qa_*` 表，写下每列含义。
2. 打开 `RagService#addQA`，按行追踪：`RagQAAddDTO` 的 `questions`/`answer` → `trim`/去重 → `snowflakeIdWorker.nextId()` 两次 → 两次 `insert` → `syncJobs.enqueue(groupId)`。
3. 打开 `QaIndexSynchronizer#synchronizeGroup`，只看它怎样用 `elasticId.toString()` 当 ES 文档 id、怎样把 question 写进向量与 text 字段、answer 写进哪个字段。

**预期观察**：一个 group 一行 `sys_qa_info`；两个问题两行 `sys_qa_es`，各有自己的 `elastic_id`；ES 文档 id 等于 `elastic_id` 字符串，不是 `QaInfo.id`。
**失败解释**：若你写出“answer 存进 `sys_qa_es`”或“ES 文档 id 是 `QaInfo.id`”，回看 DDL 与同步器第 65～74 行。

### 练习 B：从用户消息到工具字符串（Agent 路径）

1. 在 `agents/SupportAgent.java` 找到 `ToolCallbacks.from(...)`，确认 `ragTool` 是否在注册名单。
2. 找到 `executeTool` 的 `case "searchKnowledgeBase"`，列出它接受的参数别名与空值分支返回值。
3. 打开 `RagTool#searchKnowledgeBase`，把 `ToolNotifySupport`、`ragService.searchAnswers`、空结果文本、异常文本四处圈出来。
4. 回答：这段链路里模型出现了几次？`userId` 有没有参与检索过滤？

**预期观察**：模型参与两次（请求工具、生成最终文字）；`ToolContext` 的 userId 不进入 `searchAnswers`；工具返回的是字符串，不是 SSE 事件本身。
**失败解释**：若答“@Tool 描述就是授权”，回看 §四与 `PermissionAspect` 的取身份方式。

### 练习 C：手算 RRF 与按组去重

用 §3.8 的四条虚构候选，把向量榜改成 `[91001, 91002, 91003]`、BM25 榜改成 `[91002, 91005, 91001]`，重新计算：

1. 每条文档的 `1/(60+rank)` 与两路之和；
2. 降序排列；
3. 按 `groupId` 去重后的 `finalResults`；
4. 若其中一条的 `sys_qa_es` 映射已被删除，`authoritative` 返回几条。

**预期观察**：`91001` 与 `91002` 分数互换但仍同组，只留分数更高的那个；删除映射的那条被 `authoritative` 丢弃，且不从后续补足。
**失败解释**：若你把名次当 0 起算（用 `1/(60+i)`），和源码 `i+1` 不符；若把 Top5 当成“至少返回 5 条”，忽略了校验淘汰不补位。

### 练习 D：三条变化时间线

分别假设：只改 `answer`；只改 `question`；按 `groupId` 删除。对每一种，写出 t0（MySQL 提交并 enqueue）、t1（同步前搜索）、t2（同步后搜索）三行，并在 t1 行标出 `authoritative` 的哪一个条件决定了结果。

**预期观察**：在更新已提交、候选进入校验且本次答案查询读到新行的条件下，改答案读到新值；改问题在本次回查看到更新且文本不等时旧候选被 question 条件丢弃；删除在本次查不到映射时被丢弃。t2 的“同步后”也要等任务被领取、`synchronizeGroup` 成功并刷新，且新问法是否命中仍取决于索引、query 与排名——旧问法仍可能命中新文档。
**失败解释**：若写“三种情况立即一致/旧问法永不命中”，把 `fixedDelay` 的调度含义、`@Scheduled` 的实际运行状态与“接口成功”的范围混为一谈，也把检索命中当成确定性结论。

### 练习 E（可选）：单路失败与预算

在 `searchAnswers` 里找出三处 `supplyAsync` 提交（Embedding、向量、BM25）、`allOf(...).get(...)` 与内外两层 `finally` 的 `cancel(true)`，再打开 `RagTool` 的 `try`/`catch` 边界。回答：向量路失败、BM25 还在跑时会立刻失败吗？8000ms 覆盖 `authoritative` 吗？`cancel(true)` 能否证明底层 HTTP 已停止？`RagTool` 的固定失败文本覆盖哪些异常？

**预期观察**：`allOf` 要等两路都完成才把异常交给 `get`，所以不一定“立刻”失败，可能先到 deadline；最终整次失败且无 BM25 兜底，不覆盖 `authoritative`；Future 层取消不能证明物理请求停止；固定文本只覆盖 `RagTool` 的 `try` 内 `RuntimeException`，不覆盖 `notifyToolListener` 或上层分派异常。
**失败解释**：把 Future 层的取消当成底层网络取消，把 `allOf` 当成“任一 future 失败立刻抛出”，或把字符串回执当成客户端已收到。

## 八、逐测试阅读卡

> 以下全部是**测试源码阅读，未执行**。读测试只说明“作者写了什么断言”，不等于行为已验证。

### 8.1 `src/test/java/com/fancy/rideagenthub/agentbase/rag/RagConsistencyRegressionTest.java`

- **门控与替身**：无 `@EnabledIf*`，普通 JUnit 5；`QaInfoMapper`、`QaElasticMapMapper`、`QaSyncJobMapper`、`ElasticsearchClient`、`EmbeddingModel` 全是 Mockito `mock`；被测 `RagService` 是真实 `new`；`@BeforeAll` 手工初始化 MyBatis-Plus `TableInfoHelper`，`@BeforeEach` 反射注入 `syncJobs` 与真实 `SnowflakeIdWorker(1,1)`；无 `@AfterEach`，无数据库/ES 临时资源。

| 方法 | 主要断言 | 覆盖与不覆盖 |
|---|---|---|
| `addWritesDatabaseAndDurableJobWithoutCallingElasticsearch` | 两次 insert + `enqueue`，`verifyNoInteractions(es)` | 覆盖“写路径不碰 ES”；不验证真实 SQL |
| `updateIsStoredAndQueuedEvenWhenElasticsearchIsUnavailable` | 改答案入队且不碰 ES | 只测 updateAnswer；不测 ES 真的不可用 |
| `failedOrDeletedDocumentsAreNeverServedFromEsAlone` | 实际断言是：对 `id=10`、无对应映射的文档，`authoritative(...).isEmpty()` | 该方法没有显式 stub `selectList`。Spring Boot 3.5.9 的 `spring-boot-starter-test` 管理 Mockito（5.x 线），其默认返回策略对集合类型给出**空集合**，于是 `.stream()` 得到空映射并返回空列表；若某版本默认返回 null，源码会立即 `.stream()` 抛 NPE 而不是产生空结果。确切补丁版本本轮未解析依赖、测试未运行；不要把测试名当断言，也不要说它测了真实数据库 |
| `answerAlwaysComesFromCurrentDatabaseRecord` | 文档 answer 被 MySQL `"current"` 覆盖 | 直接构造候选，不经过 RRF |
| `staleQuestionVectorIsRejectedUntilResynchronized` | question 不一致 → 空 | 名字提到 vector，实际断言的是 question 文本不等 |
| `failedIndexSynchronizationRetainsJob` | `spy` 同步器抛异常 → `defer`，不 `deleteById` | `synchronizeGroup` 被 `doThrow` 替身；不测真实 ES |
| `completedIndexSynchronizationAcknowledgesJob` | 成功 → `deleteById` | 同上；不测索引内容 |
| `embeddingSharesTheOverallSearchDeadline` | 50ms 预算 + latch 卡住 embedding → 1 秒内 `RuntimeException`，`verifyNoInteractions(es)` | 覆盖超时分享；不测 8 秒默认值与真实网络 |

- **未覆盖**：RRF 公式与排序、Top5、按组去重、两路 ES 查询构造、`queryRagQA`、`deleteQA`、`updateQuestion`、真实索引 mapping、并列分数、`hit.id()` 与 source `id` 不一致的文档、真实并发下两条查询的读时点。

### 8.2 `src/test/java/com/fancy/rideagenthub/RepairDatabaseIntegrationTest.java`

- **门控**：类级 `@EnabledIfSystemProperty(named = "test.mysql.port", matches = "\\d+")`，未设置则整类跳过。
- **真实/替身**：真实 MySQL（仅测试端口）+ 真实 MyBatis mapper（`SqlSessionTemplate` + `MybatisSqlSessionFactoryBean`）；`EmbeddingModel`、`ElasticsearchClient` 为 mock；RagService 为真实对象 + 反射注入。
- **临时资源与清理**：`@BeforeAll` 建 `ride_repair_<uuid32>` 独立 schema 并执行 `sql/init.sql`；`@BeforeEach` 只在该 schema 内 `DELETE` 七张表；`@AfterAll` 仅在 schema 名匹配 `ride_repair_[0-9a-f]{32}` 时 `DROP DATABASE`。不碰应用库。
- **两处 RAG 相关断言**：

| 方法 | 断言 | 覆盖与不覆盖 |
|---|---|---|
| `failedBatchRollsBackBothQaDataAndSyncJobs` | 批量中混入非法 DTO 抛异常后，`sys_qa_info`/`sys_qa_es`/`sys_qa_sync_job` 三表均 0 行 | 覆盖事务回滚同时撤销问答与任务；不测真实 ES |
| `newMutationAfterLockedSyncCannotLoseItsJob` | 读事务 `FOR UPDATE` 持锁时 writer `enqueue` 超时；释放后 writer 成功，最终任务仍有 1 行 | 覆盖“锁持有期间新入队不被旧同步删除覆盖”的库层前提；不测 `version` 防旧任务覆盖的显式比较，也不测 `claimBatch`/`defer` 抛异常 |

- **未覆盖**：真实 ES/Embedding 端到端同步与检索、`searchAnswers` 与真实库组合、RRF、Top5、解析异常与超时行为。

## 九、一页复习

- **数据模型**：`sys_qa_info(group_id, answer)` 一组一答案；`sys_qa_es(elastic_id, group_id, question)` 一组多问题；`sys_qa_sync_job` 是任务不是数据。ES 文档 id = `elastic_id` 字符串；`answer` 在 ES 里 `index=false`，真值在 MySQL。
- **正常检索顺序**：空检查 → deadline → Embedding（串行等待）→ 向量/BM25 两路并行 → 各 `1/(60+rank)` 累加 → 按分数降序 → 按 `groupId` 去重 → Top5 → `authoritative` 校验并覆盖答案。
- **校验五条件**：id 可转 Long、映射存在、question 一致、groupId 一致、答案组存在；通过后覆盖 answer。校验淘汰不补位。
- **失败口径**：两路都提交后 `allOf` 等所有 future 完成，任一路异常（两路完成后）→ 整次 `RuntimeException("RRF Search failed")`；一路先失败另一路未完时可能先到 deadline。`RagTool` 的固定文本只覆盖其 `try` 内 `RuntimeException`；空白/无命中/校验全丢 → 空；`cancel(true)` 不等于底层请求停止；deadline 不覆盖融合与 MySQL 核对。
- **同步口径**：写接口成功 = MySQL 与该次入队已提交；`fixedDelay=5000ms` 是上次调用完成到下次调度的延迟，不是入队后可搜上界。任务到期、领取批次（5 组、锁）、Embedding/写入、`refresh` 与本次排名都满足，某文档才可能出现在这次结果里；删除组时 `info == null` 直接 return，后面 refresh 不执行。不承诺无条件收敛。
- **一致性口径**：只有更新已提交、候选进入校验且本次答案查询读到新行，才把新值覆盖到候选；两条查询各自独立、无本方法显式事务，不能推断是响应时最新值。改问题/删除后旧候选被丢弃同样以本次回查看到对应更新、ID/文本按假设一致为前提；旧问法仍可能命中新问题，新问法也不保证进入结果。
- **术语**：BM25 按词、向量按语义、RRF 按名次；`chat-es-setting.json` 无调用方，不是当前索引配置。
- **证据**：本章全部静态推导与测试源码阅读，实际运行：无。

## 十、常见误区

1. **“ES 里搜到的 answer 就是答案”**——错。`answer` 不参与检索；只有候选通过有限校验、且本次答案查询读到该行时，`authoritative` 才覆盖到候选上。
2. **“一条问题一个答案”**——错。一个 `group_id` 挂多个问题，去重发生在 group 维度。
3. **“RRF 用 BM25 分数和向量距离相加”**——错。只用两路名次，公式 `1/(60+rank)`。
4. **“Top5 意味着最终一定返回 5 条”**——错。先截取 Top5 再校验，淘汰后不从排名 6 以后补足，也不回退同组较低候选。
5. **“单路失败会立刻由另一路降级”**——错。`allOf` 等两路都完成才把异常交给调用方；一路先失败另一路未完时可能先到 deadline，最终整次失败、无降级。
6. **“接口成功就能立刻搜到（默认 ≤5s）”**——错。`fixedDelay=5000ms` 是上次调用完成后到下次调度的延迟；还要任务到期、被领取（一次 5 组、锁）、Embedding/写入、`refresh` 与本次排名都配合，源码没有可搜上界。
7. **“改了答案，同步前会返回旧答案”**——在更新已提交、候选进入校验、且本次答案查询读到新行的条件下不成立；但不是无条件保证：两条查询各自独立、无本方法显式事务，请求可能观察到较早值。真正容易短暂失效的是问题文本/向量，且旧问法仍可能命中新问题。
8. **“`@Tool` 描述说必须调用，所以模型有权查任何知识”**——错。提示词是行为指引；权限来自令牌与切面，知识库本身无逐用户过滤。
9. **“`chat-es-setting.json` 里有 N-Gram，所以知识库用了 N-Gram 分词”**——错。该文件全仓无调用方。
10. **“读过测试就是跑过测试”**——错。本章所有测试均为源码阅读，实际运行：无。

## 十一、自测与答案

> 先自己写答案，再展开对照。每题都要求给推导或源码位置，不只给结论。

<details>
<summary>1. 一组答案有两个问法，请写出它们在三张 MySQL 表和 ES 文档里的 ID 关系，并说明哪个 ID 用来检索、哪个用来当答案。</summary>

`sys_qa_info` 一行：自增 `id`（检索不用）+ 雪花 `group_id`（分组）+ `answer`（权威答案）。`sys_qa_es` 两行：各自自增 `id`（不用）+ 各自雪花 `elastic_id` + 同一个 `group_id` + 各自 `question`。同步器把 `elastic_id.toString()` 写成 ES 文档 `_id`，把 `group_id.toString()` 写成文档 `groupId`。RRF 的 key 是 ES `hit.id()`；回 `sys_qa_es` 查映射时用的是 `QaDocument#getId()` 转成的 `Long`，源码没有比较这两个 ID 是否相等（正常同步器写入时二者一致，异常/历史文档不保证）。检索字段是 `question`/`questionVector`；答案取本次 `sys_qa_info` 查询读到的 `answer`，由 `authoritative` 覆盖到 `QaDocument#answer`。雪花 ID 位数随时间和节点配置变化，示例短值仅为教学虚构。`init.sql` 未对 `group_id`/`elastic_id` 建唯一约束。依据：`RagService#addQA/#authoritative`、`QaIndexSynchronizer#synchronizeGroup`、`init.sql` 三表 DDL。
</details>

<details>
<summary>2. 管理员 `GET /rag/search?question=订单可以提前多久预约` 与客服 Agent 调 `searchKnowledgeBase` 有什么不同？分别说出身份、参数、是否经过模型、结果来源。</summary>

HTTP：身份由 `@RequirePermission({"ADMIN"})` + `PermissionAspect` 从 `UserTokenContext` 判定；参数是 `@RequestParam String question`，不改写；不经过模型；返回 `Result<List<QaDocument>>`。Agent：身份来自对话入口的令牌与 `assertOwned`，`ToolContext.userId/chatId` 由服务端注入但检索不使用；参数来自模型工具调用的 `queryStr`（兼容 `query`/`question` 别名）；经过模型两次；工具把核对后的文档拼成字符串作为工具回执，模型再生成用户文字。相同点是都调同一个 `RagService#searchAnswers`；不同点是外层身份与结果呈现。依据：`RagController#search`、`SupportAgent#executeTool`、`RagTool#searchKnowledgeBase`。
</details>

<details>
<summary>3. 向量榜 [A(rank1), B(rank2)]、BM25 榜 [B(rank1), A(rank3)]，A、B 同属 group 90001。请算分数并说出最终保留谁。</summary>

`RRF_K=60`。A = `1/(60+1) + 1/(60+3)` = `1/61 + 1/63` ≈ `0.016393 + 0.015873 = 0.032266`。B = `1/(60+2) + 1/(60+1)` = `1/62 + 1/61` ≈ `0.016129 + 0.016393 = 0.032522`。B 分数更高，降序排第一；按 `groupId` 去重时 B 先进入 `finalResults`，A 因同组被跳过。最终保留 B。若此时 `authoritative` 又因 B 的映射缺失把它丢弃，结果不会用 A 补足——Top5 与去重都在校验之前。依据：`searchAnswers` §3.4/§3.5。
</details>

<details>
<summary>4. 两条候选分数完全相同，源码能保证谁排前面吗？请说明理由。</summary>

不能。`sortedDocs.sort(comparingByValue().reversed())` 是稳定排序，能保持进入排序时的相对次序；但那个次序来自 `new ArrayList<>(scoreMap.entrySet())`，而 `scoreMap` 是 `HashMap`，迭代顺序不保证。因此并列时先后不可由源码预测；如果两条同组，谁能通过 collapse 也就不确定。不要补造“按 id 升序”之类不存在的规则。依据：`searchAnswers` 中 `scoreMap = new HashMap<>()`、`sortedDocs.sort(comparingByValue().reversed())` 与按 `groupId` 去重三段（§3.4/§3.5）。
</details>

<details>
<summary>5. 管理员刚改完答案、同步还没跑，用户搜到旧文档会读到旧答案吗？如果改的是问题呢？</summary>

改答案：在**更新已提交、旧候选进入校验、且本次 `qaInfoMapper.selectList` 读到更新后的行**时，不会因为 ES 副本落后而读到旧答案——`authoritative` 用 `info.getAnswer()` 覆盖。但它对两表各发独立查询、本方法没有显式事务或全局快照；若查询读到较早行、候选在校验中被丢弃，或并发提交发生在读取之后，本次返回就不一定是当时的最新值。改问题：若本次回查看到更新后的 `mapping.question`，旧问法搜到的候选会因文本不等被丢弃；新问法是否命中取决于实际索引、query 与排名，不能保证。同步完成后旧问法仍可能通过 `match`/语义检索命中新问题。两者都是静态推导，未实测。依据：`authoritative`、`updateAnswer`/`updateQuestion` 与 `QaIndexSynchronizer` 的异步边界。
</details>

<details>
<summary>6. 已按 groupId 删除，但 ES 文档还在并被召回，`authoritative` 会返回什么？列出它检查的条件。</summary>

在**本次回查看不到对应映射**的前提下，该候选被丢弃：按 `doc.getId()` 查不到 `sys_qa_es` 映射，`mapping == null` 直接 `continue`；若请求先读到删除前的行、并发提交随后发生，则本次不一定因该条件丢弃。若删的是单条问题而同组其他问题仍在，则被删文档在本次回查看不到映射时被丢弃。检查条件依次为：id 可转 `Long`（取 `QaDocument#getId()`，不与 ES `hit.id()` 比较）；`sys_qa_es` 映射存在；`mapping.question == doc.question`；`String.valueOf(mapping.groupId) == doc.groupId`；`sys_qa_info` 中答案组存在；全部通过才覆盖 answer 并加入结果。两路合并后 source 仍为 null 的候选在去重阶段跳过，source 解析异常可能使检索失败。依据：`authoritative`、`deleteQA`、`searchAnswers`。
</details>

<details>
<summary>7. 向量路超时但 BM25 正常，用户能拿到 BM25 的查询结果吗？8000ms 是不是整个请求的硬上限？</summary>

不能。`CompletableFuture.allOf(vectorFuture, bm25Future)` 要等两路都完成：一路先失败而 BM25 仍在跑时，不会立刻把那一路的异常交给调用方，等待也可能先到 deadline；两路完成后 `get` 抛出的异常被外层 `catch (Exception e)` 包成 `RuntimeException("RRF Search failed", e)`，没有“用另一路兜底”的分支。`RagTool` 只在其 `try` 内捕获 `RuntimeException` 才返回“知识库暂时不可用。”；`notifyToolListener` 在 try 外、`executeTool` 另有 catch，模型是否继续、事件是否送达都不保证。8000ms 是 `@Value("${rag.search.timeout-ms:8000}")` 的 Java 默认值，只覆盖 Embedding 与两路 `allOf` 等待；RRF 融合与 `authoritative` 两次 MySQL 查询不在 deadline 内，所以不是整请求硬超时，真实运行时值未核验。`finally` 的 `cancel(true)` 是逻辑取消，底层 HTTP 是否停止未知。依据：`searchAnswers`、`RagTool#searchKnowledgeBase`、`SupportAgent#executeTool` 与 `AgentStreams`。
</details>

<details>
<summary>8. BM25、向量 Embedding、RRF 各解决什么问题、代价是什么？为什么不能拿 `chat-es-setting.json` 当知识库的当前配置？</summary>

BM25 按词项统计打分，精确词命中好、同义改写弱；项目用 `match` 查 `question`，每路窗口 100。向量 Embedding 把文本变成固定维数组（配置文件写 `dimensions: 1024`，实体注解也声明 1024 维）做 kNN，能抓语义但依赖模型与索引参数；项目查 `questionVector`，`k=100`、`numCandidates=200`。RRF 只按名次融合 `1/(60+rank)`，避开异质量纲但丢掉分差、并列先后不可预测。代价还包括：**本次未进入任一路前 100 的文档不参与本次融合**，以及 Top5 后不补位；这不评价真实相关性，也不保证进入窗口的固定名次。`chat-es-setting.json` 定义的是 `my_ngram_analyzer` 与 `full_text_content` 等聊天字段，全仓 `src` 检索无调用方；知识库索引由 `QaIndexSynchronizer` 用 ES Java Client 编程式创建，且没有从该文件读 setting。不能从文件存在或注解声明推断 ES 集群真实 mapping，ik_smart/cosine 是否生效属未知。依据：两个私有检索方法、`RagService` 常量、`QaIndexSynchronizer`、`application.yaml`、全仓检索结果。
</details>

## 十二、源码核验入口与导航

- **本章主讲源码**：`controller/RagController.java`；`agentbase/tool/RagTool.java`；`agents/SupportAgent.java`（注册与 `case "searchKnowledgeBase"`）；`agentbase/rag/RagService.java`（`searchAnswers`、`authoritative`、`searchVectorOnly`、`searchBm25Only`、增改删方法）；`domain/entity/{QaInfo,QaElasticMap,QaSyncJob,QaDocument}.java`；`domain/dto/RagQA*.java`；`domain/vo/RagQAQueryVO.java`；`mapper/QaSyncJobMapper.java`。
- **边界与配置（只读）**：`agentbase/rag/QaIndexSynchronizer.java`（只看到“写后异步重建”为止）；`config/AsyncWorkConfig.java`（`ragExecutor` 4/8/32）；`src/main/resources/application.yaml`（只看 `spring.ai.dashscope.embedding.options.model/dimensions` 与键名，不输出密钥）；`src/main/resources/sql/init.sql`、`src/main/resources/sql/migrations/20261113_security_consistency.sql`（三张表 DDL）；`src/main/resources/config/chat-es-setting.json`（确认无调用方）；`src/main/resources/api/rag-consistency.md`（接口侧口径）；`exception/GlobalExceptionHandler.java`（异常到 HTTP 的静态推导）。
- **测试源码**：见 §八两张阅读卡，全部只读、**未运行**。
- **上一章**：[第 20 章 外部工具与提示词边界](../04-chat-and-agent/05-external-tools-and-prompts.md)——`RagTool` 在工具链中的位置。
- **下一章**：[第 22 章 RAG 索引同步与检索预算](02-index-sync-and-budget.md)（R2）——任务领取、重试、失败与 `Future.cancel`；R1 只交代读懂检索所需的最小边界。
- **相关专题**：[第 15 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)（一致性思路对照）、[第 17 章 分类路由与工具循环](../04-chat-and-agent/02-routing-and-tool-loop.md)（工具分派）、[第 19 章 三层记忆与工具结果反查](../04-chat-and-agent/04-memory-and-tool-results.md)（工具回执）。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、测试与 API 文档写完整仓库相对路径。定位优先用“类 + 方法”，行号会漂移。

**状态：已通过复审（P6-1，2026-11-18），并于 2026-11-22 独立复核。** R1-R1～R5 已静态复核，复审者另修正 null source 合并、旧答案读时点与练习等局部表述；本次复核只做定点修正。检查范围与结论见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)；未运行应用、测试或中间件。
