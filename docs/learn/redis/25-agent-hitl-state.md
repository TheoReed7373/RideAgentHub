# Agent 的 HITL 等待确认：break 状态机与 confirmationId

> **Redis 考点**：Agent 执行到「高风险动作」前主动挂起（Human-in-the-Loop）——挂起状态外置到 Redis Hash（`break` 字段），跨请求恢复靠服务端签发的 `confirmationId` 一次性令牌。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/OrderConfirmation.java`、`src/main/java/com/fancy/rideagenthub/agents/OrderAgent.java`、`src/main/java/com/fancy/rideagenthub/service/impl/ChatServiceImpl.java`（`resume`）

---

## 一、业务场景

订单创建是资金相关操作，不能让「模型觉得用户想下单」就直接执行。HITL（Human-in-the-Loop）断点的完整链路：

```
① Agent 补齐订单参数 → 算价（quoted()：quoteId + 时间戳）
② begin(chatId, toolCallId)      → break=yes：会话挂起，等待用户确认
③ confirm 事件（携带 confirmationId）推给前端
④ 用户点击确认/拒绝 → POST /chat/v2/r/{chatId} {confirmationId, confirmed}
⑤ CONFIRM Lua（校验 15 分钟 → break=resuming → 签发 ReadyForCreate）
⑥ OrderAgent.resume：接回工具链，先持久化悬空的工具应答
⑦ createOrder → consume()（一次性消费）→ 真正落单
```

「挂起」的难点：**Agent 的执行状态（等哪个工具调用、等谁确认）必须跨请求存活**——HTTP 请求已结束，状态只能外置。这就是 `chat:info` Hash 的 `break` 字段。

---

## 二、Redis 结构选型

`chat:info:{chatId}` Hash 上与 HITL 相关的字段：

| 字段 | 值 | 语义 |
|---|---|---|
| `break` | `yes` / `resuming` | 挂起状态机：`yes`=等待确认，`resuming`=已确认正在恢复 |
| `pendingToolCall` | toolCallId | 挂起时悬空的工具调用（恢复时先补应答） |
| `quoteId` | UUID | 报价标识 = **confirmationId**（前端回传的就是它） |
| `quoteCreatedAt` | 毫秒时间戳 | 报价时刻（15 分钟时效的基准） |
| `confirmationAccepted` | `yes` / `no` | 用户当时的选择（幂等重放的比对基准） |
| `confirmedQuote` / `ReadyForCreate` | quoteId | 创建权限（confirm 签发、consume 消费） |

`quoted()` 同时把 `chat:info` 续 24 小时 TTL——挂起中的会话不该在用户思考时过期。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `OrderConfirmation.quoted` | 报价时清旧字段 + 写 `quoteId` / `quoteCreatedAt`，key 续 24h |
| `OrderConfirmation.begin` | 校验有效报价（否则 409）；写 `break=yes` + `pendingToolCall`；返回 quoteId |
| `OrderConfirmation.confirm` | `CONFIRM` Lua：时效 + 状态机 + 幂等；失败 409 |
| `OrderConfirmation.consume` | `CONSUME` Lua：一次性消费创建权限 |
| `OrderAgent` | 工具循环中触发 confirm 事件；`resume(sink, chatId, userId, feedback)` 恢复执行 |
| `ChatServiceImpl.resume` | 恢复编排：`confirm` 授权 → `orderAgent.resume(feedback + prompt)` |
| `ChatServiceImpl.canStartChat` | `chat` 在 canStartChat 之后检查 `break == yes`（canStartChat 本身只校验 prompt 与限流） → 提示「请先确认或拒绝当前订单」 |

---

## 四、实现拆解

### 4.1 两态 `break`：为什么不是一态

- `yes`：等待用户输入——**普通对话被拦**（`canStartChat` 检查到 `break == yes` 发提示并结束流），恢复接口处理；
- `resuming`：确认已受理、Agent 正在恢复执行——**幂等重放**（用户双击确认）应放行，`CONFIRM` 检查 `confirmationAccepted` 相同决定返回 1 而非 409；恢复期间的新消息由执行锁（`chat:execution`）互斥拒绝；`OrderAgent.resume` 补齐工具应答后主动删除 break / pendingToolCall / confirmationAccepted，再继续模型循环。

### 4.2 `CONFIRM` Lua：授权的原子判定

失败分支（confirmationId 不匹配 / 超 15 分钟 / 相反决定的重放 / 非挂起态）统一映射 409「确认已失效或已被使用」：

```
quoteId 不匹配                            → return 0
confirmed=yes 且 超 15 分钟               → return 0
state == resuming 且决定相同              → return 1   （幂等重放）
state == resuming 且决定不同              → return 0
state ~= yes（未挂起）                    → return 0
→ HSET break=resuming + confirmationAccepted
→ yes：签发 confirmedQuote / ReadyForCreate = quoteId
   no ：HDEL 两者（拒绝不签发权限）
```

**幂等只对「相同决定」开放**——`confirmed=true` 重放放行，`true` 之后改提 `false` 被拒（授权已给出，不能反悔）。

`ChatServiceImpl.resume` 把用户 `prompt` **原样拼接**在固定 feedback 之后传给 `orderAgent.resume`——注入面不靠「不转发原话」防御（挡不住提示词注入），靠的是：**授权与执行分离**（模型收到的只是文本，真正的门禁在 `consume` 的 Lua 里）。

### 4.3 恢复执行：先补悬空的工具应答

`OrderAgent.resume` 的关键步骤（顺序重要）：

1. `getPersisted`（直读 MySQL 权威数据）拿到挂起前的历史；
2. **先持久化悬空的 `ToolResponseMessage`**——挂起时 `pendingToolCall` 的调用没有应答，协议要求恢复后必须成对出现（`MessageWindow` 的配对校验）。用数据库事实补，避免「持久化成功而缓存失败后重复保存工具应答」；
3. 注入固定 feedback（「用户已确认 / 已拒绝」+ 可选用户补充说明）继续工具循环；
4. `markOrderReadyForCreate`（只读）→ `createOrder` → `consume()`。

### 4.4 挂起拦截与 DANGER 守卫前置

普通对话在 `break == yes` 时被拦：**挂起期间用户补充的信息（「再等等」）不进入会话历史**，避免污染挂起前的上下文。普通聊天先检查 break，再分类与拦截 DANGER；`resume` 不调用分类器，因此没有 DANGER 分类守卫，恢复授权由 CONFIRM / CONSUME 校验。

### 4.5 会话粘滞

`ChatServiceImpl` 路由时若 Redis 里 `classification == ORDER`，**只有分类成功且不是 DANGER 后才按旧 ORDER 路由给 `OrderAgent`**——恢复期间用户的消息（「帮我确认」）可能被分类成 DAILY，粘滞保证它仍进入订单链路。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 挂起状态外置（`break` 字段） | 请求内等待（长连接） | SSE 适合流式输出不适合挂机等待；外置使恢复与挂起可在不同实例 |
| confirmationId = quoteId | 独立随机 ID | 报价与确认天然一对一：新报价 → 新 quoteId → 旧确认自动失效 |
| 两态 break | 一态（有/无） | `resuming` 承载「幂等重放窗口」与「恢复中不拦普通对话」两个语义 |
| 15 分钟时效 | 无时效 / 24h | 报价含价格——太久后价格失真；15 分钟覆盖正常决策 |
| 恢复先补工具应答 | 直接继续 | 协议要求 call/response 成对；缺失会让下一轮模型调用报错 |
| prompt 原样拼接 + Lua 门禁 | 过滤用户原话 | 过滤挡不住注入；授权在服务端 Lua，模型文本无权限 |

---

## 六、边界与已知问题

1. **确认期间用户改主意（想改地址）**：只能拒绝当前报价重新对话——改参数 = 新报价 = 新 confirmationId，语义自洽。
2. **`consume` 成功而订单创建失败**：创建权限已消费，需重新报价确认（与 [11 篇](11-two-phase-confirm-token.md) 的「消费与执行分离」同一残留）。
3. **挂起 24 小时后 key 过期**：quoted 设置 24h，但 GET /restore 命中会改成 60min，POST /restore 会 persist，不能把 24h 当作全链路硬上界——过期后 confirm 返回 409，用户重新对话。
4. **`break=resuming` 期间的新输入**：执行锁串行化，不会并发；恢复轮结束后按正常分类路由。

---

## 七、如何验证

```bash
# 1. 挂起状态：对话走到报价确认后
redis-cli HGET chat:info:<chatId> break              # 期望 yes
redis-cli HGET chat:info:<chatId> quoteId            # 期望 UUID（= confirmationId）
redis-cli HGET chat:info:<chatId> pendingToolCall    # 期望：工具调用 id
redis-cli TTL chat:info:<chatId>                     # 期望 ≤ 86400（quoted 续的 24h）

# 2. 确认后（POST /r 提交 confirmed=true）
redis-cli HGET chat:info:<chatId> break              # 期望 resuming
redis-cli HGET chat:info:<chatId> ReadyForCreate     # 期望 = quoteId
#    createOrder 后：ReadyForCreate 被消费（nil）

# 3. 幂等重放：仅 break=resuming 且决定相同时 Lua 返回成功；resume 清理 break 后不保证重放成功
#    改提相反 confirmed → 409

# 4. 时效：手工把 quoteCreatedAt 改到 16 分钟前
redis-cli HSET chat:info:<chatId> quoteCreatedAt 1730000000000
#    → 接受被拒（BusinessException 409）；拒绝不因报价超龄被拒。流内异常由 guard 转为 error 事件

# 5. 挂起期间普通对话被拦：POST /c/{id} → notify「请先确认或拒绝当前订单」
```

---

## 八、延伸阅读

- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— 令牌族全景（取消令牌 / 确认令牌对照）
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `chat:info` 的 TTL 语义与单轮执行锁
- [`19-multi-level-cache.md`](19-multi-level-cache.md) —— `MessageWindow` 工具批次配对（恢复补应答的依据）
- [`../agent/01-orchestration-and-sse.md`](../agent/01-orchestration-and-sse.md) —— 恢复链路的完整调用时序
