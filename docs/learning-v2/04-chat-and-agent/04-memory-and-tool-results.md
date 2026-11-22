# 第 19 章 三层记忆、消息窗口与工具结果反查：一条消息后来去了哪里

> 虚构乘客林小雨（`userId=7000001`）用一个固定会话 UUID 与订单 Agent 聊了很久：先问“天府三街附近有什么吃的”，工具返回了若干地点；又问“从成都东站到天府三街多少钱”，模型调了算价工具；然后她关掉页面，半小时后回来点“确认”，继续创建订单。浏览器只看到文字，服务端却要在不同时间、不同存储里回答四个问题：**下一条请求拿哪段历史**、**旧快照会不会盖掉新消息**、**工具回执是给模型看还是留着以后反查**、**清空和恢复各删了什么**。
>
> 这一章是 C1《一次对话与事件流》里“历史从哪来”的完整答案，也是旧资料 Redis 09（回填租约）、19（三级缓存）、20（工具结果缓存）的主讲章。S4 只做过“锁令牌 vs 版本号”的最小对比，本章负责把并发时间线、Lua 分支和失败边界一次讲完。
>
> **本章没有启动服务、运行测试或脚本、连接 Redis/MySQL/MongoDB/Redisson、调用模型或外部 API，也没有读取凭据。** 除明确标注“测试源码阅读”的地方，结论都是源码静态推导；人物、消息、时间、ID 与数值均为虚构示意，不是实测输出。C5（外部工具与提示词）见 [第 20 章](05-external-tools-and-prompts.md)（已通过复审）。

## 学习目标

读完本章，你应该能：

1. 用一个长对话说清用户消息、Assistant 工具调用、`ToolResponseMessage` 与“工具结果反查”是四种不同对象，并指出各自存在哪里、由谁写、由谁读。
2. 逐方法追踪 `MessageMemory#get/getPersisted/save/clearChat`，说明 Heap L1、Redis L2、MySQL L3 的读取顺序、窗口位置、回填条件、版本推进与部分成功边界。
3. 用 T1/T2 时间线推导“旧快照破坏性回填覆盖并发追加”，逐分支解释 `CURRENT_HISTORY_VERSION`、`APPEND_HISTORY_IF_VERSION_MATCH`、`OVERWRITE_IF_VERSION_MATCH` 与暂存 key，并区分版本号、互斥锁、会话锁和单轮执行锁。
4. 手算 `MessageParser` 的坏记录恢复与 `MessageWindow#tail` 的批次边界，解释合法未闭合尾批次、重复/未知 ID、`limit` 与“窗口不等于全历史”。
5. 独立追踪 `ToolRepPointerTool#recordCalling/getToolResponse` 与 `ToolResponseMemory`，说明 `callId`、`chatId` 的来源、两级写入顺序、TTL、归属校验和 `ToolContext` 边界，不把提示词承诺当源码事实。
6. 阅读本章涉及的回归/集成测试，逐项说出门控、真实或 mock 组件、断言、清理范围与未覆盖；能区分源码调用、成功条件、可能已发生但响应丢失以及完全未知。

## 前置知识与阅读时间

- 硬必读：[第 16 章 一次对话与事件流](01-chat-round-and-sse.md)——请求入口、会话元数据、单轮执行锁、Agent 终态保存与 SSE 事件。
- 硬必读：[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)——锁令牌与回填版本的最小对照。
- 推荐先读：[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)——工具回执、`ToolContext` 与四个 Agent 的工具循环。
- 推荐先读：[第 18 章 订单确认、恢复与消费](03-order-confirmation.md)——`getPersisted`、`answered` 和确认恢复的上下文。
- 基础：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)（Redis 类型、TTL、Lua、Flux）、[第 3 章 数据存在哪，事务管到哪](../00-foundations/03-data-and-transactions.md)（事务与跨库边界）。
- 阅读时间：约 120～150 分钟（含练习与自测）。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、脚本、配置与测试写完整仓库相对路径。
- **证据约定**：源码事实 = 读到实现；调用方核对 = 还追了谁调用；测试源码阅读 = 只读断言、未运行；静态推导 = 按代码顺序推演；实际运行 = 本章**无**。

---

## 零、先记住十二条事实

1. **一次历史有两条写路径**：正常聊天在特定终态/暂停时由 Agent 调 `saveNewMessages` → `MessageMemory#save`；工具执行后 `OrderAgent#handleToolCalls`（`DailyAgent`/`SupportAgent` 各有一份同构实现，`FallbackAgent` 没有工具循环）会**尝试**调 `ToolRepPointerTool#recordCalling` → `ToolResponseMemory#save`，空参数会早退，运行时异常被 catch 并记日志，不影响继续构造完整工具回执。它们不是同一份数据；辅助索引写入、内存回执构造和聊天历史落库是三个不同步骤。
2. **`MessageMemory#get` 的默认读取顺序是 Heap → Redis → MySQL**；`getPersisted` 是恢复专用路径，只查 MySQL；`POST /chat/v2/restore` 也走 `get(...,100)`，再用 `MessageParser#simpleParse` 只保留 USER/ASSISTANT 文本给前端。
3. **L1 是进程内 `ConcurrentHashMap`，没有 TTL**；L2 历史数据 key 由调用与 Lua 设置为 24 小时或切换焦点后的 30 分钟；L3 在 `sys_messages`，没有“按会话自动过期”的 SQL 策略。版本 key `chat:history:version:{chatId}` 由 Lua `PERSIST`，正常路径不设过期。
4. **版本不是锁令牌**。版本号回答“我读快照之后，这份缓存代际变没变”；`RedisLock` 的 UUID 回答“锁是不是我拿的”；`chat:execution:{chatId}` 是每轮执行互斥；`sys_chat.locked`/`chat:info:{chatId}` 的 `locked` 是会话暂停/停用状态。四者保护对象不同。
5. **窗口在读取出口，不在存储查询里**：L2 用 `LRANGE 0 -1` 取全量再在 Java 里 `MessageWindow.tail` 截；L3 的 `getLastN` 用 `orderByDesc(id).last("limit n")` 取最近 n 行再反转，但 `MessageMemory` 正常 `get` 走 `getAll`，回填缓存的也是全量。
6. **`MessageWindow#tail` 会检查工具批次配对**：重复 `toolCall.id()`、未知 `ToolResponse.id()`、pending 未闭合时插入其他消息都会抛 `IllegalStateException`；只有最后一批工具调用可以没有回执。因此“取 1 条消息”可能返回一整批 2 条以上的消息。
7. **`MessageParser#unparseHistory` 不是“报错就全丢”**：坏 JSON/无法解析的消息会清空当前已解析结果，随后跳过非 `UserMessage` 的桥接消息，从下一条用户消息重新建立上下文；原始数据库记录不被删除。
8. `MysqlMemory#append` 先 `SELECT ... FOR UPDATE` 锁定 `sys_chat` 行，墓碑（`history_deleted=true`）直接抛 410，再逐条 insert 消息。`MessageMemory#save` 的顺序是取版本号 → MySQL append → Redis 条件追加 → Heap 条件追加 → `updateChatTime`；**MySQL 成功后的缓存失败不会回滚 MySQL**。
9. `CURRENT_HISTORY_VERSION` 只在**版本 key 自身不存在**或**版本 key 自身带 TTL**时把数据 key 删掉（迁移/修复旧结构）；历史数据 key 的 24h TTL 是正常状态，**不是**这个分支的触发条件。版本 key 最后都 `PERSIST`。
10. `APPEND_HISTORY_IF_VERSION_MATCH` 在 Java 参数非空、序列化结果非空且脚本正常执行时，先 `INCR + PERSIST` 推进版本，然后：版本相同且数据 key 存在才 `RPUSH + PEXPIRE`；版本不同则 `DEL` 数据 key；数据 key 不存在则什么都不做。它**不会**凭几条新消息创建残缺历史；前置早退或脚本异常不能按“必然推进版本”理解。
11. `ToolResponseMemory` 的 Redis key 不在 `RedisKeyConstants`：`ToolResponseMemory#key` 直接拼 `chat:tool:v2:{chatId}:{callId}`；常量类里的 `TOOL_RESPONSE_KEY="tool:"`/`toolResponseKey(callId)` 在当前主链没有调用方。写入先 MySQL `insert` 再 Redis `SET ... 24h`；读取先 Redis 再 MySQL 并回写缓存。
12. `getCalling(callId)` 的 `callId` 来自模型工具参数，`chatId`/`userId` 来自服务端注入的 `ToolContext`；工具内还调用 `chatManager.assertOwned(userId, chatId, false)`。但缓存 key 的“chatId + callId”归属只由这两段代码共同构成，**不能断言跨会话隔离一定由 Redis 单层完成**，因为数据库查询同样带两个条件、服务端校验也在工具层。

---

## 一、业务场景：一段长对话的四种“历史对象”

### 1.1 虚构时间线

| 时刻 | 用户/模型看到的事 | 服务端对象 | 谁保存 |
|---|---|---|---|
| t0 | 林小雨输入“附近有什么吃的” | `UserMessage` | 本轮最终收尾时 `saveNewMessages` 一起保存 |
| t1 | 模型请求 `searchPOIsByKeyword`；服务端执行后得到地点 JSON | `AssistantMessage(tool_calls=[...])`、`ToolResponseMessage(responses=[...])`、辅助索引中的完整 JSON | 前两者进聊天历史；后者由 `recordCalling` 单独写 |
| t2 | 模型用工具结果回答“附近有……” | 普通 `AssistantMessage` | 同一轮完成时保存 |
| t3 | 用户说“从成都东站到天府三街多少钱”，模型再次调算价工具并收到报价 | 又一批工具调用/回执 | 同理 |
| t4 | 页面关闭；半小时后同一 chatId 发“确认” | 会话 Hash 里的 `break/pendingToolCall`、MySQL 历史 | 恢复路径用 `getPersisted` 读 MySQL |
| t5 | 用户问“刚才那个坐标是多少？”模型若只看到 `call_id: ...` 提示，调用 `getCalling` | 工具辅助索引的响应字符串 | `ToolResponseMemory#get` 读 Redis 或 MySQL |

这里最容易混的是 t1 的三种对象：

- `AssistantMessage.tool_calls` 是**模型要求执行什么**，包含 `id/name/arguments`；
- `ToolResponseMessage.responses` 是**服务端执行结果写回模型协议**，包含同一个 `id`、工具名和文本结果；
- `ToolResponseMemory` 里的记录是**辅助反查索引**，供以后 `getCalling` 用一个 `callId` 取回完整响应，不是 Spring AI 的 `Message` 对象。

### 1.2 为什么需要三层

- **Heap L1**：最快，当前 JVM 进程内；同一 chatId 的读写用 `ConcurrentHashMap.compute` 原子化。进程重启即消失，而且没有 TTL。
- **Redis L2**：跨请求、跨实例可读；List 保存完整 JSON 历史，另有一个版本号 key。数据有过期，版本 key 正常不过期。
- **MySQL L3**：权威历史；`sys_messages` 按自增 `id` 排序，`sys_chat` 的 `history_deleted` 是墓碑。Redis/Heap 都是它的加速层，但并发回填必须防止旧快照覆盖新数据。
- **MongoDB 不参与聊天记忆**：`OrderRouteService` 只保存订单路线轨迹（`addOrder`/`getByTraceId`/`deleteByTraceId`），不存对话消息；不要看到“轨迹”就把它当聊天历史。
- **ES 也不是这一章的记忆层**：`ESMemory` 是空 `@Component`，`MemoryAdvisor` 的两个方法直接返回 `null`；对 `src` 的检索只找到定义，没有业务调用方。知识库检索由 RAG 章承担。

### 1.3 首次出现的几个术语

- **回填（refill）**：某层 unhit 后从更低层读出数据，再写回更高层。例如 MySQL 命中后写 Redis 和 Heap。
- **租约/版本（lease/version）**：读耗时操作**之前**记一个版本号；写回时再比较，版本变了就放弃旧快照。它不是“我持有多久的锁”。
- **暂存 key（staging key）**：回填时先把完整历史写到一个临时 List，再由 Lua 原子校验版本并 `RENAME` 到正式 key；失败时删暂存。
- **窗口（window）**：给模型的上下文不是无限全历史；`lastN=100` 是“目标是最近约 100 条”，`MessageWindow#tail` 先把整段历史按工具批次配对检查完，再从满足目标数量的批次边界向前扩展，因此实际返回条数可能**超过** 100。
- **墓碑（tombstone）**：这里指 `sys_chat.history_deleted=true`；`MysqlMemory#append` 看到墓碑抛 410，`ChatInfoService#findOwned` 也拒绝已有会话继续访问。

---

## 二、最少概念：存储、键与寿命

### 2.1 一张键与表的清单

| 层 | 键/表 | 内容 | 写入者 | 寿命/TTL（按当前源码） | 身份与会话归属 |
|---|---|---|---|---|---|
| L1 | `HeapMemory.heapCache`，key 就是 `chatId` | `Entry(List<Message>, version)` | `append`/`appendIfVersionMatch`/`overwriteIfVersionMatch`/`clear` | 进程内；**无 TTL、无容量上限**；版本在 `clear` 时仍递增 | `MessageMemory` 入口先校验 userId，但 `HeapMemory` 自身只按 chatId 索引 |
| L2 数据 | `chat:history:{chatId}`（`RedisKeyConstants#chatHistoryKey`） | 每条消息 `MessageParser#parse` 后的 JSON List | `append`（测试/辅助路径）、`appendIfVersionMatch`（真实 save）、`overwriteIfLeaseValid`（回填） | 真实 save 用 `FOCUS_CHAT_TTL=24h`；切焦后 `expire(...,30min)`；回填用 `HISTORY_TTL=24h`；`APPEND_WITH_VERSION_BUMP` 的 TTL 由调用方传入 | key 只含 chatId；userId 在 `MessageMemory`/`ChatInfoService` 入口校验 |
| L2 版本 | `chat:history:version:{chatId}` | 十进制版本号字符串 | 五段 Lua 中的 `INCR/SET/PERSIST` | 正常路径 `PERSIST`，无 TTL | 只含 chatId；外部删除/淘汰后源码无 fencing 保护 |
| L2 暂存 | `chat:history:staging:{chatId}:{UUID}` | 待回填的完整消息 JSON List | `RedisMemory#overwriteIfLeaseValid` 的 MULTI | `STAGING_TTL=1min`，仅在 `EXPIRE` 成功时兜底；Lua 成功 `RENAME`、版本失效 `DEL` | key 含 chatId + 随机 token；只活一次回填 |
| L3 历史 | `sys_messages`（`ChatMessage`） | `id`、`chat_id`、`message_text`、`created_at` | `MysqlMemory#append`；清空用 `delete` | 无 TTL；按 `id` 顺序读 | `chat_id` 过滤；主键是自增 `id`，`idx_chat_id_id_desc` 在 `src/main/resources/sql/init.sql` 中是普通索引，不是唯一索引。线上 schema 若有迁移差异，本表未核验 |
| L3 会话 | `sys_chat`（`Chat`） | `chat_id`、`user_id`、`locked`、`history_deleted`、时间 | `ChatInfoService#insertChat/lockChat/retireHistory/restoreChat` | 长期记录 | `assertOwned` 比对 `String.valueOf(chat.userId)` 与服务端 userId |
| 反查索引 | Redis `chat:tool:v2:{chatId}:{callId}`；MySQL `sys_chat_tool_repsonse` | 完整工具响应字符串 | `ToolResponseMemory#save` | Redis `TTL=24h`；MySQL 无删除路径 | Redis key 与 MySQL 查询都带 chatId + callId；工具层还做 `assertOwned` |
| 会话 Hash | `chat:info:{chatId}` | `classification`、`break`、`pendingToolCall`、`locked`、`OrderId` 等 | `ChatServiceImpl`、`OrderConfirmation`、`OrderAgent`、`ChatManager` 等 | 由各自写入决定；`lockChat` 60min；`restoreChat` `PERSIST` | C1/C3 主讲，本章只在恢复与清空处引用 |

`RedisKeyConstants` 里还有一个 `TOOL_RESPONSE_KEY="tool:"` 和 `toolResponseKey(callId)`。全仓库检索显示它们没有主链调用；**不要把 `tool:{callId}` 写成真实反查 key**。表名 `sys_chat_tool_repsonse` 的拼写来自 DDL 与 `@TableName`，也不是笔误后的另一个表。

### 2.2 配置值与默认值从哪里来

本章用到的记忆参数都不是 YAML 配置项，而是 Java 常量：

- `MessageMemory.FOCUS_CHAT_TTL = Duration.ofHours(24)`；
- `MessageMemory.NON_FOCUS_CHAT_TTL = Duration.ofMinutes(30)`；
- `RedisMemory.STAGING_TTL = Duration.ofMinutes(1)`；
- `RedisMemory.HISTORY_TTL = Duration.ofHours(24)`；
- `ToolResponseMemory.TTL = Duration.ofHours(24)`；
- `ChatManager.expireLock = Duration.ofMinutes(60)`，用于 `chat:info:{chatId}` 的锁定/恢复，不是历史 key TTL。

`src/main/resources/application.yaml` 的 `spring.data.redis.timeout=3000ms` 是客户端连接/响应超时配置；它不能告诉我们一次超时后 Lua 究竟执行了没有。`RedisKeyConstants` 只是 key 拼装；值、TTL 和脚本语义以类常量与 `util/RedisScripts` 为准。

### 2.3 谁真正调用这些入口

| 目标 | 真实调用方 | 结论 |
|---|---|---|
| `MessageMemory#get` | `ChatServiceImpl#chat`（分类前）、`ChatController#POST /restore`、四个 Agent 的 `invoke` | 普通历史主读链；分类前读一次用于判断新会话/分类上下文，Agent 入口再读一次 |
| `MessageMemory#getPersisted` | `OrderAgent#resume` | 只用于确认恢复，绕过缓存 |
| `MessageMemory#save` | 四个 Agent 的 `saveNewMessages`，`OrderAgent#resume` 补工具应答 | 没有证据表明 `ChatServiceImpl#chat` 自己保存消息 |
| `MessageMemory#getUserMessage` | `CacheConsistencyRegressionTest` 直接调用；主链检索未发现业务调用 | 教学/测试路径，不作为正常对话读取说明 |
| `MessageMemory#clearChat` | 当前 `src/main/java` 未检索到对外控制器调用；测试直接调用 | 代码存在，但不能写成“用户点清空按钮一定走这里” |
| `RedisMemory#append`、`getLastN`、`APPEND_WITH_VERSION_BUMP` | `RedisCacheRegressionTest`/`RepairDatabaseIntegrationTest` 等直接调用；主链未发现 | 辅助/测试路径，真实 save 是 `appendIfVersionMatch` |
| `ToolResponseMemory#save/get` | `ToolRepPointerTool#recordCalling/getToolResponse` | 辅助索引的两端，见第八节 |

---

## 三、正常一轮：从用户消息到三层落库

### 3.1 `get`：三层读取与回填

```java
// agentbase/memory/MessageMemory.java#get（真实节选，省略参数校验）
if (chatManager.isNewOwnedChat(userId, chatId)) return List.of();

long heapLease = heapMemory.currentVersion(chatId);
List<Message> heapAll = heapMemory.getAll(chatId);
if (!heapAll.isEmpty()) return tail(heapAll, lastN);

long redisLease = redisMemory.currentVersion(chatId);
List<Message> redisAll = redisMemory.getAll(chatId);
if (!redisAll.isEmpty()) {
    fillHeapWithLease(chatId, redisAll, heapLease);
    return tail(redisAll, lastN);
}

List<Message> mysqlAll = mysqlMemory.getAll(chatId);
if (!mysqlAll.isEmpty()) {
    if (fillRedisWithLease(chatId, mysqlAll, redisLease)) {
        fillHeapWithLease(chatId, mysqlAll, heapLease);
    }
    return tail(mysqlAll, lastN);
}
return List.of();
```

逐段解释：

1. `isNewOwnedChat(userId, chatId)` 最终走 `ChatInfoService#findOwned(...,allowNew=true)`：查不到 `sys_chat` 就当新会话，直接返回空历史；查到但归属不符抛 `ForbiddenException`，`history_deleted=true` 抛 410。**这一步阻止“新会话读到同 chatId 旧缓存”**。
2. 先取 `heapLease` 再读 Heap。命中就只 `tail`，不会主动修 L2/L3；所以 L1 的内容直接决定本次模型看见什么。
3. L1 miss 后取 `redisLease`，再做耗时读取。注释明确要求租约在耗时读取**之前**捕获：如果读 Redis/MySQL 期间有 save 推进版本，旧快照就不能回填。
4. L2 命中：用 L2 全量回填 L1（带 `heapLease`），但即使回填失败也照常返回 `tail(redisAll,lastN)`。L2 是 List 的 `LRANGE 0 -1`，不是只读最后 N 条。
5. L3 命中：先尝试 `fillRedisWithLease`。**只有 L2 回填返回 true，才尝试回填 L1**；即先确认 L2 回填成功，再尝试填充 L1，以减少不完整的 L1 短路后续读取的风险；L1 自身的版本校验仍可能拒绝写入。然后无论回填是否成功，本次都返回 MySQL 快照的窗口。
6. 三层都空返回 `List.of()`。此时 `ChatServiceImpl#chat` 可能走首轮 `initChat`，但元数据写入与消息保存是两件事（C1 已讲）。

一个关键结论：**历史不是所有路径都从 `get` 读**。确认恢复走 `getPersisted`，只读 MySQL；`POST /restore` 虽然走 `get`，但 `simpleParse` 只把 USER/ASSISTANT 的文本交给前端，工具协议细节被隐藏。

### 3.2 `getPersisted`：为什么恢复必须绕过缓存

```java
// agentbase/memory/MessageMemory.java#getPersisted（真实全文）
public List<Message> getPersisted(String userId, String chatId, int lastN) {
    chatManager.assertOwned(userId, chatId, false);
    return MessageWindow.tail(mysqlMemory.getAll(chatId), lastN);
}
```

`OrderAgent#resume` 用它读历史，再查 `chat:info:{chatId}` 的 `pendingToolCall`，扫描窗口内是否已有相同 id 的 `ToolResponse`，没有才保存用户反馈。这里的设计意图在方法注释里写得很明确：一次“MySQL 成功、缓存失败”的保存之后，不能因为缓存没有应答就再写一次工具应答。

但必须把保证范围说窄：`assertOwned` 的 `allowNew=false` 会拒绝不存在/不属于当前用户/已墓碑的会话；`MessageWindow.tail` 仍可能因为工具配对损坏抛异常；`answered` 只扫描 `getPersisted` 返回的窗口，不是全历史唯一性约束，也不是并发原子去重。C3 已详细讲过这条恢复链，本章只补它的记忆侧依据。

### 3.3 `save`：先持久化、后缓存

```java
// agentbase/memory/MessageMemory.java#save（真实节选，省略参数校验）
chatManager.assertOwned(userId, chatId, false);
switchFocusChatIfNeeded(userId, chatId);

long heapVersion = heapMemory.currentVersion(chatId);
long redisVersion = redisMemory.currentVersion(chatId);
mysqlMemory.append(chatId, newMessages);
try {
    redisMemory.appendIfVersionMatch(chatId, newMessages, FOCUS_CHAT_TTL, redisVersion);
    heapMemory.appendIfVersionMatch(chatId, newMessages, heapVersion);
} catch (RuntimeException e) {
    heapMemory.clear(chatId);
    throw e;
}
chatManager.updateChatTime(chatId);
```

逐步拆开：

1. `assertOwned(...,false)`：会话必须存在且属于当前 userId，墓碑会话抛 410。
2. `switchFocusChatIfNeeded`：以 `userId` 为 key 的进程内 `userCurrentChat` 记录“这个用户当前焦点 chatId”。首次保存或同一 chat 时只给新 chat 的 L2 设 24h；切换时清旧 chat 的 L1、把旧 L2 缩到 30min、把新 L2 设回 24h。这个 Map 是实例内存，不是 Redis 共享状态；多实例下各进程各有自己的“焦点”。
3. 取两个版本：**在 MySQL 写入之前**。这样在途读请求手里的旧版本会在 save 后失效。
4. `MysqlMemory#append` 带 `@Transactional`：`SELECT ... FOR UPDATE` 查 `sys_chat`，墓碑检查，再逐条 `ChatMessageMapper.insert`。`message==null` 或 `MessageParser#parse` 返回 null/空白的行被跳过；若全部被跳过，事务可能没有任何 insert。若这里明确抛异常，后面的 L2/L1 不执行——这是“先持久化”的意义；若只是响应丢失，服务端是否已提交未知。
5. Redis `appendIfVersionMatch`：**只有当 `newMessages` 非空、且 `serialize` 后 JSON 列表非空时才会调用脚本**；空列表早退，不推进版本。脚本正常执行时先 `GET` 再 `INCR+PERSIST`，版本相同且数据 key 存在才 `RPUSH` 并刷新 24h；版本不符则 `DEL` 数据 key；数据 key 不存在则什么都不做。冷缓存不会凭几条新消息创建残缺列表。若 Lua 运行时报错，已执行的写命令不会自动回滚，因此不能由“报错”反推版本一定没变；网络响应丢失时结果同样未知。
6. Heap `appendIfVersionMatch`：只要传入 `newMessages` 非空就进入 `ConcurrentHashMap.compute`（它不检查解析结果），比较版本；版本相同且已有非空列表才追加，版本不同则把列表清空。进入 `compute` 后版本会 +1，但若外层参数为空则早退、版本不变。这解释了“Redis 序列化结果为空而 Heap 仍可能推进版本”的差异；冷保存不创建半截 heap 历史、旧回填不能造成重复追加的测试断言也建立在这个方法上。
7. 任一 L2/L1 条件写抛 `RuntimeException`：清 Heap 后向上抛；`updateChatTime` 不执行。**MySQL 已成功的内容不会因这一步回滚**，这就是“部分成功”。
8. 全部完成才 `ChatManager#updateChatTime` → `ChatInfoService#updateChatTime` 更新 `sys_chat.updated_at`；它不在 `MysqlMemory#append` 的事务里，失败也不会撤销消息。

### 3.4 一次正常保存的状态表

前提：新会话已由首轮分类成功路径写入 `sys_chat`，userId 归属正确，MySQL/Redis 命令均成功且不是并发读窗内。

| 步骤 | 调用 | 可见状态 | 若此步明确失败 |
|---|---|---|---|
| 1 | `assertOwned(false)` | 确认会话存在/归属正确/未清空 | 抛 404/403/410，后续不执行 |
| 2 | `switchFocusChatIfNeeded` | 当前进程的焦点切换；L2 TTL 变化 | Redis `EXPIRE` 的 Boolean 未被检查，源码不能据此断定失败即中断 |
| 3 | 取 heap/redis 版本 | 得到两个旧版本号 | Redis 版本脚本返回 null 会抛 `IllegalStateException`，save 终止 |
| 4 | `mysqlMemory.append` | 事务内锁 chat 行，插入消息 | 回滚则缓存未写；若只是响应丢失，服务端可能已提交 |
| 5 | `redisMemory.appendIfVersionMatch` | 若参数/serialize 非空且脚本正常执行：版本 +1；匹配且有 data 才追加并刷新 24h；早退时不改版本 | 明确抛 `RuntimeException` 会清 Heap 并重抛；脚本已部分执行或响应丢失时结果未知，MySQL 保留 |
| 6 | `heapMemory.appendIfVersionMatch` | 若 `newMessages` 非空则进入 `compute` 并 +1；匹配且有非空列表才追加 | 进入 `compute` 后进程内失败范围仅限本实例；参数为空则早退不 +1 |
| 7 | `updateChatTime` | `sys_chat.updated_at` 更新 | 消息可能已在 MySQL/缓存，更新时间可能未写；异常继续向上传播 |

**“方法返回”只说明这条调用链走完**；Agent 的 `saveNewMessages` 调用之后还有 `tryEmitComplete`，事件是否成功写入 sink、客户端是否收到，是另一层（C1）。

---

## 四、版本租约的完整推导：旧快照为什么会盖掉新消息

### 4.1 假想方案：无条件追加 + 没有版本校验（不是当前 `save`）

这一小节只用于说明“为什么需要版本”。**它不是当前 `MessageMemory#save` 的行为**：当前真实保存调用的是 `RedisMemory#appendIfVersionMatch` / `APPEND_HISTORY_IF_VERSION_MATCH`；无条件 `RedisMemory#append` / `APPEND_WITH_VERSION_BUMP` 在当前主链没有调用方，只在辅助/测试路径出现。

假想读请求在 L2 miss 后用 MySQL `SELECT ... ORDER BY id` 读到全量快照（注意：MySQL 不叫 `LRANGE`），然后直接 `DEL chat:history:{chatId}` 再把旧快照 `RPUSH` 回去。它至少有两条路会丢消息：

- 旧快照里没有 T2 刚追加的消息，整体覆盖后 L2 缺一条；
- 后续读若持续命中这份残缺 L2，就不会在这些命中请求中回 MySQL 补回来；过期、失效或其他变化可能促使后续回源。

即使把 `DEL + RPUSH` 放进一个 Lua，也只解决“Redis 内不穿插别的命令”，**挡不住 MySQL 先提交、后回填的两阶段竞态**。需要的是“我读快照时看到的版本，写回时还算不算数”。

### 4.2 假想时间线：无保护时怎样覆盖掉 M2

前提：这是**教学假想**，初始 L1/L2 都 miss，T1 读、T2 保存，没有版本校验；不是当前 `save` 的实现。

| 时刻 | T1（读） | T2（假想保存） | Redis/MySQL 可见状态 |
|---|---|---|---|
| b0 | — | — | MySQL 只有 `M1`；data 不存在 |
| b1 | 读到 MySQL 快照 `[M1]` | — | T1 本地持有旧快照 |
| b2 | — | `mysql.append(M2)` 提交 | MySQL 已有 `[M1,M2]` |
| b3 | — | 假想无条件写 L2：`RPUSH M2`，版本变 8 | data 只有 `[M2]`（这正是当前条件写要避免的残缺列表） |
| b4 | 假想回填：`DEL data; RPUSH M1` | — | **L2 丢失 M2，只剩 M1** |
| b5 | 后续请求命中 L2 | — | 不再查 MySQL，模型看不到 M2 |

### 4.2b 当前代码：冷缓存下的可达时间线（不产生 M2-only 列表）

前提：会话归属已通过；初始 L1 miss、`chat:history:{chatId}` data 不存在；版本 key 存在且为 7；T1 是读请求，T2 是同 chatId 的正常 `MessageMemory#save`，MySQL 写入成功，`APPEND_HISTORY_IF_VERSION_MATCH` 与 `OVERWRITE_IF_VERSION_MATCH` 均按脚本正常执行。

| 时刻 | T1（读） | T2（当前保存） | Redis/MySQL 可见状态 |
|---|---|---|---|
| a0 | `heapLease` 与 `redisLease=7` 先后捕获 | — | version=7，data 不存在 |
| a1 | 三层 miss，开始 `mysql.getAll`，得到旧快照 `[M1]` | — | T1 本地持有 `[M1]` |
| a2 | — | 取版本 7，`mysql.append(M2)` 提交 | MySQL `[M1,M2]` |
| a3 | — | `appendIfVersionMatch(v=7)` 进入脚本：`GET 7 == 7`，`INCR→8`；**data 不存在，所以不 RPUSH、不创建残缺列表** | version=8，data 仍不存在 |
| a4 | 写 `staging:[M1]` 并设 60s TTL | — | 暂存存在，版本仍 8 |
| a5 | `OVERWRITE_IF_VERSION_MATCH` 看到 `current=8 != 7`，`DEL staging`，**正常返回 0** | — | Java 得到 false；L2 无 data，L1 不回填 |
| a6 | T1 本次仍返回它读到的 `[M1]` | — | 当前 Prompt 不因并发自动变成 `[M1,M2]` |

**热缓存也能进入“版本不符→条件写 DEL”的分支**：data 原本可能是完整 `[M1]`，但在 T1 捕获租约后因 TTL 过期或被其他脚本删除而 miss；或者 T2 取到版本 7 后、执行脚本前又有别的写入把版本推到 9，那么 `APPEND_HISTORY_IF_VERSION_MATCH` 看到 `current != 7` 时会 `DEL data`，宁可让下一次读回 MySQL，也不会把 `M2` 追加到一份可能已过期的缓存上。这里的 `DEL` 是版本不符后的主动失效，不是“写 M2 失败”。

### 4.3 第一段：`CURRENT_HISTORY_VERSION` 做了什么

```lua
-- util/RedisScripts.java#CURRENT_HISTORY_VERSION（真实节选）
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('DEL', KEYS[2])
    redis.call('SET', KEYS[1], '0')
elseif redis.call('PTTL', KEYS[1]) >= 0 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[2])
end
redis.call('PERSIST', KEYS[1])
return tonumber(redis.call('GET', KEYS[1]))
```

- `KEYS[1]` 是版本 key，`KEYS[2]` 是数据 key。
- **版本 key 不存在**：删数据 key、把版本设为 `'0'`。这是旧数据/新版本的迁移清理，避免“没有版本却有半截缓存”继续被读。
- **版本 key 自己带 TTL**（`PTTL >= 0`）：递增版本并删数据 key。这说明系统曾经写入过带过期的版本 key，读时把它修正成不过期；数据 key 带 24h TTL 并不会走到这里。
- 最后统一 `PERSIST`。因此正常读/写之后版本 key 无 TTL；如果 Redis 因 maxmemory 策略淘汰它，下一次 `currentVersion` 会重新建 0 并删数据，源码**没有**能抵御外部删除后版本撞号的 fencing token。
- Java 侧 `currentVersion` 对 `null` 抛 `IllegalStateException("无法读取聊天历史版本号")`；它不是“返回 0 继续”。

### 4.4 第二段：保存时的 `APPEND_HISTORY_IF_VERSION_MATCH`

```lua
-- util/RedisScripts.java#APPEND_HISTORY_IF_VERSION_MATCH（真实全文）
local current = redis.call('GET', KEYS[1]) or '0'
local version = redis.call('INCR', KEYS[1])
redis.call('PERSIST', KEYS[1])
if current ~= ARGV[1] then
    redis.call('DEL', KEYS[2])
elseif redis.call('EXISTS', KEYS[2]) == 1 then
    for i = 3, #ARGV do
        redis.call('RPUSH', KEYS[2], ARGV[i])
    end
    redis.call('PEXPIRE', KEYS[2], ARGV[2])
end
return version
```

逐项看返回与分支：

- **先决条件**：`RedisMemory#appendIfVersionMatch` 在 `newMessages` 为空或 `serialize` 结果为空时会早退，脚本根本不执行，版本也不推进。`MessageMemory#save` 在 `newMessages` 为空时更早返回。下面的分支只描述“脚本确实被调用并正常执行”的情况。
- 脚本正常进入后先 `GET current`，再 `INCR + PERSIST`；**不保证“每个 save 必然推进版本”**：若 Lua 在 `INCR` 之前报错，或 Java 在调用前早退，就没有这次推进；若 `INCR` 后命令报错，已执行的 `INCR` 不会自动回滚，响应丢失时 Java 也不知道结果。
- `current == ARGV[1]` 且 data 存在：把新消息 JSON `RPUSH` 到完整历史尾部并 `PEXPIRE 24h`。这是正常热缓存追加。
- `current != ARGV[1]`：`DEL` 数据 key。含义是“写库期间版本变了，当前 L2 不再可信”，宁可让下次读回 MySQL，也不保留可能重复/错乱的列表。
- `current == ARGV[1]` 但 data 不存在：什么都不做。冷缓存不会变成只含新消息的残缺历史。
- 返回值是新版本号；Java 的 `appendIfVersionMatch` **不检查返回值**，只负责执行脚本。明确在调用前早退可说版本未变；命令报错或响应丢失时不能把“没看到返回值”翻译成“服务端没写”。

### 4.5 第三段：回填时的暂存区与 `OVERWRITE_IF_VERSION_MATCH`

```java
// agentbase/memory/RedisMemory.java#overwriteIfLeaseValid（真实节选）
String stagingKey = RedisKeyConstants.chatHistoryStagingKey(chatId, UUID.randomUUID().toString());
try {
    redisTemplate.execute(new SessionCallback<List<Object>>() { /* multi: RPUSH staging + EXPIRE 60s */ });
    Long result = redisTemplate.execute(RedisScripts.OVERWRITE_IF_VERSION_MATCH,
            List.of(stagingKey, dataKey, versionKey), String.valueOf(leaseVersion), String.valueOf(HISTORY_TTL.toMillis()));
    if (result != null && result == 1L) return true;
    return false;
} catch (Exception e) {
    // 尽力 delete(stagingKey)，吞掉清理异常
    return false;
}
```

```lua
-- util/RedisScripts.java#OVERWRITE_IF_VERSION_MATCH（真实节选）
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
local current = redis.call('GET', KEYS[3]) or '0'
if current ~= ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 0
end
redis.call('INCR', KEYS[3])
redis.call('PERSIST', KEYS[3])
redis.call('RENAME', KEYS[1], KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[2])
return 1
```

关键点：

1. **为什么先写暂存**：完整历史可能很大，不适合塞进 Lua 的 ARGV；MULTI 先把 List 写到带 60s TTL 的 `chat:history:staging:{chatId}:{UUID}`，Lua 只传三个 key 和两个参数。`RPUSH`/`EXPIRE` 本身就是写命令；它们成功、Lua 尚未执行时，data key 并未被触碰。
2. **脚本正常返回三态**：暂存不存在 `-1`；版本不符 `0` 并 `DEL staging`，**不会执行后面的 `INCR`/`RENAME`**；成功路径上，脚本依次执行 `INCR + PERSIST + RENAME + PEXPIRE` 后返回 `1`。Java 只有 `1L` 才返回 true；`-1`、`0`、`null` 和非 1 值都返回 false。
3. **TTL 的真实来源**：`RENAME` 会把 staging key 的 TTL 一并带到正式 data key；脚本随后 `PEXPIRE data 24h` **成功执行**才把它覆盖为 24h。若脚本在 `RENAME` 之后、`PEXPIRE` 之前出错，或 Java 只看到异常/响应丢失，就不能无条件断言 data key 已是 24h；正常返回 1 才可推断这些命令都执行了。
4. **Java false 不等于脚本返回 0**：前置早退（空参数/序列化为空）、暂存写入或脚本执行抛异常、脚本正常返回 -1/0、以及脚本已 `RENAME` 后响应丢失，都会让 Java 返回 false。正常返回 0 时暂存已被删除；异常/false 时暂存是否已删、正式 data 是否已换、TTL 是否变成 24h，都要按具体执行点判断。
   注意 `serialize(messages)` 在该方法的 `try` 之外：若序列化本身抛异常，它会向调用方传播，不属于这里 catch 后返回 false 的情况。
5. 暂存 key **不在** `RedisCacheRegressionTest` 的 `keys` 清理清单里；它的清理依赖正常脚本消费（0 或 1 分支）、异常 catch 尽力删除，或 60s TTL 真正设置成功后的过期。

### 4.6 带保护的正确时间线

| 时刻 | T1（读旧快照） | T2（保存新消息） | Redis |
|---|---|---|---|
| a0 | `heapLease`/`redisLease` 先被读取，假设 Redis 版本=7 | — | version=7 |
| a1 | 三层 miss，开始 `mysqlMemory.getAll`，持有 `[M1]` | — | — |
| a2 | — | 取版本 7 → `mysql.append(M2)` 提交 | MySQL `[M1,M2]` |
| a3 | — | `APPEND_HISTORY_IF_VERSION_MATCH(v=7)`：GET 7 == 7，但 data 不存在，不创建残缺历史；INCR→8 | version=8，data 仍不存在 |
| a4 | `overwriteIfLeaseValid([M1], lease=7)`：暂存写入成功 | — | staging 存在 |
| a5 | Lua **正常返回 0**：看到 current=8 ≠ 7，`DEL staging`，不执行 `RENAME` | — | Java 得到 false，L2 保持空；L1 也不回填 |
| a6 | 本次仍返回 `[M1]` 给模型 | — | 当前 Prompt 不变 |

**下一次回源能成功的前提**：a5 之后没有新的并发 save、回填或脚本错误；`sys_messages` 对当前读可见 `[M1,M2]`；版本 key 未被外部删除或重置；`CURRENT_HISTORY_VERSION` 读到的版本仍是 8；暂存写入、Lua `INCR/PERSIST/RENAME/PEXPIRE` 均正常执行并返回 1；Heap `overwriteIfVersionMatch` 也成功。满足这些条件时，下一次读才会从 MySQL 得到全量并回填 L2/L1。

**必须把两种“Java 看到失败”拆开**：

- **脚本正常返回 0**：Lua 已执行 `DEL staging`，未执行 `RENAME`，正式 data 确实没有被这次回填改写。
- **Lua 已 `RENAME` 后响应丢失或后续命令报错**：Java 同样可能只看到异常/false，但正式 data 可能已经换成 staging 内容；`PEXPIRE` 是否成功决定它是否达到 24h。此时不能沿用“返回 0 ⇒ data 未变”的结论。

注意一个容易被忽略的事实：**T1 本次返回给模型的是它读到的 MySQL 快照 `[M1]`**，租约只保护缓存不被污染，不把已经发生的 T2 消息塞回本次 Prompt。防止的是“后续所有读都被残缺 L2 短路”，不是让当前 Prompt 成为并发强一致读。

### 4.7 Heap 自己的版本保护

Heap 版本在 `HeapMemory` 实例内，用 `ConcurrentHashMap.compute(chatId, ...)` 完成比较与替换：

- `currentVersion` 没有条目返回 0；`clear` 会保留递增后的版本，让清空前捕获的租约不能因版本归零重新生效。
- `overwriteIfVersionMatch` 只在 `current == expectedVersion` 时把列表整体替换为新快照并 +1，否则保留旧条目。返回 true/false，但 `MessageMemory#fillHeapWithLease` 不检查返回值。
- `appendIfVersionMatch` 只要 `newMessages` 非空就进入 `compute`，进入后无论匹配与否都把版本 +1；匹配且已有非空列表才追加，否则写入空列表。这防止“L1 本来 miss，保存时却凭空造一条只含新消息的历史”。若参数为空则方法早退，版本不变。

这些操作只在**同一 JVM 实例**内原子。跨实例没有共享 Heap；Redis 版本脚本的原子性也只在 Redis 内成立。它们合起来不构成跨 Redis/MySQL 的全局事务。

### 4.8 四种保护对象不要混

| 机制 | key/载体 | 回答的问题 | 生命周期/失败边界 |
|---|---|---|---|
| 回填版本号 | `chat:history:version:{chatId}` + Heap Entry.version | 这份旧快照还能不能写回？ | 正常 `PERSIST`；版本不符放弃回填；外部删除/淘汰无 fencing |
| `RedisLock` UUID 令牌 | 锁 key 的值 | 释放锁时，是不是当前持有者？ | 固定 TTL、不续期；异常只记日志（S4） |
| 单轮执行锁 | `chat:execution:{chatId}`，Redisson owner | 同一会话这一轮后台任务能不能开始？ | 看门狗续期；`doFinally` 发起释放不等于成功（C1） |
| 会话 `locked` | `sys_chat.locked` + `chat:info:{chatId}.locked` | 这个会话是否暂停/停用？ | `/lock` 置 true + 60min；`POST /restore` 清 false 并 `PERSIST`（C1/C3） |

另外，订单锁 `order:lock:{orderId}` 与回填版本完全无关。不要把“Lua 内原子”写成“Redis + MySQL 全局事务”，也不要把“版本变了”写成“有人拿到了互斥锁”。

---

## 五、消息窗口与坏记录：模型实际看到什么

### 5.1 `MessageParser`：序列化与解析

`MessageParser#parse(Message)` 按 `message.getMessageType()` 写 JSON：

- `USER`：写 `type/content/metadata`；
- `ASSISTANT`：写 `type/tool_calls/content/metadata`，`tool_calls` 为 null 时写空数组；
- `TOOL`：复制每个 `ToolResponse(id,name,responseData)`，写 `type/responses/metadata`；
- `SYSTEM`：写 `type/content`；
- 未知类型：记 error 日志并返回 null；`RedisMemory#serialize`/`MysqlMemory#append` 会跳过 null/空白字符串。

`unparse(String)` 用 fastjson2 解析，`MessageType.valueOf(type)` 后重建对应 `Message`。`parseMetadata` 把 JSON 对象转 `HashMap`，没有则空 Map。`simpleParse(List<Message>)` 只生成 `SimpleMessage(role,content)`，只收 USER/ASSISTANT；`POST /restore` 用它给前端“纯文本视图”。

### 5.2 坏记录恢复的真实规则

```java
// agentbase/memory/MessageParser.java#unparseHistory（真实节选）
try { message = unparse(row); }
catch (RuntimeException e) {
    log.warn("会话历史存在损坏记录，从后续完整轮次恢复，记录偏移={}", i);
    result.clear(); recovering = true; continue;
}
if (message == null) { result.clear(); recovering = true; continue; }
if (recovering && !(message instanceof UserMessage)) continue;
recovering = false;
result.add(message);
```

读法：

1. 空行/空白直接跳过，不触发恢复。
2. 坏 JSON、未知枚举等抛 `RuntimeException`：清空已解析结果，进入恢复态；原始行不删除。
3. `unparse` 返回 null 是防御性分支（现有四种 `MessageType` 都已被覆盖，`default` 不可达）；真正遇到未知类型字符串时，`MessageType.valueOf` 先抛 `IllegalArgumentException`，走的是第 2 条的异常路径。两条路径在 `unparseHistory` 里结果相同：清空并进入恢复态。
4. 恢复态下一直跳过消息，**直到遇到 `UserMessage`**；这条用户消息及其后的消息才重新加入。**如果坏记录之后没有新的 `UserMessage`，`result` 可能一直为空并返回空列表**，不能说“一定能从下一轮恢复”。
5. 即使记录本身可解析，只要工具 id 配对错误，`MessageWindow#tail` 仍可能抛 `IllegalStateException`；解析器恢复不等于窗口一定接受。
6. 测试 `SecurityRepairRegressionTest#corruptHistoryResumesAtNextUserTurnAndDropsDanglingTools` 构造“User(old) → `{bad json` → 孤立 ToolResponse → User(new) → Assistant(answer)”，断言结果是 `new, answer`，且随后 `MessageWindow.tail(...,100)` 不抛。这是测试源码阅读，未实际运行。

所以“历史损坏”有两种结果：**解析器层清空并从下一轮恢复**；**配对层抛异常**。不能把两者混成同一种报错，也不能说解析器会删除数据库中的坏行。

### 5.3 `MessageWindow#tail`：按批次边界扩展

方法**先扫描整个传入列表的工具配对，再计算截断点**。因此即使某条非法配对位于将来会被窗口截掉的前半段，也会先抛异常；它不是“只检查返回窗口内那几条”。

```java
// agentbase/memory/MessageWindow.java#tail（真实节选）
Set<String> pending = new HashSet<>();
List<Integer> boundaries = new ArrayList<>();
for (int i = 0; i < messages.size(); i++) {
    Message message = messages.get(i);
    if (pending.isEmpty() && !(message instanceof ToolResponseMessage)) boundaries.add(i);
    if (message instanceof AssistantMessage assistant) {
        if (!pending.isEmpty()) throw invalid();
        for (var call : assistant.getToolCalls()) if (!pending.add(call.id())) throw invalid();
    } else if (message instanceof ToolResponseMessage tools) {
        for (var response : tools.getResponses()) if (!pending.remove(response.id())) throw invalid();
    } else if (!pending.isEmpty()) {
        throw invalid();
    }
}
int wanted = Math.max(0, messages.size() - limit);
int start = 0;
for (int boundary : boundaries) {
    if (boundary > wanted) break;
    start = boundary;
}
return List.copyOf(messages.subList(start, messages.size()));
```

把虚构的 6 条历史编号：

| 下标 | 消息 | 是不是边界 |
|---|---|---|
| 0 | User：“附近有什么吃的” | 是（pending 空） |
| 1 | Assistant：`tool_calls=[call-1, call-2]` | 是（进入前 pending 空），随后 pending={1,2} |
| 2 | ToolResponse：`[call-1, call-2]` | 否（pending 非空），随后 pending 空 |
| 3 | Assistant：“有这些地方……” | 是 |
| 4 | Assistant：`tool_calls=[call-3]` | 是，随后 pending={3} |
| 5 | User：“确认” | **否**；pending 非空且不是 ToolResponse，会抛异常 |

合法版本把第 5 条换成 ToolResponse `[call-3]`，此时 Assistant 调用与回执构成**闭合批次**；或让历史停在第 4 条，留下**未闭合尾批次**。两者都不是“确认应答未闭合”的同义词。此时：

- `limit=1`，`wanted=5`。边界是 0、1、3、4；最后一个 `<=5` 的边界是 4，返回下标 4 起的整批（Assistant 调用 + ToolResponse 回执），所以**返回条数可能大于 limit**。
- `limit=3`，`wanted=3`；边界 3 恰好可选，返回从“有这些地方”开始。
- 若最后一批只有 Assistant 工具调用、尚无回执（暂停等待确认），`pending` 非空但循环结束，不抛异常；这是**合法未闭合尾批次**。`OrderAgent#handleNotifyUser` 暂停时可能留下这种状态，C3 已讲；`/r` 补上 ToolResponse 后该批次才闭合。
- 重复 `call.id()`：`pending.add` 返回 false，抛异常；未知 `ToolResponse.id()`：`pending.remove` 返回 false，抛异常；同一 `ToolResponseMessage` 里多个 response 也逐个校验。
- `limit<=0` 或 `messages==null` 返回空列表；`wanted` 是计算**目标起点**用的数值，边界向前扩展后实际返回可能多于 `limit`。

### 5.4 `limit=100` 不等于全历史

四个 Agent 都用 `HISTORY_SIZE=100`，`ChatController#POST /restore` 也用 100。但：

- `MessageMemory#get` 先看 L1/L2/L3 的**全量**，再 `tail`；返回的是从某个批次边界到末尾的子列表；
- 被截掉的前半段仍在 MySQL/缓存里，只是不进入本次 Prompt；
- Agent 发现第一条不是 `SystemMessage` 时会 `addFirst(SystemMessage)` 并发 `notify("信息轮次过长……")`，这不是“记忆自动总结”，只是插入新的当前提示词；
- `originalHistorySize` 以**窗口大小**为基准，保存时只存本轮新增消息，不会把被截掉的历史重写回库。

这就是“获取的窗口可能不同于全历史”的准确含义。

---

## 六、清空、删除与焦点切换：谁先写，谁可能留残

### 6.1 `clearChat` 的真实顺序

```java
// agentbase/memory/MessageMemory.java#clearChat（真实节选）
chatManager.retireHistory(chatId);
mysqlMemory.clear(chatId);
try {
    redisMemory.clear(chatId);
} finally {
    heapMemory.clear(chatId);
}
```

- `retireHistory` → `ChatInfoService#retireHistory`：一条 `LambdaUpdateWrapper`，把 `sys_chat` 的 `history_deleted=true`、`locked=true`；**不检查影响行数**，chatId 不存在时也会正常返回。它是逻辑墓碑，该 chatId 不再复用。
- `mysqlMemory.clear`：按 `chat_id` 删除 `sys_messages`。它没有本地 `@Transactional` 注解，调用方也没有统一事务包裹；MySQL 删除成功与墓碑更新不构成一个跨方法原子事务。
- `try/finally` **从这里才开始**：只有前两步已经返回，进入 `try` 后，`finally` 才保证 `heapMemory.clear` 执行。若 `retireHistory` 或 `mysqlMemory.clear` 自身抛异常/响应丢失，代码根本没进入这个 `try`，不能说 Heap 一定被清理。
- `redisMemory.clear`：`CLEAR_HISTORY_WITH_VERSION_BUMP` 在同一个 Lua 里 `INCR+PERSIST` 版本、`DEL` 数据。脚本**正常执行**时，版本递增让清空前在途回填失效；命令报错或响应丢失时，是否已递增/删除不能由 Java 返回值断言。
- `heapMemory.clear` 放在 `finally`：只要进入 `try`，即使 Redis clear 抛异常也会执行，并把 Heap 条目换成空列表、版本递增；同 chatId 的空条目保留，防止旧租约因版本归零复活。进程在 Redis 调用中退出则 `finally` 也可能来不及运行。
- 当前 `src/main/java` 没有检索到 `clearChat` 的对外控制器入口；测试直接调用。因此不要写“用户某按钮一定调用它”。

### 6.2 墓碑对新请求和在途请求的作用不同

`ChatInfoService#findOwned(userId,chatId,allowNew)` 的行为分三种：

1. **元数据不存在**且 `allowNew=true` → 返回 null，`isNewOwnedChat` 为 true，`MessageMemory#get` 返回空历史；`allowNew=false` 时抛 404。
2. **元数据存在且归属不符** → 抛 403；这条检查在墓碑检查之前。
3. **同一 chatId 的元数据存在且 `history_deleted=true`** → 抛 410。它不是“新会话返回空”，无论 `allowNew` 是 true 还是 false 都先看归属再看墓碑。

`MysqlMemory#append` 自己又查一次 `sys_chat ... FOR UPDATE`，墓碑时抛 410，阻止后续消息写回。**但已经通过入口检查、已经读到旧快照的在途 `get` 不会在返回前重新查墓碑**，它仍可能把 t0 读到的内容返回给本轮 Prompt；版本租约只决定这份旧快照能否回填缓存，前提是相关 Redis/Heap 命令正常执行、版本未被外部删除或复用。墓碑保护的是“之后新进入且做归属检查的请求”，不是对已开始读取的任意请求做强一致撤销。

`RepairDatabaseIntegrationTest#retiredHistoryCannotBeRecreatedByLaterMessageSave` 是门控 MySQL 测试：临时 schema 中先插会话、写一条消息、`retireHistory`、清空消息，再尝试 `MysqlMemory#append`，断言 410 且 `sys_messages` 仍为 0。注意它 mock 了 `StringRedisTemplate`，只测 MySQL 侧；未运行。

### 6.3 焦点切换不是删除

`switchFocusChatIfNeeded` 在用户第一次保存到某 chat 或仍保存同一 chat 时，只 `redis.expire(newChatId,24h)`；换 chat 时：

1. `userCurrentChat.put(userId,newChatId)` 返回旧 chatId；
2. 当前进程 `heapMemory.clear(prevChatId)`；
3. `redisMemory.expire(prevChatId,30min)`；
4. `redisMemory.expire(newChatId,24h)`。

它**不清 Redis 历史、不清 MySQL、不改 `sys_chat.locked`**；只是让旧会话的 L2 更快过期，清掉本进程 L1。`userCurrentChat` 是进程内 Map，多实例下不能实现全局“单一焦点”。`expire` 的返回值没有检查，命令失败或响应丢失时 TTL 是否改变不能从方法返回判断。

### 6.4 恢复与 `locked` 不是同一件事

`ChatController#POST /restore` → `ChatManager#restoreChat` → `ChatInfoService#restoreChat`：先取该用户最近一个 `history_deleted=false` 且 `classification=ORDER`、无 `OrderId` 的 chatId；再 DB `locked=false`、Redis Hash `locked=false`、`PERSIST` 整个 `chat:info:{chatId}`；然后 Controller 调 `messageMemory.get(...)` 拿历史并用 `simpleParse` 显文本。它不改消息列表，也不调用 `clearChat`。`GET /restore` 只续 60 分钟；`POST /chat/v2/r/{id}` 是确认恢复，不是这个“恢复候选会话”。C1/C3 已给完整链。

---

## 七、工具结果反查：两份数据，两种用途

### 7.1 从工具执行到两个写入

以 `OrderAgent#handleToolCalls` 为例（`DailyAgent`/`SupportAgent` 同样有这段）：

```java
// agents/OrderAgent.java#handleToolCalls（真实节选）
for (AssistantMessage.ToolCall toolCall : toolCalls) {
    String result = executeTool(toolName, args, toolContext, sink);
    toolRepPointerTool.recordCalling(toolCall.id(), result, toolContext);
    toolResponses.add(new ToolResponseMessage.ToolResponse(
            toolCall.id(), toolName, result));
}
ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
        .responses(toolResponses).build();
history.add(toolResponseMessage);
runLoopStep(history, originalHistorySize, sink, chatId, userId);
```

真实顺序要分成四步，不能写成“已执行工具必然双写成功”：

1. `executeTool(...)` 已执行并返回 `result`（工具自身的异常由 `executeTool` 的 try 决定是否转成文本回执）。
2. 随后**尝试** `recordCalling(callId,result,toolContext)`：`callId` 空白、`response==null` 或 ToolContext 无 chatId 时直接跳过；`ToolResponseMemory#save` 抛 `RuntimeException` 时被 catch 且只记日志。工具已经执行，不能因为索引失败就重放真实工具。
3. 构造 `ToolResponseMessage.ToolResponse` 并加入**内存 `history`**；模型下一轮直接看到它，配对由 id 决定。这一步发生在构造回执之后，不是“辅助索引写入失败后自动完成”的既有持久状态。
4. 后续在**特定终态或暂停分支**才尝试调用 `saveNewMessages` → `MessageMemory#save` 保存这份回执。序列化及数据库写入成功时才可推断相应记录已落库；保存方法抛异常也可能是 MySQL 已提交、后续缓存或更新时间失败。模型流错误、`guard` 捕获、阈值终止或保存失败都可能使聊天历史没有这批消息。

因此“辅助索引失败后完整结果仍在聊天历史”是把内存状态误写成持久状态。准确说法是：辅助索引写入失败被吞掉后，**如果**回执构造成功且后续保存成功，完整结果才随会话落库；如果 `MessageMemory#save` 失败，辅助索引也可能已经写了。`recordCalling` 的注释说明“辅助索引失败不能重放已经完成的真实工具”，不代表会话保存必然成功。

`notifyUser` 是特例：同批前面的工具已执行并记录；后面的工具被写成“尚未执行”的回执，**不会** `recordCalling`；`notifyUser` 自身的应答等 `/r` 才补。测试 `notifyBatchPreservesEveryNonConfirmationResponse` 验证了前/后回执和 `recordCalling("after")` 未调用（测试源码阅读）。

### 7.2 `ToolResponseMemory` 的两级读写

```java
// agentbase/memory/ToolResponseMemory.java（真实节选）
public void save(String callId, String chatId, String response) {
    if (callId == null || callId.isBlank() || chatId == null || chatId.isBlank() || response == null) return;
    mapper.insert(ChatToolResponse.builder().callId(callId).chatId(chatId).response(response).build());
    cache(chatId, callId, response);
}
public String get(String callId, String chatId) {
    if (callId == null || callId.isBlank() || chatId == null || chatId.isBlank()) return null;
    try {
        String cached = redis.opsForValue().get(key(chatId, callId));
        if (cached != null) return cached;
    } catch (RuntimeException e) { log.warn("工具结果缓存读取失败", e); }
    ChatToolResponse row = mapper.selectOne(new LambdaQueryWrapper<ChatToolResponse>()
            .eq(ChatToolResponse::getCallId, callId).eq(ChatToolResponse::getChatId, chatId)
            .orderByDesc(ChatToolResponse::getId).last("LIMIT 1"));
    if (row == null) return null;
    cache(chatId, callId, row.getResponse());
    return row.getResponse();
}
private String key(String chatId, String callId) { return "chat:tool:v2:" + chatId + ":" + callId; }
```

- **写顺序**：MySQL `insert` 先，Redis `SET` 后。Redis 写失败被 `cache` 吞掉、只记日志，不回滚 MySQL；MySQL `insert` **明确抛异常**时不会走到 `cache`，测试 `failedToolPersistenceDoesNotPublishCache` 断言了这一点。若只是 insert 响应丢失，源码无法判定数据库是否已经写入。
- **读顺序**：Redis 命中直接返回；Redis miss 或读取抛 `RuntimeException` 才尝试 MySQL；MySQL 用 `(call_id, chat_id)` 双条件、`id DESC LIMIT 1`，读到后 best-effort 回写 Redis 24h（回写异常被吞）。
- **TTL**：只有 Redis 有 24h；MySQL `sys_chat_tool_repsonse` 没有 TTL、也没有业务删除方法。Redis 过期后可以从 MySQL 重建；MySQL 缺失时 Redis 仍可能短期返回旧值。
- **重复 callId**：`save` 每次 insert 新行；`get` 取最大 id 一行，所以“最新一次”由自增 id 决定，没有唯一键约束。
- **空值**：`callId/chatId` 空白或 `response==null` 跳过写；`get` 参数无效或 MySQL 无行时返回 **Java `null`**。框架如何把 Java `null` 呈现给模型未在仓库验证，不直接称为“字符串 `null`”；源码也没有专门的“未找到”提示。

### 7.3 `getCalling`：身份从哪里来，模型能做什么

```java
// agentbase/tool/ToolRepPointerTool.java#getToolResponse（真实节选）
@Tool(name = "getCalling", description = "根据工具调用结果历史中的调用ID，查询调用过的工具的返回结果")
public String getToolResponse(@ToolParam(description = "调用ID") String callId, ToolContext toolContext){
    ToolNotifySupport.notifyToolListener(toolContext, "根据call_id查询调用过的工具结果 (getToolResponse)");
    String chatId = toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString();
    String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
    chatManager.assertOwned(userId, chatId, false);
    return toolResponseMemory.get(callId, chatId);
}
```

- 工具 schema 名是 `getCalling`（`SecurityRepairRegressionTest#actualToolSchemaUsesDispatchName` 只读断言），Agent 的 `executeTool` 还按 `"getCalling"` 字符串分派。
- **`callId` 来自模型工具参数**，是不可信输入；**`chatId`/`userId` 来自服务端构造的 `ToolContext`**：Agent 在 `Prompt` 的 `toolContext(Map.of(...USER_ID,userId,CHAT_ID,chatId...))` 中写入，模型不能通过函数参数改掉这两个值。
- `assertOwned(userId,chatId,false)` 在查询前检查会话存在、归属和未清空；查询本身再用 `chatId + callId` 双条件。跨会话的辅助索引隔离是**工具层归属检查 + key/查询条件**共同作用的结果，不能说“只靠 Redis key 前缀就一定隔离”，也不能忽略 `ToolContext` 缺失时这里会 `NullPointerException`（`.toString()` 前没有 null 判断）。
- `recordCalling` 只从 `ToolContext` 取 chatId，不取 userId，也不做归属检查；它由服务端在工具执行后调用，不是注册给模型的工具。
- 模型什么时候调用 `getCalling` 由提示词和业务上下文驱动。`ChatProperties` 里明确写“历史工具结果可能仅以 `call_id: <call_id>` 出现”“需要真实结果时必须调用 `getCalling(call_id)`”。但当前源码的 `MessageParser#parse(TOOL)` 写的是 `responses` 数组，**没有**把完整结果替换成 `call_id:` 指针的实现；`recordCalling` 也没有改写 `ToolResponseMessage`。所以这是提示词描述的压缩约定，不是已实现的上下文改写机制。教材不能承诺模型必然调用，更不能说“所有工具结果都已从历史删除”。

### 7.4 模型输入与 `ToolContext` 的边界

| 来源 | 内容 | 谁可信 | 进入哪里 |
|---|---|---|---|
| 历史消息 | USER/ASSISTANT/TOOL/SYSTEM 的 JSON 解析结果 | 服务端恢复的历史，可能被旧窗口截断 | `new Prompt(history, options)` |
| 工具方法参数 | 模型给出的 `callId`、坐标、订单槽位等 | 不可信业务输入 | `executeTool` 分派、`@Tool` 方法参数 |
| `ToolContext` | `toolListener`、`userId`、`chatId` | 服务端在构造 Prompt 时写入 | 工具内取身份、通知监听器 |
| `ToolResponseMemory` | 完整工具响应字符串 | 服务端执行结果 | `getCalling` 返回给模型，不是鉴权凭据 |
| `sys_chat`/`chat:info` | 会话归属、确认状态、locked | 服务端真值 | `assertOwned`、业务守卫 |

一句话：**工具结果告诉模型“上次工具返回了什么”，不能替代服务端授权。** 订单创建仍要 `OrderConfirmation#consume`，角色/归属仍由 `PermissionAspect`、`ChatInfoService` 与各 Service 自己检查（C2/C3/A2）。

---

## 八、可达时间线：正常、并发、部分成功、损坏与清空

### 8.1 正常读取：L2 命中、L1 回填

前提：会话归属正确，L1 miss，L2 有完整 `[M1,M2]`，无并发 save。

| 起点 | 调用 | 状态变化 | 结果/未知 |
|---|---|---|---|
| 新一轮分类前 | `get(userId,chatId,100)` | 取 `heapLease=0`；L1 miss；取 `redisLease=7`；`LRANGE` 得到全量 | `fillHeapWithLease` 比较 0 成功，L1 写入全量、版本 1；返回 `tail` 窗口 |
| Agent `invoke` | 再次 `get(...,100)` | L1 命中 | 直接返回窗口，不再访问 Redis/MySQL |
| 本轮完成 | `saveNewMessages` → `save` | MySQL insert；Redis/Heap 条件追加；版本各 +1；更新时间 | 返回后 Agent 再尝试 `tryEmitComplete`；客户端是否收到未知 |

### 8.2 并发回填：版本不符

前提：这是**当前代码**的可达路径；初始 L1 miss、L2 data 不存在、版本=7；T1 读到旧快照 `[M1]`；T2 正常 `save(M2)`，且写库与 Redis 命令均成功。

| 时刻 | T1 | T2 | 可断言结果 |
|---|---|---|---|
| 1 | 捕获 `redisLease=7`，开始查 MySQL | — | T1 持有旧版本 |
| 2 | 拿到 `[M1]` | MySQL 提交 `M2` | T1 本次 Prompt 仍是 `[M1]` |
| 3 | — | `APPEND_HISTORY_IF_VERSION_MATCH(7)`：版本 7→8，data 不存在所以不 RPUSH、不创建 M2-only 列表 | 暂存尚未写；正式 data 仍空 |
| 4 | 写 staging，`EXPIRE 60s` | — | 暂存存在，版本仍 8 |
| 5 | Lua **正常返回 0**，`DEL staging`，不执行 RENAME | — | Java 返回 false；L2 保持空，L1 不回填 |
| 6 | 下一次读（前提见下） | — | 才可能从 MySQL 读到 `[M1,M2]` 并成功回填 |

下一次回源成功不是无条件结论：它要求 T2 之后没有新的并发写、版本 key 未被外部删除/重置、MySQL 对当前读可见 `[M1,M2]`、Redis 暂存与 Lua 均正常执行到返回 1、Heap 比较也成功。若暂存 `EXPIRE` 未成功且进程随后退出，暂存可能残留直到人工清理。

**另一条独立场景**：如果 Lua 已 `RENAME` 到 data key 后网络响应丢失，Java 同样只看到异常/false，但正式 data 可能已经换成 staging 内容；这与“正常返回 0 ⇒ 只删暂存、未 RENAME”不是同一次执行，不能混写。

### 8.3 保存部分成功：MySQL 已写、缓存失败

前提：T2 是唯一写入，MySQL append 确认成功，Redis `appendIfVersionMatch` 明确抛运行时异常。

| 阶段 | 状态 | 后续读取 |
|---|---|---|
| MySQL append | `sys_messages` 可能已有新消息；确认提交前不能把“失败”当“未写” | 若此时另一读请求查 MySQL，可能看到新消息 |
| Redis 脚本 | 可确认在调用前早退（空参数/序列化空）时可说版本未推进；命令报错或响应丢失时，脚本是否已执行、执行到哪条写命令未知 | 不能无条件写“L2 一定旧”或“data/version 必然未变” |
| catch | `heapMemory.clear`，异常抛出；`updateChatTime` 未执行 | L1 空且版本递增；下次读 L1 miss，可能从 L2 旧快照或 MySQL 新快照重建 |
| 客户端 | Agent 的保存调用抛错可能进入错误回调；`tryEmitComplete` 是否执行取决于 Agent 分支 | 不能把“保存异常”直接说成“客户端看见 error”或“数据库没有新消息” |

`CacheConsistencyRegressionTest#failedPersistenceDoesNotPublishNewMessages` 只覆盖相反方向：mock MySQL 明确抛异常，断言 L1 不发布新消息、Redis 不被调用。它没有覆盖“MySQL 成功后 Redis 失败”的真实中间件行为。

### 8.4 历史损坏与工具配对

前提：MySQL 返回顺序为 User(old)、坏 JSON、孤立 ToolResponse、User(new)、Assistant(answer)。

1. `MysqlMemory#getAll` 按 id 升序取字符串，交给 `MessageParser#unparseHistory`。
2. 坏 JSON 触发 `result.clear()`；孤立 ToolResponse 在恢复态被跳过；从 User(new) 重新建立上下文，得到 `[User(new), Assistant(answer)]`。
3. `MessageWindow.tail(...,100)` 检查配对，正常返回；坏行仍留在 `sys_messages`。
4. 若解析器没丢弃孤立 ToolResponse，`MessageWindow` 会因 `pending.remove(id)==false` 抛 `IllegalStateException`，文案是“会话中的工具记录不完整，请新建对话或联系管理员修复历史”。
5. 合法未闭合尾批次不抛：暂停等待确认时历史以带 `notifyUser` 调用的 AssistantMessage 结束；`/r` 补上回执后才闭合。

这套行为由 `SecurityRepairRegressionTest` 的两个用例覆盖（测试源码阅读，未运行）。它们使用真实 `MessageParser`/`MessageWindow`，但不接真实 Redis/MySQL。

### 8.5 工具反查：缓存命中、回源与失败

| 场景 | 起点 | 调用链 | 结果 |
|---|---|---|---|
| Redis 命中 | 模型给出合法 `callId`，当前 ToolContext 属于该 chat | `assertOwned` → `GET chat:tool:v2:{chatId}:{callId}` | 直接返回字符串；不查 MySQL |
| Redis miss/读异常 | 同上 | MySQL 双条件按 id 倒序查一行 → `cache` best-effort 回写 24h | 返回 MySQL 字符串；Redis 回写失败只记日志 |
| MySQL 无行 | 参数非空但 callId 不存在/不属于该 chat | 查询返回 Java `null` | 工具返回 Java null；框架如何呈现给模型未实测，也没有专门的“未找到”事件 |
| 写辅助索引失败 | 工具已真实执行，`recordCalling` 内 mapper insert 明确抛异常 | catch `RuntimeException`，记日志 | 工具不会被重放；回执是否已在内存取决于构造是否走到，最终是否落库取决于后续 `saveNewMessages`；明确抛错时不会调用 `cache`，响应丢失则索引是否已写未知 |
| 跨用户猜测 callId | 模型给出别的会话的 callId，但 ToolContext 是当前会话 | `assertOwned` 校验当前会话；`get` 再按当前 chatId + callId 查 | 当前实现下不会用“另一个 chatId”拼 key；但源码没有全局 fencing，结论限定在这条工具链 |

`ToolResponseMemory#key` 是私有方法，测试没有直接断言这个字符串；正文的 key 来自源码阅读。常量类里的旧 `tool:{callId}` 没有主链调用。

### 8.6 清空后的在途读：成功、失败与响应丢失分开

**成功时间线前提**：T1 进入 `get` 时已通过 `findOwned`，同一 chatId 的元数据存在且尚未墓碑；T1 捕获 Redis 版本 8、从 MySQL 读到旧快照；T2 调 `clearChat`，`retireHistory` 命中一行、`mysql.clear` 与 Redis Lua 均正常执行，T1 的回填脚本也正常返回 0。

| 时刻 | T1（在途读） | T2（清空） | 状态 |
|---|---|---|---|
| 1 | 版本 8，持有旧快照 | — | T1 尚未回填；已过的归属检查不会在返回前重做 |
| 2 | — | DB 墓碑 `history_deleted=true` + 删除 `sys_messages` | 后续新请求才会看到墓碑；T1 已读的旧内容不撤销 |
| 3 | — | Redis Lua：version 8→9、`PERSIST`、`DEL data` | 旧 data 消失，版本推进 |
| 4 | 写 staging，Lua 比较 9≠8，`DEL staging`，正常返回 0 | — | Java 得 false，T1 放弃回填 |
| 5 | 本次仍返回 t0 读到的旧快照 | — | 不是客户端可见的“已撤销”状态 |
| 6 | 后续**同一 chatId** 请求 | — | 元数据存在且 `history_deleted=true` → 归属通过后抛 **410**，不是返回空；只有元数据根本不存在且 `allowNew=true` 才返回空历史 |

**失败/响应丢失时间线**：

| 中断点 | 可确定的状态 | 不可确定/剩余窗口 |
|---|---|---|
| `retireHistory` 抛异常并向上传播 | 本次调用不会继续执行 `mysql.clear` 或进入 Redis/Heap 的 `try/finally` | 更新可能未执行，也可能已执行但响应丢失；不能只凭异常断言墓碑状态 |
| `retireHistory` 正常返回但影响 0 行 | 该次更新没有命中记录；代码不检查行数，仍继续调用 `mysql.clear`，之后才进入 Redis/Heap 的 `try/finally` | 不能从正常返回推断墓碑已建立；后续清理可能成功或在任一步失败 |
| `retireHistory` 成功，`mysql.clear` 抛异常或响应丢失 | 后续新请求做归属检查时会看到 410；代码尚未进入 `try/finally` | `sys_messages` 可能仍有旧行；Redis data 与版本未由 clear 触碰；T1 若版本仍为 8 且后续脚本成功，仍可能把旧快照回填 L2/L1 |
| 进入 `try` 后 `redis.clear` 明确报错 | `finally` 会执行 `heapMemory.clear`（若 Java 控制流到达 finally） | Lua 是否已执行、版本是否推进、data 是否删除不能由“报错”一概而论；跨库状态未知 |
| `redis.clear` 响应丢失 / 进程退出 | 不能断言 Lua 是否已执行 | `finally` 在进程正常继续执行时才保证运行；进程退出、线程中断等情况下 Heap 可能未清；客户端是否收到任何结果也未实测 |

墓碑先写、数据后删的顺序降低了“新请求继续读旧缓存”的窗口，但不是跨 MySQL/Redis 的原子事务；版本保护也只在相关脚本成功执行、版本未被外部删除或复用的前提下有效。

---

## 九、设计取舍与边界：当前能保证什么

| 机制 | 能保证（在前提成立时） | 不能保证/剩余窗口 |
|---|---|---|
| `CURRENT_HISTORY_VERSION` | 旧版本 key/数据 key 被迁移时丢弃半截缓存；正常版本 key 最终 `PERSIST` | 外部删除/淘汰版本 key 后旧号撞新号；Redis 不可用 |
| 保存条件追加 | 参数与序列化非空且脚本正常执行时，先 MySQL、后按版本条件写缓存；版本不符会 `DEL` 不可信 data | 早退不推进版本；Lua 错误不回滚已执行写命令；MySQL 与 Redis 不回滚，`updateChatTime` 不在同一事务，响应丢失结果未知 |
| 回填租约 | 暂存写入、版本比较、`RENAME`/`PEXPIRE` 均成功时替换完整快照 | 当前读请求仍返回自己读到的旧快照；正常返回 0 才表示只删暂存；异常/false 不证明服务端没写或 TTL 已设置；暂存 TTL 依赖 `EXPIRE` 成功 |
| Heap 版本 | 同实例、同 chatId 的 `compute` 比较与替换原子；清空不把版本归零 | 参数为空时早退；无 TTL/容量上限；重启丢失；跨实例没有共享 L1 |
| `MessageWindow` | 先检查传入全列表的配对；按批次向前扩展，允许合法未闭合尾批次 | 截掉部分的非法配对也会抛错；不能修复底层坏记录；返回可能多于目标数量；`answered` 只扫窗口 |
| `ToolResponseMemory` | MySQL `insert` 明确成功后 Redis 命中可减少回源；工具层用服务端 chatId 做归属检查 | Redis 写异常被吞；响应丢失时 insert 状态未知；内存回执不保证最终落库；辅助索引失败不能重放工具 |
| `clearChat` | 成功路径上墓碑先置、Redis 版本递增后删 data；进入 `try` 后 `finally` 会尝试清 Heap | 四步不是跨库事务；`retireHistory` 不查影响行数；前两步失败时 `finally` 不会运行；同一 chatId 已墓碑的新请求是 410，不是“新会话空” |

一个贯穿全章的界限：**Redis/Lua 脚本的原子性只在 Redis 内；`MysqlMemory#append` 的事务只在 MySQL 内；Heap 的 `compute` 只在一个 JVM 内。** 它们通过版本号协作，但没有 XA，也没有“所有层同时成功或同时失败”的保证。

---

## 十、动手验证：只读源码追踪练习

以下练习都不启动服务、不发请求、不运行测试，适合在 IDE 中完成。所有 chatId 用 `chat-demo-0001` 这类虚构值，不要连接项目业务 Redis/MySQL。

### 练习 A：三级读取与回填条件

1. 打开 `agentbase/memory/MessageMemory.java#get`，在纸上写出 L1 hit、L1 miss/L2 hit、L1+L2 miss/MySQL hit 三条路径的返回列表来源。
2. 跳到 `HeapMemory#getAll/currentVersion/overwriteIfVersionMatch`、`RedisMemory#getAll/currentVersion/overwriteIfLeaseValid`、`MysqlMemory#getAll`。
3. 检查点：为什么 `redisLease` 在 `redisMemory.getAll` 之前取？为什么 Redis 回填成功才回填 Heap？
4. 预期观察：`get(...,1)` 时 L2 实际被整段读出；`tail` 后才变成窗口。失败解释：若你把“limit=1”理解成 `LRANGE -1 -1`，就找不到回填全量的代码。
5. 清理：无；只读。

### 练习 B：版本脚本三态

1. 打开 `util/RedisScripts.java`，依次读 `CURRENT_HISTORY_VERSION`、`APPEND_HISTORY_IF_VERSION_MATCH`、`OVERWRITE_IF_VERSION_MATCH`、`CLEAR_HISTORY_WITH_VERSION_BUMP`。
2. 对每个脚本列出 `KEYS`、`ARGV`、所有 `return` 值，以及“版本是否 INCR”“data 是否 DEL/RENAME/PEXPIRE”。
3. 检查点：`APPEND_HISTORY_IF_VERSION_MATCH` 在 data 不存在但版本匹配时为什么不创建 key？`OVERWRITE_IF_VERSION_MATCH` **正常返回 0** 时，Java 已执行了哪些写命令（暂存 `RPUSH`/`EXPIRE`、脚本 `DEL staging`）？为什么这与“异常后 Java 返回 false”不是一回事？
4. 预期观察：只有参数/序列化非空且脚本正常进入时，追加脚本才 `INCR`；回填成功推进版本，但 `RENAME` 会先带上暂存 TTL，随后 `PEXPIRE` 成功才变成 24h；`CURRENT_HISTORY_VERSION` 不是因为 data key 有 TTL 就删它。
5. 清理：无；只读。

### 练习 C：窗口与坏历史

1. 打开 `MessageParser#parse/unparseHistory/simpleParse` 与 `MessageWindow#tail`。
2. 自己构造 5 条消息：User、Assistant 带两个 toolCalls、ToolResponse 含两个 id、Assistant 文本、Assistant 带一个未闭合 toolCall。分别算 `tail(...,1)`、`tail(...,3)` 的起点与返回条数。
3. 把第 3 条改成一个未知 id，预测异常类型与文案；把一个坏 JSON 插到第 2 条前，预测解析器输出。
4. 对照 `SecurityRepairRegressionTest#historyWindowKeepsWholeToolBatch`、`#corruptToolPairingIsRejectedWithReadableError`、`#corruptHistoryResumesAtNextUserTurnAndDropsDanglingTools`、`#notifyBatchPreservesEveryNonConfirmationResponse` 的断言。
5. 清理：无；测试只阅读，未执行。

### 练习 D：工具反查的边界

1. 从 `agents/OrderAgent.java#handleToolCalls` 找到 `recordCalling` 调用，点进 `ToolRepPointerTool#recordCalling`、`ToolResponseMemory#save`。
2. 从 `ToolRepPointerTool#getToolResponse` 反向找 `ToolContextKeyConstants.USER_ID/CHAT_ID` 的写入点，核对它不是模型参数。
3. 全局搜索 `toolResponseKey(`、`TOOL_RESPONSE_KEY`、`chat:tool:v2:`；解释为什么正文写后一个 key 而不是 `tool:{callId}`。
4. 检查点：MySQL 插入失败时 Redis 是否被写？Redis 读失败时是否还能返回？模型是否一定调用 `getCalling`？
5. 清理：无；只读。

### 练习 E：清空与恢复

1. 打开 `MessageMemory#clearChat` → `ChatManager#retireHistory` → `ChatInfoService#retireHistory` → `MysqlMemory#clear` → `RedisScripts#CLEAR_HISTORY_WITH_VERSION_BUMP` → `HeapMemory#clear`。
2. 另开一条线：`ChatController#POST /restore` → `ChatManager#restoreChat` → `ChatInfoService#restoreChat` → `MessageMemory#get` → `MessageParser#simpleParse`。
3. 检查点：为什么清空不靠“只删 Redis”？恢复为什么不走 `getPersisted`？
4. 对照 `CacheConsistencyRegressionTest#clearingChatInvalidatesBothCacheLeasesAfterPersistence` 与 `RepairDatabaseIntegrationTest#retiredHistoryCannotBeRecreatedByLaterMessageSave` 的 mock/门控。
5. 清理：无；只读。

### 10.1 测试阅读卡（逐类逐方法）

> **全部是测试源码阅读，未运行。** 这里只列与 C4 直接相关的方法；“断言”是教材对源码断言的转述，不是运行通过的结论。

| 测试类 | 门控与真实/替身 | 关键断言（方法） | 资源与清理范围 | 没有覆盖的故障 |
|---|---|---|---|---|
| `CacheConsistencyRegressionTest` | 无门控。真实 `HeapMemory`、`MessageMemory`；`RedisMemory`/`MysqlMemory`/`ChatManager` 为 Mockito | `clearingHeapInvalidatesOldLease`：clear 后旧租约失效；`heapAppendDoesNotCreatePartialHistoryOrDuplicateRefilledMessages`：冷 append 不造半截列表；`rejectedHeapRefillPreservesConcurrentAppend` / `rejectedRedisRefillDoesNotPopulateHeap`：拒绝回填保留并发内容、不污染 L1；`limitedReadCachesFullHistoryForSubsequentReads`：限条读仍缓存全量；`failedPersistenceDoesNotPublishNewMessages`：MySQL 明确抛错时不发布缓存且不调 Redis；`successfulSaveAppendsToWarmCacheAndColdSaveDoesNotHideOlderMessages`；`clearingChatInvalidatesBothCacheLeasesAfterPersistence`：验证 `mysql.clear` → `redis.clear` 顺序 | `@AfterEach` 只在同步活跃时 `TransactionSynchronizationManager.clearSynchronization()`；不连真实 Redis/DB | 真实 Redis/Lua、真实 MySQL 事务、MySQL 成功但 Redis 失败、`retireHistory`/`mysql.clear` 抛错后 Heap 是否清理、响应丢失 |
| `RedisCacheRegressionTest` | `@EnabledIfSystemProperty(test.redis.port)`。真实 `LettuceConnectionFactory`/`StringRedisTemplate`/`RedisMemory`/`MessageParser`；`MysqlMemory`/`ChatManager`/`CityCodeMapper` 为 mock | 9 个聊天用例：`appendSetsDataExpiryAtomicallyAndRejectsOlderLease`、`refillConsumesLeaseAndUsesHistoryTtl`、`clearInvalidatesZeroAndNonzeroLeases`、`dataExpirationDoesNotResetVersionOrReviveLease`、`readingLegacyVersionRemovesItsExpiryAndInvalidatesPartialHistory`、`legacyHistoryWithoutVersionIsRebuiltFromPersistence`、`coldSaveDoesNotCreatePartialHistory`、`saveAppendsToWarmCacheAndInvalidatesConcurrentRefill`、`concurrentSavesAndRefillsPreserveEveryPersistedMessage`（4 线程×10 次，40 条唯一） | `@BeforeEach` 随机 chatId，仅把 data/version 键名加入 `keys`；`@AfterEach` 只 `redis.delete(keys)`；多个用例直接调用 `overwriteIfLeaseValid`，会走 staging 路径，但 **staging key 不在清单**，正常时由脚本消费，异常时靠尽力删除或已成功设置的 TTL | 真实 MySQL 回填、脚本中途崩溃/响应丢失、异常时 staging key 的实际残留、`PEXPIRE` 失败后的 TTL、真实并发 MySQL 写入 |
| `SecurityRepairRegressionTest` | 无门控。多为局部 new + Mockito；真实 `ToolRepPointerTool`、`ToolResponseMemory`、`MessageWindow`、`MessageParser`、`ChatInfoService`、`MessageMemory+HeapMemory`，反射构造真实 `OrderAgent#handleToolCalls` | `actualToolSchemaUsesDispatchName`：schema 名为 `getCalling`；`failedToolPersistenceDoesNotPublishCache`：mapper insert 抛错时不写 Redis；`historyWindowKeepsWholeToolBatch`：limit=1 返回 2 条；`corruptToolPairingIsRejectedWithReadableError`；`corruptHistoryResumesAtNextUserTurnAndDropsDanglingTools`；`deletedChatRejectsEvenWarmCacheAccess`：墓碑 410；`orphanedCachedHistoryIsNeverReadAsANewChat`；`notifyBatchPreservesEveryNonConfirmationResponse`：补应答后窗口不抛、`recordCalling("after")` 未调用 | `@BeforeEach` 设置 `UserTokenContext` 并注入部分 mock；`@AfterEach` 只 `UserTokenContext.clear()`；不连真实 Redis/MySQL | Redis 命中/回写与实际 TTL、缓存与 DB 结果不一致、真实模型调用 `getCalling`、真实工具流失败后的历史落库、多批次交错损坏 |
| `RepairDatabaseIntegrationTest` | `@EnabledIfSystemProperty(test.mysql.port)`。真实专用端口 MySQL、临时 schema、`init.sql`、真实 `MysqlMemory`/`ChatInfoService`；`StringRedisTemplate` 为 mock | `retiredHistoryCannotBeRecreatedByLaterMessageSave`：墓碑后 append 抛 410，`sys_messages` 计数 0；`chatIdsHaveOneOwnerEvenUnderConflictingCreation`：`sys_chat.uk_chat_id` 冲突 | `@BeforeAll` 建 `ride_repair_{32hex}` schema 并执行 `init.sql`；`@AfterAll` 仅在名称匹配时 `DROP DATABASE`；`@BeforeEach` 清 7 张表，**不含 `sys_messages`**，依赖整库 DROP 兜底 | Redis/Heap 同步清理、`clearChat` 各步响应丢失、真实 Redis 版本推进、`sys_messages` 跨用例残留风险、线上 schema 差异 |
| `ChatServiceRegressionTest` | 无门控。真实 `ChatServiceImpl`；`MessageMemory`/`ChatManager`/Redis/Agent/模型全为 mock | `limitedChatStopsBeforeMemoryClassifierAndAgents` 等仅验证“不交互 memory”；`nullHistoricalTextStillUsesMultiTurnClassifierPrompt` 只 stub `memory.get` 来测分类 prompt | `@BeforeEach` 构造 mock；无 `@AfterEach`；不连真实存储 | 真实三层读取、版本租约、窗口、工具反查与清空/墓碑；该类不能作为 C4 记忆一致性证据 |

> 隔离实验提示（本轮未执行）：若以后在一次性 Redis 上观察这些 key，端口、数据与清理范围必须由实验者明确隔离，只删除自己生成的 `chat-demo-*`/`regression-*` key；不要使用 `FLUSHALL`，也不要连接项目业务库。

---

## 十一、一页复习

**四种对象**：用户消息 / Assistant 工具调用 / `ToolResponseMessage` 回执 / 辅助反查索引。前三者先在内存中配对，只有后续 `saveNewMessages` 成功才持久化到聊天历史；第四个是 `ToolResponseMemory`，供 `getCalling` 用。工具已执行 ≠ 辅助索引写入成功 ≠ 聊天历史已落库。

**三条读路径**：

- 普通：`MessageMemory#get` → L1 → L2 → L3 → 逐级回填；已通过归属检查的在途读不会再查墓碑；
- 恢复：`getPersisted` → 只查 MySQL → `MessageWindow.tail`；
- 前端恢复展示：`get` → `simpleParse` → 只有 USER/ASSISTANT 文本。

**一次 save**：归属 → 焦点/TTL → 取 L1/L2 版本 → MySQL append → Redis 条件追加 → Heap 条件追加 → 更新时间。MySQL 先写、缓存后写，不是跨库事务；空参数/空序列化会早退，Redis 脚本正常进入才推进版本。

**Lua 三件套**：

- `CURRENT_HISTORY_VERSION`：版本 key 不存在/自身带 TTL 时清 data 并修正版本，最后 `PERSIST`；
- `APPEND_HISTORY_IF_VERSION_MATCH`：正常进入脚本后先 INCR；版本匹配且 data 存在才追加，版本不符删 data，data 不存在不创建残缺列表；
- `OVERWRITE_IF_VERSION_MATCH`：暂存存在 → 版本比较 → `INCR+PERSIST+RENAME+PEXPIRE`；正常返回 -1/0/1。Java false 还可能是早退或异常，`RENAME` 带过来的 TTL 要靠成功的 `PEXPIRE` 覆盖成 24h。

**窗口**：`limit` 是目标数量，不是上限；方法先检查整个传入列表的配对再截取；批次边界向前扩展；合法尾批次可未闭合；重复/未知 id 抛异常；坏 JSON 会清空并等待下一条 User，没有后续 User 时可能返回空。

**TTL**：Heap 无 TTL；历史 data 24h/30min；版本 key `PERSIST`；暂存 60s；工具反查 Redis 24h；`chat:info` 的 `locked` 60min。全部以实际命令成功为前提。

**必须记住的否定**：MongoDB 不存聊天；ESMemory/MemoryAdvisor 未接入；`redis.append`/`getLastN` 不是主 save 链；`tool:{callId}` 不是实际 key；提示词中的 `call_id:` 压缩不是已实现改写；版本号不是互斥锁；`tryEmit*` 与 RPC 响应丢失不能证明客户端看见或服务端没写；同一 chatId 已墓碑的新请求是 410，不是“新会话空”。

**常见误区**：

1. “L2 命中就直接返回最后 N 条”——实际是 `LRANGE 0 -1` 后在 Java `tail`。
2. “每次 save 都无条件推进版本”——空参数/序列化为空会早退；脚本正常进入并执行的 save 才推进。
3. “回填失败就是缓存没变”——正常返回 0 时只删暂存；异常/响应丢失时服务端可能已 RENAME 并改 TTL。
4. “工具结果只有一份，且辅助索引失败后完整结果已在聊天历史”——聊天历史与辅助索引是两份；索引写入、内存回执、历史落库是三个不同步骤。
5. “提示词说必须调用 getCalling，所以模型一定调用”——提示词是行为约束，不是代码保证。
6. “clearChat 一定由按钮触发，finally 一定清 Heap”——当前主链未找到控制器调用；前两步异常时根本没进入 try/finally。

---

## 十二、自测与答案

> 先自己写理由，再展开。答案都给出源码位置与条件，不只看结论。

<details>
<summary>1. 为什么 `MessageMemory#get` 在首次命中 L1 之前先取 `heapLease`，L1 miss 后又先取 `redisLease`？</summary>

因为回填也是写。若读 L2/MySQL 期间有并发 save，晚到的旧快照覆盖 L1/L2 会抹掉新消息。`heapLease` 用于把 L2/MySQL 快照回填 L1；`redisLease` 用于把 MySQL 快照回填 L2。两者都必须在耗时读取前捕获。源码：`MessageMemory#get` 的两处 `currentVersion` 与 `fill*WithLease`。未实测并发调度。

</details>

<details>
<summary>2. `get(userId,chatId,1)` 从 MySQL 命中时，Redis 中最终会有什么？</summary>

若 `overwriteIfLeaseValid` 正常返回 true（Lua 返回 1），说明暂存写入、版本比较、`RENAME` 与随后 `PEXPIRE 24h` 都执行成功；Redis data key 被 MySQL 快照替换，而不是只留最后一条。若返回 false，要分早退、Lua 正常返回 0 和捕获的异常等情况：正常返回 0 不会 RENAME；响应丢失时缓存和 TTL 可能已变。以上方法正常返回后，本次 get 继续对 MySQL 快照取窗口；若 `serialize` 在 try 之外抛异常，异常会传播，不能保证 get 返回。源码：`MessageMemory#get`、`RedisMemory#overwriteIfLeaseValid`、`RedisScripts#OVERWRITE_IF_VERSION_MATCH`。

</details>

<details>
<summary>3. 一次 save 中 MySQL 成功、Redis 明确抛异常，能说“消息没保存”吗？能说 Redis 一定旧吗？</summary>

都不能。MySQL `append` 已提交则消息在 `sys_messages`；后续 Redis/Heap 异常不会回滚 MySQL。只有能确认 Redis 方法在调用前早退（空参数/序列化为空）或脚本明确未进入时，才可以说版本未由这一步推进；命令报错或超时/响应丢失时，Lua 可能已经 `INCR`/`RPUSH`，甚至执行到后面才出错，结果未知且已执行写命令不会自动回滚。源码：`MessageMemory#save` 的顺序与 catch、`RedisMemory#appendIfVersionMatch`、`APPEND_HISTORY_IF_VERSION_MATCH`。未实测真实故障。

</details>

<details>
<summary>4. `APPEND_HISTORY_IF_VERSION_MATCH` 版本匹配但 data key 不存在时为什么不 `RPUSH`？这对冷缓存意味着什么？</summary>

因为只追加本次新消息会生成“半截历史”，后续读命中它就会把 MySQL 中更早的消息永久遮住。源码选择什么都不做；在脚本正常进入并执行的前提下，它只完成 `INCR+PERSIST` 推进版本，下次读会 miss 并从 MySQL 回填完整列表。若外层参数为空或序列化结果为空，方法早退，连版本也不推进。依据：`RedisMemory#appendIfVersionMatch` 的早退与脚本的 `elseif EXISTS KEYS[2]==1` 分支；测试 `RedisCacheRegressionTest#coldSaveDoesNotCreatePartialHistory`（测试源码阅读）。

</details>

<details>
<summary>5. 为什么说版本租约不是单轮执行锁？请给出两者各保护什么。</summary>

版本租约比较“快照代际”：旧回填能不能写缓存；key 是 `chat:history:version:{chatId}`，版本变了就放弃。单轮执行锁是 Redisson `RLock`，key `chat:execution:{chatId}`，在同一会话后台执行期间挡住第二轮请求，由 `doFinally` 发起释放（C1）。一个防旧快照覆盖，一个防同轮并发；源码和操作对象都不同。

</details>

<details>
<summary>6. 历史里有 Assistant 两个 toolCalls、ToolResponse 两个 id，但 `limit=1`。返回几条？如果第二个 id 被写成未知 id 呢？</summary>

配对合法时，边界从 Assistant 调用处开始，一次返回两条消息（调用 + 回执），条数大于 limit。若 ToolResponse 的 id 不在 pending 中，`pending.remove` 返回 false，`MessageWindow#tail` 抛 `IllegalStateException`。源码：`MessageWindow#tail`；测试 `SecurityRepairRegressionTest#historyWindowKeepsWholeToolBatch/#corruptToolPairingIsRejectedWithReadableError`（未运行）。

</details>

<details>
<summary>7. 坏 JSON 会让整段聊天都不可读吗？坏记录会被删除吗？</summary>

不一定，也不能说坏记录一定会被删除。`MessageParser#unparseHistory` 遇到坏 JSON 会清空当前已解析结果、进入恢复态，跳过后续非 UserMessage，**只有遇到下一条 UserMessage 才重建上下文**；如果后面没有 User，结果可能为空。原始 `sys_messages` 行不删除。即使解析通过，只要 id 配对错误，`MessageWindow#tail` 仍会抛 `IllegalStateException`；而且它先检查整个传入列表，截掉部分的非法配对也会抛。源码：`MessageParser#unparseHistory`、`MessageWindow#tail`；测试 `corruptHistoryResumesAtNextUserTurnAndDropsDanglingTools`（未运行）。

</details>

<details>
<summary>8. `getCalling` 的 `callId`、`chatId`、`userId` 分别来自哪里？提示词说“必须调用”算代码保证吗？</summary>

`callId` 来自模型工具参数；`chatId`/`userId` 来自 Agent 构造 Prompt 时写入的 `ToolContext`，随后工具内 `assertOwned`。提示词 `ChatProperties` 写“必须调用 getCalling(call_id)”只是行为约束；源码没有把工具结果改写成 `call_id:` 指针，也没有保证模型一定会调用。依据：`ToolRepPointerTool#getToolResponse`、四个 Agent 的 `toolContext(...)`、`config/ChatProperties`、`MessageParser#parse(TOOL)`。

</details>

<details>
<summary>9. 为什么 `ToolResponseMemory` 的 key 不是 `RedisKeyConstants.toolResponseKey(callId)`？</summary>

`ToolResponseMemory#key` 私有方法直接拼 `chat:tool:v2:{chatId}:{callId}`；全仓库检索 `toolResponseKey(`/`TOOL_RESPONSE_KEY` 只找到常量定义，没有主链调用。真实读写都由 `ToolResponseMemory` 自己完成，key 带 chatId 和 callId 两个维度。旧常量存在不等于接线。未实际连接 Redis 观察。

</details>

<details>
<summary>10. `clearChat` 后为什么还要让 Heap 版本递增，而不是把条目删掉/版本归零？</summary>

如果清空后版本归零，清空前捕获过旧租约的读请求可能重新“匹配”，把旧快照写回 L1。源码 `HeapMemory#clear` 用 `compute` 写入空列表并把版本 +1（没有条目时设为 1），使旧租约失效。Redis 侧由 `CLEAR_HISTORY_WITH_VERSION_BUMP` 递增版本并删 data。源码：`HeapMemory#clear`、`RedisScripts#CLEAR_HISTORY_WITH_VERSION_BUMP`；测试 `CacheConsistencyRegressionTest#clearingHeapInvalidatesOldLease`（未运行）。

</details>

---

## 十三、源码核验入口与导航

- **本章主源码**：`agentbase/memory/{MessageMemory,HeapMemory,RedisMemory,MysqlMemory,MessageWindow,MessageParser,SimpleMessage,ToolResponseMemory,ESMemory,MemoryAdvisor}.java`；`agentbase/tool/ToolRepPointerTool.java`；`agentbase/chatinfo/ChatManager.java`；`service/base/ChatInfoService.java`；`constant/{RedisKeyConstants,ToolContextKeyConstants}.java`；`util/RedisScripts.java`；`domain/entity/{ChatMessage,ChatToolResponse,Chat}.java`；`mapper/ChatToolResponseMapper.java`；`src/main/resources/sql/init.sql` 与 `sql/migrations/20261113_security_consistency.sql`。
- **调用方**：`controller/ChatController.java`、`service/impl/ChatServiceImpl.java`、四个 `agents/*Agent.java`、`config/ChatProperties.java`、`service/base/OrderRouteService.java`（Mongo 职责对照）。
- **测试源码**：逐测试门控、真实/替身、断言、清理与未覆盖见 §10.1《测试阅读卡》；涉及 `src/test/java/com/fancy/rideagenthub/` 下五个测试类。**全部只阅读，未运行。**
- **上一章**：[第 18 章 订单确认、恢复与消费](03-order-confirmation.md)——`getPersisted`、`answered` 与确认恢复的上下文。
- **相关专题**：[第 16 章 一次对话与事件流](01-chat-round-and-sse.md)、[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)、[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)。
- **下一章**：[第 20 章 外部工具与提示词边界](05-external-tools-and-prompts.md)——地图/天气、反序列化与提示词约束；本章只解释工具结果与反查，不展开外部 API 细节（已通过复审）。
