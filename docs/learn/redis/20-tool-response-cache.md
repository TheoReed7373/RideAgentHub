# 工具调用结果缓存：Redis + MySQL 两级辅助索引

> **Redis 考点**：历史消息保留工具结果全文，窗口内仍会进入模型上下文；另建辅助索引，另用 `(callId, chatId)` 两级缓存提供「需要时再反查」的能力。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/memory/ToolResponseMemory.java`、`src/main/java/com/fancy/rideagenthub/agentbase/tool/ToolRepPointerTool.java`、`src/main/java/com/fancy/rideagenthub/agentbase/memory/MessageWindow.java`

---

## 一、业务场景

Agent 工具调用的结果动辄几 KB 到几十 KB（路线规划的完整 JSON、POI 搜索的列表）。两个矛盾：

1. **上下文长度**：每轮对话都带着全部历史工具结果，上下文迅速膨胀，模型成本与响应速度恶化；
2. **可回放性**：历史轮次的工具结果偶尔需要重新注入（用户问「刚才那条路线多少钱」）。

当前设计的分工：

| 数据 | 存哪 | 何时用 |
|---|---|---|
| 历史消息中的 TOOL 响应 | `MessageMemory` 三层（**保存全文**） | 每轮对话的上下文窗口（`MessageWindow` 截取） |
| 工具结果的**辅助反查索引** | `ToolResponseMemory`（Redis 24h + MySQL 两级） | 模型显式调用 `getCalling(callId)` 时（chatId 来自 ToolContext） |

反查能力由提示词契约驱动：`config/ChatProperties` 的领域提示词明确要求 Agent 在需要旧工具结果时**必须调用 `getCalling` 反查，不得凭记忆编造**——反查链路（`ToolRepPointerTool` → `ToolResponseMemory`）与契约配合，构成「按需再注入」。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:tool:v2:{chatId}:{callId}` | String（工具结果原文） | 24 小时（`TTL`） | Redis 缓存层 |
| （MySQL）`sys_chat_tool_repsonse`（源码与建表保留 repsonse 拼写） 表 | 行：`callId` + `chatId` + `response` | 永久 | 持久层 |

key 在 `ToolResponseMemory.key()` 内**就地拼接**（`"chat:tool:v2:" + chatId + ":" + callId`）——`RedisKeyConstants` 里的 `TOOL_RESPONSE_KEY` 常量与 `toolResponseKey()` 方法**没有调用方**（遗留死代码，见六）。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `ToolResponseMemory.save(callId, chatId, response)` | 先 `mapper.insert`（MySQL），再写 Redis |
| `ToolResponseMemory.get(callId, chatId)` | 先 Redis（miss 则）MySQL `orderByDesc(id) LIMIT 1`，命中回填 Redis |
| `ToolRepPointerTool`（`@Tool(name = "getCalling")`） | 模型可调用的反查工具：模型参数只有 `callId`，`chatId` / `userId` 由服务端 `ToolContext` 提供；经 `chatManager.assertOwned` 归属校验 |
| `MessageParser` | TOOL 消息的序列化 / 反序列化（保存全文进历史） |
| `MessageWindow.tail` | 工具批次配对（保证历史窗口里的 tool call 与响应成对出现） |

---

## 四、实现拆解

### 4.1 写入：先 MySQL 再 Redis

```java
mapper.insert(ChatToolResponse.builder().callId(callId).chatId(chatId).response(response).build());
cache(chatId, callId, response);       // try/catch：Redis 失败只 warn（已持久化）
```

与 `MessageMemory.save` 同一顺序哲学：**权威源先行**。Redis 写失败时功能退化为「反查走 DB」（慢而正确）；直接调用 `save` 时 MySQL 失败会抛异常；Agent 经 `recordCalling` 调用会捕获并记录警告，继续由消息历史保存全文，不重放已执行的工具。

**为什么用 `set` 而不是 `setIfAbsent`**：工具结果按 `(callId, chatId)` 不可变——同一调用重试 / 重放时写入的内容相同，`set` 幂等覆盖正确且实现最简单。`setIfAbsent` 在「第一次写入部分失败、第二次重写」的场景反而会拒绝正确的重写。（**set 幂等的前提是同一调用结果保持不变；当前没有唯一约束或禁止不同 response 重写的校验**——把这套照搬到可变数据就是灾难，这是本篇最重要的教学点。）

### 4.2 读取：命中直接返回，不续期

```java
String cached = redis.get(key(chatId, callId));    // 命中直接 return
if (cached != null) return cached;
// DB 回源后回填
```

**固定 24 小时 TTL，读取不续期**：工具结果的价值集中在「对话后的短时间内」（用户追问上下文）；活跃会话的 `chat:info` / 历史消息有自己的 TTL 策略，工具缓存不需要跟着续。滑动过期（读一次续一次）会让活跃会话的工具结果无限累积。

### 4.3 `getCalling` 工具：归属校验

`ToolRepPointerTool` 注册为模型可调用的工具，schema 名是 `getCalling`，模型 schema 参数**只有 `callId`**；Java 方法另接收框架注入的 `ToolContext`，从中读取 `chatId` 与 `userId`，再做 `assertOwned` 校验。模型不能指定任意 chatId 跨会话反查。

### 4.4 MySQL 侧的重复行

同一 `(callId, chatId)` 重复 save 会累积多行（每次 `mapper.insert` 新行），读取取 `orderByDesc(id) LIMIT 1`（最新）。正确性不受影响（取到的永远是最新），表会缓慢膨胀——已知存储冗余（[docs/02](../../02-cache-consistency-race.md) §3.5 同一条）。

### 4.5 `MessageWindow` 的工具配对

历史窗口的截取必须保持 `AssistantMessage(toolCalls)` 与 `ToolResponseMessage(responses)` 成对（[19 篇](19-multi-level-cache.md) 4.6）——这是「历史保存全文」一侧的正确性保障；`ToolResponseMemory` 是独立于消息序列的反查通道，两者互补。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 历史保存全文 + 辅助索引 | 历史只存 call_id 指针（替换原文） | 指针化要求「窗口外的每个 callId 都能可靠反查」（缓存过期 / DB 丢失时上下文残缺）；全文保存 + 窗口截取只排除窗口外的工具结果，窗口内长结果仍占上下文，反查只是补充 |
| 两级（Redis + MySQL） | 三级（含堆内） | 工具结果读频远低于消息序列（只有模型显式反查），堆内层收益覆盖不了复杂度 |
| `set` 覆盖写 | `setIfAbsent` | 数据不可变，覆盖幂等（4.1） |
| 固定 TTL 不续期 | 滑动续期 | 价值窗口集中，续期使 key 无限累积 |
| key 就地拼接 | 集中到 `RedisKeyConstants` | key 含两个动态段（chatId + callId），集中常量化收益低；代价是死代码常量遗留（见六） |

---

## 六、边界与已知问题

1. **`RedisKeyConstants.TOOL_RESPONSE_KEY` / `toolResponseKey()` 是死代码**：定义存在但全库 0 调用（当前 key 在 `ToolResponseMemory.key()` 就地拼接）。检索 / 重构时注意识别，避免误当作现行实现。
2. **MySQL 重复行累积**（4.4）：读取永远正确，存储缓慢膨胀；清理需后台任务（未实现）。
3. **Redis 与 MySQL 内容理论上可能不一致**：save 时 MySQL 成功、Redis 失败，之后（修复前）反查拿到的是 DB 值——若同一 (callId, chatId) 始终保存相同内容，通常只有可用性退化；但代码未禁止不同内容重写或乱序缓存回填，存在旧 Redis 值遮蔽 DB 最新行的窗口，不能承诺绝无不一致。
4. **24 小时后的反查走 DB**：慢（一次索引查询）但可用；提示词契约已允许模型「提示用户信息过旧」。

---

## 七、如何验证

```bash
# 1. 触发工具调用后（对话中查路线等）
redis-cli KEYS "chat:tool:v2:*"                       # 期望：chat:tool:v2:<chatId>:<callId>
redis-cli TTL chat:tool:v2:<chatId>:<callId>          # 期望：≤ 86400（24 小时）

# 2. 读取不续期：反查多次后
redis-cli TTL chat:tool:v2:<chatId>:<callId>          # 期望：仍单调下降，不回满

# 3. 幂等覆盖：手工改写该 key 的值后再次触发同一 callId 保存 → 值恢复正确
redis-cli SET chat:tool:v2:<chatId>:<callId> "corrupt"

# 4. 过期后反查：DEL 该 key，再让模型反查同一 callId
redis-cli DEL chat:tool:v2:<chatId>:<callId>
#    → 反查走 DB 回源成功，且回填后 key 重新出现

# 5. 隔离验证：用其他会话的 callId 反查 → 仅查询当前 ToolContext.chatId，查不到则返回 null
```

---

## 八、延伸阅读

- [`19-multi-level-cache.md`](19-multi-level-cache.md) —— 消息序列的三层缓存（与本篇的分工）
- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— `chat:info` 上的流程门禁
- [`../agent/01-orchestration-and-sse.md`](../agent/01-orchestration-and-sse.md) —— `getCalling` 的提示词契约与调用链
- [`docs/02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— set 幂等与 MySQL 重复行的盘点
