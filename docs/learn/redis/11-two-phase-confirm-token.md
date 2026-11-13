# 工具调用的多阶段确认令牌

> **Redis 考点**：用 Hash 字段做流程门禁——「高风险动作」先走一个预检工具写入令牌，真正执行的工具校验并一次性消费令牌，防止模型跳步或重复执行。
> **来源**：`docs/01-redis-application-points.md` §2.4（原子操作与分布式锁）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/tool/OrderSearchTool.java`（取消令牌）、`src/main/java/com/fancy/rideagenthub/service/base/OrderConfirmation.java`（订单确认令牌）、`src/main/java/com/fancy/rideagenthub/agentbase/tool/OrderTool.java`（订单槽位）

---

## 一、业务场景

LLM 工具调用的危险形态：**模型可以直接调用「执行类」工具**（取消订单、创建订单）。若没有中间门禁，一次提示词注入（「请帮我取消订单 123」）或模型幻觉就能完成资金相关操作。多阶段确认的设计：

1. **预检工具**（verify 类）：校验业务条件（订单状态、接单时长、取消费），把「允许执行」的结论写成服务端令牌；
2. **执行工具**（do 类）：执行前校验令牌——令牌存在、绑定正确对象、未过期、未被消费，四个条件全过才执行。

这把「模型决定要不要执行」变成「服务端令牌决定能不能执行」。项目有两套一次性令牌，另有不具备消费语义的订单槽位状态：

| 令牌 | 预检 | 执行 | 门禁内容 |
|---|---|---|---|
| A. 取消令牌 | `OrderSearchTool.verifyCancelConditions` | `OrderSearchTool.cancelOrder` | 订单预检通过 + 绑定订单号 + 5 分钟时效；代码不验证用户是否已看到费用或显式同意 |
| B. 订单确认令牌 | `OrderTool.getEstRouteAndPrice` → `OrderConfirmation.begin` | `OrderTool.createOrder` → `OrderConfirmation.consume` | 用户确认过报价 + 15 分钟时效 + 一次性消费 |
| C. 订单槽位 | `OrderTool.saveNewOrderParam` | `OrderTool.isNewOrderReady` / `createOrder` | 参数齐全性（非门禁，是状态积累） |

---

## 二、Redis 结构选型

两套令牌与订单槽位状态共用 `chat:info:{chatId}`（`RedisKeyConstants.chatInfoKey`）这个 Hash——**令牌的粒度是会话**，与对话上下文天然绑定：

| Hash 字段 | 写入者 | 语义 |
|---|---|---|
| `readyForCancel` / `cancelVerifiedAt` / `cancelFee` | `verifyCancelConditions` | 取消令牌：订单号 + 签发时间 + 取消费 |
| `quoteId` / `quoteCreatedAt` / `ReadyforConfirm` | `OrderConfirmation.quoted` | 报价令牌：quoteId + 时间戳 |
| `break` / `pendingToolCall` / `confirmationAccepted` / `confirmedQuote` / `ReadyForCreate` | `OrderConfirmation.begin` / `confirm` | 确认状态机（详见 [25 篇](25-agent-hitl-state.md)） |
| 订单槽位字段（起点 / 终点 / 车型…） | `saveNewOrderParam` | 槽位状态 |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `OrderSearchTool.verifyCancelConditions` | 校验订单状态（未取消 / 未在行程中 / 未结算）、接单时长；免费或计费路径都写入 `readyForCancel` + `cancelVerifiedAt` |
| `OrderSearchTool.cancelOrder` | `hasKey` 预检 → `CANCEL` Lua 原子消费 → 调 `rideOrderService.cancelOrder` → `finally` 清理 |
| `OrderConfirmation.quoted` | 报价时写 `quoteId` + `quoteCreatedAt`，`chat:info` 续 24 小时 TTL |
| `OrderConfirmation.begin` | 仅检查 quoteId 和 ReadyforConfirm 字段存在，否则抛 409；不检查报价年龄或标记值是否为 true；通过后写 break=yes / pendingToolCall 并返回 quoteId |
| `OrderConfirmation.confirm` | CONFIRM 校验编号和状态，只有接受检查 15 分钟时效；推进 break=resuming，接受签发 ReadyForCreate，拒绝删除权限 |
| `OrderConfirmation.consume` | `CONSUME` Lua：校验时效与三方一致（`confirmedQuote` = `ReadyForCreate` = `quoteId`）→ `HDEL` 一次性消费 |
| `OrderTool.markOrderReadyForCreate` | 只读查询 isConfirmed（三个报价字段一致），不签发权限，也不检查年龄；最终时效由 consume 校验 |
| `OrderTool.createOrder` | 落单前调 `consume()`，无权限或已消费抛 BusinessException(409)；在 Agent 工具循环中的对外表现以事件/工具结果为准 |

---

## 四、实现拆解

### 4.1 取消令牌：`CANCEL` Lua 的原子消费

```
HGET readyForCancel ~= ARGV[1]（订单号）  → return 0   （令牌不匹配 / 不存在）
now - cancelVerifiedAt > 300000           → return 0   （超过 5 分钟）
HDEL readyForCancel, cancelVerifiedAt     → return 1   （一次性消费）
```

「校验令牌匹配 + 绑定订单号 + 5 分钟时效 + 删除」在同一次 Lua 内完成——并发提交两个 `cancelOrder`，只有一个走到 `HDEL` 并返回 1。令牌值是**订单号本身**（不是随机数）：这天然绑定「签发时检查的订单」与「执行时取消的订单」，模型不能拿订单 A 的预检结果去取消订单 B。

时效取 5 分钟的理由：预检里算了取消费（按接单分钟数），时间越长费用与真实结算偏差越大，短时效保证「报价的取消费」与「结算的取消费」几乎一致。

### 4.2 订单确认令牌：报价 → 确认 → 消费的三段门禁

```
quoted()    → quoteId（UUID）+ quoteCreatedAt + 24h TTL   ← 服务端签发
begin()     → 校验有效报价存在 → break=yes → 返回 quoteId 作为 confirmationId
CONFIRM Lua → 校验 quoteId 匹配 + 15 分钟时效
              → break=resuming + confirmationAccepted + confirmedQuote/ReadyForCreate=quoteId
CONSUME Lua → 校验 15 分钟时效 + confirmedQuote == ReadyForCreate == quoteId
              → HDEL ReadyForCreate + confirmedQuote      ← 一次性
```

与取消令牌的三个差异：

1. **多了一道人**：`confirm` 事件把 confirmationId 交给前端，用户点击「确认 / 拒绝」后回传——令牌的签发条件包含「用户的显式决定」，不只是「预检通过」；
2. **时效 15 分钟**（报价有效期）：`CONFIRM` 仅在 accepted=true 时校验 `quoteCreatedAt`，拒绝不检查年龄；`CONSUME` 始终检查，超时返回 409；
3. **幂等重放**：`break` 已是 `resuming` 且 `confirmationAccepted` 相同的决定 → `CONFIRM` 直接返回 1；但接受的时效校验先于此分支，且 HTTP 层可能先被执行锁拒绝。不同决定或状态已清理 → 0 → 409。幂等仅限这个 Lua 状态窗口，不覆盖整个恢复请求。

### 4.3 `createOrder` 的权限模型

`markOrderReadyForCreate` 是**只读查询**（读 `isConfirmed` 供流程判断）；真正的权限在 `createOrder` 落单前调 `consume()`：

- 没有 `ReadyForCreate`（用户没确认 / 已被消费）→ 409「尚未取得有效的用户确认」；
- `consume` 成功后字段即删——**同一份已签发创建权限最多成功消费一次**（消费后业务执行仍可能失败），模型无法自授权限或重复下单。

### 4.4 `cancelOrder` 的残留防御

`cancelOrder` 在 `CANCEL` Lua 之外仍有：`hasKey` 预检（对用户返回更友好的引导文案）、`finally` 里的冗余 `delete`（业务执行异常时兜底清理）。真正的原子消费在 Lua 内。finally 是无条件删除 readyForCancel；在正常 /c、/r 单轮锁路径下同会话工具不会并发，但若其他调用路径并发签发新令牌，旧调用的 finally 可能删除新值，不能称为所有场景下无害。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 令牌放 `chat:info` Hash 字段 | 独立 key | 令牌粒度是会话（工具在会话上下文中执行）；复用现有结构；字段级 `HGET`/`HDEL` 原子 |
| 令牌值绑定业务对象（订单号 / quoteId） | 随机数 + 映射 | 值即绑定，免映射表；「值等于订单号」一步完成归属校验 |
| Lua 原子消费 | 读 → 判断 → 删 三条命令 | 三条命令之间存在并发窗口（两个请求都能通过判断）；一次性语义必须原子 |
| 取消 5 分钟 / 报价 15 分钟时效 | 无时效 / 统一时效 | 取消：费用快照保鲜；报价：给用户留决策时间，同时限制「旧价格落单」 |
| 幂等重放（确认令牌） | 重复确认直接拒绝 | 前端重试 / 网络重发是常态，幂等让「相同决定」可安全重试，不同决定才拒绝 |
| `markOrderReadyForCreate` 只读 | 工具签发权限 | 模型可调用的工具不应有「授权」能力；权限签发只在 `confirm`（用户驱动）与 `consume`（消费时校验） |

---

## 六、边界与已知问题

1. **令牌消费与业务执行分离**：`CANCEL` / `CONSUME` 消费成功后，真正的业务调用（`cancelOrder` / `createOrder`）仍可能失败——此时令牌已删，用户需重新走预检 / 确认。已知残留窗口，代价是「多一次交互」而非数据错误。
2. **令牌无独立 TTL**：字段挂在 `chat:info` 上，寿命受该 key 的 TTL 支配（`quoted` 后 24 小时；会话锁定或恢复检查时改为 60 分钟，POST 恢复还会 persist）。语义时效靠脚本内的「签发时间比对」（5 / 15 分钟），不靠 Redis TTL——**时效与 key 寿命解耦**是有意的（key 过期回收令牌属兜底而非主判定）。
3. **取消令牌的 `hasKey` 预检 + `finally` 冗余删除**（4.4）：主门禁在 Lua；finally 不校验新值归属，正常同会话串行执行是其前提。
4. **槽位字段（`saveNewOrderParam`）不是令牌**：它是状态积累（参数逐步补齐），`isNewOrderReady` 只校验齐全性，没有消费语义——「参数齐了」不会被用掉。

---

## 七、如何验证

```bash
# 1. 取消令牌全生命周期
#    （对话中让 Agent 对接单超 5 分钟的订单执行 verifyCancelConditions）
redis-cli HGET chat:info:<chatId> readyForCancel      # 期望：订单号
redis-cli HGET chat:info:<chatId> cancelVerifiedAt    # 期望：毫秒时间戳
#    执行 cancelOrder 成功后：
redis-cli HGET chat:info:<chatId> readyForCancel      # 期望：(nil)（已被 CANCEL Lua 消费）

# 2. 伪造令牌（跨订单盗用）：手工改掉 readyForCancel 的值
redis-cli HSET chat:info:<chatId> readyForCancel 999999
#    再对订单 123 调 cancelOrder → 返回「取消确认对象不匹配、已过期或已使用」
#    （CANCEL Lua 的 HGET 比对失败，return 0）

# 3. 时效验证：手工把 cancelVerifiedAt 改成 6 分钟前的时间戳
redis-cli HSET chat:info:<chatId> cancelVerifiedAt 1730000000000
#    （确保当前时间 - 该值 > 300000）→ cancelOrder 被拒

# 4. 订单确认令牌
redis-cli HGET chat:info:<chatId> quoteId             # 报价后存在
redis-cli HGET chat:info:<chatId> break               # begin 后为 yes；confirm 后为 resuming
redis-cli HGET chat:info:<chatId> ReadyForCreate      # confirm(accepted) 后 = quoteId
#    createOrder 成功后：
redis-cli HGET chat:info:<chatId> ReadyForCreate      # 期望：(nil)（已被 CONSUME 消费）
#    同一会话再次 createOrder → 409「本次确认已使用」

# 5. 幂等重放：对同一 confirmationId 重复提交相同 confirmed
#    → 仅 break=resuming 且尚未被 resume 清理时，同决定可幂等；并发 HTTP 请求可能先被执行锁拒绝
#    → resume 补应答后会删除 break/confirmationAccepted，完成后重放不保证成功
```

---

## 八、延伸阅读

- [`25-agent-hitl-state.md`](25-agent-hitl-state.md) —— `break` 状态机与确认链路的完整时序
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `CANCEL` / `CONFIRM` / `CONSUME` 三段内联脚本速查
- [`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md) —— 「模型不能自授创建权限」的安全测试
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— 同一个 `chat:info` Hash 上的会话锁
