# 第 17 章 分类路由与工具循环：一句话如何变成工具调用

> 林小雨（虚构，`userId=7000001`）在已有会话里输入：“成都今天天气怎么样？” 上一轮助理刚回答完一个日常问题。服务器不能把这句话直接丢给一个“万能 Agent”：它要先判断这句话属于哪类业务，再把历史、系统提示和工具清单交给对应的 Agent。Agent 调用模型，模型可能不直接回答，而是请求“查天气”；服务端执行工具，把结果作为工具回执交回模型，模型再决定继续调用工具还是给出最终回答。这个“分类 → 路由 → 模型请求工具 → 服务端执行 → 回执 → 下一轮”的闭环，就是本章的主题。
>
> C1 已讲清一次请求如何进入后台、如何通过 SSE 结束；本章不重复锁、订阅、线程池和恢复。**C2 只讲分类、四个 Agent 的差异、工具注册/分派和循环终止**。人工确认与 `notifyUser` 的完整协议由 [C3](03-order-confirmation.md) 主讲；三层记忆、工具结果反查和回填由 [C4](04-memory-and-tool-results.md) 主讲；地图/天气等外部工具细节与提示词边界由 [C5](05-external-tools-and-prompts.md) 主讲（均已通过复审）。本章提到它们时只给理解循环所需的最小上下文。
>
> **本章没有启动服务、运行测试、连接 Redis/MySQL/Elasticsearch、调用模型或外部 HTTP**。除明确标注“测试源码阅读”的地方，结论都来自仓库源码静态推导；所有模型输出、工具返回和事件序列都是示意，不是实测结果。

## 学习目标

读完本章，你应该能：

1. 说清分类、规范化、路由、工具调用、工具回执、循环和事件分别是什么，并从 `ChatServiceImpl#chat` 追到对应 Agent。
2. 分别写出新会话与有历史会话的分类上下文（`SINGLE_TURN`、旧 `classification`、最后一条 `AssistantMessage` 文本、本轮 prompt），解释分类缓存 key 的编码、指纹、TTL 与禁用方式。
3. 按源码列出四个 Agent 实际注册的工具、手写 `executeTool` 分派的分支，以及 `notifyUser`、`getCalling`、FallbackAgent 的特殊之处。
4. 画出至少两条可达时间线：正常工具调用并形成 `ToolResponseMessage` 再进入下一轮；模型错误/超时、循环超过阈值或显式暂停。
5. 区分工具结果、SSE 事件、Flux 错误和业务完成；说明工具回执为什么不是用户授权，以及 `ToolContext` 中的身份从哪里来。

## 前置知识与阅读时间

- 必读：[第 16 章 一次对话与事件流](01-chat-round-and-sse.md)——后台任务、sink、`autoConnect(2)`、事件类型与保存边界。
- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——Flux/SSE、Redis key/TTL 与 Lua 的最小概念。
- 必读：[第 6 章 权限与防护](../01-auth/02-authorization-and-defense.md)——身份来自服务端、工具参数不可信的基本口径。
- 必读：[第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)——ORDER 业务和订单工具的大致范围。
- 必读：[第 12 章 工单生命周期与权限](../03-support-and-cache/01-ticket-lifecycle.md)——SUPPORT 业务里的工单与订单查询。
- 相关：[第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)——工具和确认状态里用到的锁/令牌口径。
- 后续主讲：[C3 订单确认、恢复与消费](03-order-confirmation.md)、[C4 三层记忆与工具结果反查](04-memory-and-tool-results.md)、[C5 外部工具与提示词边界](05-external-tools-and-prompts.md)（均已通过复审）。
- 阅读时间：约 100～130 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、API 文档和测试写完整仓库相对路径。

---

## 零、先记住十个事实

1. **分类没有独立类**：分类逻辑在 `service/impl/ChatServiceImpl.java#chat/#resolveClassification/#normalizeClassification/#lastAssistantText` 里，前面没有 `ClassifierAgent` 之类的类。
2. 分类输入不是“用户原话”一项：新会话用 `SINGLE_TURN`、`null`、prompt；有历史时用旧 `classification` 字段、最后一条 Assistant 文本、prompt。
3. 分类缓存 key 只由这三段文本的指纹组成，**没有 userId**；它是内容缓存，不是用户隔离，也不是鉴权。
4. 缓存值会再次 `normalize`；空白或非法缓存值会退化为未命中并调用模型，但不会被主动删除。
5. `DANGER` 与 `null` 在 `initChat`、写 `classification`、调用 Agent 之前就结束；旧 `classRedis=ORDER` 的提前分支又在这两者之后。
6. 四个 Agent 都自己构建历史、系统提示和 `Prompt`，都设置 `internalToolExecutionEnabled(false)`；模型只“请求”工具，服务端在 `executeTool` 里手动执行。
7. 工具“类存在”不等于“注册”，也不等于“分派”，更不等于“真实请求能走到”；`RegeoTool`、`ChatStatusTool` 是典型反例。
8. 工具有 `@Tool` 定义，但 `OrderAgent` 对 `notifyUser` 有特殊分支，不经过普通 `executeTool`；`getCalling` 是显式改名的工具。
9. 工具调用循环的硬阈值是“新增历史消息数超过 200”（严格 `> 200`），不是工具调用次数；FallbackAgent 没有循环。
10. 模型流每次最多 3 分钟；单次工具、RAG、天气各有局部超时；**没有整轮总时限**。只有 `executeTool` 的 try 内捕获的 `Exception` 会变成工具回执文本；其他回调/回执/保存异常不保证同样处理。

---

## 一、学习地图与虚构输入

### 1.1 七个容易混的词

| 词 | 在本项目里的具体含义 | 不是什么 |
|---|---|---|
| 分类 classification | `ChatServiceImpl` 调分类模型或读缓存，得到 `ORDER/DAILY/SUPPORT/OTHER/DANGER` 之一 | 不是独立服务，也不是权限判断 |
| 规范化 normalize | `normalizeClassification` 去空白、转大写，只保留五个合法标签 | 不是分词，也不是安全过滤 |
| 路由 route | `switch` 或旧 ORDER 分支决定调用 `OrderAgent/DailyAgent/SupportAgent/FallbackAgent` | 不是按 URL 路由，也不是前端跳转 |
| 工具调用 tool call | 模型返回 `AssistantMessage.ToolCall`，请求服务端执行某个已注册工具 | 不是模型自己执行了数据库或 HTTP |
| 工具回执 tool result | 服务端执行后构造 `ToolResponseMessage.ToolResponse`，作为下一轮模型输入的一部分 | 不是给浏览器的 SSE 事件，也不是用户授权 |
| 循环 loop | 模型要求工具 → 服务端执行 → 回执 → 再请求模型，直到没有工具调用或触发终止条件 | 不是固定次数的 `for` 循环 |
| 事件 event | `AgentEvent` 的 `message/tool_start/error/confirm/notify`，通过 sink 尝试发给客户端 | 不等于业务完成，也不保证客户端收到 |

### 1.2 完整调用链

```text
用户 HTTP 请求（身份已在 C1 的拦截器/切面中确定）
  -> ChatRequestRunner: 锁、归属、chatExecutor（C1）
  -> ChatServiceImpl#chat: 锁定/限流/归属/break
  -> memory.get(...,100)
  -> 读 Redis chat:info:{chatId} 的 classification（classRedis）
  -> resolveClassification(contextFeature, contextText, prompt)
       -> ClassificationCache.get（命中且合法则返回）
       -> 否则 ChatClient.classify -> normalize
       -> 合法则 ClassificationCache.put
  -> null? -> message + complete，结束
  -> notify(classification)
  -> DANGER? -> message + complete，结束
  -> 历史为空? -> ChatManager.initChat -> ChatInfoService.insertChat
  -> classRedis == "ORDER"?
       -> 需要时 notify 换话题提醒 -> OrderAgent.invoke -> return（不写新分类）
  -> 写 Redis classification
  -> switch: ORDER / DAILY / SUPPORT / OTHER -> 对应 Agent
  -> Agent: 历史 + SystemMessage + UserMessage -> Prompt
  -> ChatModel.stream(prompt)
  -> 模型返回内容片段或 ToolCall
  -> executeTool 手动执行 -> ToolResponseMessage
  -> 递归 runLoopStep -> 直到纯文本/错误/超限/暂停
  -> saveNewMessages（成功终态）
  -> tryEmitComplete / tryEmitError（尝试）
```

### 1.3 一条虚构输入

林小雨的会话 `chatId=9f1d...` 已有几轮历史，旧 `classification=DAILY`。她输入：

```text
成都今天天气怎么样？
```

本章假设：

- 旧 Hash 的 `classification` 仍是 `DAILY`；
- 历史里最后一条 Assistant 文本是“成都今天多云”；
- 分类缓存未命中，分类模型返回 `DAILY`；
- `DailyAgent` 的模型请求了 `getWeatherByLatLon`，工具返回一段虚构天气文本；
- 模型第二轮给出最终回答。

第 7 节会用这条输入走正常时间线。另一个输入“帮我从成都东站叫车到天府机场”只用于说明 ORDER 路由和 `notifyUser` 暂停的边界，不展开 C3 的确认协议。

---

## 二、分类：在 `ChatServiceImpl` 里，不在独立类

### 2.1 分类之前已经发生的事

分类不是请求的第一站。`ChatServiceImpl#chat` 先做：

1. `chatManager.isLocked(id)`：会话被显式锁定就 `notify` + `complete`；
2. `canStartChat`：`param` 为 null 或 prompt 空白 → `error` + `complete`；限流超限 → `notify` + `complete`；
3. `chatManager.assertOwned(userId, id, true)`：归属/清空检查；
4. Redis Hash `break=yes`：订单确认暂停中，提醒先确认/拒绝并结束。

这些分支都发生在分类之前，C1 已讲。分类只处理“已经允许继续的普通聊天”。

### 2.2 新会话与有历史会话的上下文

```java
// service/impl/ChatServiceImpl.java#chat（真实节选）
List<Message> messages = memory.get(userId, id, 100);
Object classRedis = redisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(id),
        "classification");
if (messages.isEmpty()) {
    classification = resolveClassification(
            SINGLE_TURN_CONTEXT, null, param.getPrompt());
} else {
    classification = resolveClassification(
            String.valueOf(classRedis), lastAssistantText(messages), param.getPrompt());
}
```

`SINGLE_TURN_CONTEXT` 的源码值是字符串 `"SINGLE_TURN"`。

| 场景 | `contextFeature` | `contextText` | 分类模型 user 消息 | 说明 |
|---|---|---|---|---|
| 新会话/历史为空 | `"SINGLE_TURN"` | `null` | 直接是本轮 `prompt` | 不拼接“上一轮路由/助理回复” |
| 有历史，Hash 有旧分类 | `String.valueOf(classRedis)`，例如 `"DAILY"` | `lastAssistantText(messages)` | `CLASSIFIER_USER_PROMPT` 格式化三段 | 旧分类是 Hash 字段，不是从消息内容推断 |
| 有历史，Hash 无分类 | `"null"`（`String.valueOf(null)`） | 最后 Assistant 文本或 `null` | 仍走多轮模板，`%s` 会打印 `null` | Hash 过期/缺失时的真实值 |
| 有历史，但没有 AssistantMessage，且旧分类不是 `SINGLE_TURN` | 同上 | `null` | 走多轮模板 | `lastAssistantText` 找不到就返回 null |
| 有历史，Hash 恰为 `"SINGLE_TURN"` 且无 Assistant 文本 | `"SINGLE_TURN"` | `null` | **直接使用 prompt，不格式化多轮模板** | 异常/外部状态：正常分类不会把 `"SINGLE_TURN"` 写进 Hash；这是 `resolveClassification` 的精确条件 `SINGLE_TURN.equals(contextFeature) && contextText == null` 导致的例外 |

`lastAssistantText` 的实现只找最后一个 `AssistantMessage` 的 `getText()`，不会读工具回执，也不会读 `ToolResponseMessage`。因此：

- 最后一条 Assistant 是“只带工具调用、内容为空”的消息时，`contextText` 可能是空字符串；
- 最后一条 Assistant 的文本为 null 时，`contextText` 是 null；
- 空字符串与 null 在缓存编码中不同（见 3.2）。

**因此不能简单说“有历史就一定走多轮模板”**：正常历史里 Hash 是合法标签（`ORDER/DAILY/SUPPORT/OTHER/DANGER`）或 `"null"`，会走 `CLASSIFIER_USER_PROMPT`；但如果 Hash 恰好是 `"SINGLE_TURN"` 且最后 Assistant 文本为 null，源码的直接路径条件成立，会像新会话一样只把 prompt 交给分类模型。这是异常/外部状态（正常路由不会把 `"SINGLE_TURN"` 写入 `classification`），并且它会与真正的新会话共享相同的缓存输入（见 3.1）。

### 2.3 `resolveClassification` 的顺序

```java
// service/impl/ChatServiceImpl.java#resolveClassification（真实节选）
private String resolveClassification(String contextFeature, String contextText, String prompt) {
    String cached = normalizeClassification(classificationCache.get(contextFeature, contextText, prompt));
    if (cached != null) {
        log.info("分类缓存命中，跳过模型调用：{}", cached);
        return cached;
    }

    String userMessage = SINGLE_TURN_CONTEXT.equals(contextFeature) && contextText == null
            ? prompt
            : CLASSIFIER_USER_PROMPT.formatted(contextFeature, contextText, prompt);
    long currentMillis = System.currentTimeMillis();
    String classification;
    try {
        classification = normalizeClassification(chatClient.prompt()
                .system(CLASSIFIER_SYS_PROMPT)
                .user(userMessage)
                .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL).temperature(0.5).build())
                .call().content());
    } catch (RuntimeException e) {
        log.warn("分类模型调用失败", e);
        return null;
    }
    log.info("分类耗时：{}ms，分类结果：{}", System.currentTimeMillis() - currentMillis, classification);

    if (classification != null) {
        classificationCache.put(contextFeature, contextText, prompt, classification);
    }
    return classification;
}
```

关键点：

- **先读缓存，再走模型**；缓存值也要 `normalize`，所以缓存里是 `"daily"` 也能命中为 `DAILY`。
- 缓存返回空白或非法标签时，`normalizeClassification` 返回 null，代码会继续调用模型；**不会删除**这个坏缓存值，但模型成功后会用同一个 key 覆盖写。
- 模型调用是同步的 `.call().content()`，不是 Agent 的 `stream`；它阻塞在 `chat-request-*` 线程（C1 的线程边界），不受 `AgentStreams` 的 3 分钟流超时保护。
- 模型抛 `RuntimeException` 时返回 null；`ChatServiceImpl#chat` 收到 null 后发 `message` 并 `complete`，不会 `initChat`、不会写 classification、不会调用 Agent。
- 模型返回空白/非法标签时同样返回 null；不会缓存。
- 只有合法标签才 `put`；`put` 的内容是规范化后的标签。

### 2.4 分类规范化

```java
// service/impl/ChatServiceImpl.java#normalizeClassification（真实节选）
private String normalizeClassification(String classification) {
    if (!StringUtils.hasText(classification)) {
        return null;
    }
    String normalized = classification.strip().toUpperCase(Locale.ROOT);
    return switch (normalized) {
        case "ORDER", "DAILY", "SUPPORT", "OTHER", "DANGER" -> normalized;
        default -> null;
    };
}
```

因此：

- `" daily\n"` → `DAILY`；
- `"UNKNOWN"`、`"DAILY。"`、空字符串 → null；
- 只有五个标签能进入后续分支；
- `switch` 的 `default` 分支在当前 `normalize` 约束下通常不可达，但代码仍保留“未知分类”提示，属于防御性分支。

### 2.5 DANGER、null 与旧 ORDER 的真实顺序

`ChatServiceImpl#chat` 的顺序不能压缩成“分类结果决定一切”：

```text
classification == null
  -> message("路由失败，请换种方式问问题。") + complete
  -> 不 initChat、不写 classification、不调用 Agent

notify(classification)
  -> classification == "DANGER"
     -> message("您的命令不被支持，请换个内容继续吧。") + complete
     -> 不 initChat、不写 classification、不调用 Agent

messages.isEmpty()
  -> chatManager.initChat(...)   // 首轮非 DANGER 才可能走到

classRedis != null && classRedis.equals("ORDER")
  -> 若新 classification != "ORDER"：notify("当前处于下单流程，如果您想换个话题，请新建对话。")
  -> orderAgent.invoke(...)
  -> return
  -> 不写新 classification、不进入 switch

其他情况
  -> 写 Hash classification
  -> switch ORDER/DAILY/SUPPORT/OTHER
```

要点：

- `null` 在 `notify` 之前结束，所以客户端不会看到分类标签；
- `DANGER` 在 `notify` 之后、`initChat` 之前结束，客户端可能先看到 `notify:"DANGER"`，再看到拒绝文本；
- 旧 `classRedis=ORDER` 分支只在 DANGER/null 之后；即使新分类是 `DAILY/SUPPORT/OTHER`，也不会进入对应 Agent，也不会写新分类；
- 旧分类分支只在 `classRedis` **不为 null 且字符串精确等于 `"ORDER"`** 时成立；`"order"` 不会命中；
- 旧分类是 ORDER 但新分类也是 ORDER 时，不发“换话题”提醒，仍调用 `OrderAgent`；
- 旧分类是 ORDER 但新分类是 DANGER 时，先走 DANGER 分支，不会调用 `OrderAgent`；
- `initChat` 在旧分类分支之前；若首轮历史为空且 Hash 里恰好已有旧 ORDER 分类，仍可能先写 `sys_chat`（C1 的保存表）。

### 2.6 路由例子

| 输入（虚构） | 历史 | `classRedis` | 分类结果 | 实际去向 | 是否写新 classification |
|---|---|---|---|---|---|
| 新会话：“成都今天天气” | 空 | null | `DAILY` | `DailyAgent` | 是，写 `DAILY` |
| 有历史：“帮我叫车去天府机场” | 非空，上一轮 DAILY | `DAILY` | `ORDER` | `OrderAgent` | 是，写 `ORDER` |
| 有历史：“今天天气怎么样” | 非空，正在下单 | `ORDER` | `DAILY` | `OrderAgent`，并先发换话题提醒 | 否，保持 `ORDER` |
| 有历史：提示词注入 | 非空，正在下单 | `ORDER` | `DANGER` | 拒绝文本，不调用 Agent | 否 |
| 新会话：模型返回未知标签 | 空 | null | null | 路由失败文本，不调用 Agent | 否 |

---

## 三、分类缓存：内容指纹，不是用户隔离

### 3.1 key 的真实构成

```java
// service/base/ClassificationCache.java#buildKey（真实节选）
private String buildKey(String contextFeature, String contextText, String prompt) {
    String raw = "v2" + encode(contextFeature) + encode(contextText) + encode(prompt);
    String contextFingerprint = fingerprint("CTXv2" + encode(contextFeature));
    return RedisKeyConstants.chatClassifyKey(contextFingerprint, fingerprint(raw));
}

private String encode(String value) {
    return value == null ? "-1:" : value.length() + ":" + value;
}
```

`RedisKeyConstants#chatClassifyKey` 的格式是：

```text
chat:classify:{contextFingerprint}:{payloadFingerprint}
```

其中：

- `contextFingerprint = SHA-256("CTXv2" + encode(contextFeature))` 的十六进制前缀；
- `payloadFingerprint = SHA-256("v2" + encode(contextFeature) + encode(contextText) + encode(prompt))` 的十六进制前缀；
- 指纹长度由 `chat.guard.fingerprint-length` 控制，默认 32，代码把值夹在 8 到 64 个十六进制字符之间（SHA-256 完整十六进制是 64）；
- **没有 userId、token、角色或租户字段**。

这意味着两个不同用户输入完全相同的上下文与 prompt 时，可能共享同一条分类缓存。分类结果只是“这句话属于哪类业务”，不是某个用户的数据；共享内容缓存不构成越权，但也不能把 key 当作身份隔离。

`buildKey` 在 `get`/`put` 的 Redis `try/catch` **之外**调用：`fingerprint` 依赖 `MessageDigest.getInstance("SHA-256")`，若运行环境异常，`IllegalStateException` 会从 `buildKey` 抛出，不会被“Redis 读/写失败”的 catch 吞掉。不要把“缓存异常都能降级”写成无条件的。

另外，key 最终是**截断的 SHA-256 十六进制前缀**，不是原始编码：长度前缀只解决拼接边界歧义，理论上仍存在哈希碰撞；两个不同输入通常得到不同 key，但这不是绝对保证。`ChatGuardRegressionTest` 验证的是这些输入的 key 不同（在其哈希未碰撞的前提下），不是数学上的永不碰撞。

### 3.2 null、空串和分隔符

`encode` 用“长度 + 冒号 + 原文”保留边界：

| 值 | 编码 |
|---|---|
| `null` | `-1:` |
| 空字符串 `""` | `0:` |
| `"A"` | `1:A` |
| `"A\0B"`（含 NUL） | `3:A\0B` |

所以：

- `null` 与空字符串不会合并成同一个 key；
- `("DAILY","A","B\0C")` 与 `("DAILY","A\0B","C")` 的拼接不会因为分隔符而混淆；
- `ChatGuardRegressionTest` 的 `embeddedNullCharactersCannotMergeContextAndPrompt`、`nullAndEmptyContextUseSeparateKeys`、`cacheSeparatesPreviousRoutesContextAndPrompts` 就是在验证这些边界（测试源码阅读，未运行）。

**长度是 Java `String.length()`，不是 Unicode 码点数**：它返回 UTF-16 代码单元数。例如 `"😀"`（U+1F600）在 Java 里 `length()==2`（一个代理对），但只有一个 Unicode 码点；`encode("😀")` 得到 `2:😀`。这不影响“同一条字符串的编码边界”判断，但不要把它解释成“字符个数”或“码点个数”。

### 3.3 TTL、默认值与禁用

`ClassificationCache` 的配置来自 `config/ChatGuardProperties.java`（`@ConfigurationProperties(prefix = "chat.guard")`）：

| 键 | 默认值 | 作用 |
|---|---|---|
| `chat.guard.classify-cache-ttl-seconds` | `21600`（6 小时） | 分类结果缓存 TTL |
| `chat.guard.fingerprint-length` | `32` | key 中 SHA-256 十六进制前缀长度 |
| `chat.guard.rate-limit-max` | `30` | 用户对话限流（C1） |
| `chat.guard.rate-limit-window-seconds` | `60` | 限流窗口（C1） |

`ClassificationCache#get`：

```java
if (properties.getClassifyCacheTtlSeconds() <= 0) {
    return null;
}
String key = buildKey(contextFeature, contextText, prompt);
try {
    String cached = redisTemplate.opsForValue().get(key);
    return StringUtils.hasText(cached) ? cached : null;
} catch (Exception e) {
    log.warn("读取分类缓存失败，降级为未命中: key={}", key, e);
    return null;
}
```

`ClassificationCache#put`：

```java
if (!StringUtils.hasText(classification) || properties.getClassifyCacheTtlSeconds() <= 0) {
    return;
}
String key = buildKey(contextFeature, contextText, prompt);
try {
    redisTemplate.opsForValue().set(key, classification,
            properties.getClassifyCacheTtlSeconds(), TimeUnit.SECONDS);
} catch (Exception e) {
    log.warn("写入分类缓存失败: key={}", key, e);
}
```

所以：

- **TTL <= 0 是禁用缓存**，不是“永不过期”：`get` 直接 null，`put` 直接返回，完全不访问 Redis；
- 每次 `put` 用配置的秒数设置 TTL；`get` 不续期；
- 空/空白分类不写；
- Redis 读失败 → 当未命中，继续调模型；
- Redis 写失败 → 只记日志，本次路由不受影响；
- **写缓存是尝试，不是保证覆盖**：`put` 的 `set` 可能失败，模型结果仍照常返回；下一次仍可能未命中；
- `buildKey` 在 Redis `try` 之外，key 构建/指纹计算的异常不属于“Redis 读写降级”；
- 缓存的值仍要经过 `normalizeClassification`，坏值不会被当作合法分类。

### 3.4 命中、未命中、非法值和异常

| 情况 | `ClassificationCache.get` | `resolveClassification` | 是否调分类模型 | 是否写缓存 |
|---|---|---|---|---|
| 合法缓存值 | 返回值 | normalize 后合法，直接 return | 否 | 否 |
| 缓存值是空白 | **null**（`hasText` 为 false） | 继续 | 是 | 模型合法才尝试写 |
| 缓存值非空但非法 | 返回原文 | normalize 为 null | 是 | 模型合法才尝试写（同 key 覆盖是尝试） |
| 未命中 | null | 继续 | 是 | 模型合法才尝试写 |
| Redis 读异常 | null（降级） | 继续 | 是 | 模型合法时尝试写 |
| Redis 写异常 | — | 模型结果照常返回 | 视缓存命中 | 写失败只记日志，不保证覆盖 |
| `buildKey` 异常 | 不会被 Redis catch 捕获 | 异常向上抛出 | 不确定 | 不写 |
| TTL <= 0 | null | 继续 | 是 | 不写 |
| 模型返回空白/非法 | — | null | — | 不写 |

**缓存不能保证分类一定正确**：内容相同只代表“分类模型看到的输入相同”，不代表模型判断正确，也不代表业务数据属于该用户。分类结果最终仍只决定路由，真正的身份、归属和权限检查在 C1/A2 的入口与工具内部。

### 3.5 一个 key 的计算示例

假设：

```text
contextFeature = "SINGLE_TURN"
contextText    = null
prompt         = "成都今天天气"
```

则拼接原文是：

```text
v2 + 11:SINGLE_TURN + -1: + 6:成都今天天气
```

`contextFingerprint` 是对 `CTXv2` + `11:SINGLE_TURN` 做 SHA-256 后截取 32 个十六进制字符；`payloadFingerprint` 是对上面整段原文做同样处理。具体哈希值取决于实现，本章不编造一个假值；你需要记住的是**输入、编码和两段指纹的结构**。

---

## 四、四个 Agent：共享骨架，不同工具

### 4.1 共享骨架

`OrderAgent`、`DailyAgent`、`SupportAgent` 都遵循同一套骨架（FallbackAgent 少了工具循环）：

```java
// agents/OrderAgent.java#invoke（真实节选）
public void invoke(Sinks.Many<AgentEvent> sink, String chatId, String userId, String userMessage) {
    List<Message> history = new ArrayList<>(messageMemory.get(userId, chatId, HISTORY_SIZE));
    int originalHistorySize = history.size()
            + (!history.isEmpty() && !(history.getFirst() instanceof SystemMessage) ? 1 : 0);

    String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
    String location = buildLocationString(userId);
    String sysPromptString = ORDER_AGENT_SYS_PROMPT.formatted(currentTime, location);

    if (history.isEmpty()) {
        history.add(new SystemMessage(sysPromptString));
    } else {
        Message firstMessage = history.getFirst();
        if (firstMessage instanceof SystemMessage) {
            history.set(0, new SystemMessage(sysPromptString));
        } else {
            history.addFirst(new SystemMessage(sysPromptString));
            sink.tryEmitNext(AgentEvent.notify("信息轮次过长，智能体性能将退化，建议您创建新对话。"));
        }
    }
    history.add(new UserMessage(userMessage));
    runLoopStep(history, originalHistorySize, sink, chatId, userId);
}
```

要点：

- `HISTORY_SIZE` 三个有循环的 Agent 都是 `100`；`MessageMemory.get` 内部还会按完整工具批次取窗口（C4）。
- 系统提示：历史为空时新增；历史第一条是 SystemMessage 时替换；否则 `addFirst` 并给用户一个“轮次过长”的 `notify`。
- `originalHistorySize` 的 `+1` 是为了把“即将插入的 SystemMessage”计入原始长度，避免把系统提示当成新增消息重复保存；具体保存范围见 6.4。
- 用户消息是 `UserMessage`，来自 `ChatServiceImpl` 的 prompt；它没有经过“系统提示词拼接”，而是作为独立消息加入历史。
- 三个 Agent 的系统提示都包含当前时间和用户位置；FallbackAgent只包含当前时间（见 4.6）。

### 4.2 对比表

| 项目 | OrderAgent | DailyAgent | SupportAgent | FallbackAgent |
|---|---|---|---|---|
| 触发分类 | `ORDER`，或旧 `classRedis=ORDER` 的提前分支 | `DAILY` | `SUPPORT` | `OTHER` |
| 职责 | 新订单下单全流程：槽位、完整性、路线/价格、确认、创建 | 日常咨询：天气、地点、POI、估时估价 | 订单售后、工单、知识库、取消/改目的地 | 无关闲聊，引导回出行业务 |
| 历史读取 | `messageMemory.get(userId,chatId,100)` | 同左 | 同左 | 同左 |
| 系统提示 | `ORDER_AGENT_SYS_PROMPT`，含时间/位置、标准流程、订单字段 | `DAILY_AGENT_SYS_PROMPT`，含时间/位置、工具清单 | `SUPPORT_AGENT_SYS_PROMPT`，含时间/位置、RAG/订单/工单 SOP | `OTHER_CLIENT_SYS_PROMPT`，含当前时间，只做业务引导 |
| 工具注册 | `OrderTool`、`POISearchTool`、`ToolRepPointerTool`、`TrafficTool`、`UserPOISearchTool`、`GeoRegeoTool` | `CurWeatherTool`、`GeoRegeoTool`、`POISearchTool`、`UserPOITool`、`DailyTool`、`ToolRepPointerTool`、`POIAroundTool` | `OrderSearchTool`、`TicketTool`、`ToolRepPointerTool`、`RagTool` | 无 |
| 手写分派 | `executeTool` 的订单/POI/航班/用户POI/地理分支；`notifyUser` 特判 | `executeTool` 的天气/地理/POI/用户POI/估时估价/`getCalling` 分支 | `executeTool` 的订单/工单/RAG/`getCalling` 分支 | 无 `executeTool` |
| 模型 | `deepseek-chat`（`OpenAiChatOptions`） | 同左 | 同左 | 同左 |
| 工具自动执行 | `internalToolExecutionEnabled(false)` | 同左 | 同左 | 同左，但没有工具注册 |
| 循环 | `runLoopStep` 递归，**新增历史消息**超过 200 条终止（含 User/Assistant/Tool 全部消息） | 同左 | 同左 | 无循环，只调用一次模型流 |
| 结束/保存 | 纯文本成功/`finalMsg==null` 时尝试 `saveNewMessages`，成功后再尝试 complete；`notifyUser` 的保存可能失败；模型流错误路径不保存 | 同左（无暂停） | 同左（无暂停） | 完成时尝试保存并 complete；错误路径不保存 |
| `/r` 恢复 | `OrderAgent#resume` 专属 | 无 | 无 | 无 |

**不要强求四者结构相同**：FallbackAgent 没有工具、没有 `runLoopStep`、没有 `ToolContext`；OrderAgent 多了 `resume` 和 `notifyUser` 暂停；DailyAgent 多了写入型用户 POI 工具；SupportAgent 多了 RAG 和工单工具。

### 4.3 OrderAgent

OrderAgent 的注册工具来自 `OrderTool`（`saveNewOrderParam`、`isNewOrderReady`、`getEstRouteAndPrice`、`explainPrice`、`notifyUser`、`markOrderReadyForCreate`、`createOrder`）、`POISearchTool`、`ToolRepPointerTool`、`TrafficTool`、`UserPOISearchTool`、`GeoRegeoTool`。

其中与 ORDER 业务最相关的是：

- `saveNewOrderParam`：把模型给出的槽位写 Redis；会删除旧报价/确认状态；参数值不做完整业务校验。
- `isNewOrderReady`：检查缺失字段，校验车型/预约/加急/预约时间/坐标，删除格式错误字段；全部通过时写 `ReadyforRoute=true`。
- `getEstRouteAndPrice`：要求 `ReadyforRoute`；可调用高德路线、写 Mongo 预览轨迹、算价，然后调用 `orderConfirmation.quoted(chatId)`；返回文本邀请模型调用 `notifyUser`。
- `notifyUser`：工具定义存在，但 `OrderAgent#handleToolCalls` 会先特判它，不进入普通 `executeTool`；`OrderAgent#handleNotifyUser` 先读取订单 Hash，再在 try 内调用 `orderConfirmation.begin`（只要求 `quoteId` 与 `ReadyforConfirm` 存在，并设置 `break=yes`/`pendingToolCall`；**不检查报价年龄/金额/用户授权**），再序列化 JSON、`saveNewMessages`、再写 `break=yes`、尝试发 `confirm`/`complete`（C3）。
- `markOrderReadyForCreate`：只检查 `orderConfirmation.isConfirmed(chatId)`，返回“可以创建”或“尚未取得确认”；它**不签发**创建权限。
- `createOrder`：检查 Redis `ReadyForCreate`，从 Redis 槽位构建 `CreateOrderDTO`，调用 `OrderConfirmation.consume`，再调用 `RideOrderService#createOrder`，最后锁定会话并写 `OrderId`。

OrderAgent 的普通工具回执可能触发数据库/Redis/Mongo/HTTP 副作用；模型输出只是请求，真正的执行和校验在工具方法里。C3 会完整讲确认和消费，C2 只需记住：**工具返回“订单已创建”来自服务端真实创建，不是模型自己说的**。

### 4.4 DailyAgent

DailyAgent 的注册工具：

- `CurWeatherTool`：`getWeatherByLatLon`、`get3dayWeatherForecastByLatLon`、`getRain2hForecastByLatLon`、`getWeatherAlertsByLatLon`、`getAirQualityByLatLon`；
- `GeoRegeoTool`：`geo`、`regeo`；
- `POISearchTool`：`searchPOIsByKeyword`；
- `UserPOITool`：`listUserPOIs`、`getUserPOIDetailByTag`、`searchUserPOIDetail`、`createNewPOI`、`updateUserPOIInfo`、`deleteUserPOI`；
- `DailyTool`：`estimateBufferTime`、`estimatePrice`；
- `ToolRepPointerTool`：`getCalling`；
- `POIAroundTool`：`searchAroundPOI`。

`executeTool` 的分支与注册基本一一对应；用户 POI 的读写都用 ToolContext 里的 `userId`。天气/地理工具接受模型给出的经纬度或地址；它们是外部服务调用，成功与返回内容不由分类结果保证。

### 4.5 SupportAgent

SupportAgent 的注册工具：

- `OrderSearchTool`：`searchOrderById`、`searchOrdersByTime`、`searchOrdersByCount`、`verifyCancelConditions`、`cancelOrder`、`modifyOrderDestination`；
- `TicketTool`：`createTicket`、`queryTicketProgress`、`queryUnfinishedTickets`、`escalateTicket`、`appendUserMessage`；
- `ToolRepPointerTool`：`getCalling`；
- `RagTool`：`searchKnowledgeBase`。

`executeTool` 的 `searchKnowledgeBase` 接受 `queryStr`、`query`、`question` 三种参数名，缺参时返回 `"Missing required argument: queryStr"`；这属于手写分派的兼容处理，不是工具 schema 本身有三个参数。工具内部的订单归属、工单归属和取消前置条件由具体服务/工具检查，C2 不逐一展开。

### 4.6 FallbackAgent 没有工具循环

```java
// agents/FallbackAgent.java#invoke（教学节选；SystemMessage 处理已省略，见源码）
public void invoke(Sinks.Many<AgentEvent> sink, String chatId, String userId, String userMessage) {
    List<Message> history = new ArrayList<>(messageMemory.get(userId, chatId, HISTORY_SIZE));
    // 此处省略 SystemMessage 的新增/替换/头部插入与“轮次过长”notify
    history.add(new UserMessage(userMessage));
    Prompt prompt = new Prompt(history, OpenAiChatOptions.builder()
            .model("deepseek-chat")
            .topP(0.7)
            .temperature(0.7)
            .internalToolExecutionEnabled(false)
            .build());
    AgentStreams.subscribe(this.openAiChatModel.stream(prompt), sink,
            chatResponse -> { /* 发 message、累积 contentBuffer */ },
            error -> { /* error 事件 + tryEmitError */ },
            () -> { /* 保存新增消息 + tryEmitComplete */ });
}
```

FallbackAgent：

- 没有工具字段、没有 `ToolCallbacks.from`、没有 `executeTool`、没有 `runLoopStep`；
- 只调用一次 `openAiChatModel.stream(prompt)`；
- 完成回调把 `AssistantMessage` 追加到历史，尝试 `saveNewMessages`，保存成功后才尝试 `complete`；
- 没有 200 条工具循环阈值，也没有 `/r` 的 `resume`；
- 模型若仍返回工具调用（没有注册工具时理论上不应发生），代码不会执行工具，只会把 AssistantMessage 加入历史；保存和 complete 仍是带前提的调用/尝试。

### 4.7 模型与提示词

- 三个业务 Agent 的 `OpenAiChatOptions` 都显式 `model("deepseek-chat")`；FallbackAgent 也是 `deepseek-chat`，但多了 `topP(0.7)`、`temperature(0.7)`。
- 这些 Agent 注入的是 `ChatModel openAiChatModel`；真实连接由 Spring AI 的 OpenAI 兼容配置提供，`src/main/resources/application.yaml` 的 `spring.ai.openai.base-url` 指向 DeepSeek，API key 是占位符；具体凭据不属于教材内容。
- 分类模型走的是 `ChatClient`：`ChatServiceImpl` 注入 `@Qualifier("dashScopeChatModel") ChatModel`，构建 `ChatClient` 时设置默认模型 `qwen-plus-2025-12-01`、`topP(0.7)`，但分类调用用 `DashScopeChatOptions.model("qwen3-max-preview").temperature(0.5)` 覆盖。源码常量是 `ROUTER_DEFAULT_MODEL` 与 `CLASSIFIER_MODEL`。
- `config/ChatProperties.java` **不是** `@ConfigurationProperties`，它只是存放静态提示词字符串的类；真正绑定配置的是 `ChatGuardProperties`、`AuthProperties`、`RideOrderTimeoutProperties` 等。
- 系统提示词是模型指令，不是服务端授权。提示词里写“必须确认后创建”不代表模型可以绕过 `OrderConfirmation`；真正的创建门禁在 `OrderTool#createOrder`、`OrderConfirmation#consume` 和订单服务里（C3）。

---

## 五、工具注册、分派与执行

### 5.1 三件事要分开

| 层次 | 在代码里是什么 | 例子 |
|---|---|---|
| 工具类存在 | 一个 `@Component` 类里有 `@Tool` 方法 | `RegeoTool`、`ChatStatusTool`、`OrderTool` |
| 注册 | Agent 把对象传给 `ToolCallbacks.from(...)`，模型能看到 schema | `OrderAgent` 注册 `OrderTool`、`GeoRegeoTool` 等 |
| 分派 | Agent 的 `executeTool` 的 `switch`/特殊分支按 `ToolCall.name()` 真正执行工具 | `OrderAgent` 的 `saveNewOrderParam` 等 case |
| 正常模型请求 | 模型在注册 schema 下返回一个 `ToolCall.name()` | 注册决定模型“看得到什么”，但不是 `executeTool` 执行的必要条件 |

`ToolCallbacks.from` 只负责从 `@Tool` 方法生成工具定义；它不会替 Agent 执行 `switch` 里的逻辑。所有业务 Agent 都设置 `internalToolExecutionEnabled(false)`，因此 Spring AI 不会自动执行工具，执行完全由 Agent 手写代码完成。

**注册与分派不是同一张表**：`executeTool` 只按 `toolName` 走 `switch`/特殊分支，**不会在执行点再次检查该名字是否在 `ToolCallbacks` 注册列表里**。因此：

- 正常模型只能看到注册过的工具 schema，所以正常请求一般只会返回注册名；
- 但如果模型/调用方给出了一个“未注册但 `switch` 有 case”的名字，代码仍可能执行该 case；
- 反之，注册了但 `switch` 没有 case 的名字会落到 `"Unknown tool: ..."`；
- “注册”是正常模型请求的入口条件，不是手写分派的绝对必要条件。不要把“注册+分派+模型请求同时满足”写成数学上的充要条件。

### 5.2 `OrderAgent` 的注册与分派

```java
// agents/OrderAgent.java#runLoopStep（真实节选；用 ... 省略 toolListener/历史等上下文）
List<ToolCallback> callbacks = List.of(ToolCallbacks.from(
        orderTool, poiSearchTool, toolRepPointerTool, trafficTool, userPOISearchTool, geoRegeoTool));
...
Prompt prompt = new Prompt(history, OpenAiChatOptions.builder()
        .model("deepseek-chat")
        .toolCallbacks(callbacks)
        .toolContext(Map.of(
                ToolContextKeyConstants.TOOL_LISTENER, toolListener,
                ToolContextKeyConstants.USER_ID, userId,
                ToolContextKeyConstants.CHAT_ID, chatId))
        .internalToolExecutionEnabled(false)
        .build());
```

| 注册对象 | 注册出的工具 | `executeTool` 分派 |
|---|---|---|
| `OrderTool` | `saveNewOrderParam`、`isNewOrderReady`、`getEstRouteAndPrice`、`explainPrice`、`notifyUser`、`markOrderReadyForCreate`、`createOrder` | 除 `notifyUser` 外都有 case；`notifyUser` 由 `handleToolCalls` 特判 |
| `POISearchTool` | `searchPOIsByKeyword` | 有 |
| `ToolRepPointerTool` | `getCalling`（`@Tool(name="getCalling")`） | 有 |
| `TrafficTool` | `getFlightInfo` | 有 |
| `UserPOISearchTool` | `listUserPOIs`、`getUserPOIDetailByTag`、`searchUserPOIDetail` | 有（只读） |
| `GeoRegeoTool` | `geo`、`regeo` | 有 |

`notifyUser` 的特殊性：

- 它被 `OrderTool` 注册，模型能看到；
- `handleToolCalls` 在遍历工具调用时先判断 `toolName.equals("notifyUser")`，不会调用 `executeTool`；
- 同批中位于 `notifyUser` **之前**的普通工具已经按顺序执行，可能已产生数据库/Redis/Mongo/HTTP 副作用；只有 `notifyUser` **之后**的工具才被标成“尚未执行：请等待用户确认后按需重新调用”；
- `notifyUser` 本身的工具应答不会在此时追加，而是等用户在 `/r` 反馈后由 `OrderAgent#resume` 补成 `ToolResponseMessage`（C3）；
- `OrderAgent#handleNotifyUser` 先读取订单 Hash（在 try 外），再在 try 内调用 `orderConfirmation.begin(chatId, toolCall.id())`（只要求 `quoteId` 与 `ReadyforConfirm` 存在，设置 `break=yes`/`pendingToolCall`，不检查报价年龄/金额/用户授权），然后序列化订单 JSON、`saveNewMessages`、再写 `break=yes`，最后尝试发 `confirm`、`complete`；任一步失败可能留下不同的 Hash/历史状态，无法保证自动回滚或暂停前消息必然保存；
- 因此 `OrderTool#notifyUser` 方法体在这个路径里没有被执行；报价/确认状态由 `begin` 和 Redis 状态检查，C3 主讲。

### 5.3 `DailyAgent` 的注册与分派

| 注册对象 | 主要工具 | 分派 |
|---|---|---|
| `CurWeatherTool` | 当前天气、三天预报、2 小时降水、预警、空气质量 | 5 个 case |
| `GeoRegeoTool` | `geo`、`regeo` | 有 |
| `POISearchTool` | `searchPOIsByKeyword` | 有 |
| `UserPOITool` | `listUserPOIs`、`getUserPOIDetailByTag`、`searchUserPOIDetail`、`createNewPOI`、`updateUserPOIInfo`、`deleteUserPOI` | 有 |
| `DailyTool` | `estimateBufferTime`、`estimatePrice` | 有 |
| `ToolRepPointerTool` | `getCalling` | 有 |
| `POIAroundTool` | `searchAroundPOI` | 有 |

### 5.4 `SupportAgent` 的注册与分派

| 注册对象 | 主要工具 | 分派 |
|---|---|---|
| `OrderSearchTool` | `searchOrderById`、`searchOrdersByTime`、`searchOrdersByCount`、`verifyCancelConditions`、`cancelOrder`、`modifyOrderDestination` | 6 个 case |
| `TicketTool` | `createTicket`、`queryTicketProgress`、`queryUnfinishedTickets`、`escalateTicket`、`appendUserMessage` | 5 个 case |
| `ToolRepPointerTool` | `getCalling` | 有 |
| `RagTool` | `searchKnowledgeBase` | 有（兼容 `queryStr/query/question` 参数名） |

### 5.5 FallbackAgent：没有工具

`FallbackAgent` 没有 `ToolCallback` 列表，没有 `ToolContext`，也没有 `executeTool`。因此“模型请求工具”在 FallbackAgent 上没有服务端执行路径；它只能输出文本。

### 5.6 “类存在”不等于“能走到”

- `RegeoTool` 有 `@Tool` 的 `regeo`，但它没有被任何 Agent 注入；Agent 使用的是 `GeoRegeoTool`。
- `ChatStatusTool` 有 `lockSession`，也没有被任何 Agent 注册；模型无法通过它锁定会话。
- `OrderAgent` 不注册 `DailyTool`、天气、`POIAroundTool`、`RagTool`、`TicketTool`、`OrderSearchTool`；`DailyAgent` 不注册订单/工单/RAG；`SupportAgent` 不注册天气/用户 POI 写入/下单工具。
- `OrderAgent` 注册了 `notifyUser`，但分派是特殊分支；`OrderAgent` 注册了 `getCalling`，有普通 case。
- 工具 schema 可能会出现，但 `switch` 的 `default` 仍会把未知名字变成 `"Unknown tool: ..."` 文本，而不是异常；模型可能据此重试。

### 5.7 参数是不可信输入

`executeTool` 从 `ToolCall.arguments()` 得到 JSON 字符串，用 `ObjectMapper.readTree` 解析，再按字段取值：

```java
case "getWeatherByLatLon" -> {
    double lat = rootNode.get("lat").asDouble();
    double lon = rootNode.get("lon").asDouble();
    return curWeatherTool.getWeatherByLatLon(lat, lon, toolContext);
}
```

这意味着：

- 模型可以给出错误、缺失、类型不对或恶意的参数；
- 没有字段时可能得到 0/null，或者抛异常；
- **只有 `executeTool` 的 try 内捕获的 `Exception`** 会被变成 `"Tool execution failed: " + e.getMessage()` 的工具回执；`handleToolCalls` 里的 `recordCalling`、`ToolResponseMessage` 构造、历史列表操作、递归调用等不在这个 try 内，不能笼统保证“任何工具相关异常都变成回执继续”；
- `recordCalling` 对 `callId` 为空、`response` 为 null、缺少 `chatId` 的情况直接跳过；它只捕获 `RuntimeException`，`ToolResponseMemory.save` 的 MySQL 插入与 Redis 写入是两个不同边界（DB 失败会抛出后被记录日志，Redis 缓存失败在 `cache` 内单独捕获）；
- 未知工具名返回 `"Unknown tool: ..."`，也不会自动终止；
- 服务端没有把模型参数当作身份、权限或授权；身份在 `ToolContext` 里，由服务端传入。

### 5.8 工具回执与 `getCalling`

每次普通工具执行后，Agent 会：

1. 调用工具方法得到字符串；
2. 调用 `toolRepPointerTool.recordCalling(toolCall.id(), result, toolContext)`，尝试把结果另存到 `ToolResponseMemory`（MySQL + Redis `chat:tool:v2:{chatId}:{callId}`，Redis TTL 24 小时）；无效参数会跳过，DB 插入或 Redis 缓存的 `RuntimeException` 分别被捕获记录，其他异常不保证被吞掉；
3. 构造 `ToolResponseMessage.ToolResponse(id, name, result)`，加到一个 `ToolResponseMessage`，追加到历史；
4. 递归 `runLoopStep`，把包含工具回执的历史再次交给模型。

这里有三层不同副作用，不能合成一句“工具结果已保存”：

- **工具执行**：工具方法可能改 MySQL/Redis/Mongo/外部服务，发生在 `executeTool` 内；
- **辅助索引**：`recordCalling` 可能在中间轮就写 `ToolResponseMemory` 的 MySQL/Redis，即使聊天历史还没 `saveNewMessages`；
- **聊天历史回执**：`ToolResponseMessage` 先只存在内存 `history`，要等最终 `saveNewMessages` 成功才进入消息表。

`getCalling` 工具本身：

- 名字由 `@Tool(name = "getCalling")` 显式指定；
- 从 `ToolContext` 取 `chatId/userId`，先 `chatManager.assertOwned(userId, chatId, false)`；
- 再按 `callId + chatId` 读 `ToolResponseMemory`；
- 找不到会返回 null/空结果（具体由 memory 返回）。

提示词要求“历史工具结果可能只显示 `call_id` 时，用 `getCalling`”。当前 `MessageParser`/`MessageWindow` 保存和读取的是完整 `ToolResponseMessage`，本章没有看到把工具回执替换成 `call_id: ...` 的代码路径；因此不能把“历史一定只有指针”当成事实。C4 会继续核验工具结果反查与历史窗口。

---

## 六、工具循环：模型请求、服务端执行、再请求

### 6.1 `runLoopStep` 的回调

```java
// agents/DailyAgent.java#runLoopStep（真实节选；省略 contentBuffer/assistantMessageRef 等声明，Order/Support 同构）
AgentStreams.subscribe(this.openAiChatModel.stream(prompt), sink,
        chatResponse -> {
            Generation generation = chatResponse.getResult();
            if (generation == null) return;
            AssistantMessage msg = generation.getOutput();
            String content = msg.getText();
            if (content != null && !content.isEmpty()) {
                sink.tryEmitNext(AgentEvent.message(content));
                contentBuffer.append(content);
            }
            assistantMessageRef.set(msg);
        },
        error -> {
            log.error("[DailyAgent] Chat Error", error);
            sink.tryEmitNext(AgentEvent.error(error.getMessage()));
            sink.tryEmitError(error);
        },
        () -> {
            AssistantMessage finalMsg = assistantMessageRef.get();
            if (finalMsg == null) {
                saveNewMessages(userId, chatId, history, originalHistorySize);
                sink.tryEmitComplete();
                return;
            }
            AssistantMessage build = AssistantMessage.builder()
                    .content(contentBuffer.toString())
                    .properties(finalMsg.getMetadata())
                    .toolCalls(finalMsg.getToolCalls())
                    .build();
            history.add(build);
            List<AssistantMessage.ToolCall> toolCalls = finalMsg.getToolCalls();
            if (!toolCalls.isEmpty()) {
                handleToolCalls(toolCalls, history, originalHistorySize, sink, chatId, userId);
            } else {
                saveNewMessages(userId, chatId, history, originalHistorySize);
                sink.tryEmitComplete();
            }
        });
```

### 6.2 一轮循环的精确步骤

1. 用当前 `history` 构造 `Prompt`，注册工具，设置 `ToolContext`，`internalToolExecutionEnabled(false)`。
2. `openAiChatModel.stream(prompt)` 返回模型流；`AgentStreams#subscribe` 订阅并加 3 分钟超时。
3. `next` 回调处理每个 `ChatResponse`：有文本就发 `message` 并累积；保存最后一个 `AssistantMessage` 到 `assistantMessageRef`。
4. 模型流完成时，`complete` 回调：
   - `finalMsg == null`：尝试保存已有新增历史；保存成功后再尝试完成；
   - 否则用累积文本、metadata、toolCalls 构造最终 `AssistantMessage`，加入历史；
   - 没有 toolCalls：尝试保存新增消息，成功后再尝试完成；
   - 有 toolCalls：`handleToolCalls`（可能产生工具/索引副作用，聊天历史仍要等最终保存）。
5. `handleToolCalls` 遍历工具调用：
   - `notifyUser`（仅 OrderAgent）：把后续工具的应答标记为“尚未执行”，进入暂停；
   - 普通工具：`executeTool` → `recordCalling` → 收集 `ToolResponse`；
   - 整批工具结束后，构造一个 `ToolResponseMessage`，加入历史；
   - 递归 `runLoopStep`，下一轮模型看到工具回执。
6. 回到步骤 1，直到没有工具调用、错误、超时、达到阈值或暂停。

### 6.3 终止条件

| 条件 | 发生位置 | 结果 | 是否保存消息 |
|---|---|---|---|
| 模型完成且没有工具调用 | 完成回调 | `saveNewMessages` 成功后才尝试 `tryEmitComplete`；两者都可能失败 | 保存成功则写入新增部分；失败则不一定 |
| `finalMsg == null` | 完成回调 | 同上；通常只有用户/系统消息 | 同上 |
| 工具调用批次完成 | `handleToolCalls` | 回执加入内存历史，递归下一轮 | 中间轮不调用 `saveNewMessages` |
| 新增历史消息数 `> 200` | 每次 `runLoopStep` 开头 | `error` 事件 + `tryEmitComplete`（尝试） | 否 |
| `OrderAgent` 的 `notifyUser` | `handleToolCalls` 特判 | Hash 读取在 try 外；try 内 `begin` → JSON → 保存 → 写 break → 尝试 confirm/complete | 只有保存成功才可能已写消息；confirm/complete 仍是尝试 |
| 模型流错误/超时（onComplete 前） | `AgentStreams` failure consumer | 先 `guard` 调 Agent error，再尝试 `sink.tryEmitError`；complete 可能未执行 | 否（该失败路径不保存）；此轮 ToolCall 不执行 |
| 工具执行抛异常（仅 `executeTool` try 内） | `executeTool` catch | 返回 `"Tool execution failed: ..."` 回执，继续 | 由后续终态决定 |
| `recordCalling`/回执构造/其他回调异常 | `handleToolCalls`/回调链 | 不属于 `executeTool` 的 catch；可能冒泡到完成回调/错误消费者 | 不确定；辅助索引可能已写 MySQL/Redis |
| 未知工具名 | `executeTool` default | 返回 `"Unknown tool: ..."` 回执，继续 | 由后续终态决定 |
| 保存失败 | `saveNewMessages` → `messageMemory.save` | 异常进入错误消费者；MySQL 可能已写入，Redis/heap 可能失败 | 不能断言完整保存或回滚 |

`history.size() - originalHistorySize > 200` 是**新增消息条数**的阈值，不是工具调用次数：一轮模型可能一次返回多个 `ToolCall`，这些都会在同一次 `handleToolCalls` 里执行；如果单批工具很多，阈值不会在批次内部逐个检查。阈值检查在每轮 `runLoopStep` 开头，超过就终止。FallbackAgent 没有 `runLoopStep`，不适用。

### 6.4 `originalHistorySize` 与保存范围

`saveNewMessages` 只保存 `history.subList(originalHistorySize, history.size())`：

- 历史为空：`originalHistorySize=0`，保存范围包含 SystemMessage、用户消息、助手回复和工具回执；
- 历史第一条是 SystemMessage：`originalHistorySize=size`，替换系统提示不算新增，保存范围包含用户+助手+工具回执；
- 历史第一条不是 SystemMessage：`originalHistorySize=size+1`，新增的 SystemMessage 不计入保存范围，保存范围包含用户+助手+工具回执。

这意味着“模型看到的系统提示”不一定每次都会重新写入数据库；不要把 `history` 的长度直接当成“本轮新增消息数”。C1/C4 对保存链有更完整的说明。

**保存/事件都有前提**：`saveNewMessages` 只是调用 `messageMemory.save`；只有它不抛异常，才谈“已保存”。`MessageMemory.save` 先写 MySQL，再尝试 Redis/heap；MySQL 可能已提交而 Redis/heap 失败，不能把“方法返回”与“所有层成功”画等号。`tryEmitNext`/`tryEmitComplete`/`tryEmitError` 都是 sink 尝试，返回值没有检查；客户端是否收到还要看连接和响应状态。

### 6.5 `notifyUser` 暂停是 C3 的前置

OrderAgent 的 `notifyUser` 不是普通工具：

- 它在模型返回的 `ToolCall` 里出现时，同批**之前**的普通工具已经执行并可能产生副作用；`handleToolCalls` 只给同批**后续**工具写“尚未执行”的应答；
- `notifyUser` 自己的工具应答不在此刻追加，等 `/r` 的用户反馈后由 `OrderAgent#resume` 补成 `ToolResponseMessage`（C3）；
- `handleNotifyUser` 先读取订单 Hash（在 try 外；若这次 Redis 读取抛异常，不会进入该方法的 catch，而会冒泡到完成回调/failure consumer），再在 try 内按 `begin` → 序列化订单 JSON → `saveNewMessages` → 再写 `break=yes` → 尝试 `confirm`/`complete` 的顺序执行；
- `begin` 只检查 `quoteId` 和 `ReadyforConfirm` 存在，并在通过后写 `pendingToolCall`/`break`；它不校验报价年龄、金额或用户授权（C3 讲确认/消费的时效与权限）；
- 只有 `begin`、序列化、保存都成功，才会走到发送 `confirm` 的尝试；每一步都可能抛异常，catch 会尝试发 error 并 complete，但 Hash/历史/辅助索引可能处于不同状态，不能承诺自动回滚或暂停前消息必然保存；
- 它不进入下一轮模型；用户确认/拒绝后走 `/chat/v2/r/{id}` 的 `OrderAgent#resume`（C3）。

---

## 七、可达时间线

### 7.1 T1：正常工具调用并进入下一轮

前提（假设）：会话有历史，旧分类 `DAILY`，用户问天气；分类缓存未命中，分类模型返回 `DAILY`；模型请求 `getWeatherByLatLon`，工具成功；没有超时/断开；HTTP 下游已订阅。

| 阶段 | 所在线程（按调用推导，未实测） | 动作与可见状态 |
|---|---|---|
| t0 | `chat-request-*` | `ChatServiceImpl#chat` 通过锁定/限流/归属/break；`memory.get` 返回历史；`classRedis=DAILY` |
| t1 | `chat-request-*` | `resolveClassification("DAILY", 最后Assistant文本, prompt)`：缓存 miss → 同步分类模型 → `DAILY`；`notify("DAILY")` |
| t2 | `chat-request-*` | 历史非空，不 `initChat`；尝试写 Hash `classification=DAILY`；`switch` 调 `DailyAgent.invoke` |
| t3 | `chat-request-*` | Agent 构造历史+系统提示+UserMessage；`openAiChatModel.stream(prompt)` 返回 Flux；`AgentStreams#subscribe` 订阅 |
| t4 | 模型客户端/Reactor 回调线程（不保证固定） | 模型先返回文本片段和 `ToolCall(getWeatherByLatLon)`；`message` 事件尝试写入 sink；`contentBuffer` 累积 |
| t5 | 同一回调链 | 模型流完成，完成回调构造 AssistantMessage，发现 toolCalls；`handleToolCalls` 调 `executeTool` |
| t6 | 工具执行线程（由回调链决定，未知） | `CurWeatherTool` 调外部天气服务；工具内 `ToolNotifySupport` 发 `tool_start`；返回天气文本；`recordCalling` 另存结果；构造 `ToolResponseMessage` |
| t7 | 递归 `runLoopStep` 的当前线程 | 历史包含用户/助手/工具回执；第二次 `openAiChatModel.stream`；模型返回纯文本 |
| t8 | 模型回调线程 | 无 toolCalls → 若 `saveNewMessages`（用户+助手+工具回执+最终助手）成功，再尝试 `tryEmitComplete`；保存/事件都可能失败 |
| t9 | 发出 sink 终态的线程 | `doFinally` 发起 `unlockAsync`（C1）；客户端看到事件/连接结束，不能由此确认保存或解锁成功 |

**假设与不可观测项**：分类缓存是否命中、模型是否真的请求该工具、天气服务返回什么、工具/保存/解锁的实际结果、事件是否到达客户端、模型回调与工具的实际线程。所有事件都是示意。

### 7.2 T2a：当前轮 onComplete 之前模型流出错

**前提**：当前这一轮模型流还没有执行 `onComplete`，可能已经收到文本片段或 `ToolCall` 片段，但随后 `onError`；或 `AgentStreams` 的 3 分钟 `takeUntilOther` 触发 `TimeoutException("模型响应超时")`。

| 阶段 | 动作 | 结果 |
|---|---|---|
| f0 | 模型流 `onError` 或超时 | 进入 `AgentStreams#subscribe` 的 failure consumer |
| f1 | failure consumer 先调用 `guard(sink, () -> error.accept(failure))` | Agent 的 error 回调此时才被调用：发 `AgentEvent.error(error.getMessage())`（尝试），再尝试 `sink.tryEmitError(error)` |
| f2 | failure consumer 随后再尝试 `sink.tryEmitError(failure)` | 第二次终止也是尝试；顺序不是“Agent 回调先于消费者” |
| f3 | 工具执行 | `complete` 没有执行，`finalMsg.getToolCalls()` 没有被读取，`handleToolCalls` 没有被调用；**当前轮已收到的工具调用片段不会因这个失败路径执行** |
| f4 | 消息/辅助索引 | 该错误回调不调用 `saveNewMessages`；本轮没有新的 `ToolResponseMessage`，也没有本轮新 `recordCalling`；如果本轮之前已有工具执行，那些副作用仍可能存在（见 7.3） |
| f5 | 客户端/锁 | 可能看到 error 事件或只看到连接结束；若 HTTP 响应已提交，则不能再改成 500；是否已提交未实测；连接后的 sink 终态仍会尝试 `doFinally` 解锁（C1） |

**不要用“模型报错”推断“工具一定没执行”**：这条时间线只保证**当前轮尚未 complete 的工具批次**没有执行；更早轮次已经执行过的工具和辅助索引不会因为这个错误自动回滚。

### 7.3 T2b：上一轮工具已执行，下一轮模型流出错

**前提**：第一轮模型正常 `onComplete`，`handleToolCalls` 已经执行了普通工具并形成 `ToolResponseMessage`；随后递归进入下一轮模型流，这一轮在 `onComplete` 前 `onError`/超时。

| 阶段 | 动作 | 结果 |
|---|---|---|
| g0 | 第一轮 `complete` | 构造 AssistantMessage；`handleToolCalls` 执行工具；`executeTool` 返回结果；`recordCalling` 可能写 `ToolResponseMemory`；`ToolResponseMessage` 加入内存 `history` |
| g1 | 工具副作用 | 写型工具（订单/工单/POI/取消等）可能已经改 MySQL/Redis/Mongo/外部服务；读型工具也可能已调用外部 HTTP；这些副作用不因下一轮模型错误回滚 |
| g2 | 下一轮模型流 | 以包含工具回执的历史再次 `openAiChatModel.stream`；在 `onComplete` 前 `onError`/超时 |
| g3 | failure consumer | 先 `guard` 调 Agent error，再尝试 `tryEmitError`；`complete` 不执行 |
| g4 | 消息 | 上一轮的 `ToolResponseMessage` 只在内存历史中；`saveNewMessages` 尚未执行，聊天消息表可能没有本轮新增（此前轮次可能已保存过） |
| g5 | 辅助索引 | `recordCalling` 可能在上一轮已经写 MySQL/Redis；即使聊天消息未保存，辅助工具结果也可能已持久化（C4 讲反查） |
| g6 | 客户端/锁 | 与 7.2 相同：事件和错误终态是尝试，HTTP 状态不可承诺，`doFinally` 是否/何时成功不可见 |

**结论**：讨论“工具已执行后模型出错”必须画成 g0～g6，不能与 7.2 的“当前轮还没 complete”混成一条时间线。两条路径的差别是：7.2 当前批工具未执行；7.3 上一批工具已执行，但本轮聊天历史未保存、辅助索引可能已写。

### 7.4 完成回调异常、工具异常与模型流异常

| 异常位置 | 会发生什么 | 工具/保存/客户端 |
|---|---|---|
| 模型流 `onError`/超时（未 complete） | 进入 failure consumer（7.2）；当前轮 ToolCall 不执行 | 当前轮无新工具副作用；`saveNewMessages` 不调用；事件尝试发送 |
| 完成回调抛异常（例如 `saveNewMessages`、JSON/历史处理） | `doOnComplete` 抛出的异常进入 failure consumer；`guard` 调 Agent error，再尝试 `tryEmitError` | 工具可能已在同一完成回调中执行；保存可能部分完成（MySQL 先写、Redis/heap 后写）；不能保证全有或全无 |
| `executeTool` 内工具方法/参数解析异常 | 被 `executeTool` 的 `catch (Exception)` 捕获，返回 `"Tool execution failed: ..."` 文本 | 只有后续 `recordCalling`/回执构造也成功，才形成正常 `ToolResponseMessage` 并继续；工具可能有部分副作用 |
| `recordCalling`/回执构造/其他回调异常 | 不在 `executeTool` 的 catch 内；可能冒泡到完成回调或 failure consumer | 辅助索引可能已写 MySQL/Redis；聊天历史/保存不确定 |

客户端可见性：以上事件和错误终态都是 `tryEmit*` 尝试；客户端可能看到 `error` 事件、只看到连接结束，或看不到完整序列；HTTP 状态受响应提交影响。

### 7.5 T3：达到 200 条阈值或 `notifyUser` 暂停

**阈值分叉**：第 201 条新增历史消息进入下一轮 `runLoopStep` 时，开头判断 `history.size() - originalHistorySize > 200` 成立：

- 发 `AgentEvent.error("本轮工具调用次数过多，请重新发起请求")`（尝试）；
- 尝试 `tryEmitComplete`；
- 不调用 `saveNewMessages`，本轮新增消息不保证写入；
- 该阈值分支本身不会撤销此前工具的副作用或已写辅助索引；实际是否保留还取决于其他操作。

**暂停分叉**（仅 OrderAgent）：`handleToolCalls` 遇到 `notifyUser`：

- 同批**之前**的普通工具已经执行，可能已有副作用；同批**之后**的工具才标记为“尚未执行”；
- `notifyUser` 自己的应答等 `/r` 反馈后补（C3）；
- `handleNotifyUser` 先读 Hash（try 外），try 内按 `begin`（只查 `quoteId`/`ReadyforConfirm` 存在）→ 序列化 → `saveNewMessages` → 写 `break` → 尝试 `confirm`/`complete`；任一步失败都可能留下不同状态；
- 只有前面步骤成功，才可能看到 `confirm`；即使 `confirm`/`complete` 被调用，也只是 sink 尝试，客户端是否收到不保证；
- 不进入下一轮模型，等待 `/r` 的确认/拒绝（C3）。

这两个分叉都不是“正常纯文本完成”，但也不一定走同一条错误路径：阈值是 error 事件 + complete 尝试；暂停是 `confirm` + complete 尝试；`begin`/序列化/保存失败则走 `handleNotifyUser` 的 catch（error + complete 尝试），而不是把它当成完整的“有效报价校验”单一分支。

### 7.6 客户端断开

C1 已详细说明：连接已建立时，HTTP 下游取消不会自动取消内部订阅，后台可能继续跑工具和模型；`tryEmitNext` 可能失败；`doFinally` 是否/何时运行取决于连接状态。C2 只需记住：**断开不会让已经发生的工具副作用回滚，也不保证循环立即停止**。

---

## 八、身份、权限与工具回执

### 8.1 `ToolContext` 的来源

Agent 在构造 `Prompt` 时传入：

```java
toolContext(Map.of(
        ToolContextKeyConstants.TOOL_LISTENER, toolListener,
        ToolContextKeyConstants.USER_ID, userId,
        ToolContextKeyConstants.CHAT_ID, chatId))
```

- `userId` 来自 C1 的服务端身份链：`UserTokenContext` → Controller → `ChatRequestRunner` → `ChatServiceImpl` → Agent 方法参数；不是模型参数；
- `chatId` 来自 URL 路径，经过 UUID、归属校验（C1）；
- `TOOL_LISTENER` 是 Agent 自己创建的 `Consumer<String>`，通常发 `AgentEvent.tool(msg)`；
- 模型调用工具时，工具方法通过 `ToolContext` 读取这些值；模型不能把 `userId/chatId` 作为工具参数覆盖它们（除非某个工具自己接受同名参数并错误使用，当前工具没有把模型参数当身份）。

### 8.2 工具参数与模型输出都是不可信输入

- 模型生成的 `ToolCall.arguments()` 是外部输入；它可能缺失、类型错误、包含任意文本；
- `executeTool` 的解析和 `switch` 是服务端的“最小校验”，不是完整业务校验；
- 工具内部的权限/归属检查依赖具体实现：
  - `OrderSearchTool#searchOrderById` 用 `ToolContext` 里的 userId 调 `RideOrderService#getOrderDetail(orderId, userId, USER)`；
  - `TicketTool#queryTicketProgress` 对指定 ticketId 额外比较 `detail.getUserId()`，不属于当前用户就返回“无权查看”；
  - `ToolRepPointerTool#getToolResponse` 先 `chatManager.assertOwned(userId, chatId, false)`；
  - 天气、地理、公开 POI 搜索没有用户归属概念，属于信息查询；
  - 不是每个工具都在方法内部重新做归属校验，不能把“工具存在”当成“已鉴权”。
- `ToolNotifySupport` 的监听器回调失败会被捕获并记日志，工具继续执行；`tool_start` 事件发送失败不等于工具失败。

### 8.3 工具回执不是创建订单的授权

`OrderTool#createOrder` 的真实门禁是：

1. `redisGetObj(chatInfoKey, "ReadyForCreate") != null`，否则返回“订单非可创建态”；
2. 逐字段 `HGET` 组装 `CreateOrderDTO`（字段缺失会抛 `IllegalArgumentException`，此时还没消费许可）；
3. `orderConfirmation.consume(chatId)`，它要求 `confirmedQuote` 与 `ReadyForCreate` 都等于当前 `quoteId`；
4. 调用 `RideOrderService#createOrder`；
5. 创建成功后锁定会话（`lockChat`）并写 `OrderId`。

`markOrderReadyForCreate` 只检查 `isConfirmed` 并返回文本，不会签发 `ReadyForCreate`；真正的确认状态由 `OrderConfirmation#confirm` 的 Lua 设置（C3）。系统提示里“用户确认后创建”只是模型行为约束，不是服务端授权。

因此：

- 模型说“用户已确认”不等于确认；
- 工具回执“订单已创建”是服务端真实创建后的结果，不能反过来把回执当授权；
- 工具参数 `orderId`、`ticketId` 等仍需服务层/工具层归属校验；
- 工具结果可以作为模型上下文，但不能作为“用户同意”的证据；C3 讲确认令牌和消费。

### 8.4 提示词不是安全控制

系统提示词里有很多“必须”“严禁”“必须调用”的规则，这些是**模型行为引导**：

- 模型可能不遵守；
- 用户可能通过输入或工具回执进行提示词注入；
- 服务端没有把提示词文本解析成权限规则；
- `DANGER` 只是分类标签，不是安全边界；分类模型可能漏判/误判，真正的鉴权在 C1/A2 的入口和工具/服务的业务校验；
- 真正的安全边界在 C1/A2 的身份/权限、工具方法的业务校验、C3 的确认协议和订单服务。

---

## 九、失败与边界

### 9.1 分类缓存/模型

| 场景 | 源码行为 | 用户可见 | 备注 |
|---|---|---|---|
| Redis 读失败 | `get` catch → null | 无额外事件；继续分类 | 只是降级为未命中 |
| Redis 写失败 | `put` catch → 日志 | 无额外事件 | 本次路由已完成 |
| 分类模型运行时异常 | `resolveClassification` catch → null | `message("路由失败，请换种方式问问题。")` + complete | 不 initChat、不写分类、不调 Agent |
| 模型返回空白 | normalize → null | 同上 | 不缓存 |
| 模型返回非法标签 | normalize → null | 同上 | 不缓存 |
| 缓存里是非法值 | normalize → null → 调模型 | 取决于模型 | 不删除坏缓存，成功后覆盖 |
| TTL <= 0 | 不读不写缓存 | 每轮调模型 | 禁用缓存，不是永不过期 |
| DANGER | `notify("DANGER")` + 拒绝文本 + complete | 可能看到 notify 和 message | 不 initChat/不写分类/不调 Agent |

### 9.2 模型流与工具

| 场景 | 源码行为 | 客户端/终态 | 是否保存 |
|---|---|---|---|
| 当前轮模型流在 onComplete 前错误/超时 | failure consumer → guard 调 Agent error → 再尝试 `tryEmitError`；complete 未执行 | error 事件/连接结束（尝试）；HTTP 状态通常无法改 | 当前轮不调用 `saveNewMessages`；当前轮 ToolCall 未执行 |
| 上一轮工具已执行，下一轮模型流出错 | 上一轮 `handleToolCalls`/`recordCalling` 已执行；下一轮 failure consumer | 同上 | 上一轮聊天历史未保存；辅助索引可能已写 MySQL/Redis；工具副作用可能已发生 |
| 完成回调异常（如保存/历史处理） | `doOnComplete` 异常进入 failure consumer | error 事件/错误终态（尝试） | 工具可能已执行；MySQL 可能已写入，Redis/heap 可能失败 |
| `executeTool` try 内工具方法/参数异常 | `executeTool` catch → `"Tool execution failed: ..."` 回执 | 不是 error 事件；模型继续 | 由后续终态决定；工具可能有部分副作用 |
| `recordCalling`/回执构造/其他回调异常 | 不在 `executeTool` catch 内；可能冒泡到完成回调/failure consumer | 可能 error 事件/错误终态 | 辅助索引可能已写；聊天历史不确定 |
| 未知工具名 | 返回 `"Unknown tool: ..."` 回执 | 不是 error 事件；模型可能重试 | 由后续终态决定 |
| 工具返回业务错误文本 | 作为正常 `ToolResponse` | 模型可能继续/解释 | 由后续终态决定 |
| 循环超过 200 条 | error 事件 + `tryEmitComplete` | 流结束（尝试），不是 Flux error | 否 |
| `notifyUser` 无 `quoteId`/`ReadyforConfirm` | Hash 读取在 try 外；`begin` 只查这两个键是否存在，缺失抛 409，`handleNotifyUser` catch | error 事件 + complete（尝试）；Hash 读取异常则冒泡到 failure consumer | 可能未保存/部分保存；不检查报价年龄/金额/授权 |
| `notifyUser` 的 begin 成功但序列化/保存失败 | try 内异常，catch error + complete | error 事件 + complete（尝试） | 暂停前消息不一定保存；Hash 可能已写 `break/pending` |
| 消息保存失败 | 完成回调抛异常进入 failure consumer | error 事件/错误终态（尝试） | MySQL 可能已写入，Redis/heap 可能失败 |
| 客户端断开 | 后台可能继续（C1） | 客户端收不到后续 | 不确定 |

### 9.3 正常完成、error 事件、Flux 错误不是一回事

- **正常完成**：Agent 的 `saveNewMessages` 成功后才尝试 `tryEmitComplete`；客户端看到流结束只是观察，不等于保存/锁一定成功（C1）。
- **error 事件**：`AgentEvent.error(...)` 是应用数据；它后面可能跟 `tryEmitError`，也可能因 sink 已终止而失败。
- **Flux error**：Runner 订阅期的锁/归属错误会返回 `Flux.error`；这是流创建/订阅层的错误，不是 Agent 的工具错误。
- **任务异常**：`executeTool` try 内的异常变成工具回执文本；但 `recordCalling`、回执构造、完成回调、保存或特殊 `notifyUser` 分支的异常可能冒泡到 failure consumer，不能一概说“工具异常都只是回执”。
- **客户端可见性**：事件、错误终态和 HTTP 状态都受响应提交、MVC/Reactor 和客户端连接影响；本章未实测，不承诺具体状态码。

### 9.4 超时的层级

| 层级 | 时限 | 作用范围 | 证据 |
|---|---|---|---|
| 分类模型 | 无显式超时 | `chatClient...call()` 同步调用；依赖底层客户端配置 | `ChatServiceImpl#resolveClassification` |
| Agent 单次模型流 | 3 分钟 | 每次 `AgentStreams#subscribe`；`takeUntilOther` 产生 `TimeoutException` | `agents/AgentStreams.java#subscribe` |
| 工具/外部服务 | 各工具/客户端自己的配置 | 天气 `qweather.timeout`、RAG 预算、地图客户端等 | `agentbase/qweather/config/QweatherConfig.java`、`RagService`、`AmapConfig` 等（C5 展开） |
| 整轮对话 | 无 | 没有跨 `chat/re...` 的总 deadline | C1 |
| 循环阈值 | 200 条新增历史消息 | 只对有 `runLoopStep` 的三个 Agent | 各 Agent `runLoopStep` |

3 分钟只覆盖模型流，不覆盖工具执行；工具可以阻塞更久。Java/Reactor 层取消也不等于外部网络请求一定停止。

---

## 十、设计取舍与边界

- **内容指纹缓存**：在有效且合法的缓存命中期间，相同上下文可跳过分类模型调用；代价是 key 不含用户，不能当作用户隔离；坏缓存值会被当未命中，TTL 是最终清理手段。
- **同步分类 + 流式 Agent**：分类要在路由前给出结果，所以用同步 `.call()`，会阻塞 `chat-request-*`；Agent 回答需要流式输出，所以用 `stream`。两者超时机制不同。
- **手写工具分派**：相比自动执行，手写 `executeTool` 可以控制参数、记录结果、插入特殊分支（`notifyUser`），但注册、schema、`switch` 和提示词四处可能漂移；未知工具只能返回文本。
- **`executeTool` try 内异常转回执**：让模型有机会解释/重试；代价是工具真实副作用可能已发生，客户端不一定看到 error 事件，模型可能重复调用。`recordCalling`、回执构造、完成回调等其他异常不在这个 catch 内，不能套用同一结论。
- **200 条新增消息阈值**：防止无限循环，但不是精确工具调用上限；单批多个工具仍会执行，副作用可能在终止前发生。
- **提示词约束**：帮助模型按流程调用工具，但不是鉴权；确认、归属、支付等必须由服务端状态和业务代码保证。
- **Fallback 无工具**：避免无关请求误触业务工具，但无法在 OTHER 场景执行任何查询；模型若产生工具调用也没有执行路径。

---

## 十一、测试与评测脚本（未运行）

本章只读 Java 测试与 Python 评测脚本，**没有执行任何测试、脚本、服务、Redis、MySQL 或模型**。

| 测试/脚本 | 门控与真实/替身 | 覆盖的 C2 行为 | 没覆盖 / 副作用 |
|---|---|---|---|
| `ChatServiceRegressionTest` | 无门控；真实 `ChatServiceImpl`，`ChatModel`/`ClassificationCache`/`ChatRateLimiter`/四个 Agent/`MessageMemory`/`ChatManager` 全 mock | 限流先于记忆/分类；缓存命中跳过模型；模型失败只发 message 且不缓存/不 init；空白/非法标签不缓存不落分类；` daily\n` 规范化为 `DAILY`；非法缓存值退回模型；DANGER 不被旧 ORDER 改写；活跃 ORDER 仍走 OrderAgent；null 历史文本仍使用多轮提示词 | 真实 Redis/模型/Agent/工具/SSE/落库；mock 行为不代表真实组件 |
| `ChatGuardRegressionTest` | 无门控；Mockito `StringRedisTemplate`/`ValueOperations` + `HashMap` 替身 | 上下文/prompt 的分隔符、NUL、null vs 空串不串 key；不同上下文/不同 prompt 分离；TTL<=0 完全不访问 Redis；空分类不写；Redis 异常读/写降级；匿名/禁用限流 | 真实 Redis 并发、key 过期、实际 TTL |
| `ChatGuardRedisIntegrationTest` | `test.redis.port` 门控；`127.0.0.1:port` 真实 Redis + 真实 `ClassificationCache`/`ChatRateLimiter` | 真实分类缓存上下文/NUL 隔离与约 6 小时 TTL；分类 key 前缀、TTL 断言；限流脚本 | 真实 HTTP/模型/Agent/DB；分类测试的 `redis.keys("chat:classify:*")` 会把实例上所有匹配 key 加入清理清单，可能删除已有缓存；只能用于一次性专用 Redis（C1 第九节详述） |
| `SecurityRepairRegressionTest` | 无门控；真实 `AgentStreams`/`ChatRequestRunner`/`ChatInfoService`，mapper/Redisson/ChatService/ChatManager 等为 mock；`OrderAgent` 真实构造 + mock 依赖 | 完成回调异常会终止 sink；`notifyBatchPreservesEveryNonConfirmationResponse` 验证同批非确认工具应答；`modelCannotGrantItselfCreationPermission` 验证 `markOrderReadyForCreate` 不签发权限；Runner 首事件与解锁调用 | 真实模型/工具/Redis/DB；真实锁释放与客户端断开 |
| `src/test/llm_judge_eval.py` + `llm_judge_cases.jsonl` | 非 JUnit；需要真实服务、有效 token、真实模型/工具/Redis/MySQL；judge 可选 | 黑盒分类标签、工具启动文案、message/notify/error/confirm 事件、HITL 轮次、主观 rubric；用例包含 ORDER/DAILY/SUPPORT/OTHER/DANGER、多轮和工具名断言 | 不验证内部类、缓存 key、锁、保存、线程；会写业务数据/调用付费模型；`/r` 只发 prompt，不带 `confirmationId/confirmed`，不能证明当前确认链 |

表中的“验证/覆盖”均为**读取测试源码后的断言归纳**，不是运行结果；本章没有执行任何测试或脚本。

**`ChatServiceRegressionTest`** 是理解分类分支最有用的 Java 测试：它没有真实 Redis/模型，但用 mock 把 `resolveClassification` 的前置分支固定下来。例如：

- `cachedClassificationSkipsModel` 让 `cache.get("SINGLE_TURN", null, "prompt")` 返回 `DAILY`，验证 `DailyAgent.invoke` 被调用且 `ChatModel.call` 从未发生；
- `modelLabelsAreNormalizedBeforeCachingAndRouting` 让模型返回 `" daily\n"`，验证缓存写入 `"DAILY"`、路由到 DailyAgent；
- `invalidCachedResultIsReplacedUsingClassifier` 让缓存返回 `"invalid"`，验证模型被调用；
- `blankOrInvalidModelResultsAreNotCachedOrPersisted` 让模型返回空白，验证不缓存、不写 Hash classification、不 `initChat`，最后事件是 `message`；
- `dangerCannotBypassGuardDuringActiveOrder` 让 `classRedis=ORDER`、分类 DANGER，验证不调用 OrderAgent；
- `activeOrderKeepsItsAgentForNormalTopicChanges` 让 `classRedis=ORDER`、分类 DAILY，验证仍调用 OrderAgent；
- `nullHistoricalTextStillUsesMultiTurnClassifierPrompt` 构造没有 Assistant 文本的历史，验证多轮提示词仍出现“上一次路由给DAILY”。

这些是**测试源码阅读**，不是运行结果；mock 的模型/缓存行为不能证明真实 DeepSeek/DashScope 的分类质量。

**`ChatGuardRegressionTest`** 是缓存 key 的单元边界测试：它用内存 `HashMap` 模拟 Redis，验证编码/指纹的分离性质；`redisFailureDegradesCacheAndLimiter` 验证 Redis 异常时分类缓存降级和限流放行。它没有真实 Redis，因此不能验证真实 TTL、网络异常或并发。

**`ChatGuardRedisIntegrationTest`** 是唯一直接验证真实 `ClassificationCache` key/TTL 的 Java 测试，但它需要 `test.redis.port`，并且分类测试会收集并删除实例上所有 `chat:classify:*`；运行前必须确认是一次性 Redis。它不覆盖模型或 Agent。

**`SecurityRepairRegressionTest`** 与 C2 相关的三个断言：

- `completionCallbackFailureTerminatesSink`：`AgentStreams.subscribe(Flux.empty(), sink, ..., complete 抛异常)` 后，收集 sink 会抛异常；说明完成回调异常会进入错误终态（测试源码事实，未运行）；
- `notifyBatchPreservesEveryNonConfirmationResponse`：OrderAgent 的 `handleToolCalls` 在 `notifyUser` 前后工具调用时，历史里会保留“尚未执行”的 `ToolResponse`，`MessageWindow.tail` 可以通过；
- `modelCannotGrantItselfCreationPermission`：`markOrderReadyForCreate` 在未确认时返回“尚未”，且不访问 Redis；它验证的是工具方法，不是完整确认链（C3）。

**`llm_judge_eval.py`** 是黑盒评测脚本，不是 JUnit：

- 入口：`python src/test/llm_judge_eval.py`；可传 `--cases`、`--out`、`--report-txt`、`--max-side-effect`、`--timeout`；
- 默认向 `SUT_BASE_URL`（`http://localhost:8080`）的 `/chat/v2/c/{id}` 发 `POST`，用 `SUT_TOKEN` 鉴权，按 `data:` 行解析 SSE；
- 用例包含分类标签、工具名、禁止在确认前调用 `createOrder`、HITL 轮次等断言；judge 可选；
- 它会创建/写入会话、消息、订单、工单等业务数据，可能调用付费模型/judge、覆盖报告文件；
- 它不能证明内部缓存 key、锁、保存、线程或断线后的后台行为；按源码 `/r` 只发送 prompt，不带 `confirmationId/confirmed`，无法满足当前确认参数；
- **本章未运行**，也不以此宣称任何分类/工具链通过。

在**已检索的 Java 测试类**中，没有完整的 HTTP→分类模型→Agent 模型→工具→SSE 链路测试；`ChatGuardRedisIntegrationTest` 只测 Redis 脚本/缓存，`ChatServiceRegressionTest` 全 mock 模型/Agent，`SecurityRepairRegressionTest` 只覆盖部分错误/权限边界。`RedisCacheRegressionTest`、`CacheConsistencyRegressionTest` 主要验证 C4 的记忆/版本租约，不作为 C2 的分类/工具循环证据；若运行它们，仍需一次性 Redis/隔离数据，清理口径见 C1/C4。运行任何依赖真实 Redis/MySQL/模型的实验都需要隔离数据和费用/权限确认；本章不提供可运行命令，也不运行。

## 十二、动手验证：只读源码追踪

**练习 A：写出三组分类上下文**

分别写出以下情况传给 `resolveClassification` 的三个参数，并说明 `CLASSIFIER_USER_PROMPT` 是否使用：

1. 新会话，prompt=“成都今天天气”；
2. 有历史，Hash `classification=DAILY`，最后一条 Assistant 文本=“成都今天多云”，prompt=“那明天呢”；
3. 有历史，Hash 恰为 `"SINGLE_TURN"`，最后一条 Assistant 文本为 null，prompt=“那明天呢”（异常/外部状态）。

<details>
<summary>练习 A 参考答案</summary>

1. `("SINGLE_TURN", null, "成都今天天气")`；`resolveClassification` 判断 `SINGLE_TURN` 且 `contextText==null`，user 消息直接用 prompt，不格式化 `CLASSIFIER_USER_PROMPT`。
2. `(String.valueOf("DAILY"), "成都今天多云", "那明天呢")`，即 `("DAILY","成都今天多云","那明天呢")`；用 `CLASSIFIER_USER_PROMPT.formatted` 生成“上一次路由给DAILY……助理最后回复……用户输入……”。如果 Hash 字段缺失，则 `contextFeature` 是字符串 `"null"`，不是 Java null；仍走多轮模板。
3. `("SINGLE_TURN", null, "那明天呢")`；虽然历史非空，但源码的 `SINGLE_TURN.equals(contextFeature) && contextText == null` 成立，仍然直接用 prompt，不格式化多轮模板。这是异常/外部状态，正常路由不会把 `"SINGLE_TURN"` 写进 `classification`，并且会与真正的新会话共享相同的缓存输入。
</details>

**练习 B：分类缓存 key 的边界与哈希**

解释为什么 `cache.put("DAILY","A\0B","C","ORDER")` 与 `cache.get("DAILY","A","B\0C")` 的编码输入不同；在什么前提下才会说它们“不会命中”？null 与空字符串为什么不同？TTL 设为 0 时会发生什么？

<details>
<summary>练习 B 参考答案</summary>

`buildKey` 用 `encode` 给每段加“长度 + 冒号”前缀：`"A\0B"` 编码为 `3:A\0B`，`"A"` 与 `"B\0C"` 的拼接边界不同；`raw` 的长度前缀让 NUL 不能把两段合并。`encode(null)="-1:"`，`encode("")="0:"`，因此不同。最终 key 是截断 SHA-256 的指纹，**只有在指纹不碰撞的前提下**，编码不同才推出 key 不同；不能把“编码不同”写成“数学上绝不碰撞”。补充：`String.length()` 是 UTF-16 代码单元数，例如 `"😀"` 的 `length()` 是 2，但只有一个 Unicode 码点。TTL<=0 时 `get` 直接 null、`put` 直接返回，完全不访问 Redis，等价于禁用缓存，而不是“缓存永不过期”。
</details>

**练习 C：注册、分派与真实可达**

以 `OrderAgent` 为例，列出至少三个“存在但不可达”或“注册但特殊处理”的例子，并说明 `notifyUser` 为什么不是普通 `executeTool` 分支。再说明“注册”为什么不是手写分派的绝对必要条件。

<details>
<summary>练习 C 参考答案</summary>

- `RegeoTool` 有 `@Tool`，但没有被任何 Agent 注入；OrderAgent 用的是 `GeoRegeoTool`。
- `ChatStatusTool` 有 `lockSession`，没有被注册，模型无法在正常 schema 下调用。
- `notifyUser` 由 `OrderTool` 注册，但 `OrderAgent#handleToolCalls` 先特判，不走 `executeTool`；它进入 `handleNotifyUser`，通过 `orderConfirmation.begin` 检查 `quoteId`/`ReadyforConfirm` 是否存在（不检查报价年龄/金额/授权）并设 `break`，再序列化、保存、尝试发 `confirm`/`complete`，等待 `/r`（C3）。
- 普通工具如 `saveNewOrderParam` 才是 `executeTool` 的 case；`getCalling` 是显式命名的 case。
- `executeTool` 按返回的 `toolName` 走 `switch`，不会在执行点再次检查注册列表；正常模型受 schema 约束只返回注册名，但“未注册但 switch 有 case”的名字若被传入仍可能执行，所以注册不是分派执行的绝对必要条件。
</details>

**练习 D：循环与错误在哪里终止**

分别判断以下情况会不会继续下一轮、是否保存、是否发 error 事件；说明工具异常与模型流错误的区别：

1. 模型完成，`toolCalls` 为空；
2. 模型完成，返回 1 个 `getWeatherByLatLon`，工具执行成功；
3. `executeTool` 内工具方法抛异常；
4. 新增历史消息达到 201 条；
5. `OrderAgent` 返回 `notifyUser`；
6. 第一轮模型在 `onComplete` 前 `onError`，但之前已收到 ToolCall 片段；
7. 第一轮正常 complete 并执行工具，第二轮模型在 `onComplete` 前 `onError`。

<details>
<summary>练习 D 参考答案</summary>

1. 不继续；尝试 `saveNewMessages`，成功后再尝试 complete；无 error（保存/事件仍可能失败）。
2. 执行工具、`recordCalling`、追加 `ToolResponseMessage`、递归下一轮；中间轮不调用 `saveNewMessages`；无 error（除非后续失败）；辅助索引可能已写。
3. 只有这个 `executeTool` try 内的异常被 catch，返回 `"Tool execution failed: ..."` 回执，继续下一轮；不是 error 事件，副作用可能已部分发生。`recordCalling`/回执构造等不在此 catch 内。
4. 下一轮 `runLoopStep` 开头判断 `>200`，尝试发 error 事件和 complete，不调用 `saveNewMessages`；不是 Flux error。
5. `handleToolCalls` 特判；同批之前工具已执行；`begin` 只查 `quoteId`/`ReadyforConfirm` 存在并设 `break`，随后序列化/保存/再写 break/尝试 confirm+complete；任一步可能失败，保存/confirm 不是必然；等待 `/r`（C3）。
6. 当前轮在 `onComplete` 前失败，`finalMsg`/ToolCall 不会被完成回调读取，`handleToolCalls` 不执行；当前轮无新工具副作用；错误回调不保存；客户端事件是尝试。
7. 第一轮 complete 后工具已执行，`recordCalling` 可能写辅助索引，`ToolResponseMessage` 在内存历史；第二轮错误不保存聊天历史，工具副作用/辅助索引仍可能已存在；不是“工具没执行”。
</details>

**练习 E：哪些身份是模型不能伪造的**

指出 `ToolContext` 中 `userId/chatId` 的来源，并说明工具参数 `orderId/ticketId` 与它们有什么不同。再举一个工具内部检查归属的例子。

<details>
<summary>练习 E 参考答案</summary>

`userId` 从 C1 的服务端身份链（`UserTokenContext` → Controller → Service → Agent 参数）传入；`chatId` 来自 URL 路径并经 UUID/归属校验；它们由 Agent 放进 `ToolContext`，模型不能把工具参数当作它们。`orderId/ticketId` 是模型生成的业务参数，属于不可信输入。`OrderSearchTool#searchOrderById` 用服务端 `userId` 调订单服务；`TicketTool#queryTicketProgress` 对指定 ticketId 比较 `detail.getUserId()`；`ToolRepPointerTool#getToolResponse` 用 `chatManager.assertOwned(userId, chatId, false)`。不是所有工具都做归属检查，不能泛化。
</details>

---

## 十三、一页复习

- **分类没有独立类**：`ChatServiceImpl#chat` 先守卫，再 `memory.get`、读旧 `classification`、`resolveClassification`。
- **分类上下文**：新会话 `SINGLE_TURN` + null + prompt；正常有历史用旧分类 + 最后 Assistant 文本 + prompt；Hash 缺失时旧分类字符串是 `"null"`。例外：Hash 恰为 `"SINGLE_TURN"` 且最后 Assistant 文本为 null 时，即使有历史也直接用 prompt（异常/外部状态）。
- **缓存 key**：`chat:classify:{SHA256("CTXv2"+encode(contextFeature)) 前缀}:{SHA256(raw) 前缀}`；`encode(null)="-1:"`、空串 `"0:"`、非空 `len:value`（Java `String.length()` 是 UTF-16 代码单元数）；**无 userId**，不是用户隔离；截断 SHA-256 理论上可碰撞。
- **缓存 TTL**：`chat.guard.classify-cache-ttl-seconds` 默认 21600 秒；`<=0` 禁用；读失败当未命中，写失败只记日志，模型结果合法时只尝试写入、不保证覆盖；`buildKey` 在 Redis try 之外；空白缓存返回 null，非空非法值返回后才被 normalize 拒绝；坏值不主动删除，会退回模型。
- **规范化**：trim/strip + 大写，只允许 `ORDER/DAILY/SUPPORT/OTHER/DANGER`；其余 null。
- **分支顺序**：null → message+complete；否则 notify；DANGER → message+complete；历史为空 → initChat；旧 `classRedis=ORDER` → 可发换话题提醒 → OrderAgent → return（不写新分类、不进 switch）；其余尝试写分类后 switch。
- **四个 Agent**：Order/Daily/Support 有工具循环；Fallback 无工具、无循环；只有 Order 有 `notifyUser` 暂停和 `resume`。
- **工具层次**：类存在、`ToolCallbacks` 注册、`executeTool` 分派、模型返回 name 是不同问题；注册不是手写分派的绝对必要条件，`executeTool` 不会在执行点复查注册列表。
- **循环**：模型完成无工具 → 尝试保存，成功后再尝试 complete；有工具 → 执行+回执+递归；新增消息 `>200` → error+complete 尝试；当前轮模型 onError 不执行该轮 ToolCall；上一轮工具已执行后下一轮 onError 时，副作用/辅助索引可能已存在但聊天历史未保存；只有 `executeTool` try 内异常变成文本回执；`notifyUser` 的 begin/序列化/保存依次可能失败，confirm/complete 只是尝试（C3）。
- **身份/安全**：`ToolContext` 的 userId/chatId 服务端注入；模型参数不可信；工具回执不是授权；`createOrder` 的门禁是 `ReadyForCreate` + `consume`，不是提示词。
- **超时**：分类同步调用无显式时限；每次模型流 3 分钟；工具各自局部超时；无整轮总时限。

---

## 十四、自测与答案

**1. 为什么说“分类没有独立类”？分类入口在哪？**

<details>
<summary>参考答案</summary>

分类逻辑在 `service/impl/ChatServiceImpl.java#resolveClassification`，由 `#chat` 调用；`normalizeClassification` 和 `lastAssistantText` 也在同一个类。仓库没有独立的 Classifier/ClassifierAgent 类。分类前还有锁定、限流、归属、`break` 等守卫（C1），分类后才路由到四个 Agent。
</details>

**2. 新会话与有历史会话的分类参数分别是什么？**

<details>
<summary>参考答案</summary>

新会话：`("SINGLE_TURN", null, prompt)`，user 消息直接用 prompt。正常有历史：`(String.valueOf(classRedis), lastAssistantText(messages), prompt)`；`classRedis` 来自 Redis Hash 的 `classification`，可能是 `"DAILY"`、`"ORDER"`，也可能是字符串 `"null"`；`lastAssistantText` 只找最后一个 AssistantMessage 的文本，可能为 null 或空串，正常合法旧分类/“null”会使用 `CLASSIFIER_USER_PROMPT`。例外：若 Hash 恰为 `"SINGLE_TURN"` 且 Assistant 文本为 null，尽管历史非空，源码仍走 `SINGLE_TURN.equals(contextFeature) && contextText == null` 的直接 prompt 路径；这是异常/外部状态，并与新会话共享缓存输入。
</details>

**3. 分类缓存 key 是否按用户隔离？为什么？**

<details>
<summary>参考答案</summary>

不按用户隔离。`buildKey` 只使用 `contextFeature/contextText/prompt` 的编码与截断 SHA-256 指纹，没有 userId/token/角色字段。不同用户输入相同内容可能共享分类结果。长度前缀只解决拼接边界，理论上仍有哈希碰撞；`buildKey` 本身在 Redis try 之外，指纹计算异常不会被缓存降级捕获。分类只是内容到标签的映射，不是权限；真正的身份和归属检查在入口和工具/服务里。
</details>

**4. 缓存命中、非法缓存、模型失败分别会怎样？**

<details>
<summary>参考答案</summary>

命中且合法：直接返回，跳过模型。缓存值是空白：`get` 因 `hasText` 返回 null；缓存值非空但非法：`get` 返回原文，随后 `normalizeClassification` 拒绝并调模型；两种情况都不删除旧值，模型成功后写缓存只是**尝试**，失败不保证覆盖。模型抛 RuntimeException：返回 null，`chat` 发“路由失败”message 并 complete，不 initChat/不写分类/不调 Agent。模型空白/非法：同样返回 null，不缓存。TTL<=0：完全不访问缓存，每轮调模型。`buildKey` 异常不属于 Redis 读写降级。
</details>

**5. DANGER、null 和旧 ORDER 分支的先后顺序是什么？**

<details>
<summary>参考答案</summary>

顺序是：`classification == null` → message+complete；否则 `notify(classification)`；若 DANGER → message+complete；然后历史为空才 `initChat`；然后若 `classRedis` 精确等于 `"ORDER"` → 必要时发换话题提醒，调用 OrderAgent 并 return，不写新分类/不进 switch；其余才写 classification 并 switch。DANGER 不会被旧 ORDER 分支覆盖；旧 ORDER 分支即使新分类是 DAILY/SUPPORT/OTHER 也仍走 OrderAgent。
</details>

**6. 四个 Agent 的职责和循环差异是什么？**

<details>
<summary>参考答案</summary>

OrderAgent 负责下单全流程，注册 6 组工具对象、`executeTool` 的分派分支最多，并带 `notifyUser` 暂停和 `resume`；DailyAgent 负责天气/地点/POI/估时估价，注册 7 组工具对象（比 OrderAgent 多一组，但分派分支略少）；SupportAgent 负责订单售后/工单/RAG；三者都有 `runLoopStep` 工具循环和 200 条新增消息阈值。FallbackAgent 只处理 OTHER，没有工具、没有 `executeTool`、没有循环、没有 resume，只调用一次模型流并保存文本。
</details>

**7. 为什么 `notifyUser` 不是普通工具分派？**

<details>
<summary>参考答案</summary>

`OrderTool` 注册了 `notifyUser`，模型能看到它，但 `OrderAgent#handleToolCalls` 在遍历时先判断名字，不调用 `executeTool`。同批之前的普通工具已执行并可能有副作用；只有后续工具被标记为“尚未执行”；`notifyUser` 自己的应答等 `/r` 反馈后补。`handleNotifyUser` 先读 Hash（try 外），try 内 `begin` 只查 `quoteId`/`ReadyforConfirm` 存在并设 `break/pending`（不查年龄/金额/授权），随后序列化 → `saveNewMessages` → 写 `break` → 尝试 `confirm`/`complete`；任一步可能失败，保存/confirm 不是必然。工具方法体本身在这个路径不执行；确认/拒绝后由 `/r` 的 `OrderAgent#resume` 继续（C3）。
</details>

**8. 模型请求工具后，服务端如何执行并回到模型？**

<details>
<summary>参考答案</summary>

模型返回 `AssistantMessage.ToolCall`（id/name/arguments）；完成回调才读取最后的 toolCalls，把 Assistant 消息加入历史，`handleToolCalls` 用 `executeTool` 解析 JSON 参数并调用对应工具方法；结果经 `recordCalling` 尝试另存（null/空参数跳过，只捕获 RuntimeException），再构造 `ToolResponseMessage` 加入内存历史；递归 `runLoopStep`，下一轮模型看到工具回执。`internalToolExecutionEnabled(false)` 表示 Spring AI 不会自动执行，执行完全由这段手写代码完成。工具执行、辅助索引写入、聊天历史回执是三种不同副作用：`recordCalling` 可能在最终保存前就写 MySQL/Redis。
</details>

**9. `executeTool` 内工具异常、未知工具、模型错误、保存失败、200 条阈值分别是什么终态？**

<details>
<summary>参考答案</summary>

只有 `executeTool` try 内的异常：返回 `"Tool execution failed: ..."` 作为工具回执，循环继续，不是 error 事件；`recordCalling`/回执构造/其他回调异常不套用。未知工具：返回 `"Unknown tool: ..."`，继续。模型错误/超时必须拆：当前轮在 `onComplete` 前失败时，该轮 ToolCall 未执行；若上一轮正常 complete 并执行了工具，下一轮才失败，则工具副作用/辅助索引可能已存在，但聊天历史未保存。failure consumer 先 `guard` 调 Agent error，再尝试 `tryEmitError`；该失败回调不保存。保存失败：`messageMemory.save` 抛异常进入 failure consumer；MySQL 可能已写入，Redis/heap 可能失败，不能断言完整保存。超过 200 条新增消息：下一轮开头发 error 事件 + complete 尝试，不保存本轮新增，不是 Flux error。客户端可见性都受 sink/响应状态影响。
</details>

**10. 工具回执为什么不等于用户授权？**

<details>
<summary>参考答案</summary>

工具回执只是模型上下文；它可能来自模型参数、工具执行结果或编造的内容，不代表用户同意。`createOrder` 的真实门禁是 Redis `ReadyForCreate` 和 `OrderConfirmation.consume`，确认状态由服务端确认 Lua 设置（C3）；`markOrderReadyForCreate` 只检查 `isConfirmed`，不签发权限。系统提示里的“必须确认”只是模型行为约束，不是鉴权。工具参数也不可信，需要具体工具/服务检查归属。
</details>

---

## 十五、常见误区与导航

### 15.1 常见误区

- 以为分类由一个独立 `ClassifierAgent`/分类服务完成：实际在 `ChatServiceImpl` 内联。
- 以为缓存 key 带 userId：实际只对上下文和 prompt 做指纹，不是用户隔离。
- 以为缓存命中就一定正确、不会再调模型：缓存值还要 normalize；坏值会退回模型；不同输入也可能因截断 SHA-256 碰撞而共享 key。
- 以为 `TTL=0` 是永不过期：实际是禁用缓存。
- 以为 `DANGER` 在旧 ORDER 分支之后：实际 DANGER 在前面，先拒绝，不调 OrderAgent。
- 以为有历史时新分类一定决定 Agent：旧 `classRedis=ORDER` 时仍走 OrderAgent，且不写新分类；Hash 恰为 `"SINGLE_TURN"` 且无 Assistant 文本时还会走单轮 prompt 路径。
- 以为模型会自己执行工具：`internalToolExecutionEnabled(false)`，服务端手写 `executeTool` 执行。
- 以为工具类存在就能被模型调用：`RegeoTool`、`ChatStatusTool` 没注册；`notifyUser` 注册但特殊分派；注册也不是手写 `switch` 的绝对前提。
- 以为工具异常一定会发 error 事件：只有 `executeTool` try 内的异常被 catch 成工具回执；`recordCalling`、回执构造、完成回调等异常可能冒泡。
- 以为工具回执是 SSE 事件：工具回执是 `ToolResponseMessage`，`tool_start` 才是事件。
- 以为工具回执/提示词等于授权：创建订单看 `ReadyForCreate` + `consume`，确认协议在 C3。
- 以为 3 分钟模型流是整轮时限：只是每次模型流；工具和分类不受它保护。
- 以为循环阈值是工具调用次数：是新增历史消息条数，严格 `>200`。
- 以为 Fallback 也有工具循环：它没有工具、没有 `executeTool`、没有 `runLoopStep`。
- 把测试源码断言说成“测试通过”：本章没有运行任何测试。

### 15.2 导航与源码入口

- **上一章**：[第 16 章 一次对话与事件流](01-chat-round-and-sse.md)——请求、锁、订阅、事件与保存边界。
- **下一章**：[第 18 章 订单确认、恢复与消费](03-order-confirmation.md)——`notifyUser`、`/r`、确认编号与一次性消费（已通过复审）。
- **相关专题**：[C4 三层记忆与工具结果反查](04-memory-and-tool-results.md)——历史窗口、工具结果反查；[C5 外部工具与提示词边界](05-external-tools-and-prompts.md)——地图/天气工具与提示词约束。
- **已通过的前置**：[第 4 章](../00-foundations/04-redis-flux-and-sse-basics.md)、[第 6 章](../01-auth/02-authorization-and-defense.md)、[第 7 章](../02-orders/01-order-lifecycle.md)、[第 12 章](../03-support-and-cache/01-ticket-lifecycle.md)、[第 15 章](../03-support-and-cache/04-locks-leases-and-ids.md)。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、API 文档和测试写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 分类入口与上下文 | `service/impl/ChatServiceImpl.java#chat/#resolveClassification/#normalizeClassification/#lastAssistantText` |
| 分类缓存与配置 | `service/base/ClassificationCache.java#get/#put/#buildKey/#fingerprint/#encode`；`config/ChatGuardProperties.java`；`constant/RedisKeyConstants.java#CHAT_CLASSIFY_CACHE_PREFIX/#chatClassifyKey`；`src/main/resources/application.yaml`（`chat.guard.*`） |
| 分类提示词 | `config/ChatProperties.java`（`CLASSIFIER_SYS_PROMPT`、`CLASSIFIER_USER_PROMPT`；该类不是 `@ConfigurationProperties`） |
| 四个 Agent | `agents/OrderAgent.java`、`agents/DailyAgent.java`、`agents/SupportAgent.java`、`agents/FallbackAgent.java` |
| 模型流、超时与错误 | `agents/AgentStreams.java#subscribe/#guard`；各 Agent 的 `runLoopStep/#handleToolCalls/#saveNewMessages` |
| 工具注册/分派 | `agentbase/tool/OrderTool.java`、`POISearchTool.java`、`ToolRepPointerTool.java`、`TrafficTool.java`、`UserPOISearchTool.java`、`UserPOITool.java`、`CurWeatherTool.java`、`GeoRegeoTool.java`、`POIAroundTool.java`、`DailyTool.java`、`OrderSearchTool.java`、`TicketTool.java`、`RagTool.java` |
| 工具事件/上下文 | `agentbase/tool/ToolNotifySupport.java`；`constant/ToolContextKeyConstants.java`；`agentbase/memory/ToolResponseMemory.java` |
| 工具回执与历史 | `agentbase/memory/MessageParser.java`、`MessageWindow.java`、`MessageMemory.java`、`MysqlMemory.java`；S4/C4 的保存链 |
| 配置/模型 | `src/main/resources/application.yaml`（`spring.ai.openai.*`、`spring.ai.dashscope.*`）；`config/AsyncWorkConfig.java`（线程池，C1） |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/ChatServiceRegressionTest.java`、`ChatGuardRegressionTest.java`、`ChatGuardRedisIntegrationTest.java`、`SecurityRepairRegressionTest.java`；`src/test/llm_judge_eval.py`、`src/test/llm_judge_cases.jsonl` |
