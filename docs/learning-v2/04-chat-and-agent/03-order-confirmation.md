# 第 18 章 订单确认、恢复与消费：从报价到一次性创建

> 林小雨（虚构，`userId=7000001`）已经和订单 Agent 把起点、终点、车型聊清楚：成都东站到天府三街，快车，实时单，不加急。模型调了算价工具，Redis 里多了一次报价；下一轮模型调 `notifyUser`，服务端把订单摘要和一个编号发给前端，然后**主动结束这一轮**。林小雨点了“确认”，浏览器带着那个编号再发一次请求；服务端确认编号、放行恢复，模型继续跑，调 `createOrder`，订单才真正落库。
>
> 这条链里最容易被写成“模型让用户确认，用户同意后模型创建订单”的童话版本。真实情况是：**模型只负责发起暂停和转述；编号由服务端生成；用户的决定由服务端脚本核验；创建权限由 Redis 里的一次性字段持有；订单金额在创建时会被服务端重新计算。** 本章就按源码把这五句话展开。
>
> **本章没有启动服务、运行测试、连接 Redis/MySQL/MongoDB、发送 HTTP/模型请求或读取真实凭据**。除明确标注“测试源码阅读”的地方，结论都来自仓库源码静态推导；所有订单号、金额、里程、时间和事件序列都是虚构示意，不是实测输出。**C4 主讲完整的三层记忆与工具结果反查，C5 主讲外部工具与提示词边界**；本章只写恢复所需的最小记忆上下文，未编写的章节只标“计划”，不建立失效链接。

## 学习目标

读完本章，你应该能：

1. 按真实顺序复述“报价 → 准备确认 → 暂停 → 用户接受或拒绝 → 恢复 → 一次性消费 → 创建订单”，并指出每一步改的是 Redis、MySQL，还是只尝试发一个事件。
2. 画出 `chat:info:{chatId}` 中与确认有关的字段，说明每个字段的写入者、删除者、生命周期，以及“24 小时 TTL”在什么条件下才成立、哪些路径会改掉它。
3. 逐行解释 `CONFIRM` 与 `CONSUME` 两个 Lua 的检查与写入，区分接受和拒绝在报价年龄上的差异，说明“重复同一决定”“相反决定”“编号错误”“状态不符”各自的真实结果。
4. 按真实先后追踪 `POST /chat/v2/r/{id}`：同步的 UUID 校验、订阅时执行的执行锁与归属检查、后台的 prompt 回填、限流、`OrderConfirmation#confirm`、`OrderAgent#resume` 与工具应答持久化；区分 Java 层的 400/409 业务异常、有效拒绝、事件发送尝试与客户端是否真的收到。
5. 说明 `consume` 为什么发生在数据库创建之前、创建时哪些 DTO 字段会被覆盖、消费成功但后续失败或响应丢失时哪些能断言、哪些只能标“未知”。
6. 区分“确认状态、会话 `locked`、单轮执行锁、SSE 事件、`ToolResponseMessage`”五种对象，并解释直接 `POST /order` 为什么不经过这套确认门禁。

## 前置知识与阅读时间

- 必读：[第 16 章 一次对话与事件流](01-chat-round-and-sse.md)——请求入口、单轮执行锁、`autoConnect(2)`、五种事件与保存边界。
- 必读：[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)——`OrderAgent` 的工具分派、`notifyUser` 特判、工具回执与 `ToolContext`。
- 必读：[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)——服务端计价公式、Agent 报价与“二次规划”的差价、DTO 副本覆盖。
- 必读：[第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)——订单状态机、创建订单的写入与后置副作用。
- 相关：[第 6 章 权限、归属与防护](../01-auth/02-authorization-and-defense.md)——身份、角色、归属两层模型。
- 相关：[第 3 章 数据存在哪，事务管到哪](../00-foundations/03-data-and-transactions.md)——创建订单链没有本地事务注解。
- 相关：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——Lua 的原子范围、TTL 与 SSE。
- 后续主讲：[C4 三层记忆与工具结果反查](04-memory-and-tool-results.md)、[C5 外部工具与提示词边界](05-external-tools-and-prompts.md)（均已通过复审）。
- 阅读时间：约 100～130 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、API 文档与测试写完整仓库相对路径。

---

## 零、先记住十个事实

1. **`quoteId` 就是 `confirmationId`**：`OrderConfirmation#quoted` 用 `UUID.randomUUID()` 生成，`begin` 把它返回，再写进 `confirm` 事件的 JSON。**正常 Agent 路径不把它作为模型工具回执返回**；这不等于模型绝无其他途径获知、猜测或伪造。服务端只信 Redis 里的当前 `quoteId`，编号保密本身不是授权基础。
2. **`begin` 只检查两个字段存在**：`quoteId` 与 `ReadyforConfirm`。它不校验报价年龄、金额、用户身份，也不校验会话是否已锁定。
3. **`break` 有两个写入值（字段也可以不存在）**：`yes` 表示“等用户决定”；`resuming` 表示“确认脚本已受理，恢复线程即将消费这个状态”；`resume` 成功后删除该字段。它不是会话 `locked`，也不是单轮执行锁。
4. **`CONFIRM` 接受要过 15 分钟**（`> 900000` 毫秒才拒绝，正好 900000 仍可接受）；**拒绝不查年龄**。在 `break=resuming` 且决定相同、接受仍未超龄时，脚本返回 1（可重放）；决定相反一定返回 0。
5. **`CONSUME` 也查 15 分钟，锚点是 `quoteCreatedAt`（报价时间），不是用户点确认的时间**。成功后它只 `HDEL` 两个字段；这是 Redis 内的一次性操作，发生在 MySQL 创建订单之前。
6. **`isConfirmed` 只比较三个字符串**（`quoteId`、`confirmedQuote`、`ReadyForCreate`），不查年龄、不查 `locked`。所以 `markOrderReadyForCreate` 可能说“可以创建”，而随后 `createOrder` 的 `consume` 因超龄拒绝。
7. **`/r` 的真实顺序**分两段：控制器里先做 UUID 格式校验（同步，返回 `Flux` 之前）；`Flux.defer` 的 supplier 在**订阅时**才执行单轮执行锁与归属检查；随后 `doOnSubscribe` 把后续工作提交到后台线程：prompt 回填 → prompt 非空 → 限流 → 再次归属 → `confirm` → 拼 feedback → `OrderAgent#resume`。
8. **恢复读的是数据库事实**：`OrderAgent#resume` 用 `MessageMemory#getPersisted`（只读 MySQL）而不是 `get`；`pendingToolCall` 从 Redis 读；工具应答先写 MySQL，再删 `break/pendingToolCall/confirmationAccepted`，最后才进模型循环。
9. **创建时金额会被重算**：`RideOrderServiceImpl#createOrder` 重新调高德规划、重新调计价，覆盖 DTO 里的 `estPrice`、`estDistance`、`radio` 和 `mongoTraceId`。服务端记录的是“当前 `quoteId` 被接受”，不是金额/槽位快照；用户看到的摘要与最终落库金额可能不同，不能把确认写成“这套参数已被逐项锁定”。
10. **这套门禁只覆盖 Agent 工具路径**：直接 `POST /order` 有登录权限、身份覆盖、参数校验与二次计价，但**不查** `OrderConfirmation`。不要写成“所有创建订单入口都必须先确认”。

---

## 一、学习地图与第一个虚构实例

### 1.1 先看没有令牌的朴素方案会怎样

如果只靠模型自觉，流程会是：“模型把订单摘要写给用户 → 用户回复‘确认’ → 模型自己判断用户同意了 → 调 `createOrder`”。它至少有三个洞：

- **模型可以编造“用户已确认”**：提示词不是状态机，模型可能在用户只是问价时就创建订单。
- **同一句“确认”可以被重放**：网络重试、两个标签页、客户端重复提交，都可能触发两次创建。
- **模型无法证明自己确认的是哪一次报价**：用户改过终点、模型重新算过价之后，旧的“确认”仍然存在。

当前实现把这三件事拆开：**服务端生成报价编号 → 服务端记录“等确认”状态 → 服务端脚本核验用户的决定 → 服务端发一张只能消费一次的创建许可**。模型在这条链里只做两件事：请求暂停（`notifyUser`）和在被恢复后继续调工具。

### 1.2 七个容易混的编号与状态

| 名字 | 在本项目里的具体含义 | 谁生成/谁写 | 不是什么 |
|---|---|---|---|
| `chatId` | 会话 UUID；Redis key 是 `chat:info:{chatId}` | 客户端（`/newuuid` 或任意合法 UUID）；服务端校验格式与归属 | 不是用户身份，也不证明会话属于谁 |
| `userId` | 乘客身份 | Token → `AuthTokenInterceptor` → `UserTokenContext`（ThreadLocal）→ Controller；Agent 侧经 `ToolContext` 进入工具与 DTO | 不信任请求体里自称的 userId |
| `quoteId` | 一次报价的编号 | `OrderConfirmation#quoted` 里的 `UUID.randomUUID()`，服务端生成 | 不是模型生成，也不是订单号 |
| `confirmationId` | `confirm` 事件里交给前端的编号，值等于 `quoteId` | 同一个 UUID，由 `begin` 返回并写进事件 JSON | 不是“第二种令牌”，也不是订单号；正常路径不把它当工具回执给模型，但编号不是靠保密授权 |
| `pendingToolCall` / `toolCallId` | 等待用户决定的那次 `notifyUser` 工具调用的 id | 模型响应里的 `ToolCall.id()`；`begin` 把它写进 Redis | 不是授权凭证，只用于把工具调用和迟到应答配对 |
| `break` | Redis 字段：`yes` / `resuming` / 不存在 | `begin` 写 `yes`；`CONFIRM` 写 `resuming`；`resume` 删除 | 不是会话 `locked`，也不是执行锁 |
| `ReadyforConfirm` | “已经算过价”的标记 | `quoted` 写 `true`；`saveNewOrderParam` 删除；确认/消费后**不删** | 不是“用户已确认” |
| `ReadyForCreate` / `confirmedQuote` | 创建许可；两者都等于当前 `quoteId` 才算有效 | `CONFIRM` 接受时写；拒绝、重新报价、`CONSUME` 删除 | 不是订单，也不是“已创建” |
| `confirmationAccepted` | 本次决定 `yes`/`no` | `CONFIRM` 写；`resume` 删除 | 不是“订单已创建” |

字段大小写以源码为准，容易写错：`ReadyforConfirm`（小写 f）、`ReadyforRoute`（另一个字段）、`ReadyForCreate`（大写 F/C）、`confirmedQuote`。Redis 的 Hash 字段名区分大小写，写错就是“字段不存在”。

### 1.3 一个虚构报价

假设（全部虚构，仅用于讲解）：

- 起点“成都东站”，终点“天府三街”，快车（`VEHICLE_TYPE=1`），实时（`IS_RESERVATION=0`），不加急（`IS_EXPEDITED=0`）。
- 模型调 `getEstRouteAndPrice(noRoute=false)`，高德返回 12.4 km、1680 秒，计价返回 38.60 元。
- Redis 里于是有一套订单槽位和一套价格拆分；`quoted` 生成 `quoteId=6f1c…a7`（示意 UUID），`quoteCreatedAt` 记为当前毫秒。
- 下一轮模型调 `notifyUser`，`begin` 把 `pendingToolCall` 记为那次工具调用的 id（示意 `call_9`），`break=yes`。

前端收到的 `confirm` 事件（示意，字段按 `OrderInfoEnum` 的大写名，值是字符串）：

```json
{"type":"confirm","payload":"{\"VEHICLE_TYPE\":\"1\",\"IS_RESERVATION\":\"0\",\"IS_EXPEDITED\":\"0\",\"START_ADDRESS\":\"成都东站\",\"START_LAT\":\"30.63\",\"START_LNG\":\"104.14\",\"END_ADDRESS\":\"天府三街\",\"END_LAT\":\"30.55\",\"END_LNG\":\"104.06\",\"EST_PRICE\":\"38.60\",\"EST_DISTANCE_KM\":\"12.4\",\"EST_TIME\":\"1680\",\"confirmationId\":\"6f1c…a7\"}"}
```

注意三层：外层是 `AgentEvent`；`payload` 是一个**字符串**，里面才是订单 JSON；订单 JSON 里没有 `MONGO_TRACE_ID`，也没有价格拆分字段（`PRICE_RADIO` 等）。`confirmationId` 是服务端塞进去的，不是模型写的；但“没有把它作为工具回执返回”只描述正常代码路径，不能推出“模型永远不可能知道这个值”。这些字段的具体来源见 2.6。

### 1.4 七步学习地图

| 步 | 谁触发 | 改什么 | 本章小节 |
|---|---|---|---|
| 1 报价 | 模型调 `getEstRouteAndPrice` | 读取订单槽位；写 `EST_PRICE`、`EST_DISTANCE_KM`、`EST_TIME`、`MONGO_TRACE_ID`（需要规划时）、`quoteId`、`quoteCreatedAt`、`ReadyforConfirm`；可能写 Mongo 预览轨迹 | 二 |
| 2 准备确认 | 模型调 `notifyUser` | `pendingToolCall`、`break=yes`；保存暂停前的聊天消息 | 三 |
| 3 暂停 | 服务端发 `confirm` 事件并结束 sink | 只尝试发事件；不改数据库 | 三 |
| 4 用户决定 | 前端 `POST /r` 带 `confirmationId` 与 `confirmed` | `CONFIRM` 写 `break=resuming`、`confirmationAccepted`，接受时写创建许可 | 四、五 |
| 5 恢复 | `OrderAgent#resume` | 先补 `ToolResponseMessage` 到 MySQL，删 `break/pending/accepted`，再跑模型循环 | 五 |
| 6 消费 | 模型在恢复后调 `createOrder` | `CONSUME` 删 `ReadyForCreate/confirmedQuote` | 六 |
| 7 创建 | `RideOrderService#createOrder`，返回后仍在同一个工具方法里 | 雪花订单号 → 坐标检查 → 重新路线 → 新轨迹 id/距离 → 重新计价 → Mongo 新轨迹 → MySQL 插单 → 状态缓存/延迟队列/GEO；`createOrder` 返回后：清预览轨迹 → 读三个展示字段 → `lockChat` → 写 `OrderId` → 格式化返回文本 | 六 |

### 1.5 本章不展开的部分

- **完整记忆机制**：三层读写、租约回填、窗口截断、工具结果反查，属于 C4。本章只讲 `getPersisted` 为什么读 MySQL、`ToolResponseMessage` 为什么要先保存。
- **地图/天气/反序列化/提示词细节**：属于 C5。本章只把系统提示词当作“模型行为约束”引用。
- **分类与工具循环的通用机制**：属于 C2。本章只复用“模型请求工具、服务端执行、结果回填”的骨架。

---

## 二、报价：`quoted` 到底写了什么

### 2.1 谁调用 `quoted`

`OrderConfirmation#quoted` 只有一个业务调用方：`OrderTool#getEstRouteAndPrice`。它有两个分支，都会在最后调 `quoted`：

```java
// agentbase/tool/OrderTool.java#getEstRouteAndPrice（真实节选）
if(noRoute){
    PriceEstimateVO estPrice = getEstPrice(redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name()),
            redisGet(chatInfoKey, "EST_TIME"), ...);
    putPricePara(chatInfoKey, estPrice);
    redisPut(chatInfoKey, OrderInfoEnum.EST_PRICE.name(), estPrice.getEstPrice().toPlainString());
    redisDel(chatInfoKey, "ReadyforRoute");
    orderConfirmation.quoted(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
    return "新订单完成算价，……请使用notifyUser()通知用户确认。";
}
String mongoId = UUID.randomUUID().toString();
EstRouteVO estRoute = amapRouteService.getDrivingRouteEst(...);
PriceEstimateVO estPrice = getEstPrice(estRoute.getEstKm(), estRoute.getEstTime(), ...);
orderRouteService.addOrder(mongoId, estRoute);
putPricePara(chatInfoKey, estPrice);
redisPut(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name(), mongoId);
redisPut(chatInfoKey, OrderInfoEnum.EST_PRICE.name(), estPrice.getEstPrice().toPlainString());
redisPut(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name(), estRoute.getEstKm());
redisPut(chatInfoKey, "EST_TIME", estRoute.getEstTime());
redisDel(chatInfoKey, "ReadyforRoute");
orderConfirmation.quoted(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
```

三个前置事实：

- `noRoute=true` 只在 `MONGO_TRACE_ID` 存在、`EST_TIME` 与 `EST_DISTANCE_KM` 都存在时生效；否则方法自己把 `noRoute` 改回 `false`（第 146～154 行）。所以“不重新规划、只算价”是一条**有条件**的捷径。
- `ReadyforRoute` 由 `isNewOrderReady` 在参数齐备时写入（`OrderTool.java` 第 126 行）。`getEstRouteAndPrice` 会检查它，算完再删。
- **每次算价都会生成新的 `quoteId`**；但第二次算价前必须让 `ReadyforRoute` 重新成立（通常再调一次 `isNewOrderReady`）。如果模型在同一批里直接连调两次 `getEstRouteAndPrice`，第二次会先被 `ReadyforRoute == null` 挡回“请使用 isNewOrderReady()”，第一次的报价仍然有效。

### 2.2 Redis 的实际 key 与字段

key 由 `RedisKeyConstants#chatInfoKey` 生成：`chat:info:{chatId}`。这是一个**多个机制共用的 Hash**：分类缓存、会话锁定、订单槽位、报价/确认、订单号都写在这里。与确认有关的字段如下。

| 字段 | 写入者 | 删除者 | 生命周期与备注 |
|---|---|---|---|
| `quoteId` | `quoted`（`HSET`） | `saveNewOrderParam`（`HDEL`）；整 key 过期 | 每次算价换新；确认 JSON 里的 `confirmationId` 就是它 |
| `quoteCreatedAt` | `quoted`（`HSET`，`System.currentTimeMillis()` 字符串） | 没有主动删除；整 key 过期 | 15 分钟年龄校验的锚点；`saveNewOrderParam` **不删**它，但 `quoteId` 已删，旧值不再参与判定 |
| `ReadyforConfirm` | `quoted`（`HSET true`） | `saveNewOrderParam`；整 key 过期 | **确认/消费后不删**，是“粘性”标记，不是一次性令牌 |
| `ReadyforRoute` | `isNewOrderReady` | `getEstRouteAndPrice`、`saveNewOrderParam` | 只表示“参数齐备、可以算价” |
| `pendingToolCall` | `begin`（`HSET`，值=`toolCall.id()`） | `OrderAgent#resume`；整 key 过期 | 恢复时构造 `ToolResponseMessage` 的唯一依据 |
| `break` | `begin` 写 `yes`；`CONFIRM` 写 `resuming` | `OrderAgent#resume`；整 key 过期 | `yes` 会挡 `/c`；`resuming` 不挡 `/c`（只靠执行锁） |
| `confirmationAccepted` | `CONFIRM` 写 `yes`/`no` | `OrderAgent#resume`；整 key 过期 | 用于判断“重复同一决定” |
| `confirmedQuote` | `CONFIRM` 接受时写（值=`quoteId`） | 拒绝、`quoted`、`saveNewOrderParam`、`CONSUME`；整 key 过期 | 创建许可的一半 |
| `ReadyForCreate` | `CONFIRM` 接受时写（值=`quoteId`） | 同上 | 创建许可的另一半；`OrderTool#createOrder` 先看它是否存在 |
| `EST_PRICE`、`EST_DISTANCE_KM`、`EST_TIME`、`PRICE_RADIO`、`MILEAGE_FEE`、`LONG_DISTANCE_SURCHARGE`、`TIME_FEE`、`EXPEDITED_FEE` | `getEstRouteAndPrice`（槽位与拆分） | `saveNewOrderParam` 删 `EST_TIME`/`EST_DISTANCE_KM`，不删 `EST_PRICE` 与拆分；整 key 过期 | 报价内容；`createOrder` 组装 DTO 时会读，但金额随后被服务端覆盖 |
| 订单槽位（`VEHICLE_TYPE`、`IS_RESERVATION`、`IS_EXPEDITED`、`SCHEDULED_TIME`、`START_*`、`END_*`） | `saveNewOrderParam` | 参数格式错误时 `isNewOrderReady` 会删非法字段 | 用户数据；改任一参数都会触发 `saveNewOrderParam` 的整组清理 |
| `classification` | `ChatServiceImpl#chat` 在“不是旧 `ORDER` 缓存分支”时写本轮分类结果 | 无主动删除 | 若 Redis 已有 `classification=ORDER`，`chat` 会提前调用 `OrderAgent` 并 `return`，**不写新分类**；因此它不一定是“每轮最新分类” |
| `locked` | `lockChat`：DB `Update` 后执行 Lua `HSET locked=true` + `EXPIRE 60min`；调用者包括 `OrderTool#createOrder`、`ChatStatusTool#lockSession`、`POST /chat/v2/lock/{id}` | `restoreChat` 写 `false` 并 `PERSIST`；整 key 过期 | 会话锁定，见第八章；TTL 是否变成 60 分钟取决于 Lua 是否成功执行 |
| `OrderId` | `createOrder` 成功后 `HSET` | 无主动删除 | 恢复候选判定会看它是否存在 |

### 2.3 `quoted` 是三条 Redis 命令，不是一次事务

```java
// service/base/OrderConfirmation.java#quoted（真实）
public void quoted(String chatId) {
    var hash = redis.opsForHash();
    String key = RedisKeyConstants.chatInfoKey(chatId);
    hash.delete(key, "ReadyForCreate", "confirmedQuote");
    hash.putAll(key, java.util.Map.of("quoteId", UUID.randomUUID().toString(), "ReadyforConfirm", "true",
            "quoteCreatedAt", String.valueOf(System.currentTimeMillis())));
    redis.expire(key, java.time.Duration.ofHours(24));
}
```

按调用顺序，这里是**三次独立的 Redis 往返**：

1. `HDEL ReadyForCreate confirmedQuote`：先撤掉上一次的创建许可；
2. `HSET quoteId/ReadyforConfirm/quoteCreatedAt`（`putAll` 是单条多字段 `HSET`）：换新报价；
3. `EXPIRE 24h`：刷新整个 key 的 TTL。

它不是 Lua，也不是事务。可推导的边界：

- **第 1 步成功、第 2 步确认未执行**：旧创建许可已撤掉，新报价没写上；`quoteId` 可能还是旧的（如果之前有），`ReadyforConfirm` 也可能仍是旧值。此后 `begin` 可能读到“旧 quoteId + 旧 ReadyforConfirm”，把它当成有效报价返回；客户端可能从未见过这个编号（如果上一次 confirm 未送达），或只见过旧摘要。这是一个**静默的中间态**。
- **第 2 步成功、第 3 步确认未执行**：报价字段在，但 TTL 没被重置。key 可能保留旧的 TTL（例如之前被 `lockChat` 设成 60 分钟），也可能没有 TTL（如果之前被 `restoreChat` `PERSIST` 过）。`quoteId` 能读到，不代表“24 小时内一定在”。
- **命令超时/连接中断**：Java 抛异常，工具 `executeTool` 会把它转成 `Tool execution failed: ...` 文本回执；但**无法判断命令是否已在 Redis 执行**。上一段那两条“下一步确认未执行”的推演，只在能确认“失败命令确实没执行”时成立（例如连接尚未建立就被拒绝）；如果异常来自响应丢失，键值可能已经变成下一状态。`putAll` 是单条 `HSET`，Redis 单命令不会写一半，但“没写一半”不等于“没写”。
- **跨数据库不可能原子**：即使 `quoted` 换成 Lua，也只保证 Redis 侧；Mongo 预览轨迹、MySQL 订单都不在同一个原子范围里。

教材后续说“报价写了 24 小时 TTL”时，准确含义是：**`quoted` 的第 3 条命令若成功，会把整个 `chat:info:{chatId}` 的 TTL 设为 24 小时**；不保证此后没有别的写入者改它。

### 2.4 24 小时 TTL 的适用条件：同一个 key 有四个改 TTL 的路径

| 路径 | 对 `chat:info:{chatId}` 的 TTL 做什么 | 触发时机 |
|---|---|---|
| `OrderConfirmation#quoted` | `EXPIRE 24h` | 每次算价 |
| `ChatManager#lockChat` | Lua `HSET locked=true` + `EXPIRE 60min`（`ChatManager.expireLock`）；DB `sys_chat.locked=true` 先执行 | 三个入口：`OrderTool#createOrder` 成功返回前；`ChatStatusTool#lockSession`；`POST /chat/v2/lock/{id}`。Redis 脚本失败时，DB 可能已改成 true，TTL 可能未改成 60 分钟 |
| `ChatManager#getRestorableChat` | `EXPIRE 60min` | `GET /chat/v2/restore` 命中候选时；`EXPIRE` 的返回值没有检查，key 不存在/命令失败时不能断言已续期 |
| `ChatInfoService#restoreChat` | `PERSIST`（移除 TTL） | `POST /chat/v2/restore` 执行恢复时；先 DB 置 false、Redis 写 `locked=false`，再 `PERSIST`；各步失败互不自动回滚 |
| 其他 `HSET/HDEL`（分类、槽位、`begin`、`CONFIRM`、`resume`） | **不改 TTL** | 各自动作 |

因此：

- 报价刚写完、随后没有任何锁定/恢复动作，且 `EXPIRE 24h` 成功时，TTL 是 24 小时；
- 订单创建成功**且** `lockChat` 的 Lua 成功时，同一个 key 被改成 60 分钟——报价字段即使还在，也只剩 1 小时；若 Redis 脚本失败，TTL 可能仍是旧值或未知，不能断言“订单成功后必然只剩 1 小时”；
- 用户执行过 `POST /restore` **且** `PERSIST` 成功时，key 变成无 TTL，直到下一次 `quoted`/`lockChat` 再设；
- `GET /restore` 在候选命中且 `EXPIRE` 成功时，把 key 续到 60 分钟。

**不要把“24 小时”当成报价年龄。** 报价能否被接受/消费，由 `quoteCreatedAt` 与 `System.currentTimeMillis()` 的差值决定（15 分钟）；TTL 只决定“这些字段还在不在”。两者是不同的时钟、不同的机制。

### 2.5 什么会让旧报价失效

| 动作 | 对旧 `quoteId` 的影响 | 用户可见后果 |
|---|---|---|
| `saveNewOrderParam`（参数和槽位均非空） | 删除 `ReadyforRoute`、`quoteId`、`ReadyforConfirm`、`ReadyForCreate`、`confirmedQuote`、`EST_TIME`、`EST_DISTANCE_KM` | 旧 `confirmationId` 再提交时，`CONFIRM` 第 2 行就因 `quoteId` 不匹配返回 0；`/r` 里 `confirm` 抛 409 业务异常（`guard` 尝试发通用 error，HTTP 状态未实测）。若方法因 `null` 早退，则不清理 |
| 重新准备参数后再次 `getEstRouteAndPrice` | 先重新写 `ReadyforRoute`，再算价；`quoted` 先删创建许可，再写新 `quoteId` | 旧编号立即作废；即使年龄没超，也确认不了。若没有重新准备，第二次算价直接返回“请使用 isNewOrderReady()”，旧编号仍有效 |
| 用户点“拒绝” | `CONFIRM` 删除 `confirmedQuote`/`ReadyForCreate` | 旧编号仍在，`break=resuming`；恢复后模型可以重新报价 |
| `consume` | 删除 `ReadyForCreate`/`confirmedQuote` | 编号还在，但没有创建许可；再 `createOrder` 会得到“订单非可创建态” |
| key 过期 | 所有字段消失 | `CONFIRM` 读到 `quoteId=nil`，返回 0 |

注意 `saveNewOrderParam` 的失效前提：方法在 `params == null || slots == null` 时**早退**，返回“请提供有效订单参数”，不会执行后面的 `HDEL`。只有参数和槽位非空时，它才先删 `ReadyforRoute/ReadyforConfirm/ReadyForCreate/quoteId/confirmedQuote/EST_TIME/EST_DISTANCE_KM`，再逐槽位写入。删除和写入是多条非事务 Redis 命令，失败/响应丢失时不能断言“整组一定清空或一定保留”。

注意 `ReadyforConfirm` 的“粘性”：拒绝或消费之后它仍然存在。所以“有 `ReadyforConfirm`”只意味着“这个会话算过价”，不意味着“报价还有效”。`begin` 依赖它，这也是为什么 `begin` 不能当作授权。

### 2.6 `confirm` 事件的 JSON 里到底有什么

`OrderAgent#handleNotifyUser` 先遍历 `OrderInfoEnum`，把 Redis 里存在的槽位放进 `Map<String,String>`；跳过 `MONGO_TRACE_ID`；再单独取 `EST_TIME`；最后塞入 `confirmationId`。所以：

- **字段名**是枚举的 `name()`（全大写），不是中文描述，也不是 DTO 字段名；
- **值都是字符串**（Redis Hash 读出来就是字符串）；
- **缺失字段直接不出现**，不是 `null`；
- `MONGO_TRACE_ID`（Mongo 预览轨迹 id）**不发**给前端；
- `EST_TIME` 不在枚举里，但会以 `"EST_TIME"` 出现；
- 价格拆分（`PRICE_RADIO` 等）**不在枚举里，所以不发**；
- `confirmationId` 由服务端 `begin` 的返回值写入；正常路径只把它放进 `confirm` 事件，不作为模型工具回执。它仍可能被猜测或经其他通道获知，授权不靠编号保密。

这也意味着：前端看到的摘要 JSON 是 `handleNotifyUser` 在**暂停时从 Redis Hash 读出的一份展示快照**；模型在同一轮之前输出的自然语言摘要来自模型，两者可能不一致（模型可能把价格说错）。这份 JSON 没有哈希、金额签名或槽位快照，`confirm` 只匹配当前 `quoteId`，`createOrder` 又会在 `consume` 前重新逐字段读 Hash，所以“展示快照”和“最终 DTO”不是原子绑定的同一份对象。如果要展示“服务端此刻的报价”，可以用 `confirm` 事件里的 `EST_PRICE`，但不要把它理解成用户看过且后续参数绝不变化的合同。

### 2.7 15 分钟是“报价年龄”，不是“等待时间”

`quoteCreatedAt` 在 `quoted` 时写入，之后**不会**因为用户打开页面、点击确认、模型思考而更新。接受和消费都用同一个锚点：

- 接受：`confirm` 脚本用 `System.currentTimeMillis() - quoteCreatedAt`；
- 消费：`consume` 脚本用另一个 `System.currentTimeMillis()`；
- 中间隔着的模型调用、工具调用、数据库操作都消耗同一段 15 分钟。

所以“用户在 15 分钟内点了确认”不等于“订单一定能在 15 分钟内创建”。如果用户在第 14 分钟确认，模型恢复后又算了 2 分钟路况，`consume` 会因 16 分钟而拒绝。这个差异在 7.3 的时间线里展开。

---

## 三、准备确认：`notifyUser` 与 `begin`

### 3.1 同批工具：`notifyUser` 之前已经执行，之后只写“尚未执行”

`OrderAgent#handleToolCalls` 按模型返回的顺序遍历工具调用。遇到 `notifyUser` 时：

```java
// agents/OrderAgent.java#handleToolCalls（真实节选）
if ("notifyUser".equals(toolName)) {
    // 同批后续调用没有执行，显式应答；确认调用的响应留到真实用户反馈后追加。
    int index = toolCalls.indexOf(toolCall);
    for (int i = index + 1; i < toolCalls.size(); i++) {
        var deferred = toolCalls.get(i);
        toolResponses.add(new ToolResponseMessage.ToolResponse(deferred.id(), deferred.name(),
                "尚未执行：请等待用户确认后按需重新调用。"));
    }
    if (!toolResponses.isEmpty()) history.add(ToolResponseMessage.builder().responses(toolResponses).build());
    handleNotifyUser(toolCall, history, originalHistorySize, sink, chatId, userId, toolContext);
    return; // 中断循环，等待用户确认
}
String result = executeTool(toolName, args, toolContext, sink);
toolRepPointerTool.recordCalling(toolCall.id(), result, toolContext);
toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result));
```

由此可确定：

- **同批在 `notifyUser` 之前的普通工具已经执行**：它们的结果在 `toolResponses` 里，可能已经写 Redis/MySQL/外部服务。
- **同批在 `notifyUser` 之后的工具没有执行**：只给它们写“尚未执行”的文本回执，`recordCalling` 不会被调用（`SecurityRepairRegressionTest#notifyBatchPreservesEveryNonConfirmationResponse` 断言了 `after` 没有被记录）。
- **`notifyUser` 自己的应答不在这里**：它的 `ToolResponse` 等 `/r` 的用户反馈后再补。如果 `notifyUser` 是批次里的第一个工具，`toolResponses` 为空，连 `ToolResponseMessage` 都不会加；历史以一条“带未完成工具调用”的 `AssistantMessage` 结束（`MessageWindow` 允许最后一个批次未闭合）。
- **工具循环被 `return` 中断**：不会递归调用下一轮模型，直接进入暂停流程。

### 3.2 `begin` 的真实检查：只有“存在”，没有“有效”

```java
// service/base/OrderConfirmation.java#begin（真实）
public String begin(String chatId, String toolCallId) {
    String key = RedisKeyConstants.chatInfoKey(chatId);
    Object quote = redis.opsForHash().get(key, "quoteId");
    if (quote == null || redis.opsForHash().get(key, "ReadyforConfirm") == null) {
        throw new BusinessException(409, "请先完成有效报价");
    }
    redis.opsForHash().putAll(key, java.util.Map.of("pendingToolCall", toolCallId, "break", "yes"));
    return quote.toString();
}
```

它检查的只有两件事：`quoteId` 存在、`ReadyforConfirm` 存在。**不检查**：

- `quoteCreatedAt` 是否在 15 分钟内；
- `EST_PRICE` 等槽位是否齐全；
- 会话是否属于当前用户（这由上游 `assertOwned` 保证，不是 `begin`）；
- 会话是否已 `locked`；
- 这个 `toolCallId` 是否真的来自当前模型响应（它由 `handleToolCalls` 传入，不在工具参数里；模型不能直接指定）。

因此 `begin` 的 409 只表示“没有报价或没算过价”，不表示“报价一定有效”。它写 `pendingToolCall` 和 `break=yes` 用的是 `putAll`（一条多字段 `HSET`），但**两个 `HGET` 与这条 `HSET` 不是原子的**：理论上两次 `begin` 可以交错覆盖 `pendingToolCall`。实际并发主要被执行锁挡住（7.6），但锁不是 Redis 脚本级的保证。

### 3.3 暂停时的精确顺序

```java
// agents/OrderAgent.java#handleNotifyUser（真实节选，省略日志）
Map<String, String> orderParams = new HashMap<>();
for (OrderInfoEnum key : OrderInfoEnum.values()) {
    if(key.equals(OrderInfoEnum.MONGO_TRACE_ID)) continue;
    Object value = stringRedisTemplate.opsForHash().get(chatInfoKey, key.name());
    if (value != null) orderParams.put(key.name(), value.toString());
}
Object time = stringRedisTemplate.opsForHash().get(chatInfoKey, "EST_TIME");
if (time != null) orderParams.put("EST_TIME", time.toString());

try {
    String confirmationId = orderConfirmation.begin(chatId, toolCall.id());
    orderParams.put("confirmationId", confirmationId);
    String orderJson = objectMapper.writeValueAsString(orderParams);
    saveNewMessages(userId, chatId, history, originalHistorySize);
    stringRedisTemplate.opsForHash().put(chatInfoKey, "break", "yes");
    sink.tryEmitNext(AgentEvent.comfirm(orderJson));
    sink.tryEmitComplete();
} catch (Exception e) {
    sink.tryEmitNext(AgentEvent.error("序列化订单参数失败: " + e.getMessage()));
    sink.tryEmitComplete();
}
```

真实顺序是：

1. **循环读 Hash**（在 `try` 外）：字段多、往返多；任一次 Redis 读失败都会抛出 `handleNotifyUser`，**不会**进入下面的 `catch`，而是冒泡到模型完成回调，最终由 `AgentStreams` 的 failure consumer 处理（C2 已讲）。
2. `begin(chatId, toolCall.id())`：读两个字段、写 `pendingToolCall` + `break=yes`。**注意：`break` 在这一步就已经是 `yes`。**
3. `orderParams.put("confirmationId", ...)`：编号进入 JSON。
4. `objectMapper.writeValueAsString`：序列化；HashMap 的键顺序不保证。
5. `saveNewMessages`：把从 `originalHistorySize` 起的新消息保存（通常是刚才的 `AssistantMessage`，以及可能的“before/after”工具回执）。
6. 再 `HSET break=yes`：与第 2 步重复；不是新增语义，只是冗余写法。
7. `sink.tryEmitNext(confirm)`：**尝试**发事件，返回值没检查。
8. `sink.tryEmitComplete()`：**尝试**结束 sink。

源码注释写着“1. 发出 confirm；2. 保存消息；3. 设置 break”，但**实际代码顺序是 begin → 序列化 → 保存 → 再写 break → 发事件 → 结束**。以代码为准。

### 3.4 暂停时到底保存了什么

`saveNewMessages` 只在 `history.size() > originalHistorySize` 时，把新增的 `[AssistantMessage, ToolResponseMessage?]` 一次性交给 `MessageMemory#save`。

- 若 `notifyUser` 前面有已执行的工具：`history` 末尾会多一条 `ToolResponseMessage`，里面包含“前面工具的真实结果 + 后面工具的‘尚未执行’”；这条和 `AssistantMessage` 由 `MysqlMemory#append` 在**它自己的本地事务**里逐条插入，通常是两行；这不是与 Redis/heap 的跨存储事务。
- 若 `notifyUser` 是第一个工具：只保存 `AssistantMessage`，历史里没有对应的 `ToolResponse`；`MessageWindow` 允许“最后一个批次未闭合”，所以恢复时能读。
- `notifyUser` 自己的应答**没有**保存；它要等 `/r`。

这里的保存是“暂停前消息”的唯一一次落库。**它失败不等于“数据库里没有这一轮”**，要分两种：

1. `MysqlMemory#append` 的本地事务未提交/回滚（或 `chat` 行不存在、已清空，直接抛 410）→ 这批消息确实没落库；但客户端此前已经通过 `message` 事件看到了模型流出的文本，这些文本本身不是数据库行。
2. MySQL 已经写入，随后 `redisMemory.appendIfVersionMatch`、`heapMemory.appendIfVersionMatch` 或 `chatManager.updateChatTime` 抛异常 → `MessageMemory#save` 先清 heap 再抛出，**MySQL 里可能已经有这条 `AssistantMessage`/`ToolResponseMessage`**。

这也是为什么暂停阶段和恢复阶段必须分开看：这里保存的是“暂停前的新增消息”；`notifyUser` 自己的工具应答还没有构造，`HDEL`、`answered` 都是后面的 `OrderAgent#resume` 才做的事（5.3）。

### 3.5 “客户端收到 `confirm`”的条件

`tryEmitNext` / `tryEmitComplete` 只是尝试。能否收到，取决于：

- HTTP 下游订阅当时仍然存在（`autoConnect(2)` 已连接，且客户端没断开）；
- sink 还没被终止；
- 网络与 SSE 线格式正常（C1 已说明控制器没有 `produces`，线格式未实测）。

所以以下情况都可能出现“Redis 已经是 `break=yes`，但前端从未看到编号”：

- 保存成功后、发事件前进程崩溃/连接断开；
- `tryEmitNext` 失败（无订阅者/已取消）；
- 客户端在收到 `confirm` 前关闭页面，之后重连也没有补发机制（仓库里没有“重发 confirm”的接口）。

这时会话处于**暂停但没有可用编号**的状态：`/c` 被 `break=yes` 挡住，`/r` 需要 `confirmationId` 而客户端没有。可推导的结局是该 `chatId` 直到 key 过期（或人工清理 `break` 字段）前都难以继续：`restore` 只改 `locked`/TTL，不删 `break`，不能解决这个状态。教材不承诺存在自动补发（见 9 的失败表）。

### 3.6 暂停失败的分支

| 失败点 | 已发生的状态 | 客户端可能看到 | 能否自动恢复 |
|---|---|---|---|
| 循环读 Hash 抛异常（`try` 外） | `handleNotifyUser` 自己还没写 `pendingToolCall`/`break`；异常冒泡到模型完成回调 → failure consumer。但**同批在 `notifyUser` 之前执行的工具可能已经写了 Redis/MySQL/外部服务**，不能理解成“本轮没有任何副作用” | `error` 事件（Agent 的 error 回调）或只看到流错误 | 否；本轮暂停前消息没保存，需重发消息 |
| `begin` 抛 409（无 `quoteId`/`ReadyforConfirm`） | 没写 `pending/break` | `error` 事件（catch 文本“序列化订单参数失败: 请先完成有效报价”） | 模型可重新算价；用户需再发消息 |
| 序列化失败（极少） | `begin` 已写 `pending/break=yes` | 同上（catch 文本仍是“序列化…”） | 否；暂停但无编号，`/c` 被挡 |
| `saveNewMessages` 失败 | `begin` 已写 `pendingToolCall`/`break=yes`。保存失败分两种：① MySQL `append` 未提交/事务回滚 → 这批暂停前消息没有落库；② MySQL 已写，随后 Redis/heap 或 `updateChatTime` 抛异常 → MySQL 可能已有这批消息。`break` 是否仍为 `yes`，还取决于此后有没有并发写入、key 过期或人工清理：本方法没有删除它，但不能把“没有删”当成“字段一定还在” | catch 尝试发 `error` + `complete`（文本仍是“序列化…”）；能否收到未实测 | 暂停状态**可能成立也可能不成立**。`notifyUser` 的用户应答尚未构造，`HDEL`/`answered` 属于后来的 `OrderAgent#resume`，与这里的失败无关。若客户端此前已拿到编号，`/r` 仍可能走通；它读 MySQL 窗口，能否看到这批消息取决于是否已落库 |
| 再写 `break` 失败 | `begin` 已写 `break`；历史可能已保存 | catch 发 `error` + `complete` | 状态已是暂停；能否继续取决于客户端是否有编号 |
| 发事件失败 | 暂停状态完整；历史已保存 | 可能只看到连接结束 | 否；无补发 |
| `tryEmitComplete` 失败 | 同上 | 可能一直悬挂/由连接关闭结束 | 不确定 |

注意 catch 的文案固定是“序列化订单参数失败”，但 `try` 里还包括 `begin`、保存、写 break、发事件——**文案不能当作真实失败原因**。这是读日志时的一个坑。

### 3.7 一个被注册但不会执行的方法

`OrderTool#notifyUser` 方法体是：

```java
@Tool(description = "通知用户检查新订单参数并确认创建订单")
public String notifyUser(ToolContext toolContext) {
    // 这个工具只是为了让大模型显式的调用这个Tool以便触发sink.tryEmitComplete()结束的。
    // 这部分逻辑要写在OrderAgent的选择工具swtich里面。
    String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
    if (stringRedisTemplate.opsForHash().get(chatInfoKey, "ReadyforConfirm") == null) {
        return "尚未进行路径规划。";
    }
    return "";
}
```

但 `OrderAgent#handleToolCalls` 在 `toolName == "notifyUser"` 时直接转 `handleNotifyUser` 并 `return`，`executeTool` 的 `switch` 里**没有** `case "notifyUser"`。所以这个方法体在当前 Agent 路径里**不会被执行**；它的 `@Tool` 注解只负责让模型在 schema 里看到这个工具。读代码时不要把它当成真正的暂停实现。

---

## 四、用户决定：`CONFIRM` 脚本逐行

### 4.1 输入、返回与 Java 包装

```java
// service/base/OrderConfirmation.java#confirm（真实）
public void confirm(String chatId, String confirmationId, Boolean accepted) {
    if (confirmationId == null || accepted == null) throw new BusinessException(400, "请提供确认编号和明确的确认结果");
    Long result = redis.execute(CONFIRM, List.of(RedisKeyConstants.chatInfoKey(chatId)),
            confirmationId, accepted ? "yes" : "no", String.valueOf(System.currentTimeMillis()));
    if (!Long.valueOf(1).equals(result)) throw new BusinessException(409, "确认已失效或已被使用，请重新核对订单");
}
```

- `KEYS[1]` = `chat:info:{chatId}`
- `ARGV[1]` = 客户端回传的 `confirmationId`
- `ARGV[2]` = `"yes"` / `"no"`
- `ARGV[3]` = 服务端 `System.currentTimeMillis()` 的字符串

脚本正常执行时返回 0/1；Java 把 0 统一翻译成 **409**，不区分“编号错”“状态不符”“超龄”。缺参数在 Java 里是 **400**，脚本根本不会执行。**脚本运行时错误（例如非数字时间戳导致 `tonumber` 为 nil 后参与算术）或网络异常不会变成“返回 0”**：Java 侧看到异常，实际是否已执行可能未知，不能把异常归入 0 分支。

### 4.2 逐行解释

```lua
-- service/base/OrderConfirmation.java 中的 CONFIRM（真实脚本，按源码转写）
local state = redis.call('HGET', KEYS[1], 'break')
if redis.call('HGET', KEYS[1], 'quoteId') ~= ARGV[1] then return 0 end
if ARGV[2] == 'yes' and tonumber(ARGV[3]) - tonumber(redis.call('HGET', KEYS[1], 'quoteCreatedAt') or '0') > 900000 then return 0 end
if state == 'resuming' then
    if redis.call('HGET', KEYS[1], 'confirmationAccepted') == ARGV[2] then return 1 end
    return 0
end
if state ~= 'yes' then return 0 end
redis.call('HSET', KEYS[1], 'break', 'resuming')
redis.call('HSET', KEYS[1], 'confirmationAccepted', ARGV[2])
if ARGV[2] == 'yes' then
    redis.call('HSET', KEYS[1], 'confirmedQuote', ARGV[1], 'ReadyForCreate', ARGV[1])
else redis.call('HDEL', KEYS[1], 'confirmedQuote', 'ReadyForCreate') end
return 1
```

逐行：

1. `state` 先读出来（`yes`/`resuming`/字段不存在即 `nil`；没有代码写 `false`）。
2. **编号必须等于当前 `quoteId`**，否则 0。这是“确认的是哪一次报价”的唯一绑定。
3. **只有接受才查年龄**：`yes` 时，`now - quoteCreatedAt > 900000` 返回 0；`no` 直接跳过这一行。`quoteCreatedAt` **字段不存在**时 `or '0'` 会按 0 处理，在正常系统时钟下接受会被判成年限已过（严格说结果取决于 `ARGV[3]`）；若字段存在但不是数字，`tonumber` 返回 nil，后面的减法会触发 Lua 运行时错误，不能归为“返回 0”。**正好 900000 毫秒只通过年龄条件**（严格大于才拒绝）；能否最终成功还取决于编号、`break` 状态（`consume` 还取决于许可）。
4. `state == 'resuming'`：说明这个状态已经被前一次 `CONFIRM` 受理、`resume` 还没清理。
   - 如果 `confirmationAccepted` 与本次决定相同 → 返回 1（**重放受理，不重复写字段**；接受已在第 3 步通过年龄，拒绝没有年龄门槛；不能据此推断许可仍存在）；
   - 否则 → 0（**相反决定不能覆盖**）。
5. `state ~= 'yes'` → 0。即：没挂起、已消费、已被清理，都不接受。
6. 写 `break=resuming`、`confirmationAccepted=决定`。
7. 接受：写 `confirmedQuote` 与 `ReadyForCreate`，两者都等于 `ARGV[1]`；拒绝：删掉这两个字段。
8. 返回 1。

**脚本的原子性范围**：以上所有 `HGET/HSET/HDEL` 在 Redis 内是一个原子执行单元，不会被别的客户端看到中间状态。但这只是 **Redis 内部的隔离**：

- 它不覆盖 `begin`、`quoted` 的多命令序列；
- 它不覆盖 MySQL 的工具应答保存与订单插入；
- 它不会在“返回 0”时回滚任何东西（返回 0 的分支本来就没写）；
- 如果脚本运行时报错（例如 key 类型错误），Redis 不会回滚已经执行的写命令；这两个脚本的写都在末尾，理论风险低，但不能把“Lua”说成“事务”。

### 4.3 接受与拒绝的差异

| 维度 | `confirmed=true` | `confirmed=false` |
|---|---|---|
| 编号必须匹配 | 是 | 是 |
| `break` 必须是 `yes`（或 `resuming` 且同决定） | 是 | 是 |
| 报价年龄 ≤ 15 分钟 | **是**（否则 0） | **否**（不检查） |
| 写 `confirmationAccepted` | `yes` | `no` |
| 写/删创建许可 | 写 `confirmedQuote`、`ReadyForCreate` | 删两者 |
| 结果 | `break=resuming`，进入恢复 | `break=resuming`，进入恢复 |
| 恢复后的 feedback | “用户已确认当前报价，可以创建订单。” | “用户拒绝当前报价，禁止创建订单。” |

“拒绝不查年龄”是一个有意可推导的效果：报价过期后，用户仍然可以退出暂停状态。否则 `break=yes` 会永久挡住 `/c`（`/c` 只在 `break` 精确等于 `"yes"` 时拦），用户既不能接受（超龄）也不能拒绝，只能等 key 过期或换会话。**这是从代码能推出的逃生口，不宣称是原始设计动机。**

### 4.4 `resuming` 窗口：同决定可重放，但有条件

`break=resuming` 的窗口是“`CONFIRM` 已受理、`OrderAgent#resume` 还没删字段”。在这个窗口里：

- **同一决定**：还要先过该决定自己的检查。同一 `yes` 在 15 分钟内返回 1，超过 15 分钟在第 3 步就返回 0；同一 `no` 不查年龄，返回 1。**`state=resuming` 且决定相同时，脚本直接返回 1，不执行任何写命令**；因此不能从“返回 1”推断 `confirmedQuote/ReadyForCreate` 一定存在——它可能已经被 `consume` 删除、被其他写入撤掉，或从未写入。`resume` 仍然会跑：如果上次在保存工具应答时失败，这次可能补存；如果上次模型失败且 `break` 已被删，重试会变成“状态不符”（见下）。
- **相反决定**：返回 0。`break` 仍是 `resuming`，`confirmationAccepted` 仍是旧值，创建许可仍是旧决定的结果。这防止“先点确认、再点拒绝”把已发出的许可撤掉又制造混乱。
- **离开窗口之后**：`resume` 删除了 `break`/`pendingToolCall`/`confirmationAccepted`。此时再提交同一决定，`state` 读不到 → `state ~= 'yes'` → 0 → 409。**所以“所有重复确认都直接失败”是错的；“重复确认一定成功”也是错的。**

`confirmationAccepted` 是判断“重复”的唯一依据；它不区分请求来源、不区分浏览器标签页。服务端没有“这是第几次点击”的计数。

### 4.5 900000 与两个时钟

- `quoteCreatedAt` 是 `quoted` 执行时 JVM 的 `System.currentTimeMillis()`；
- `CONFIRM` 与 `CONSUME` 的 `now` 也是 JVM 的 `System.currentTimeMillis()`；
- Redis 的 TTL 用的是 **Redis 服务器时间**。

这带来三个可推导的边界：

1. **多实例时钟偏移**：如果 A 实例写 `quoteCreatedAt`，B 实例做确认，两个 JVM 的时钟不一致，15 分钟的判定会偏松或偏紧。代码没有用 Redis `TIME` 统一时钟。
2. **TTL 与年龄不同步**：Redis 的 24 小时（或 60 分钟）到点会删 key；Java 的 15 分钟只影响“接受/消费是否返回 1”。key 还在，不代表年龄没超；key 没了，也不代表“用户没确认过”。
3. **`System.currentTimeMillis()` 可回拨**：NTP 校时、手动改时钟都可能让差值为负（脚本会当成“很新”）。这不是本项目独有的问题，但教材不能把 15 分钟说成“绝对可靠”。

`> 900000` 的边界：`now - created = 900000` 时只表示**没有触发年龄拒绝**；`CONFIRM` 能否返回 1 还要编号匹配、`break=yes`（或 `resuming` 且同决定），`CONSUME` 还要 `confirmedQuote/ReadyForCreate` 都等于当前 `quoteId`。`900001` 是 **900001 毫秒**（约 15 分 0.001 秒），不是“1 秒”。教材例题统一用毫秒计算，不混用秒。

### 4.6 Java 侧的 400/409 与流内呈现

- `confirmationId == null` 或 `accepted == null` → `BusinessException(400)`；
- 脚本正常返回 0 → `BusinessException(409)`；脚本运行时错误或 Redis 响应丢失 → Java 异常，是否已执行未知；
- 这些异常发生在 `chatExecutor` 线程的 `service.resume` 里，被 `AgentStreams#guard` 捕获：`guard` **尝试** `tryEmitNext(AgentEvent.error("处理失败，请稍后重试"))`，然后 `tryEmitError`。客户端是否收到这个 `error`、HTTP 响应是否已经提交、流是否一定以 SSE 呈现，都未实测；**不能写成“客户端一定收到 error 事件”或“一定不是 HTTP 400/409”**。能确定的是：代码没有把这两个业务异常直接映射成控制器层的同步 400/409。

只有一个阶段更早：`ChatRequestRunner#run` 的 `UUID.fromString(chatId)` 在构造并返回 `Flux` 之前就同步执行，非法 UUID 会直接从控制器方法里抛出。`Flux.defer` 的 supplier 则要到**订阅时**才执行；锁获取失败、`assertOwned` 的 404/403/410 都在这个 supplier 里，被它自己的 `catch` 转成 `Flux.error(e)` 返回。所以准确说法是“订阅期由 supplier 转成错误信号”，不是“控制器方法里同步抛出”。这些信号最终映射成什么 HTTP 状态，仍取决于全局异常处理与响应是否已提交；**不要用 HTTP 状态反推确认失败的具体原因。**

---

## 五、恢复：`POST /chat/v2/r/{id}` 的真实顺序

### 5.1 入口六关

```java
// controller/ChatController.java（真实节选）
@RequirePermission
@PostMapping("/r/{id}")
public Flux<AgentEvent> resume(@PathVariable String id, @RequestBody ChatParamDTO chatParam) {
    String userId = UserTokenContext.getUserIdInString();
    return requestRunner.run(userId, id, chatParam, true);
}
```

`ChatRequestRunner#run` 的 `resume=true` 分支：

1. **UUID 格式**：`UUID.fromString(chatId)` 在 `Flux.defer` 之前同步执行；不合法直接抛 `IllegalArgumentException`。它发生在返回 `Flux` 之前，是否被 MVC 映射成 400、是否已提交响应，未实测。
2. **单轮执行锁**：`tryLockAsync(0, -1, SECONDS, owner)`，`get(3s)` 只是等待异步结果；拿不到锁返回 `Flux.just(error("会话正在处理中，请等待本轮结束"))` 的单事件流，**不是**同步 HTTP 错误；实际是否以 SSE/HTTP 200 呈现未实测。
3. **归属**：`chats.assertOwned(userId, chatId, !resume)` → `false`，即会话必须已存在（404）、属于当前用户（403）、未被清空（410）。这一步和锁获取一样，写在 `Flux.defer` 的 supplier 里，**订阅时才执行**；异常被同一段 `catch` 捕获并作为 `Flux.error(e)` 返回。HTTP 映射同样未实测。
4. **订阅与后台**：`Flux.defer` 里的锁/归属代码是在**订阅时**执行的，不是在控制器方法同步执行；随后 `doOnSubscribe` 把任务提交给 `chatExecutor`，外面包 `AgentStreams.guard`，并用 `publish().autoConnect(2)` 保持后台订阅。
5. **后台**：`service.resume(chatId, request, sink, userId)`。
6. **终态**：`doFinally` 尝试 `unlockAsync(owner)`。

`/r` **不检查** `chatManager.isLocked`。它把恢复入口限制在 `OrderAgent#resume` 对 `break=resuming` 的检查；这能挡住普通“未确认”的恢复，但不是身份授权，也不能证明 `break` 一定由当前用户的确认写入。若 `break` 被错误地留成 `resuming`，已锁定的会话理论上也能进恢复流程；正常流程里 `resume` 会在跑模型前删掉它。

### 5.2 `ChatServiceImpl#resume` 的精确先后

```java
// service/impl/ChatServiceImpl.java#resume（真实全文）
public void resume(String id, ChatParamDTO chatParam, Sinks.Many<AgentEvent> sink, String userId) {
    if (chatParam != null && chatParam.getConfirmed() != null && chatParam.getConfirmationId() != null
            && !StringUtils.hasText(chatParam.getPrompt())) {
        chatParam.setPrompt(Boolean.TRUE.equals(chatParam.getConfirmed()) ? "确认" : "拒绝");
    }
    if (!canStartChat(id, chatParam, sink, userId)) {
        return;
    }
    chatManager.assertOwned(userId, id, false);
    orderConfirmation.confirm(id, chatParam.getConfirmationId(), chatParam.getConfirmed());
    String feedback = Boolean.TRUE.equals(chatParam.getConfirmed()) ? "用户已确认当前报价，可以创建订单。" : "用户拒绝当前报价，禁止创建订单。";
    orderAgent.resume(sink, id, userId, feedback + chatParam.getPrompt());
}
```

按顺序：

1. **prompt 回填**：只有 `confirmed` 与 `confirmationId` 都不为 null、且 `prompt` 为空白时，才把 prompt 设为“确认”/“拒绝”。这一步发生在限流之前。若两个字段缺一个，prompt 仍是空白 → 下一步直接报错。
2. **`canStartChat`**：`param == null` 或 `prompt` 空白 → `error("消息内容不能为空")` + `complete`，**不消耗限流**；否则 `ChatRateLimiter#tryAcquire`，超限发 `notify` + `complete`，也**不会**进入确认。
3. **归属**：`assertOwned(userId, id, false)`（第三次检查，前面 Runner 已查过一次；`getPersisted` 和 `save` 里还会再查）。
4. **`confirm`**：400/409 都可能在这里抛出；异常冒泡到 `guard`。
5. **feedback**：固定句 + 用户 prompt 原样拼接。**用户的 prompt 仍然是不可信输入**，只是被塞进工具应答文本。
6. **`orderAgent.resume`**。

这里没有 `chatManager.isLocked`、没有分类、没有 `memory.get`。恢复不重新分类，也不重新路由。

### 5.3 `OrderAgent#resume`：先检查、再补应答、再删状态、最后跑模型

```java
// agents/OrderAgent.java#resume（真实节选，省略日志）
String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
Object breakFlag = stringRedisTemplate.opsForHash().get(chatInfoKey, "break");
if (breakFlag == null || !"resuming".equals(breakFlag.toString())) {
    sink.tryEmitNext(AgentEvent.error("非法的恢复请求：对话不在等待确认状态"));
    sink.tryEmitComplete();
    return;
}
List<Message> history = new ArrayList<>(messageMemory.getPersisted(userId, chatId, HISTORY_SIZE));
int originalHistorySize = history.size();
Object pending = stringRedisTemplate.opsForHash().get(chatInfoKey, "pendingToolCall");
String lastToolCallId = pending == null ? null : pending.toString();
if (lastToolCallId == null) {
    sink.tryEmitNext(AgentEvent.error("无法找到待确认的工具调用"));
    sink.tryEmitComplete();
    return;
}
ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
        lastToolCallId, "notifyUser", feedback);
ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(List.of(toolResponse)).build();
boolean answered = history.stream().filter(ToolResponseMessage.class::isInstance)
        .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream())
        .anyMatch(r -> lastToolCallId.equals(r.id()));
if (!answered) {
    messageMemory.save(userId, chatId, List.of(toolResponseMessage));
    history.add(toolResponseMessage);
}
originalHistorySize = history.size();
stringRedisTemplate.opsForHash().delete(chatInfoKey, "break", "pendingToolCall", "confirmationAccepted");
runLoopStep(history, originalHistorySize, sink, chatId, userId);
```

关键点：

- **`break` 必须是 `resuming`**：不是 `yes`、不是 null、不是其他字符串，都会发 `error` 并 `complete`，**不抛异常**。所以 `/r` 到达这里时，错误以事件形式出现，不走 409。
- **历史来自 MySQL**：`getPersisted` 先 `chatManager.assertOwned(userId, chatId, false)`（查 `sys_chat`，可抛 403/404/410），再调 `mysqlMemory.getAll(chatId)`，最后用 `MessageWindow.tail` 取窗口。它不读 heap、不读 Redis。原因写在注释里：避免“一次持久化成功而缓存失败后重复保存工具应答”。
- **`pendingToolCall` 来自 Redis**：不是从历史里扫描 `notifyUser`。源码里有一个私有 `getLastNotifyUserToolCallId`，但 `resume` 没调用它（当前是未使用方法）。如果 Redis 字段丢了，即使历史里有 `notifyUser`，也会停在“无法找到待确认的工具调用”。
- **`answered` 检查**：扫描历史里所有 `ToolResponseMessage` 的 `id`，看这个 `lastToolCallId` 是否已经有应答。有就跳过保存；没有就 `save` 一条新的 `ToolResponseMessage`，内容固定是 `notifyUser` + `feedback`。`name` 被硬编码成 `"notifyUser"`，不是从历史里取的。
- **`answered` 只扫描 `getPersisted` 当前窗口**：`getPersisted` 用 `MessageWindow.tail(mysqlMemory.getAll(...), 100)`；窗口以 100 条为目标，但为保留完整工具批次可能向前扩展。它不是“全历史严格去重”，更不是并发原子幂等；如果历史损坏被 `MessageParser` 丢弃、或应答落在窗口外，`answered` 可能为 false，重试就会再存一次。
- **保存先于删除，但保存失败不等于 MySQL 一定没写**：`MessageMemory#save` 的顺序是 MySQL `append` → Redis/heap → `updateChatTime`。若 `append` 未提交/回滚，MySQL 没有这批消息；若 MySQL 已写、随后 Redis/heap 或更新时间抛异常，MySQL 可能已有 `ToolResponse`。代码在异常时不会执行 `HDEL`，但重试时 `answered` 取决于 MySQL 历史和窗口，可能是 true，也可能是 false，不能无条件写成“保存失败 → answered=false”。
- **`HDEL` 的结果也分“明确未执行”和“响应丢失”**：只有能确认 Redis 删除命令没有执行，才可在其他条件不变时假设 `break=resuming` 仍残留；命令超时/断连时，字段可能已经删掉，实际状态未知。
- **`originalHistorySize` 重置**：保存应答后把 `originalHistorySize` 设为加入后的长度，避免后续 `saveNewMessages` 重复保存这条应答。
- **没有把用户反馈变成 `UserMessage`**：用户的确认/拒绝只以 **工具应答文本** 进入历史。这是“恢复不是新用户回合”的直接证据。

### 5.4 `ToolResponseMessage` 配对与失败窗口

`MessageWindow` 的规则（C4 会完整讲）：

- 一个 `AssistantMessage` 的每个 `toolCall.id()` 都要在后面的 `ToolResponseMessage` 里恰好应答一次；
- 未闭合的**最后**一个工具批次允许存在（为了暂停）；
- 重复应答、未知 id、工具批次中间插入普通消息都会抛 `IllegalStateException`。

把 `MessageMemory#save` 的阶段拆开，同一条“保存失败”在重试时会落到不同结果：

| `save` 停在哪 | MySQL 是否已有这条应答 | 重试时 `answered` 的可推导结果 | 还要注意 |
|---|---|---|---|
| `mysqlMemory.append` 抛（本地事务未提交/回滚，或 `chat` 行不存在/已清空抛 410） | 否 | false（当前窗口内没有该 id） | 历史仍以未闭合的工具批次结束；重试会再存一次 |
| `append` 成功，`redisMemory.appendIfVersionMatch` 或 `heapMemory.appendIfVersionMatch` 抛 | 是 | 通常 true | `save` 会先 `heapMemory.clear(chatId)` 再抛；MySQL 已有应答，窗口只要包含它就会跳过保存 |
| `append` 与两层缓存都成功，`updateChatTime` 抛 | 是 | 通常 true | 消息已完整写入 MySQL 与缓存，只是 `sys_chat.updated_at` 没刷新 |
| `append` 没抛，但 `MessageParser#parse` 对某条返回 `null/blank` 并跳过 | 否（该条没写） | false | “save 没抛异常”也可能缺这条应答 |

“通常 true”不是保证：`answered` 只看 `getPersisted` 的窗口是否包含这一行，窗口截断、`MessageParser` 丢弃坏记录、或并发写入把该行挤到窗口外，都可能让它变回 false。另外，`answered` 的“先查再存”不是原子操作；如果两个 `/r` 在锁失效等条件下同时进入 `resume`，两者都可能判定 false 并各存一次。

回到 `MessageWindow` 的配对规则：

- 暂停时如果 `notifyUser` 是第一个工具，历史以“未闭合的 assistant”结束，是合法的；
- 恢复补上 `notifyUser` 的应答后，批次闭合，模型可以继续；
- 如果保存失败，历史可能仍未闭合；重试**不一定**再走 `answered=false`：若 MySQL 已写入这条应答（只是后续 Redis/heap/更新时间失败），重试读到它就会 `answered=true`；若 MySQL 未写或窗口不包含，才会再存一次（若成功，批次闭合）。
- 如果**先保存成功、删除 `break` 失败**：若 `HDEL` 明确未执行，`break` 仍是 `resuming`；若响应丢失，`break` 可能已删、也可能未删，不能断言。重试时 `getPersisted` 若在窗口内读到已存在的应答 → `answered=true` → 不再重复保存；若窗口截断/解析丢弃 → 可能重复。不能把“answered 去重”当成全历史幂等。
- `MysqlMemory#append` 是 `@Transactional`，但 `MessageParser#parse` 对某条消息返回 `null/blank` 时会**跳过该条**而不抛错；所以“save 成功”不等于“每个新增 Message 都落库”，某些工具应答可能根本没进 MySQL。
- `MessageWindow#tail` **不是所有历史损坏都抛异常**：它只在工具配对错误（重复 id、未知 id、pending 中出现非 assistant/tool 消息等）时抛 `IllegalStateException`。`MessageParser#unparseHistory` 在遇到坏记录时可能清空已解析结果，并从之后的第一条 `UserMessage` 重新恢复；恢复出的历史可能更短，也可能把 `notifyUser` 的应答丢掉。这两种损坏要分开讲。

### 5.5 `/r` 的结果表

| 输入/状态 | `resume` 走到哪 | 服务端可推导的结果 | 客户端可能观察到 |
|---|---|---|---|
| `confirmed=true`，编号正确，`break=yes`，年龄 ≤15 分钟 | `confirm` 返回 1 → 尝试 `resume` | 接受受理：许可已写；恢复读取、保存和删除状态仍可能失败，订单尚未因此创建 | 后续模型可能产生 `message`/`tool_start`；是否发送或送达、HTTP 是否已提交未实测 |
| `confirmed=false`，编号正确，`break=yes`，任意年龄 | `confirm` 返回 1（不查年龄） | 有效拒绝；创建许可被删 | 可能看到模型解释或重新报价；是否真的发出取决于模型与事件发送 |
| `confirmed=true`，编号正确，年龄 >15 分钟 | `CONFIRM` 第 3 行返回 0 | 409；若此前 `break=yes`，它保持 `yes`；若此前已 `resuming`，它保持 `resuming`（前提是脚本正常返回 0，不是运行时错误） | `guard` **尝试**发通用 `error`；是否收到、HTTP 状态未实测；`/c` 是否被挡看 `break` 当前值 |
| 编号错误/为空 | `quoteId` 不匹配或 Java 400 | 409/400，未改状态 | 同上；不能凭 HTTP 状态区分是哪一种 |
| `break` 不是 `yes`/`resuming`（已消费、已清理、key 过期） | `CONFIRM` `state ~= 'yes'` → 0 | 409，未改状态 | 同上 |
| `break=resuming`，同决定，接受年龄 ≤15 分钟 | `CONFIRM` 第 4 行返回 1 | 重放受理；**不写任何字段**；许可可能仍在，也可能已被消费/删除 | 可能再次进入 `resume`；不能凭返回 1 断言许可存在，也不能据此断言模型一定会继续 |
| `break=resuming`，同决定，接受年龄 >15 分钟 | 第 3 行先返回 0 | 409 | 尝试发通用 `error` |
| `break=resuming`，相反决定 | 第 4 行返回 0 | 409 | 尝试发通用 `error` |
| `prompt` 空白且缺 `confirmationId`/`confirmed` | `canStartChat` 第一步 | `error("消息内容不能为空")` + `complete`；不消耗限流 | 该 `error` 是构造出来写入 sink 的；能否收到未实测 |
| `prompt` 非空但缺 `confirmationId`/`confirmed` | `confirm` 的 null 检查 | 400；已消耗限流 | 通用 `error`（尝试发）；HTTP 状态未实测 |
| 限流超限 | `canStartChat` | 写入 `notify` + `complete`；不进入 `confirm` | `notify` 事件（尝试发） |
| `break=resuming` 但 `pendingToolCall` 丢失 | `OrderAgent#resume` 第二步 | 写入 `error("无法找到待确认的工具调用")` + `complete`；不抛异常 | 该 `error` 是否送达未实测；`break` 此时未被删（本分支在其之前返回），可修字段后重试 |
| `resume` 保存工具应答失败 | `messageMemory.save` 抛异常，代码在 `HDEL` 之前返回，所以**本次删除一定未执行** | `guard` 尝试发通用 error；MySQL 可能已写也可能未写；`break` 是否仍为 `resuming`，还要看这期间有没有并发写入、过期或人工清理改过它 | 重试时 `answered` 可能是 true 或 false（5.4 表）；不能保证一次成功 |
| 同一会话并发第二个 `/r` | Runner 拿不到执行锁 | Runner 直接返回单事件 `error("会话正在处理中…")`；未进入 `confirm`，不改确认状态 | 该单事件流是否被客户端看到、HTTP 状态如何，均未实测 |

注意最后一行：并发被拒绝发生在 `confirm` **之前**，所以不会消耗确认状态，也不会把决定写成相反——前提是这次拒绝确实发生在 Runner 的锁获取处，而不是别处先失败。

### 5.6 为什么恢复必须读数据库

如果 `resume` 用 `memory.get`（heap → Redis → MySQL），可能出现：

- 暂停时 MySQL 已保存，但 Redis 缓存写失败、heap 被清空；
- 恢复时 heap/Redis 没有这条 `ToolResponseMessage`，于是再次 `save` 同一条应答 → MySQL 里出现**重复的工具应答** → `MessageWindow` 判定“重复 id” → 历史不可用。

`getPersisted` 直接读 MySQL，绕开缓存不一致；`answered` 再做一次**窗口内**检查，而不是全历史幂等证明。**代价**是恢复不看热缓存，必须回源；MySQL 不可用会抛异常。历史损坏也不能一概写成“必抛”：

- `MessageParser#unparseHistory` 对坏记录的处理是“清空已解析结果，进入 recovering，直到下一条 `UserMessage` 再开始收集”；所以可能只恢复出后半段历史，或丢掉某条 `ToolResponse`。
- `MessageWindow#tail` 只在特定工具配对错误（重复 id、未知 id、pending 中出现非 assistant/tool 消息等）抛 `IllegalStateException`；不是所有坏记录都会让它抛。
- 如果被丢掉/截断的正是 `notifyUser` 的应答，`answered` 可能为 false，重试会再次 `save`，从而制造重复；这不是“恢复一定成功”，也不是“历史损坏一定立即报错”。

---

## 六、从确认到订单：`isConfirmed`、`consume` 与创建

### 6.1 `isConfirmed` 与 `markOrderReadyForCreate`

```java
// service/base/OrderConfirmation.java#isConfirmed（真实）
public boolean isConfirmed(String chatId) {
    String key = RedisKeyConstants.chatInfoKey(chatId);
    Object quote = redis.opsForHash().get(key, "quoteId");
    return quote != null && quote.equals(redis.opsForHash().get(key, "confirmedQuote"))
            && quote.equals(redis.opsForHash().get(key, "ReadyForCreate"));
}
```

```java
// agentbase/tool/OrderTool.java#markOrderReadyForCreate（真实节选）
return orderConfirmation.isConfirmed(chatId) ? "订单已取得用户确认，可以创建" : "尚未取得用户对当前报价的确认";
```

三个事实：

- `isConfirmed` **只比较字符串**，不查 `quoteCreatedAt`、不查 `break`、不查 `locked`、不查订单是否已存在。
- `markOrderReadyForCreate` **不签发任何字段**；它只是把布尔结果翻译成文本。C2 已强调“它不是授权”。
- 因此 `markOrderReadyForCreate` 返回“可以创建”之后，`createOrder` 仍可能因超龄、编号被换、已被消费而失败。**模型看到的是文本，不是保证。**

### 6.2 `createOrder` 的前置检查与 DTO 组装

```java
// agentbase/tool/OrderTool.java#createOrder（真实节选）
String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
if(redisGetObj(chatInfoKey, "ReadyForCreate") == null){
    return "订单非可创建态";
}
CreateOrderDTO orderDTO = CreateOrderDTO.builder()
        .userId(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString())
        .mongoTraceId(redisGet(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name()))
        .vehicleType(Integer.parseInt(redisGet(chatInfoKey, OrderInfoEnum.VEHICLE_TYPE.name())))
        .isReservation(Integer.parseInt(redisGet(chatInfoKey, OrderInfoEnum.IS_RESERVATION.name())))
        .isExpedited(Integer.parseInt(redisGet(chatInfoKey, OrderInfoEnum.IS_EXPEDITED.name())))
        .scheduledTime(...)
        .startAddress(...).startLat(...).startLng(...).endAddress(...).endLat(...).endLng(...)
        .estPrice(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.EST_PRICE.name())))
        .estDistance(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name())))
        .radio(new BigDecimal(redisGet(chatInfoKey, PriceEnum.PRICE_RADIO.name())))
        .build();
orderConfirmation.consume(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
String orderId = rideOrderService.createOrder(orderDTO);
```

顺序与要点：

1. **`ReadyForCreate != null` 的快速检查**：只是“看起来有许可”。它不校验 `ReadyForCreate == quoteId`（那是 `consume` 的事），也不查年龄。**它不是安全边界**。
2. **组装 DTO**：`redisGet` 在字段缺失时抛 `IllegalArgumentException("订单缺少字段：...")`，被 `executeTool` 的 catch 转成 `Tool execution failed: ...` 文本；工具循环继续。`userId` 来自 `ToolContext`（服务端注入的当前用户），不是模型参数。
3. **`consume`**：真正的 Redis 一次性门禁，见 6.3。注意它在 DTO 组装**之后**才执行；`consume` 只检查当前 `quoteId`、许可字段和年龄，不检查 DTO 里的金额/槽位是否与暂停时展示的快照一致。
4. **`rideOrderService.createOrder`**：真正写订单，见 6.4。

因此有一个容易漏掉的并发边界：`OrderTool#createOrder` 在 `consume` 前分多次 `HGET` 组装 DTO；如果这些读取之间发生重新报价、部分字段清理、外部直接改 Hash 或 Redis 故障，DTO 可能是“混合版本”。正常参数修改会先删 `quoteId`，使后续 `consume` 失败；但 `saveNewOrderParam` 对 `null` 参数/槽位会早退、清理本身是多条非事务命令，所以不能把“正常路径会清字段”当成跨字段快照保证。`consume` 成功只证明“当前 quoteId 的一次性许可被消费”，不证明 DTO 与用户看到的摘要逐项相同。

### 6.3 `CONSUME` 逐行

```lua
-- service/base/OrderConfirmation.java 中的 CONSUME（真实脚本，缩进按可读性排版）
local quote = redis.call('HGET', KEYS[1], 'quoteId')
if tonumber(ARGV[1]) - tonumber(redis.call('HGET', KEYS[1], 'quoteCreatedAt') or '0') > 900000 then return 0 end
if not quote or redis.call('HGET', KEYS[1], 'confirmedQuote') ~= quote or
   redis.call('HGET', KEYS[1], 'ReadyForCreate') ~= quote then return 0 end
redis.call('HDEL', KEYS[1], 'ReadyForCreate', 'confirmedQuote')
return 1
```

1. 读当前 `quoteId`；
2. **先查年龄**：`now - quoteCreatedAt > 900000` 返回 0；字段缺失时按 `'0'` 参与减法。在正常系统时钟下（`now` 是 1970 年以来的毫秒数，远大于 900000），这必然超龄；但严格说脚本只是做减法，结论取决于 `ARGV[1]`，不要把它写成“脚本专门判断了缺字段”；
3. 再要求 `quote` 存在、`confirmedQuote == quote`、`ReadyForCreate == quote`；
4. 只有全部通过才 `HDEL` 两个字段，返回 1。

**失败时不删字段**：如果脚本正常返回 0（例如只是超龄），`ReadyForCreate`/`confirmedQuote` 还在，`isConfirmed` 仍是 `true`；下一次 `createOrder` 会在同一个年龄检查上再次失败，直到重新报价。若脚本运行时错误或网络异常，实际是否已执行未知，不能归入“0 且字段未删”。这是“确认有效、但已过期”的真实表现。

`consume` 的“一次性”范围要写准：**在同一 Redis 状态、同一现存许可下，脚本保证只有一个调用能成功 `HDEL` 并返回 1**。它不覆盖重新报价/再次签发（会造出新的许可）、其他代码直接改 Hash、key 过期/TTL 丢失、Redis 故障或响应丢失，也不覆盖 DTO 读取、MySQL/Mongo 写入和工具端后续步骤。不要把它写成“全局最多创建一单”的幂等保证。

`consume` 用一个 `System.currentTimeMillis()` 作为 `ARGV[1]`，与 `CONFIRM` 的时钟同源（JVM），但与 Redis TTL 不同源（4.5）。

### 6.4 `RideOrderServiceImpl#createOrder`：金额是“再算一次”的

```java
// service/impl/RideOrderServiceImpl.java#createOrder（真实节选）
if (UserTokenContext.isLoggedIn()) dto.setUserId(UserTokenContext.getUserIdInString());
if (dto.getUserId() == null || dto.getUserId().isBlank()) throw new BusinessException(401, "缺少下单身份");
if (dto.getVehicleType() == null) dto.setVehicleType(1);
if (dto.getIsExpedited() == null) dto.setIsExpedited(0);
if (dto.getIsReservation() == null) dto.setIsReservation(0);
if (dto.getIsReservation() == 0) dto.setScheduledTime(null);
CreateOrderDTO.ValidationResult result = dto.validateAndSupplement(amapGeoRegeoService);
if (!result.isComplete()) throw new IllegalArgumentException("订单信息不完整，缺失: " + result.missingFields());
CreateOrderDTO validatedDto = result.supplementedDTO();
Long orderId = snowflakeIdWorker.nextId();
if (validatedDto.getStartLat() == null || validatedDto.getStartLng() == null
        || validatedDto.getEndLat() == null || validatedDto.getEndLng() == null) {
    throw new IllegalArgumentException("起终点经纬度不能为空");
}
var estRoute = amapRouteService.getDrivingRouteEst(
        validatedDto.getStartLng().doubleValue(), validatedDto.getStartLat().doubleValue(),
        validatedDto.getEndLng().doubleValue(), validatedDto.getEndLat().doubleValue());
if (estRoute == null || estRoute.getEstKm() == null || estRoute.getEstTime() == null)
    throw new BusinessException(502, "路径规划失败：返回里程/时间为空");
String traceId = UUID.randomUUID().toString();
validatedDto.setMongoTraceId(traceId);
validatedDto.setEstDistance(new BigDecimal(estRoute.getEstKm()));
PriceEstimateVO estPrice = calculatePrice(estRoute.getEstKm(), estRoute.getEstTime(),
        validatedDto.getVehicleType(), validatedDto.getIsExpedited());
validatedDto.setEstPrice(estPrice.getEstPrice());
validatedDto.setRadio(estPrice.getEstPriceRadio());
orderRouteService.addOrder(traceId, estRoute);
```

按顺序：

1. **身份**：如果当前线程的 `UserTokenContext` 有登录身份，直接覆盖 DTO 的 `userId`；否则要求 DTO 里已有非空 `userId`（Agent 路径由 `ToolContext` 提供），否则 401。这是“客户端/模型不能指定乘客”的实现。
2. **默认值**：车型默认 1、加急 0、预约 0；实时单强制清空 `scheduledTime`。
3. **`validateAndSupplement`**：返回的是**新的 DTO 副本**（`supplementedDTO()`），可能通过逆地理编码补地址；缺字段 → `IllegalArgumentException`。
4. **生成订单号**：`snowflakeIdWorker.nextId()` 在坐标检查和路线之前就执行，只得到一个本地雪花 ID，**尚未写库**；后续任何一步失败，这个 ID 不会被分配出去。
5. **坐标检查**：起终点经纬度不能为空（在副本上检查；`null` → `IllegalArgumentException`）。
6. **重新路径规划**：无论 Agent 报价时是否规划过，这里都会再调一次高德；返回 null/里程/时间为空 → 502。
7. **设置新轨迹 id 与距离**：生成新的 `traceId` 并写进副本；`new BigDecimal(estRoute.getEstKm())` 覆盖 `estDistance`，格式异常 → 502。**此时还没有写 Mongo。**
8. **重新计价**：`calculatePrice` 覆盖 `estPrice` 和 `radio`。**DTO 里从 Redis 带来的 `EST_PRICE` 与 `PRICE_RADIO` 在这里被替换；`EST_DISTANCE_KM` 也在上一步被新路线覆盖。**
9. **写 Mongo 新轨迹**：计价完成后才执行 `orderRouteService.addOrder(traceId, estRoute)`；这一步失败会跳到 `insert` 之外的异常出口，不会插入订单行。

所以“用户确认的 38.60 元”与订单的 `est_price` 可能不同：路况变了、路线变了、计价参数变了，都会让最终落库金额变化。但原因不止“重算”：`CONFIRM` 只记录当前 `quoteId` 的接受/拒绝，没有把金额、槽位、JSON 或 DTO 做成快照/哈希/原子绑定；`OrderTool#createOrder` 在 `consume` 前逐字段读 Hash，若中间有并发/外部写入/部分命令失败，实际 DTO 也可能与暂停摘要不一致。正常参数保存会删 `quoteId` 使旧编号失效，但这不是跨字段原子快照保证。O5 已从计价角度讲过差价；本章只强调它和确认语义的关系。

### 6.5 写入与后置副作用

```java
// service/impl/RideOrderServiceImpl.java#createOrder（真实节选，续）
RideOrder order = RideOrder.builder()
        .orderId(orderId).userId(convertToLong(validatedDto.getUserId()))
        .orderStatus(RideOrderStatus.CREATED.getCode())
        .estDistance(validatedDto.getEstDistance()).estPrice(validatedDto.getEstPrice())
        .priceRadio(validatedDto.getRadio() == null ? BigDecimal.ZERO : validatedDto.getRadio())
        ...
        .build();
try {
    rideOrderMapper.insert(order);
} catch (RuntimeException failure) {
    try { orderRouteService.deleteByTraceId(order.getMongoTraceId()); }
    catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    throw failure;
}
if (order.getOrderId() == null) throw new BusinessException(500, "订单创建失败");
String createdOrderId = order.getOrderId().toString();
orderGrabService.syncOrderStatus(createdOrderId, RideOrderStatus.CREATED.getCode());
orderDelayQueue.schedule(createdOrderId, Duration.ofMinutes(timeoutProperties.getAcceptTimeoutMinutes()));
orderGeoPool.add(createdOrderId, order.getStartLng(), order.getStartLat());
return createdOrderId;
```

- **`createOrder` 没有本地 `@Transactional`**（O1/F3 已核验）。这句话不能推广成“任何调用上下文里 `insert` 必然自动提交”：当前 Agent 工具路径和 `RideOrderController#create` 都没有开外层事务，在这种上下文下 MyBatis 的单条 `insert` 通常按连接/自动提交设置执行；如果其他调用方在外层事务中调用 `createOrder`，`insert` 会加入外层事务，提交/回滚边界由外层决定。教材只对“当前两条入口没有外层事务”的事实作条件化陈述。
- 后置三个动作都带“事务同步判断 + 异常吞噬”：`syncOrderStatus` 与 `orderDelayQueue.schedule` 通过各自的 `afterCommit` 辅助方法注册（**当前 `createOrder` 没有事务同步时，辅助方法会立即执行**）；`orderGeoPool.add` 实际调用 `remove → invalidate`，有事务同步时注册 `afterCommit`，没有时立即失效；三者都 `catch RuntimeException` 并记日志，所以它们通常不会让 `createOrder` 抛异常；但它们的失败会让缓存/队列与 DB 短暂不一致。
- `insert` 失败时：删除**新轨迹**（用 `order.getMongoTraceId()`），把清理异常 `addSuppressed`，再抛出。**不能凭异常断言订单不存在**：在当前“无外层事务”的入口下，超时/连接中断可能让库端已提交；若有外层事务，异常也可能触发外层回滚；若数据库在提交前失败，则可能没写。要结合调用上下文和订单表核查。
- 返回的 `orderId` 是前面生成的雪花 ID，builder 已写入；`insert` 后仍检查 `order.getOrderId() == null`，空值才抛 500（正常路径不会为空）。

### 6.6 回到 `OrderTool#createOrder`：预览轨迹、锁会话、写 `OrderId`

下面五步**都发生在 `OrderTool#createOrder` 方法内部、这个方法返回之前**：`rideOrderService.createOrder` 返回订单号之后，工具方法还要清理预览轨迹、读三个展示字段、锁会话、写 `OrderId`、拼返回文本。它们不是“工具返回之后的服务端动作”，而“工具返回文本”正是这串步骤的最后一步。

```java
// agentbase/tool/OrderTool.java#createOrder（真实节选，续）
String orderId = rideOrderService.createOrder(orderDTO);
try { orderRouteService.deleteByTraceId(orderDTO.getMongoTraceId()); }
catch (RuntimeException e) { log.warn("订单预览轨迹清理失败", e); }
String estDistanceKm = redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name());
String estTimeSec = redisGet(chatInfoKey, "EST_TIME");
String estPriceStr = redisGet(chatInfoKey, OrderInfoEnum.EST_PRICE.name());
chatManager.lockChat(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
redisPut(chatInfoKey, "OrderId", orderId);
return String.format("订单已创建，订单编号%s，…本轮对话已锁定，引导用户后续需求新建对话。", ...);
```

- **预览轨迹清理**：`createOrder` 在副本上换了新 `mongoTraceId`（`validateAndSupplement` 返回的是副本），调用方 `orderDTO` 仍保留**报价时的预览轨迹 id**；所以这里删的是预览，不是订单轨迹。删除被 `try/catch` 包住，`RuntimeException` 只记日志，**不会冒泡成工具失败文本**（7.7 的失败来源里不应再列这一项）；若删除命令响应丢失，预览是否仍在也未知。
- **三个展示字段的读取**：`EST_DISTANCE_KM`、`EST_TIME`、`EST_PRICE` 用 `redisGet`（缺字段抛 `IllegalArgumentException`）**在订单创建之后**才读。如果其中任一字段在创建后被清掉/过期，这里会抛异常 → 被 `executeTool` 转成失败文本；**订单已经存在，但 `lockChat` 与 `OrderId` 都没有开始执行**。如果读取命令本身响应丢失，字段是否可读也不是确定结果。
- **`lockChat`**：先 `ChatInfoService.lockChat` 做 DB `UPDATE sys_chat SET locked=true`（不检查影响行数），再执行 Lua `HSET locked=true` + `EXPIRE 60min`（脚本内部原子）。可能的分支：DB 更新抛异常 → Redis 脚本未调用；DB 可能未改，也可能已提交而响应丢失；DB 成功、Redis 脚本抛异常/响应丢失 → DB 可能已 `locked=true`，Redis 的 `locked` 与 TTL 可能已写、可能没写，不能推定；Redis 脚本返回 0（理论上 HSET 后 EXPIRE 才会 0）也不代表业务层检查过返回值——返回值被忽略，代码会继续往下走，`OrderId` 仍可能写入。只有 `lockChat` 抛出异常时，`OrderId` 才确定未执行；若异常是响应丢失，DB/Redis 的实际状态仍可能已变。
- **`OrderId`**：最后才写；它是 `getRestorableChat` 判定“这个会话已下过单”的依据（有 `OrderId` 就不算可恢复）。`HSET OrderId` 抛异常时**不能断言字段缺失**：命令可能已执行而只是响应丢失；也不能断言会话一定可恢复。只有明确读到 `OrderId` 不存在时，才能按“缺字段”继续分析。
- **返回文本的价格是报价值**：`estPriceStr` 来自 Redis 的 `EST_PRICE`（报价时写入），不是刚插入订单行的 `est_price`（创建时重算）。所以工具返回“预计价格 38.60 元”时，订单表里的金额可能已经是另一个数。
- **格式化/返回也可能失败**：`TimeUtil.getMinutes(estTimeSec)` 或 `String.format` 在最后一行执行；若 `EST_TIME` 格式异常，订单、锁、`OrderId` 可能都已完成，但工具仍以异常文本结束。返回文本是给模型看的；模型再写自然语言。**“订单已创建”这句话来自工具返回，但工具返回本身是服务端在 `createOrder` 返回后才拼的**；如果它在返回前失败（包括上面三个展示字段读取失败），模型看到的是 `Tool execution failed`，用户可能以为没创建。

### 6.7 直接 `POST /order`：另一条路

```java
// controller/RideOrderController.java#create（真实）
@PostMapping
@RequirePermission
public Result create(@RequestBody CreateOrderDTO req) {
    req.setUserId(UserTokenContext.getUserIdInString());
    String orderId = rideOrderService.createOrder(req);
    return Result.ok(orderId);
}
```

它：

- 只要求登录（`@RequirePermission` 无角色值），**不查** `OrderConfirmation`、`ReadyForCreate`、`quoteId`；
- 用 Token 身份覆盖请求体的 `userId`；
- 同样经过 `validateAndSupplement`、重新规划、重新计价、插入与后置副作用；
- 没有“用户看过报价并确认”的服务端记录。

所以：**确认协议是 Agent 工具路径的门禁，不是订单服务的全局门禁。** 不能写“所有创建订单的入口都必须先确认”；也不能反过来写“确认协议可以被直接 API 绕过，所以它没用”——它保护的是“模型不能自作主张创建订单”，而不是“人类不能直接调用订单 API”。直接 API 的授权在登录与订单服务自身的校验层（A2/O1）。

---

## 七、可达时间线与并发

以下时间线全部是**示意**：模型是否真的调用某工具、Redis/MySQL/高德的实际返回、线程与网络时序都未实测。有时间线会写出“尝试发出/服务端状态变化”，但**不把事件写入 sink 当成客户端已收到**；只列服务端状态的表格，也不能反推用户看到了什么。

### 7.1 T1：正常确认并创建

初始状态：会话 `chatId=9f1d…`（虚构），`quoteId=q1`，`ReadyforConfirm=true`，`break` 不存在，历史里有订单参数。

| 阶段 | 动作 | 状态变化 | 可见结果 |
|---|---|---|---|
| t0 | 模型调 `getEstRouteAndPrice` | 读槽位；写 `EST_PRICE=38.60`、`EST_DISTANCE_KM=12.4`、`EST_TIME=1680`、`MONGO_TRACE_ID`（需要规划时）、`q1`、`quoteCreatedAt=t0`、`ReadyforConfirm=true`；删 `ReadyForCreate/confirmedQuote`；TTL 尝试设 24h | 工具文本“请使用 notifyUser 确认” |
| t1 | 模型调 `notifyUser`，`handleToolCalls` 拦截 | `begin` 写 `pendingToolCall=call_9`、`break=yes`；JSON 加 `confirmationId=q1`；保存 assistant（+前后工具回执）；再写 `break=yes` | `confirm` 事件**尝试**发出；sink **尝试**结束 |
| t2 | 执行锁在 `doFinally` **尝试**释放 | 锁可能已释放，也可能因异步失败仍持有 | 若订阅已建立且事件送达，客户端看到事件；连接结束是否可见未实测 |
| t3 | 用户点“确认”，前端 `POST /r` `{confirmationId:q1, confirmed:true}` | 先同步通过 UUID 格式；订阅时（defer supplier）拿执行锁、`assertOwned`；随后后台线程：prompt 回填“确认”、限流通过 | HTTP 流已建立；事件是否送达未实测 |
| t4 | `confirm` | `break=yes → resuming`；`confirmationAccepted=yes`；`confirmedQuote=q1`；`ReadyForCreate=q1` | 无独立事件；继续 |
| t5 | `OrderAgent#resume` | `break=resuming` ok；`getPersisted` 读 MySQL；`pendingToolCall=call_9`；**假设窗口内没有该 id**，`answered=false`；保存 `ToolResponse(call_9, notifyUser, feedback)`；删 `break/pending/accepted` | 模型开始下一轮；若 MySQL 已写、后续缓存/更新时间失败，重试时 `answered` 可能为 true |
| t6 | 模型调 `markOrderReadyForCreate` | 只读 `isConfirmed` → true | 工具文本“可以创建” |
| t7 | 模型调 `createOrder` | 快速检查 `ReadyForCreate` 存在；组装 DTO；`consume` 成功 → 删两个许可；`createOrder` 重新规划/计价 → 新轨迹 → insert → 状态缓存/队列/GEO | 工具文本“订单已创建…”（若后续步骤都成功） |
| t8 | 清理预览轨迹；读 `EST_DISTANCE_KM/EST_TIME/EST_PRICE` 三个展示字段；`lockChat`；写 `OrderId` | DB/Redis `locked=true`（TTL 60min，前提是 Lua 成功）；`OrderId` 写入 | 返回文本；模型再写自然语言 |
| t9 | 模型最终文本 → `saveNewMessages` → `tryEmitComplete` | 历史新增最终 assistant；锁 **尝试**释放 | 若事件送达，客户端看到 message 和流结束；否则只能看到连接关闭/error |

**能断言的**：如果 t0～t8 的每一步都成功，Redis 的许可被消费一次、MySQL 有一行 `CREATED(10)` 订单、会话被锁。
**不能断言的**：模型一定按这个顺序调用；高德/计价返回相同；事件一定到达客户端；锁一定释放；金额与 t0 的报价一致。

### 7.2 T2：两个标签页，相同或相反的决定

初始状态同 T1：`break=yes`、`q1`。

| 子场景 | 时序 | 结果 |
|---|---|---|
| 并发同一决定 | T1 先拿到 `chat:execution:{chatId}`；T2 的 `tryLockAsync(0)` 立即失败 | T1 进入 `confirm`/`resume`；T2 的 Runner 返回单事件 `error("会话正在处理中，请等待本轮结束")` 的 `Flux`（客户端是否看到、HTTP 状态未实测）。**T2 没有消耗/改变确认状态** |
| 串行同一决定（T1 已跑完） | T1 的 `resume` 已删 `break`；T2 再 `confirm` | `quoteId` 仍匹配，但 `state` 不是 `yes`/`resuming` → 0 → 409 业务异常（`guard` 尝试发通用 error）。**失败** |
| 串行同一决定（T1 在 `resuming` 窗口内失败） | T1 的 `confirm` 成功但 `resume` 在保存应答时失败；若 `HDEL` 明确未执行，`break` 仍 `resuming`、`confirmationAccepted=yes` | T2 同 `yes`、年龄 ≤15 分钟 → 1 → 再次 `resume`；`answered` 取决于 MySQL 与窗口（可能 true，也可能 false） |
| 串行相反决定（T1 在 `resuming` 窗口内） | `confirmationAccepted=yes`，T2 发 `no` | 第 4 行 `!=` → 0 → 409。**已发出的创建许可不会被撤回** |
| 串行相反决定（T1 已跑完） | `break` 已删 | `state` 不是 `yes` → 0 → 409 |
| 两个 `/c` 并发 | 同一执行锁 | 后到者的 Runner 返回“会话正在处理中”的单事件流，不进入分类/模型 |

**结论**：真实并发主要被**单轮执行锁**串行化；确认脚本只负责“同一决定在窗口内按各自年龄规则可重放、相反决定不可覆盖”。不要写成“两个标签页会同时确认成功”，也不要写成“重复确认一律失败”。这条结论的前提是执行锁在整轮期间确实被持有：租约到期、解锁失败、看门狗未按预期续期，或请求走 `/lock`、`/restore` 这类不经过 Runner 的入口时，串行化不成立（7.6）。

### 7.3 T3：报价过期，接受失败但拒绝可行

各行是独立示例，初始均为 `quoteId=q1`、`break=yes`、`quoteCreatedAt=t0`；假设编号匹配、脚本正常执行，且没有并发修改或过期。最后一行另以指定时刻确认成功作为前提。

| 时刻 | 动作 | 结果 |
|---|---|---|
| t0+14:59 | 用户点“确认” | `CONFIRM` 年龄 899000 ≤ 900000 → 1；`break=resuming`，许可写入 |
| t0+15:00:00 | 用户点“确认”（正好 900000 毫秒） | `> 900000` 不成立，年龄条件通过 → `CONFIRM` 返回 1（还需编号/`break=yes` 等条件），**仍可接受** |
| t0+15:00:00.001 | 用户点“确认” | 900001 毫秒 > 900000 → 0 → 409。900001 是毫秒数，不是“1 秒”；写成 `900001 ms` 才对 |
| t0+15:00:01 | 用户点“确认” | 901000 毫秒（15 分 1 秒）> 900000 → 0 → 409；`break` 仍 `yes`，许可未写 |
| 过期后 | 用户点“拒绝” | `no` 跳过年龄 → 1 → `break=resuming`，删许可 → 恢复 → 会话回到普通状态 |
| 过期后 | 用户想改参数重新报价 | `/c` 被 `break=yes` 挡住（“请先确认或拒绝当前订单”）；只能先拒绝或换会话 |
| 已确认但创建拖过 15 分钟 | 用户在 t0+14:00 确认，模型在 t0+16:00 才调 `createOrder` | `CONSUME` 以 t0 为锚点 → 0 → `createOrder` 返回失败文本；许可仍在，但每次重试都失败，直到重新报价 |

这张表说明两个独立的 15 分钟：**接受时检查一次，消费时再检查一次，锚点相同**。用户“点得早”不能保证“建得成”。

### 7.4 T4：消费成功，但创建失败或响应丢失

初始：`ReadyForCreate=q1`、`confirmedQuote=q1`、`break` 已删（恢复完成）。

| 子场景 | 顺序 | 结果 | 能否自动恢复 |
|---|---|---|---|
| 路径规划失败 | `consume` 成功（删许可）→ `createOrder` 调高德返回空 → 502 | 订单未插入；许可已消费；模型收到 `Tool execution failed: 路径规划失败…` | 不能；再调 `createOrder` 会先被 `ReadyForCreate == null` 挡成“订单非可创建态”，必须重新报价+确认 |
| Mongo 写轨迹失败 | `consume` 成功 → 计价后 `orderRouteService.addOrder` 抛异常 | MySQL 尚未 `insert`，所以订单行未插入；许可已消费；Mongo 新轨迹可能已写也可能未写（响应丢失未知）；预览轨迹可能还在 | 不能；同上；新轨迹同样要核查 |
| MySQL 插入抛异常 | `consume` 成功 → `insert` 抛（可能是超时/连接断，也可能真失败） | 新轨迹**尝试**删除；许可已消费；订单**可能已提交也可能没有**（取决于调用上下文和提交时机） | 不能凭异常断言；要查订单表/订单列表 |
| 订单已插入，展示字段读取失败 | `createOrder` 返回 orderId → 预览轨迹删除 → `redisGet(EST_TIME/EST_PRICE/EST_DISTANCE_KM)` 缺字段/异常 | 订单存在；本次流程里 `lockChat` 与 `OrderId` **尚未开始**；工具返回失败文本 | 不能自动。订单已落库，但“会话是否 `locked`”不能由这一步没执行来推断：此前可能有 `ChatStatusTool#lockSession`、`POST /lock`、`restore` 或并发轮次改过它；`OrderId` 也取决于历史状态 |
| 订单已插入，`lockChat` 失败 | `createOrder` 返回 orderId → 展示字段读取成功 → `lockChat` 的 DB 更新或 Redis 脚本失败 | 订单存在；DB `locked` 可能已 true；Redis `locked`/TTL 可能已写也可能没写；`OrderId` 未写；工具返回失败文本 | 不能自动；可能被 DB `locked` 挡住，也可能因无 `OrderId` 被 `restore` 解锁，取决于实际失败点 |
| 订单已插入，写 `OrderId` 失败 | `lockChat` 成功 → `HSET OrderId` 抛/响应丢失 | 订单存在；会话可能已锁；`OrderId` 可能未写，也可能已写但响应丢失；工具返回失败文本 | 不能自动；只有明确读到缺失，`getRestorableChat` 才可能把它当可恢复 |
| 全部成功，但最终 `message`/`save`/`complete` 失败 | 订单/锁/`OrderId` 都已写 | 客户端可能只看到部分文本或 error；订单已存在 | 对话层可重试，但订单不会因此消失 |

**共同结论**：`consume` 是“先撤销许可，再尝试创建”。撤销是不可逆的；创建不是。所以“消费成功”只证明“许可被用掉了”，不证明“订单创建成功”。

### 7.5 T5：Redis 脚本/客户端响应丢失

| 子场景 | 已发生的状态 | 重试行为 |
|---|---|---|
| `confirm` 脚本执行成功（`resuming`+许可），但 HTTP 连接在事件前断开 | 状态已变；客户端没收到任何确认结果 | 重试同一决定：同 `resuming`、同 `confirmationAccepted`、接受年龄 ≤15 分钟 → 1 → 继续；接受超龄 → 0；拒绝 → 1（不查年龄） |
| `confirm` 脚本执行报错（如连接在返回前中断） | **未知**：脚本可能已执行，也可能没有 | 不能假设；重试同一决定：若已执行则走重放规则，若未执行则走 `state=yes` 的正常接受/拒绝；两种情况结果可能不同 |
| `resume` 保存工具应答成功，删 `break` 前 Redis 失败 | 应答在 MySQL；若 `HDEL` 明确未执行，`break` 仍 `resuming`；若响应丢失，`break` 可能已删也可能未删 | 重试同决定：若 `break` 仍 `resuming` → 1 → `answered` 取决于窗口 → 可能不重复保存 → 再删；若 `break` 已删 → `confirm` 返回 0 |
| `resume` 删 `break` 成功，模型调用失败 | 应答在 MySQL；`break` 已删；许可仍在（未消费） | 再 `/r` 会被 `confirm` 的 `state ~= 'yes'` 拒绝；但 `/c` 已恢复可用，用户可以继续聊天；许可仍在，模型重新走流程时仍可能 `createOrder`（若没有重新报价） |
| `quoted` 的 `EXPIRE` 丢失 | 新 `quoteId` 在，TTL 可能是旧的/无 | 15 分钟年龄仍按 `quoteCreatedAt`；TTL 只影响字段何时消失 |

这里要特别小心“未知”状态：Redis 命令超时/连接中断时，Java 只看到异常，**不能从异常类型推断脚本是否执行**。教材只能给“可能已变/可能未变”的两种分支，不能给确定结论。

### 7.6 真实并发是否受执行锁限制

- **同一 `chatId` 的 `/c` 与 `/r` 共用 `chat:execution:{chatId}`**（`ChatRequestRunner` 对两个入口都 `getLock`）。正常运行时，同一会话的确认/恢复是串行的。
- **不同 `chatId` 不互斥**：两个会话可以同时确认各自的报价；它们共享 Redis，但 key 不同。
- **锁不是永久的**：`tryLockAsync(0, -1, SECONDS, owner)` 是否由 Redisson 看门狗续期，取决于实际 Redisson 配置与运行状态；仓库没有显式 `lockWatchdogTimeout`，**不能把库默认（例如 30 秒）当成生产事实**。如果看门狗没有按预期生效、JVM 崩溃、或应用与 Redis 网络分区，租约可能到期，另一个请求就能进入。
- **解锁是尝试**：`doFinally` 调 `unlockAsync(owner)`，失败时锁可能继续被看门狗续期（同一 JVM 活着），也可能租约到期后释放；代码没有确认解锁成功。
- **`/lock` 与 `/restore` 不经过执行锁**：`POST /chat/v2/lock/{id}`、`GET/POST /restore` 是同步 `Result` 接口，不走 `ChatRequestRunner`。它们可以与会话内的轮次并发，改 `locked`/TTL。
- **执行锁不等于确认状态的原子性**：`CONFIRM` 脚本内的 Redis 读取/写入由 Lua 原子执行；执行锁只减少并发进入 `confirm` 的机会，不覆盖 `quoted/begin` 的多命令，也不跨 MySQL。即使锁失效，`CONFIRM` 仍会按状态机返回 0/1。

### 7.7 成功、失败、未知三态

| 观察 | 能断言 | 不能断言 |
|---|---|---|
| `confirm` 返回 1 | 若脚本走的是 `state=yes` 分支：写了 `break=resuming`、`confirmationAccepted`，接受时写许可；若走的是 `state=resuming` 且同决定：**直接返回 1，不写任何字段** | 不能推断许可一定存在/一定被写；订单不会自动创建；用户是否看到结果未知 |
| `consume` 返回 1 | 同一 Redis 状态下，许可被脚本 `HDEL` 一次 | MySQL 订单存在；金额等于报价；后续锁会话成功；其他写入没有重新签发许可 |
| `createOrder` 返回文本“订单已创建” | 工具方法走到了最后一行（`createOrder` 返回了 id，`lockChat`/`OrderId` 也成功） | 模型把这句话完整传给用户；客户端收到；订单行金额等于确认摘要 |
| 工具回执为 `Tool execution failed` | `executeTool` 捕获了工具执行的异常；订单服务可能已成功返回，失败发生在之后的展示字段读取/`lockChat`/`OrderId`/格式化。预览轨迹清理的 `RuntimeException` 被 catch 只记日志，**不会**产生这条失败文本 | 订单不存在；许可没被消费；会话锁定状态确定 |
| 客户端收到 `confirm` 事件 | 事件被写入过 sink 且下游订阅存在 | 用户点了确认；Redis 状态正确；事件不会丢失 |
| `resume` 发了 `error` | `guard` 或 `resume` 的显式分支执行了 | 具体是 400/409/状态不符；HTTP 状态码；客户端收到该 `error` |

---

## 八、五个不同的对象与信任边界

### 8.1 确认状态、会话 `locked`、执行锁、事件、`ToolResponse`

| 对象 | 存在哪里 | 谁写 | 作用 | 生命周期 |
|---|---|---|---|---|
| 确认状态 | Redis `chat:info` 的 `quoteId/quoteCreatedAt/ReadyforConfirm/break/pendingToolCall/confirmedQuote/ReadyForCreate/confirmationAccepted` | `quoted`/`begin`/`CONFIRM` 写，`consume` 删许可；`resume` 删 `break/pendingToolCall/confirmationAccepted` | 决定“这次报价能不能被接受/消费” | 报价后出现；确认/拒绝/消费/清理后部分消失；key 过期全消失 |
| 会话 `locked` | MySQL `sys_chat.locked` + Redis `locked` | `lockChat`（DB 更新后 Lua `HSET+EXPIRE`）；`restoreChat` 置 false 并 `PERSIST` | 阻止 `/c` 继续普通对话（`isLocked`） | 可能由下单成功、`ChatStatusTool#lockSession` 或 `POST /lock` 置 true；restore 后 false；Redis 侧 TTL 是否 60 分钟取决于 Lua 是否成功 |
| 单轮执行锁 | Redis/Redisson `chat:execution:{chatId}` | `ChatRequestRunner` | 同一会话同一时刻只允许一个轮次（前提是锁确实持有到期） | 订阅时获取，`doFinally` 尝试释放；是否由看门狗续期取决于 Redisson 实际配置，仓库未显式设置，未实测 |
| SSE 事件 | 内存 sink → HTTP 流 | 各 Agent/Service | 把 message/tool/error/confirm/notify 传给客户端 | 瞬时；不落库；客户端可能收不到 |
| `ToolResponseMessage` | 内存历史 + MySQL `chat_message`（Redis/heap 缓存） | `resume`/各 Agent `saveNewMessages` | 把工具调用与结果配对，供下一轮模型使用 | 随消息持久化；可能重复/缺失，受 `MessageWindow` 校验 |

它们的关系不是“一个东西的四种叫法”：

- 确认状态可以存在而 `locked=false`（报价阶段）；
- `locked=true` 时确认状态通常已消费，但代码不强制；
- 执行锁可以在确认状态为 `yes` 时仍被另一个请求持有；
- `confirm` 事件发出但 Redis 没变（发事件在写状态之后，但事件失败不回滚）；
- `ToolResponseMessage` 写了但 `break` 没删（保存成功、`HDEL` 明确未执行）；如果 `HDEL` 是响应丢失，`break` 是否还在未知。

### 8.2 信任来源表

| 值 | 生成位置 | 服务端是否信任 | 用途 |
|---|---|---|---|
| `chatId` | 客户端 URL | 只信“格式 + 归属查询结果”；不证明历史归属 | Redis key、`assertOwned` |
| `userId` | Token → 拦截器 → ThreadLocal → Controller | 是（服务端身份链） | Agent 的 `ToolContext`、DTO、订单归属 |
| `quoteId`/`confirmationId` | `quoted` 的 `UUID.randomUUID()` | 服务端只把客户端回传的编号与 Redis 当前 `quoteId` 比较；编号本身不是身份凭证 | 绑定“当前哪次报价”；正常路径不作为模型工具回执，但不靠保密授权 |
| `quoteCreatedAt` | `quoted` 的 `System.currentTimeMillis()` | 是（服务端时钟） | 15 分钟年龄；非数字值可能让 Lua 报错 |
| `pendingToolCall`/`toolCallId` | 模型响应 | 否（但只用于配对，不作授权） | 构造 `ToolResponse` |
| `break`/`ReadyforConfirm`/`confirmedQuote`/`ReadyForCreate` | 服务端代码 | 是（Redis 当前值） | 状态机；`resuming` 重放返回 1 不代表字段被重写 |
| 模型文本（“用户已确认”） | 模型 | 否 | 只给用户看 |
| 工具回执文本 | 工具方法拼接 | 部分是（例如“订单已创建”来自真实返回） | 模型上下文；不是授权 |
| `EST_PRICE`（报价） | `RidePricing` → `OrderTool` 写 Redis | 是（但创建时会重算） | 展示/确认内容 |
| 订单 `est_price` | `calculatePrice` 在创建时重算 | 是 | 落库金额 |

**一句话**：用户身份来自 Token；报价编号由服务端生成，正常路径不作为模型工具回执，但编号不是秘密、保密不是授权；用户的决定来自 `/r` 请求体但必须与服务端 Redis 对齐；模型的任何文本都不是确认。

### 8.3 提示词只是流程约束

`ChatProperties.ORDER_AGENT_SYS_PROMPT` 写着：

```text
D) 用户确认与创建
    - 调用 notifyUser() 输出“将要创建的订单摘要”，并请用户确认/修改。
    - 用户确认无异议：调用 markOrderReadyForCreate()，随后调用 createOrder() 创建订单。
```

这是**模型行为引导**，不是服务端规则：

- 模型可以不调 `notifyUser` 直接调 `createOrder`；通常先撞上 `ReadyForCreate == null`，得到“订单非可创建态”；若有旧许可，则可能进入 `consume`（若编号匹配/未超龄）；
- 模型可以调 `markOrderReadyForCreate` 并在文本里说“已确认”；它不会写任何字段；
- 模型可以在用户只是问价时调 `createOrder`；通常先被 `ReadyForCreate == null` 挡成“订单非可创建态”，若有旧许可再被 `consume` 挡；
- 反过来，模型也可以在用户已确认后不调 `createOrder`，订单不会创建。

服务端的安全边界不在提示词里，而在 `OrderTool#createOrder` 的 `ReadyForCreate` 检查 + `OrderConfirmation#consume` 的 Lua + `RideOrderService` 的身份/校验/计价。

### 8.4 直接 API 的边界

`POST /order` 不经过 `OrderConfirmation`，但它有自己的边界：

- `@RequirePermission` 只要求登录；
- Controller 用 Token 覆盖 `userId`；
- Service 再覆盖一次（若 ThreadLocal 有值），并校验非空；
- DTO 校验/补充、坐标检查、重新规划、重新计价、插入。

它**没有**“用户同意这次报价”的记录。把它和 Agent 路径混为一谈，会得出两个错误结论：

- “确认协议能防止所有未授权下单”——不能，直接 API 不需要它；
- “既然直接 API 能下单，确认协议没有意义”——它防的是模型幻觉/重放/误创建，不是防人类调用自己的 API。

---

## 九、失败与边界总表

| 场景 | 源码行为 | 用户/模型可见 | 备注 |
|---|---|---|---|
| `quoted` 第 2 步成功、第 3 步命令明确未执行 | 报价字段在，TTL 未重置 | `EXPIRE` 返回 `false` 时代码不检查，工具可能仍返回成功文本；抛异常时 `Tool execution failed` | 旧 TTL 可能是 60 分钟/无；若异常来自响应丢失，TTL 可能其实已刷新 |
| `quoted` 第 1 步成功、第 2 步命令明确未执行 | 旧许可已删，可能残留旧 `quoteId`/`ReadyforConfirm` | 工具抛异常 → `Tool execution failed` | 可能形成“有报价但用户没见过”的中间态；响应丢失时键值可能已换新，不能套用这条 |
| `begin` 无 `quoteId`/`ReadyforConfirm` | 409 | catch → `error("序列化订单参数失败: 请先完成有效报价")` + `complete` | 文案与真实原因不符 |
| `begin` 成功但暂停前消息保存失败 | `break=yes`、`pendingToolCall` 已写；保存可能 MySQL 未写/回滚，也可能 MySQL 已写、后续 Redis/heap/更新时间失败 | `error` + `complete`（catch，尝试发） | 此时还没构造 `notifyUser` 的用户应答，`HDEL`/`answered` 属于之后的 `resume`；暂停状态是否成立还要看 `break` 是否被并发/过期改掉 |
| `confirm` 缺参数 | 400 | `guard` 尝试发 `error("处理失败，请稍后重试")`；是否收到/HTTP 状态未实测 | 不能确定是 HTTP 400 |
| `confirm` 编号错/状态不符/接受超龄 | 409 | 同上 | 不区分原因；脚本正常 0 不写字段 |
| `confirm` 拒绝超龄 | 成功（不查年龄） | 进入 `resume`；模型是否继续、事件是否送达都取决于后续步骤，未实测 | 报价过期的逃生口 |
| `confirm` 同决定重放 | `resuming` 且接受不超龄时返回 1 | 进入 `resume`；不保证模型一定继续 | **不写字段**；不能推断许可仍在 |
| `confirm` 相反决定 | 409 | 通用 error（尝试发） | 许可不撤回 |
| `resume` 的 `break` 不是 `resuming` | 显式 `error("非法的恢复请求…")` + `complete` | `error` 事件（尝试发） | 不是异常 |
| `pendingToolCall` 丢失 | 显式 error + complete | `error` 事件（尝试发） | 历史里有 `notifyUser` 也没用；本分支不删 `break`（并发/过期另论） |
| `getPersisted` 历史损坏 | `MessageParser` 可能清空并从后续 User 恢复；`MessageWindow` 只在特定工具配对错误时抛 `IllegalStateException` | `guard` 通用 error，或静默得到较短历史 | 不是所有损坏都抛；被丢掉的应答可能导致重复保存 |
| 保存工具应答失败 | `save` 抛异常，代码在 `HDEL` 之前返回，本次删除一定未执行；MySQL 可能已写也可能未写 | 通用 error（尝试发） | 重试 `answered` 可能 true/false；`break` 是否仍 `resuming` 还要看期间有无并发/过期 |
| `markOrderReadyForCreate` 返回“可以创建”但已超龄 | 只读三字段 | 模型以为可创建 | `createOrder` 的 `consume` 会拒绝 |
| `createOrder` 的 `ReadyForCreate` 为空 | 返回“订单非可创建态” | 工具文本 | 可能是没确认/已消费/被撤 |
| `createOrder` 缺槽位 | `redisGet` 抛 | `Tool execution failed` | 工具循环继续；许可尚未消费（异常发生在 consume 前） |
| `consume` 超龄/许可不符 | 409 / 脚本 0 | `Tool execution failed` | 脚本正常 0 不删两个许可字段；运行时错误另论 |
| `createOrder` 路径规划失败 | 502 | `Tool execution failed` | 许可已消费，不能重试 |
| `insert` 抛异常 | 删除新轨迹（尝试）+ 重抛 | `Tool execution failed` | 订单可能已提交（当前无外层事务时的超时）；有外层事务另论 |
| `createOrder` 的展示字段读取失败 | 订单已插入后 `redisGet` 抛异常 | `Tool execution failed` | 订单存在；本次流程里 `lockChat`/`OrderId` 尚未执行，但会话可能已被其他入口锁过 |
| `lockChat`/`OrderId` 失败 | 异常 → 工具失败文本 | 订单存在，状态可能不一致 | DB 锁/Redis 锁/`OrderId` 可能已写也可能未写；无自动补偿 |
| 客户端断开 | sink 取消；后台可能继续 | 可能看不到事件 | 锁/DB 状态不因断开自动回滚 |
| Redis 命令超时 | 异常；是否执行未知 | 工具失败/通用 error | 不能断言状态 |

---

## 十、设计取舍与边界

### 10.1 这套实现保证什么

在“Redis/MySQL/网络都正常、没有并发绕过、模型按预期调用”的前提下：

- 在正常参数保存/重新报价且 Redis 命令成功的路径上，一次报价只有一个当前 `quoteId`；重新报价/改参数会让旧编号失效（早退/部分失败另论）；
- 一次接受在 `state=yes` 的正常分支最多签发一份创建许可；`consume` 成功后不能再用同一现存许可创建；`resuming` 同决定重放不重写字段，不能据此推断许可仍在；
- 接受必须 ≤15 分钟（按报价时间）；拒绝不受年龄限制；
- 恢复只从 `break=resuming` 进入；在 `resuming` 窗口内同一决定若返回 1，不会重复写字段（接受仍受 15 分钟限制，拒绝不查年龄）；
- 用户的接受/拒绝只能由 `/r` 的服务端脚本落成 `break/confirmationAccepted/许可`；模型文本不能代替；报价字段由 `quoted`/`saveNewOrderParam` 改，`consume` 只删许可；
- 创建的订单身份来自 Token/ToolContext，客户端/模型不能指定乘客。

### 10.2 这套实现不保证什么

- **不保证金额不变**：创建时重新规划/计价；
- **不保证 DTO 与暂停摘要逐项相同**：`OrderTool#createOrder` 在 `consume` 前逐字段读 Hash，确认脚本不做快照/哈希绑定；
- **不保证订单一定创建**：`consume` 成功之后还有多个失败点；
- **不保证失败后能自动恢复**：许可已消费，重试通常要重新报价+确认；
- **不保证“抛异常=订单不存在”**：当前入口没有外层事务时，`insert` 可能按自动提交执行，超时可能已提交；有外层事务时边界另论；
- **不保证确认事件一定到客户端**：`tryEmit*` 不检查返回值；
- **不保证并发完全串行**：执行锁可能过期/解锁失败；`/lock`、`/restore` 不走执行锁；
- **不保证 15 分钟用同一时钟**：年龄锚点由 JVM 的 `System.currentTimeMillis()` 写入和比较，多实例 JVM 时钟可能偏移；Redis TTL 用的是另一套时钟，只影响字段存续，不参与年龄判定；
- **不保证 `isConfirmed` 等于“现在能创建”**：它不查年龄、不查 `locked`；
- **不保证所有创建入口都走确认**：直接 `POST /order` 不走；
- **不保证 Lua 等于跨库事务**：Redis 原子范围之外还有 MySQL/Mongo。

### 10.3 为什么把“报价编号”和“工具调用 id”分开

- `quoteId` 绑定**一次报价编号**：脚本只按当前 `quoteId` 匹配，不校验金额/槽位快照，也没有哈希；所以它约束“哪个编号被接受”，不证明“每个字段被用户看过且永不变化”。
- `toolCallId` 绑定**一次模型工具调用**：它只用于把迟到的用户反馈补成 `ToolResponseMessage`，让历史协议闭合。模型可以生成/影响这个 id；即使它猜中/伪造 `quoteId`，仍要经过 `/r` 的归属检查、`break` 状态和接受年龄（`consume` 还要许可匹配），编号保密本身不构成授权。
- 两者混用会让“重放同一工具调用”变成“重放同一报价”，语义就错了。

### 10.4 为什么先消费再创建

代码顺序是 `consume` → `createOrder`。可推导的效果：

- 在**同一 Redis 状态、同一现存许可**下，防止两个并发的 `createOrder` 都成功消费它（Lua 的 `HDEL` 是原子的，只有一个返回 1）；重新报价/再次签发、其他写入、故障丢失不在此保证内；
- 代价是“创建失败也要重新确认”。这是“宁可让用户再确认一次，也不让同一现存许可创建两单”的取舍；不能同时保证“失败可重试”和“只创建一次”，也不是全局幂等。

---

## 十一、测试与评测脚本（只读，未运行）

> 本节只读测试源码，**没有运行任何测试或脚本**。测试名和断言不代表真实通过；mock 的行为不代表真实组件保证。

### 11.1 `SecurityRedisIntegrationTest`

- **门控**：类上有 `@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")`；只有显式设置 `-Dtest.redis.port=<端口>` 才启用。
- **真实/替身**：`@BeforeAll` 用 `LettuceConnectionFactory("127.0.0.1", port)` 建**真实 Redis** 连接；`OrderConfirmation` 直接用 `new OrderConfirmation(redis)`（真实对象，不是 mock）。邮件发送用 mock。
- **确认相关方法**：
  - `confirmationIsBoundToQuoteAndConsumedOnce`：`quoted(suffix)` → `begin(suffix,"call")` → 错编号 `confirm` 抛异常 → 正确编号 `confirm(...true)` 成功 → **同编号同决定再 `confirm` 不抛异常**（`assertDoesNotThrow`）→ 相反决定 `confirm(...false)` 抛异常 → `isConfirmed` true → `consume` 成功 → 再 `consume` 抛异常。
  - `rejectionAndNewQuotesInvalidateCreationPermission`：`quoted` → `begin` → `confirm(false)` → `consume` 抛；再 `quoted` → `begin` → `confirm(true)` → 再 `quoted` → `isConfirmed` false。
  - `duplicateNodeConfigurationIsRejectedAndLostLeaseStopsGeneration`：用 Redisson 适配的 `StringRedisTemplate` 再跑一遍 `quoted/begin/confirm/consume`，主要验证节点租约；确认链只是顺带。
- **清理**：`@AfterEach` 用 `redis.keys("*" + suffix + "*")` 再 `delete`。`suffix` 是每个测试新生成的 UUID，确认测试里直接把它**当作 chatId**，所以命中 `chat:info:{suffix}`。这是“按后缀模式清理”，不是精确 key；如果其它 key 恰好包含这个 UUID 也会被删。
- **固定 key**：节点租约测试里 `redis.delete("id:node:1:1")` 删除的是**固定节点 key**，不是后缀模式；它会影响同一 Redis 实例上任何使用 `workerId=1,nodeId=1` 的实例。这个测试只能在一次性隔离实例跑。
- **未覆盖**：15 分钟边界（所有报价都是刚写的）、拒绝超龄、`break=resuming` 超龄重放、`consume` 与 `confirm` 的锚点差异、`quoted` 多命令部分失败、`begin` 缺 `ReadyforConfirm`、24h TTL、`lockChat` 的其他入口与 TTL 失败、`/r` 的 Controller→Runner→Service→Agent 全链、`ToolResponse` 持久化/`answered` 窗口、执行锁并发、故障注入。

### 11.2 `SecurityRepairRegressionTest`

- **门控**：无；纯 JUnit + Mockito。
- **`notifyBatchPreservesEveryNonConfirmationResponse`**：用反射构造真实 `OrderAgent`，mock `MessageMemory`/`StringRedisTemplate`/`ToolRepPointerTool`；`OrderConfirmation#begin` mock 返回 `"quote"`。调用私有 `handleToolCalls`，批次是 `before`（未知工具）、`confirm`（`notifyUser`）、`after`（未知工具）。断言：历史最后一条 `ToolResponseMessage` 的 id 集合是 `{before, after}`（**不包含 `confirm`**）；`recordCalling("after")` 从未调用；再手工追加 `confirm` 的应答后，`MessageWindow.tail` 不抛。
- **`modelCannotGrantItselfCreationPermission`**：mock `OrderConfirmation`，调用 `markOrderReadyForCreate`，断言返回文本包含“尚未”，并 `verifyNoInteractions(redis)`——因为 mock 的 `isConfirmed` 根本不碰 Redis。
- **未覆盖**：真实的 `begin`/JSON 序列化/`saveNewMessages`/`confirm` 事件顺序；`createOrder` 的真实门禁；`resume` 的 `answered` 分支。

### 11.3 `ChatServiceRegressionTest`

- **门控**：无；真实 `ChatServiceImpl` + 全 mock 依赖。
- **`resumeCannotBypassUserRateLimit`**：`limiter.tryAcquire` 返回 false，`resume("chat", new ChatParamDTO("confirm"), ...)`；断言 `orders` 无交互、首个事件是 `notify`。它证明**限流在 `confirm` 之前**。
- **`nullResumeRequestCompletesWithoutSpendingQuota`**：`resume(chat, null)`；断言 `limiter`/`orders` 无交互，首个事件是 `error`。
- **注意**：这个测试类没有给 `ChatServiceImpl` 注入 `OrderConfirmation`（字段是 `@Resource`，构造器里没有）；上述两个用例都在 `confirm` 之前返回，所以不会 NPE。它**没有覆盖** `confirm` 分支。

### 11.4 覆盖缺口（教材视角）

| 缺口 | 为什么重要 | 现有测试是否覆盖 |
|---|---|---|
| 15 分钟接受/消费边界 | 4.5/7.3 的核心 | 否 |
| 拒绝超龄仍成功 | 4.3 的逃生口 | 否 |
| `resuming` 同决定重放 + 超龄 | 4.4 的窗口条件 | 否（测试里的报价都是新的） |
| `quoted` 多命令部分失败 | 2.3 的中间态 | 否 |
| `/r` 端到端（含 ToolResponse 保存） | 5.3/5.4 | 否 |
| `answered=true` 去重 | 5.4 | 否 |
| 执行锁并发/锁过期 | 7.2/7.6 | 否（只有 Runner 的 mock 锁测试） |
| `lockChat`/`OrderId`/展示字段读取失败 | 7.4 | 否 |

### 11.5 `src/test/llm_judge_eval.py`

- 它是**黑盒评测脚本**，不是 JUnit：通过 `requests` 调真实服务、SSE 流、真实模型/Redis/MySQL；会产生业务数据与费用。
- `/c` 与 `/r` 共用 `call_chat`，payload 只有 `{"prompt": ..., "model": ...}`（`resume_url` 调用也只传 `prompt=hitl_reply`）。**它不携带 `confirmationId` 与 `confirmed`。**
- 按当前 `ChatServiceImpl#resume`：`prompt` 非空、字段缺失 → `confirm(null,null,?)` 抛 400 → `guard` 通用 error；`prompt` 为空 → “消息内容不能为空”。所以这个脚本的 HITL 恢复**无法走通当前确认协议**，除非它另行构造请求体（当前源码没有）。
- 脚本可能把 `confirm` 事件当作“命中 HITL”并继续发 `/r`，但那一步会失败；它不能证明“确认链路通过”。
- **未运行**，不称通过。

---

## 十二、动手验证：五组只读源码追踪

> 全部只读：不启动服务、不连 Redis/MySQL、不发请求。每组的“预期观察”是静态推导。

### 练习 A：从事件 JSON 反推写入者

1. 打开 `domain/enums/OrderInfoEnum.java`，列出所有枚举名。
2. 打开 `OrderAgent#handleNotifyUser`，找出哪些字段被排除、`EST_TIME` 和 `confirmationId` 在哪里加。
3. 打开 `OrderTool#saveNewOrderParam` 与 `getEstRouteAndPrice`，指出每个 JSON 字段的 Redis 写入点。
4. **预期观察**：`MONGO_TRACE_ID` 永不出现；`EST_PRICE` 来自 `getEstRouteAndPrice`；`confirmationId` 来自 `begin` 的返回值；JSON 里没有价格拆分。

### 练习 B：从 `/r` 请求体追到 Lua 的 ARGV

1. `ChatParamDTO` 有哪三个字段？Jackson 遇到未知字段怎么处理？
2. `ChatServiceImpl#resume` 在什么条件下回填 `prompt`？回填值是什么？
3. `OrderConfirmation#confirm` 如何把 `confirmationId`/`confirmed`/`now` 映射成 `ARGV[1..3]`？
4. **预期观察**：`confirmed` 是 `Boolean`，null 会在 Java 层 400；`prompt` 只影响 feedback 文本，不参与脚本判定；`ARGV[3]` 是服务端时间。

### 练习 C：从 `consume` 追到订单行

1. `OrderTool#createOrder` 在 `consume` 之前检查了什么？之后组装了哪些字段？
2. `RideOrderServiceImpl#createOrder` 覆盖了 DTO 的哪些字段？哪些字段保留？
3. Mongo 的 `addOrder` 与 MySQL 的 `insert` 谁先谁后？`insert` 失败时删的是哪条轨迹？
4. **预期观察**：`estPrice/estDistance/radio/mongoTraceId` 被覆盖；`userId` 按当前线程身份覆盖（Agent 线程没有 ThreadLocal 时保留 `ToolContext` 值）；`vehicleType/isReservation/isExpedited` 补默认或保留；`scheduledTime` 只在预约单保留，实时单被清空；地址可能被补全，坐标保留；顺序是**先 `snowflakeIdWorker.nextId()` 生成订单号、再检查坐标与规划路线、再设置新 traceId/距离、再计价、再写 Mongo 新轨迹**，最后 MySQL `insert`；`createOrder` 没有本地 `@Transactional`，但当前两个入口也没有外层事务，不能推广成“任何调用上下文都自动提交”；失败删新轨迹，`OrderTool` 删除的是预览轨迹。再追一层：这些后置步骤（预览清理、三个展示字段读取、`lockChat`、`OrderId`、格式化返回）都在 `OrderTool#createOrder` **返回之前**执行，所以“工具返回成功”才意味着它们都走完了。

### 练习 D：从一条错误文本反查分支

错误文本：`确认已失效或已被使用，请重新核对订单`。

1. 它在哪个类抛出？对应脚本返回什么？
2. 列出能让脚本返回 0 的至少五种情况（编号、年龄、状态、重放相反、key 过期）。
3. 这些情况里哪些会改状态？哪些不会？
4. **预期观察**：0 的分支都在写命令之前；返回 0 不改任何字段；Java 统一 409；`guard` 尝试转成通用 error。注意 `break=resuming` 且同决定时，`CONFIRM` 会直接返回 1，**没有任何写命令**；所以“返回 1”不能反推“许可字段一定存在/刚被写”。脚本运行时错误也不是返回 0。

### 练习 E：从测试名反查断言与清理

1. `confirmationIsBoundToQuoteAndConsumedOnce` 断言了哪几步？哪一步是“重复同决定不抛”？
2. `SecurityRedisIntegrationTest` 的门控注解是什么？`suffix` 如何生成、如何清理？
3. 哪个测试删除了固定 key `id:node:1:1`？为什么它不能随便在共享实例跑？
4. **预期观察**：门控是系统属性；清理是 `keys("*"+suffix+"*")`；固定 key 删除会波及其它使用同节点号的实例。再补一问：`answered` 为什么不是全历史幂等？因为它只扫 `getPersisted` 返回的 `MessageWindow.tail(...,100)` 窗口，窗口可能为完整工具批次扩展，历史损坏时还可能被 `MessageParser` 截短。

---

## 十三、一页复习

**七个编号/状态**：`chatId`（会话）、`userId`（身份）、`quoteId=confirmationId`（报价编号，服务端生成）、`pendingToolCall`（模型工具调用 id，只配对）、`break`（`yes`/`resuming`）、`ReadyForCreate+confirmedQuote`（创建许可）、`confirmationAccepted`（本次决定）。

**一条链**：

```text
saveNewOrderParam → isNewOrderReady(ReadyforRoute)
→ getEstRouteAndPrice(quoted: 新 quoteId/quoteCreatedAt, ReadyforConfirm)
→ notifyUser(begin: pendingToolCall, break=yes; 存暂停前消息; 尝试发 confirm)
→ POST /r(UUID → 执行锁 → 归属 → 订阅时后台回填/限流 → confirm)
→ CONFIRM(break=resuming, accepted; yes 写许可, no 删许可; resuming 同决定只返回 1)
→ resume(读 MySQL 历史, 补 notifyUser 应答, 删 break/pending/accepted)
→ markOrderReadyForCreate(只读 isConfirmed)
→ createOrder(ReadyForCreate? → 逐字段读 DTO → consume → 雪花 ID/坐标/路线 → 新轨迹与计价 → Mongo → MySQL insert → 后置动作)
→ 仍在工具方法内: 清预览轨迹 → 读展示字段 → lockChat → 写 OrderId → 拼返回文本
```

**必须记住的约束**：

1. `begin` 只看两个字段存在；不是授权。
2. 接受查 15 分钟，拒绝不查；`> 900000` 才拒绝；900000 毫秒只通过年龄条件，900001 是毫秒不是 1 秒。
3. `resuming` 窗口内同决定可重放（接受需 ≤15 分钟；拒绝不查年龄）；**重放返回 1 不写字段，不能推断许可仍在**；相反决定 0；窗口外同决定也 0。
4. `consume` 锚点是 `quoteCreatedAt`，在 DB 创建之前；成功后不可重试；它的“一次性”只覆盖同一现存许可，不覆盖重新签发/状态丢失。
5. `isConfirmed` 不查年龄；`markOrderReadyForCreate` 不签发。
6. 创建时金额会重算；确认摘要 ≠ 订单金额；DTO 读取也不是快照。
7. 暂停阶段保存的是暂停前消息；`notifyUser` 的用户应答、`HDEL`、`answered` 属于恢复阶段。恢复读 MySQL；工具应答先保存再删状态；保存失败不等于 MySQL 未写；`answered` 只看窗口。
8. 直接 `POST /order` 不走确认；确认只保护 Agent 路径。
9. 执行锁、会话 `locked`、确认状态、事件、ToolResponse 是五种对象；`lockChat` 不只在创建订单时调用。
10. 所有 `tryEmit*`、保存、解锁、Redis 命令都只是尝试；客户端是否收到、订单是否存在都要分开说。

**一句话**：报价编号由服务端发，用户决定由服务端核；许可只能消费一次，创建仍可能失败。

---

## 十四、自测与答案（8 题）

> 先自己写，再展开。答案要求写出“为什么”，不能只给结论。

**1. `confirmationId` 是谁生成的？模型能不能伪造？如果客户端回传了别的会话的编号会怎样？**

<details>
<summary>参考答案</summary>

`quoted` 里 `UUID.randomUUID()` 生成，`begin` 返回并写入 confirm JSON。正常路径**不把它作为模型工具回执返回**，而是放进给前端的 `confirm` 事件；但这不等于模型绝无可能猜中或经其他通道获知，所以不能写“永远看不到/不能伪造”。真正的门禁是：客户端回传别人的编号时，`confirm` 用当前 `chatId` 的 key 比较 Redis 当前 `quoteId`，不匹配 → 0 → 409；编号本身不绑定 userId，`assertOwned` 先保证会话属于当前用户，两个保护缺一不可。授权不靠编号保密。
</details>

**2. 说出 `quoteId`、`ReadyforConfirm`、`break`、`pendingToolCall`、`ReadyForCreate`、`confirmationAccepted` 的写入者和删除者。**

<details>
<summary>参考答案</summary>

`quoteId/quoteCreatedAt/ReadyforConfirm`：`quoted` 写；`saveNewOrderParam` 删 `quoteId/ReadyforConfirm`；`ReadyforConfirm` 在确认/消费后不删。`pendingToolCall`：`begin` 写；`resume` 删。`break`：`begin`/`CONFIRM` 写；`resume` 删。`ReadyForCreate/confirmedQuote`：`CONFIRM` 接受写；拒绝、`quoted`、`saveNewOrderParam`、`CONSUME` 删。`confirmationAccepted`：`CONFIRM` 写；`resume` 删。整 key 过期时全部消失。
</details>

**3. `notifyUser` 是批次里的第一个工具 vs 第二个工具，暂停时保存的消息有什么不同？**

<details>
<summary>参考答案</summary>

第一个：`toolResponses` 为空，不添加 `ToolResponseMessage`；`saveNewMessages` 只保存 `AssistantMessage`（带未闭合的 `notifyUser` 调用）。`MessageWindow` 允许最后批次未闭合。第二个（前面有工具）：前面的工具已执行，结果在 `toolResponses`；`notifyUser` 后面的工具写“尚未执行”；`handleToolCalls` 先添加一条包含“前面结果+后面未执行”的 `ToolResponseMessage`，再 `handleNotifyUser`；保存时 assistant 与这条回执由 `MysqlMemory#append` 的本地事务逐条插入（不是与 Redis/heap 的跨存储事务）。两种情况下 `notifyUser` 自己的应答都不保存，等 `/r`。
</details>

**4. 有效拒绝（`confirmed=false`，编号正确）和编号错误分别会发生什么？**

<details>
<summary>参考答案</summary>

有效拒绝：`break=yes`、编号匹配；`CONFIRM` 跳过年龄检查，写 `break=resuming`/`confirmationAccepted=no`，删 `confirmedQuote/ReadyForCreate`，返回 1；Java 继续，feedback=“用户拒绝当前报价，禁止创建订单。”+prompt；`resume` 补 `ToolResponse`、删状态、跑模型。这是**成功的业务记录**。编号错误：`quoteId` 不匹配 → 0 → Java 409 → `guard` **尝试**发通用 `error` 事件；状态不变（`break` 仍是 `yes`）。两者不是“都失败了”，而是一个有效决定、一个无效请求；事件是否送达客户端未实测。
</details>

**5. 为什么“15 分钟内点了确认”仍可能创建失败？`isConfirmed` 在这里起了什么作用？**

<details>
<summary>参考答案</summary>

15 分钟锚点是 `quoteCreatedAt`，`consume` 在创建时再查一次。用户在第 14 分钟确认，模型恢复后再加上 2 分钟工具/模型耗时，`consume` 时已 16 分钟 → 0 → 409。`isConfirmed` 只比较 `quoteId/confirmedQuote/ReadyForCreate` 三个字符串，不查年龄，所以 `markOrderReadyForCreate` 仍会说“可以创建”；它只是文本，不是保证。`ReadyForCreate != null` 的快速检查也不查年龄，所以同样会通过，真正拒绝的是 `consume`。
</details>

**6. 两个标签页同时点“确认”，其中一个点“拒绝”，分别会怎样？**

<details>
<summary>参考答案</summary>

并发时只有拿到 `chat:execution:{chatId}` 的那个进入；另一个的 Runner 返回单事件 `error("会话正在处理中…")` 的 `Flux`（客户端是否看到、是否以 SSE/HTTP 200 呈现未实测），**不会**改确认状态。若第一个已完成 `resume`（`break` 已删），第二个再提交：`state` 不是 `yes` → 0 → 409。若第一个在 `resuming` 窗口内失败、`break` 仍是 `resuming`：同决定、接受未超龄 → 1（可重放，但**不重写字段**）；相反决定 → 0（不能覆盖）。`break` 是否仍在，取决于删除命令是“明确未执行”还是“响应丢失”。所以“两个标签页都成功”不成立，“一定都失败”也不成立。
</details>

**7. `consume` 成功之后 `createOrder` 失败，能否重试？订单是否一定不存在？**

<details>
<summary>参考答案</summary>

不能自动重试：`consume` 已 `HDEL` 两个许可；再调 `createOrder` 会被 `ReadyForCreate == null` 挡成“订单非可创建态”。要重新报价+确认。订单是否一定不存在：不能断言。当前两个入口没有外层事务，`createOrder` 没有本地 `@Transactional`，在这种上下文下 `insert` 可能按自动提交执行；若失败是客户端超时/连接中断，数据库可能已提交。代码只尝试删新轨迹并重抛。若有外层事务，`insert` 会加入外层，提交/回滚边界另论。要查订单表/列表。教材只能说“可能已存在”。
</details>

**8. 直接 `POST /order` 能不能绕过确认？这说明确认协议保护什么？**

<details>
<summary>参考答案</summary>

能：Controller 只要求登录，设置 `userId` 后直接 `rideOrderService.createOrder`，不查 `OrderConfirmation`。但它有自己的边界：身份覆盖、DTO 校验、重新规划/计价、插入。确认协议保护的是“模型不能自作主张/重放/误创建”，不是“所有创建入口都必须确认”，也不是“人类不能直接调用订单 API”。把它说成全局门禁是错的；说它因此没用也是错的。
</details>

---

## 十五、客服取消预检

> 本章前十四节讲的是**下单确认令牌**：`quoteId` → `ReadyForCreate` → `consume`。客服取消订单走的是**另一套一次性令牌**，不是同一把锁、也不是同一个字段。P2/C5 旧回链把"取消链"指向本章，本节补齐这条已接线但此前没有正文解释的链。读完本节，读者应能只靠本文推导：用户说"帮我取消订单"时，服务端为什么先要调一次 `verifyCancelConditions`，五分钟窗口从哪一刻算，以及模型说"用户同意了"为什么不等于服务端放行。
>
> **本节只读源码推导，未运行服务、测试或中间件。** 订单号 5001、会话 `chatId=7f3c2a91-5b0e-4d62-9a41-8c2f6e1b7d30`（合法虚构 UUID；本报告有时缩写为 `C`）、费用与时间都是虚构示意。

### 15.1 入口与身份：工具不是 HTTP 入口

**题设先立住**：林小雨（`userId=7000001`，`USER`）在一个已存在的合法会话 `chatId=7f3c2a91-…` 里先发"我想取消 5001"。这一轮经 `ChatRequestRunner#run` 的 `UUID.fromString(chatId)`（在 `Flux.defer` 之前同步校验）、单轮执行锁与 `chatManager.assertOwned` 后进入 `ChatServiceImpl#chat`；分类由 `ChatServiceImpl#resolveClassification` 判为 `SUPPORT`，路由到 `SupportAgent`（分类与路由见第 17 章）。**模型输出工具调用请求** `verifyCancelConditions(orderId="5001")` 后，服务端才执行工具；不是用户一句话就自动走到工具。用户紧接着只回复"是"时，是否再次调用 `cancelOrder` 同样取决于**这一轮模型的输出**，不是由那个字直接触发方法。

`SupportAgent` 注册 `OrderSearchTool`、`TicketTool`、`ToolRepPointerTool`、`RagTool` 四组工具（`ToolCallbacks.from(...)`），并关闭内部工具执行（`internalToolExecutionEnabled(false)`），工具调用走手写 `SupportAgent#executeTool` 的 `switch`：

- `case "verifyCancelConditions"` → `orderSearchTool.verifyCancelConditions(orderId, toolContext)`；
- `case "cancelOrder"` → `orderId` + `reason` → `orderSearchTool.cancelOrder(orderId, reason, toolContext)`。

工具刚进入时先 `ToolNotifySupport.notifyToolListener(toolContext, "检查取消订单条件 (verifyCancelConditions)")` / `"取消订单 (cancelOrder)"`，listener 经 `AgentEvent.tool(msg)` 变成一条 **`tool_start` SSE 事件尝试写出**（`tryEmitNext` 返回值未检查）；这条进度提示不是工具返回值。工具执行完，`SupportAgent#handleToolCalls` 用 `toolRepPointerTool.recordCalling(toolCall.id(), result, toolContext)` 记录回执，再构造 `ToolResponseMessage.ToolResponse(id, toolName, result)` 加入历史并递归下一轮模型。工具返回的文本**不直接**变成 SSE 事件；客户端随后看到的说明文字是下一轮模型输出的 `AgentEvent.message(...)`（同样是尝试写出）。

两个工具的**身份都来自 `ToolContext`**：`CHAT_ID` 给出 `chat:info:{chatId}`（令牌所在的 Hash），`USER_ID` 给出当前登录用户。模型传来的只有 `orderId` 和取消原因 `reason`，没有 `userId`，也没有"是否确认"布尔值。`verifyCancelConditions` 调 `getOrderDetail(oid, userIdStr, UserRole.USER)`，再在当前用户 ID 非空且订单用户 ID 非 null 时比较字符串归属；`cancelOrder` 在 `hasKey` 通过后**再次** `getOrderDetail(oid, userId, UserRole.USER)` 并再次归属校验。两处都不接受调用方提交的 userId。

还要注意：直接 HTTP `POST /order/{orderId}/cancel` 走 `RideOrderController#cancel`：`@RequirePermission` 只要求登录，随后 `UserTokenContext.hasAnyRole("DRIVER") ? 2 : 1` 得到 `cancelRole`，与请求体的 `cancelRole` 不符抛 `ForbiddenException("取消身份与当前登录角色不符")`。Agent 工具链调用的是 `RideOrderServiceImpl#cancelOrder(oid, userId, 1, reason)`，**固定 cancelRole=1（用户身份）**，没有 HTTP 入口那一层角色一致性校验。不要写成"Agent 取消和直接 HTTP 取消走完全相同的入口"。

### 15.2 首次校验：verifyCancelConditions 实际写了什么

`OrderSearchTool#verifyCancelConditions` 的顺序是"通知 → 取身份 → 查归属 → 看状态 → 算钱 → 分步写字段"，任一步早退都不写令牌：

1. 从 `ToolContext` 取 `CHAT_ID`（拼 `chatInfoKey`）与 `USER_ID`；`orderId.trim()` 为空返回"订单号不合法"。
2. `getOrderDetail(oid, userIdStr, UserRole.USER)` 失败返回"订单不存在或查询失败"；`vo.getUserId()` 与当前 `userId` 不等返回"无权限操作该订单"，身份处理或比较抛异常时返回"用户身份无效"。
3. 状态门槛按源码顺序：`status == null` → "订单状态异常"；`CANCELLED` → "订单已经是取消状态，无需重复操作。"；`>= IN_TRIP && < FINISHED_WAIT_PAY` → "行程已开始，无法直接取消，请创建工单联系人工协助结束行程。"；`>= FINISHED_WAIT_PAY` → "订单已进入结算/支付阶段，无法直接取消，请创建工单联系人工协助。"。这些分支都不写令牌。
4. 司机已接单（`acceptTime != null`）且 `minutes = max(0, Duration.between(acceptTime, BusinessTime.now()).toMinutes()) > 5`（严格大于；接单整 5 分钟不收费），调 `calcCancelFee(minutes)`，然后**三条分步写入**：`HSET cancelFee <toPlainString()>`、`HSET readyForCancel <Long.valueOf(oid).toString()>`（规范化订单号，不是模型传来的原始串）、`HSET cancelVerifiedAt <System.currentTimeMillis()>`（**JVM 侧时钟毫秒值**）。返回文案为"司机已接单超过5分钟(已接单%d分钟)。取消需支付取消费 %s 元给司机作为空驶补偿。请确认用户是否想取消。"
5. 免费取消分支 `HDEL cancelFee`，同样 `HSET readyForCancel` 与 `HSET cancelVerifiedAt`，返回"当前可以无责免费取消订单。"

`calcCancelFee` 的规则是基础 5 元 + 超出 5 分钟的分钟数 × 0.5 元，上限 20 元，`HALF_UP` 保留两位。这是**按接单时长计费**，和后面的令牌时效完全无关。

**关键边界：两个方法都没有调用 `expire`。** `readyForCancel`、`cancelVerifiedAt`、`cancelFee` 没有独立 TTL，它们所在的 `chat:info:{chatId}` 整体 TTL 由别处改写（报价 `quoted` 设 24h、会话 `lockChat` 3600s、`getRestorableChat` 60min、`restoreChat` 的 `PERSIST`；见本章 §2.4），而且每条路径都取决于命令是否成功。取消令牌的"五分钟有效"不是 Hash 字段过期，而是由下一次 `CANCEL` Lua 现场比较时钟得出；整 Hash 若因其他路径过期，令牌随之消失，但那不是取消逻辑自身的定时清理。

### 15.3 CANCEL Lua 逐行：匹配订单号、年龄与原子删除

`OrderSearchTool` 内的 `CANCEL` 脚本（`DefaultRedisScript`，源码 `agentbase/tool/OrderSearchTool.java` 顶部）：

- `KEYS[1]` = `chatInfoKey`；
- `ARGV[1]` = 规范化订单号（同样是 `Long.valueOf(oid).toString()`）；
- `ARGV[2]` = Java 侧 `System.currentTimeMillis()` 转成的字符串。

脚本顺序：

1. `HGET readyForCancel` 与 `ARGV[1]` 不匹配 → `return 0`。**这是跨订单匹配检查**：为订单 5001 做的预检不能拿来取消 5002，即使两单都属于同一用户。
2. `verified = tonumber(HGET cancelVerifiedAt or '0')`；`tonumber(ARGV[2]) - verified > 300000` → `return 0`。这是**严格大于** 5 分钟：正好 300000 毫秒仍通过，300001 才拒绝。时钟输入是 JVM 毫秒，不是 Redis `TIME`。
3. `HDEL readyForCancel cancelVerifiedAt`——两个字段在 Lua 内原子删除，返回 1。

`cancelFee` **不在 Lua 中删除**。脚本只消费"令牌 + 时效"两个字段；费用字段由 Java 层在业务调用之后清理。这是 Redis 内的原子操作，不覆盖 MySQL：Lua 返回 1 只说明令牌已消费，不说明订单已取消。

### 15.4 消费与业务事务分离：三条清理路径各不同

`OrderSearchTool#cancelOrder` 的顺序必须按分支讲，不能笼统说"finally 都删"：

1. `hasKey(chatInfoKey, "readyForCancel")` 为假 → 立即返回原文 **"先使用verifyCancelConditions()检查取消订单条件"**；**不执行 Lua，不进 try/finally**。
2. `oid.trim()` 为空 → "订单号不合法"；随后**再次** `getOrderDetail(oid, userId, UserRole.USER)`（失败 → "订单不存在或查询失败"）并再次归属校验（不符 → "无权限操作该订单" / "用户身份无效"）。这一步让读者看到：令牌存在不等于当前订单仍属本人、仍是原状态。
3. `execute(CANCEL, [chatInfoKey], Long.valueOf(oid).toString(), String.valueOf(System.currentTimeMillis()))`；返回非 1 → 立即返回完整原文 **"取消确认对象不匹配、已过期或已使用，请重新检查此订单"**；**不调用订单服务，不进 try/finally**。Lua 此时没有删任何字段，Hash 中的 `readyForCancel`/`cancelVerifiedAt`/`cancelFee` 保持原样。
4. Lua 返回 1 → 脚本内已经原子 `HDEL readyForCancel cancelVerifiedAt`（令牌与时效字段已消费）；只有现在才进入 `try` 调 `rideOrderService.cancelOrder(oid, userId, 1, reason)` → `RideOrderServiceImpl#cancelOrder` → `withOrderLock(orderId, () -> cancelOrderUnderLock(...))`。
5. **业务正常返回**：`finally` 里的 `HDEL readyForCancel` 执行（Lua 已删，冗余兜底）；这条命令正常返回才继续执行后面的 `HDEL cancelFee`；订单服务返回的金额字符串非空白时，工具文案为 **"取消成功，需要支付取消费%s元。"**，否则 **"取消成功。"**。
6. **业务抛 `BusinessException`**（如锁内 409 "订单状态已变化，请刷新后重试"）→ `catch` 直接 `return be.getMessage()`；**业务抛其他 `Exception`** → `catch` 直接 `return "取消失败，请稍后重试"`。这两个 `return` 仍会触发 `finally` 的 `HDEL readyForCancel`，但控制流已经离开方法，**finally 之后的 `HDEL cancelFee` 被跳过**。若费用字段原先存在且未被其他操作覆盖或清理，本次失败后仍可读到它。
7. 清理本身也是 Redis 命令：`finally` 的 `HDEL`、其后的 `HDEL cancelFee` 都可能因 Redis 异常失败，工具不检查删除条数，也没有捕获这两处删除抛出的异常。`finally` 抛异常时会取代原本的业务返回或 catch 返回，后面的费用清理也不会开始。正常业务返回后的费用删除若抛异常，订单仍可能已经取消。异常由 `SupportAgent#executeTool` 捕获并转成 `Tool execution failed: ...` 回执。可回读 Hash 和订单行来区分实际落库与清理状态。

因此 **Lua 消费、MySQL 更新和清理不是一段自带的事务**：`OrderSearchTool`、`RideOrderServiceImpl#cancelOrder/#cancelOrderUnderLock` 都没有本地 `@Transactional`；`withOrderLock` 只是 Redisson 应用锁并在事务同步可用时挂 `afterCompletion` 释放。Lua 的 `HDEL` 在 Redis 内原子，但不回滚 MySQL；MySQL 条件更新成功与否不影响已经发生的 Redis 删除，业务失败也不退还令牌。`finally` 只尝试无条件删除 `readyForCancel`，不会补偿业务失败。Lua 成功时原许可已经被删掉。若其他写入方在两次删除之间重新签发，冗余删除也可能删到新标记。正常的同会话单轮锁会限制工具并行。

锁内取消入口 `cancelOrderUnderLock` 做这些事：`getRequiredOrder` + 状态门槛（`PAID`/`CANCELLED` 或 `>= FINISHED_WAIT_PAY` 拒绝）；按 `cancelRole` 校验归属（`cancelRole=1` 要求 `userId == operatorIdLong` 且 `status <= USER_MAX_CANCEL_STATUS`）；仅 `cancelRole=1` 且 `driverId != null` 且 `acceptTime != null` 且 `minutes > 5` 才用 `calcCancelFee(minutes)` 计算费用；条件更新 `.eq(order_status, status)` 写入 `CANCELLED`/`cancelRole`/`cancelReason`/`real_price=cancelFee` 并清零价格字段；`updated == 0` 抛 409 "订单状态已变化，请刷新后重试"；成功才 `syncOrderStatus`、`orderGeoPool.remove`、`releaseDriver`、`orderDelayQueue.cancel`。工具侧 `calcCancelFee` 与锁内公式独立实现但规则相同（5.00 + 超 5 分钟数×0.50，上限 20.00，`HALF_UP` 两位）；最终落库值取锁内重算。两次读取的接单时间或计费整分钟不同，预估与最终金额就可能不同。

### 15.5 提示词要求确认，脚本不检查"用户是否同意"

`ChatProperties` 的 `SUPPORT_AGENT_SYS_PROMPT` 把 `verifyCancelConditions` 列为强制前置，并给出取消 SOP：Step 1 查询订单状态；Step 2 调 `verifyCancelConditions` 获取预估违约金；Step 3 **向用户明确告知"取消将产生 XX 元费用，是否确认？"**；Step 4 **得到用户明确"是/确认"后**才调 `cancelOrder`。

这段是**行为约束，不是鉴权**。CANCEL Lua 只检查 `readyForCancel` 是否等于本次订单号、从 `cancelVerifiedAt` 起算的年龄是否超过 5 分钟；它不读取任何"用户看过费用"或"用户表示同意"的证据。`OrderSearchTool#cancelOrder` 也不接收确认布尔值——它的第二个参数是取消原因 `reason`。所以"模型说用户同意了"不能替代服务端状态：脚本能防的是**错单**和**超龄**，防不了"模型在用户没表态时擅自调用"。把提示词的 Step 3/Step 4 写成"服务端保证用户已确认"是错的。

### 15.6 两种"五分钟"与两个时钟

| 项 | 锚点 | 比较 | 用途 |
|---|---|---|---|
| 接单时长计费 | `acceptTime`（司机接单时间） | `Duration.between(acceptTime, BusinessTime.now()).toMinutes() > 5` | 决定是否收费、收多少（`calcCancelFee`） |
| 取消令牌时效 | `cancelVerifiedAt`（首次校验写入时） | `ARGV[2] - verified > 300000`（ARGV[2] 是 JVM `System.currentTimeMillis()`） | 决定预检结果能否用于本次取消 |
| 确认/建单令牌时效（本章 §4.5、§6.3） | `quoteCreatedAt`（报价时） | `now - quoteCreatedAt > 900000` | 下单确认与消费的 15 分钟窗口 |

三者都是**严格大于**，但锚点和目的不同。不能用"已经接单超过 5 分钟，所以令牌过期"推断；也不能把取消令牌的 5 分钟说成 Hash 字段的 TTL。取消链没有给字段设 TTL，时效是 Lua 现场算出来的。时长的三种写法不能混：`300000` ms = 整 5 分钟（**不**过期）；`300001` ms = 5 分钟再加 1 毫秒（刚过期）；`301000` ms = 5 分 01 秒（过期）。把"5 分 01 秒"写成 300001 是把秒当成毫秒。

### 15.7 一条成功线与一条失败线（静态推导）

**成功线（订单 5001，虚构；`chatId=7f3c2a91-…`）**：

1. 题设：`UUID.fromString` 通过、当前用户 `userId=7000001` 拥有该 `chatId`；第一轮分类为 SUPPORT，模型输出 `verifyCancelConditions(orderId="5001")`；工具入口先尝试发 `tool_start("检查取消订单条件 (verifyCancelConditions)")`。
2. 工具从 `ToolContext` 读 `userId`/`chatId`，`getOrderDetail("5001","7000001",USER)` 确认本人订单、状态 `DRIVER_ACCEPTED`，`acceptTime` 距今 12 分钟 → `minutes=12 > 5` → `calcCancelFee(12)=5.00+(12−5)×0.50=8.50`；三条写入 `cancelFee=8.50`、`readyForCancel=5001`、`cancelVerifiedAt=T0`（预检**估算** 8.50）。
3. 工具返回文本经 `SupportAgent#handleToolCalls` 的 `recordCalling` 与 `ToolResponseMessage` 记入历史；模型按提示词 Step 3 输出"取消将产生 8.50 元费用，是否确认？"作为 `AgentEvent.message` **尝试**写出（客户端是否实收未实测）。这里模型文字里的 8.50 仍是预检估算。
4. 用户答"是"；题设该轮也通过入口校验并路由到 SUPPORT，**模型在这一轮输出** `cancelOrder(orderId="5001", reason="费用争议")`（用户文字不直接触发方法）。工具入口尝试发 `tool_start("取消订单 (cancelOrder)")`；`hasKey("readyForCancel")` 命中；再次 `getOrderDetail` + 归属校验仍通过，订单状态仍 `DRIVER_ACCEPTED`、`order_status=20`。
5. CANCEL Lua：匹配 `readyForCancel=5001 == ARGV[1]`、`T1−T0=120000 < 300000` → 原子 `HDEL readyForCancel/cancelVerifiedAt` 返回 1。此时距接单已 **14 分钟**（120000 ms = 2 分钟）。
6. 进 `RideOrderServiceImpl#cancelOrder` → `withOrderLock` → `cancelOrderUnderLock`：按**当前** `acceptTime` 重算 `minutes=14 > 5` → `calcCancelFee(14)=5.00+(14−5)×0.50=9.50`。条件更新写 `CANCELLED`、`real_price=9.50`；`syncOrderStatus`/`orderGeoPool.remove`/`releaseDriver`/`orderDelayQueue.cancel` 成功。
7. `finally` 的 `HDEL readyForCancel` 执行；其后的 `HDEL cancelFee` 执行；工具返回 **"取消成功，需要支付取消费9.50元。"**（工具文本里的 9.50 与落库值一致）。该文本被记入 `ToolResponseMessage` 供模型续跑；客户端可见的最后一句是下一轮模型输出的 `AgentEvent.message` 尝试，工具回执本身不变成 SSE 事件。

**可观测**：MySQL 订单 5001 的 `real_price=9.50`、`order_status=90(CANCELLED)`；Redis Hash 三个取消字段都不存在；SSE 侧尝试发出过两条 `tool_start` 与后续 `message`。**8.50 是预检估算，9.50 是两分钟后锁内按 14 个整分钟重算的费用。** 若两次计算都要得到 8.50，两个采样点都必须落在接单后 12 分钟至不足 13 分钟的区间。这段链路只写 `real_price` 取消费字段，**不证明发生实际扣款**（支付默认 503，见第 11 章）。

**失败线（错单）**：预检仍为 5001 写了 `readyForCancel=5001`、`cancelFee=8.50`、`cancelVerifiedAt=T0`；用户答"是"后模型输出 `cancelOrder(orderId="5002", reason="费用争议")`。入口 `tool_start` 已尝试写出；`hasKey` 命中，再次 `getOrderDetail("5002",…)` 与归属校验通过（同用户另一单），但 CANCEL Lua 第一步取到 `readyForCancel=5001`，而 `ARGV[1]=5002`，两者不等 → 返回 0；工具立即返回完整原文 **"取消确认对象不匹配、已过期或已使用，请重新检查此订单"**，**不调用订单服务，不进 finally**。订单 5002 状态不变，5001 也未被取消；三个字段都还在 Hash 中（`cancelFee=8.50` 未被删），在 5 分钟内用正确订单号再取消仍可通过，超过 300000 ms 则被年龄检查拒绝。该失败文本被记入工具回执供模型续跑，不会自动成为 SSE error 事件。
   **可观测**：MySQL 两单状态都不变；Redis Hash 仍有 `readyForCancel=5001` 与 `cancelFee=8.50`；SSE 只有 `tool_start` 和模型后续尝试写出的文字，没有"取消成功"回执。

### 15.8 折叠自测

<details>
<summary>自测 1：司机 12 分钟前接单，预检在接单第 12 分钟写了令牌；5 分 01 秒后模型才调 cancelOrder，会发生什么？费用还收吗？</summary>

注意"接单时长"和"令牌年龄"两个时钟：题设里令牌写入时接单已 12 分钟（预检算 8.50），5 分 01 秒后令牌年龄 `ARGV[2] - cancelVerifiedAt = 301000`（不是 300001），`301000 > 300000`，CANCEL Lua 返回 0。工具立即返回完整原文"取消确认对象不匹配、已过期或已使用，请重新检查此订单"，**不调用订单服务，不进 finally**。`readyForCancel`、`cancelVerifiedAt`、`cancelFee=8.50` 都还在 Hash 里；本次没有落库，不产生取消费，也不发生扣款。300000 ms（整 5 分钟）不触发拒绝；300001 ms 是"5 分钟 + 1 毫秒"，301000 ms 才是"5 分 01 秒"。费用规则本身按 `acceptTime` 计算，与令牌年龄无关。
</details>

<details>
<summary>自测 2：模型在用户没有明确回答时直接调 cancelOrder，脚本能拦住吗？提示词和代码各负什么责？</summary>

分两种情形。若此前从未调过 `verifyCancelConditions`，`hasKey(readyForCancel)` 为假，工具直接返回"先使用verifyCancelConditions()检查取消订单条件"，拦住了。若此前已调过预检（例如用户只是问了费用但没表态），且当前 `getOrderDetail` 复查仍属本人、状态仍允许、订单号匹配、令牌年龄 ≤ 300000、Lua 返回 1，**工具会继续调订单服务**；但是否真的取消成功，还要看锁内 `getRequiredOrder`/归属/状态/条件更新的结果——**令牌通过只意味着进入业务流程，订单可能已变更而返回 409**。源码没有验证用户是否看过费用或明确同意；拦这一层靠 `SUPPORT_AGENT_SYS_PROMPT` 的 Step 3（告知费用并问"是否确认？"）与 Step 4（得到明确"是/确认"后调用），属于提示词约束而非服务端鉴权。脚本负责"对单 + 时效 + 一次性消费"，提示词负责"让用户先看到费用并表态"，两者不能互相替代。工具是否被调用取决于模型这一轮的输出，用户回复"是"本身不触发方法。
</details>

<details>
<summary>自测 3：Lua 返回 1 后，业务条件更新因为订单状态已变返回 409。令牌还在吗？费用字段呢？</summary>

`readyForCancel`/`cancelVerifiedAt` 不在：CANCEL Lua 在返回 1 前已原子 `HDEL` 这两项。但 `cancelFee` **可能保留**：业务 `BusinessException` 的 `catch` 直接 `return be.getMessage()`，仍触发 `finally` 的 `HDEL readyForCancel`（冗余），却跳过了 finally 之后的 `HDEL cancelFee`。所以此刻 Hash 里可能只剩 `cancelFee`（例如预检算出的 8.50）。在题设的 Lua 成功且无再次写入时，后续冗余删除报错也无法恢复已删除的令牌，只会改变对模型返回的工具回执。若随后有其他路径改写 Hash，当前槽位以回读为准。业务 409 不会回滚 Redis 的原子删除。用户要重新发起 `verifyCancelConditions`，按当前状态重算并重写令牌/费用，再调 `cancelOrder`；这不是代码强制"必须重新询价"，而是令牌已被消费后的事实约束。这正是"令牌消费与业务事务分离"的含义：Redis 原子消费、MySQL 条件更新和两条清理语句分属不同真值来源。
</details>

### 15.9 本节源码核验入口

| 结论 | 源码位置（相对 `src/main/java/com/fancy/rideagenthub/`） |
|---|---|
| 工具注册与分派 | `agents/SupportAgent.java#runLoopStep/#executeTool/#handleToolCalls`（`ToolCallbacks.from(...)`、`internalToolExecutionEnabled(false)`、两个 case、`recordCalling`+`ToolResponseMessage`） |
| 身份与首次校验 | `agentbase/tool/OrderSearchTool.java#verifyCancelConditions`（`ToolContext` 的 `CHAT_ID`/`USER_ID`、`getOrderDetail(oid,userId,USER)`+归属兜底、状态分支、三条 HSET/HDEL、无 `expire`）；私有 `#calcCancelFee` |
| 取消脚本与三条清理路径 | `agentbase/tool/OrderSearchTool.java` 的 `CANCEL` Lua（严格 >300000、订单号匹配、原子 HDEL 两项）、`#cancelOrder`（`hasKey`、再次查单/归属、非 1 完整文案、try/catch return/finally 只删 `readyForCancel`、finally 之后才删 `cancelFee`） |
| 锁内业务取消 | `controller/RideOrderController.java#cancel`（`@RequirePermission`、`cancelRole` 推导与不符文案）；`service/impl/RideOrderServiceImpl.java#cancelOrder/#withOrderLock/#cancelOrderUnderLock/#calcCancelFee`（无本地 `@Transactional`） |
| 提示词约束 | `config/ChatProperties.java` 的 `SUPPORT_AGENT_SYS_PROMPT` 工具定义与取消 SOP Step 1～4 |
| 令牌回执的可见层 | `agentbase/tool/ToolNotifySupport.java#notifyToolListener`（只读 `TOOL_LISTENER`，异常只记日志）；`domain/dto/AgentEvent.java#tool`(type=`tool_start`)/`#message`(type=`message`)；`agents/SupportAgent.java:101,167` 的 `toolListener = msg -> sink.tryEmitNext(AgentEvent.tool(msg))`；工具返回文本只进 `ToolResponseMessage`，不自动发出 |

**相关回链**：下单确认令牌见本章 §二～§六；[第 20 章 §八](05-external-tools-and-prompts.md#八提示词约束-vs-代码守卫三组逐条对照)把提示词约束与代码守卫并列；[第 24 章](../06-practice-and-review/02-isolated-labs.md) L0-1 用静态推演对比确认异常与工具异常的分叉。

---

## 十六、常见误区与导航

### 16.1 常见误区

- 把 `confirmationId` 当成模型生成的“确认码”：它是服务端 UUID；正常路径不作为工具回执返回，但不等于模型绝无可能获知/猜测，授权也不靠编号保密。
- 把“正常路径没返回编号”说成“模型永远看不到/绝不能伪造”：这超出了源码能证明的范围；服务端只按 Redis 当前 `quoteId`、状态和年龄校验。
- 把 `begin` 当成完整校验：它只查 `quoteId` 和 `ReadyforConfirm` 存在。
- 把 `ReadyforConfirm` 当成“用户已确认”：它只表示算过价，且确认/消费后不删。
- 把 `break=yes` 和 `break=resuming` 当成同一状态：前者等用户，后者等恢复线程；`/c` 只拦 `yes`。
- 把“重复确认一定失败”或“一定成功”当成结论：要看是否在 `resuming` 窗口、决定是否相同、接受是否超龄；`resuming` 同决定返回 1 也不写字段。
- 把“拒绝也查 15 分钟”当成结论：拒绝不查年龄。
- 把 900000 当成“大于等于就拒绝”，或把 900001 当成“1 秒”：900000 毫秒只通过年龄条件；900001 是 900001 毫秒。
- 把 `confirm` 返回 1 当成“许可一定存在/订单一定能创建”：`resuming` 同决定可直接返回 1、不写字段；许可可能已被消费或删除。
- 把 `consume` 当成“订单已创建”：它只删许可，创建在后面；也不覆盖重新报价/再次签发/状态丢失。
- 把 `isConfirmed` 当成“现在能创建”：它不查年龄、不查 `locked`。
- 把 `markOrderReadyForCreate` 当成签发许可：它只读 `isConfirmed` 并返回文本。
- 把创建金额当成确认金额：`createOrder` 重新规划/计价，覆盖 DTO；确认脚本也不做金额/槽位快照。
- 把工具返回的“预计价格”当成订单落库金额：`OrderTool#createOrder` 的返回文本读的是 Redis 报价值，不是刚插入的 `est_price`。
- 把 `getPersisted` 当成普通记忆读取：它只读 MySQL，为了避开缓存不一致。
- 把 `answered` 当成全历史严格去重：它只扫描 `getPersisted` 返回窗口；窗口以 100 为目标但可能扩展，历史损坏/截断时可能重复保存。
- 把“保存失败”当成“MySQL 一定没写”：`MessageMemory#save` 先写 MySQL，再写 Redis/heap、更新时间；后两步失败时 MySQL 可能已有工具应答，重试的 `answered` 可能为 true。
- 把暂停阶段的保存失败说成“数据库里没有这一轮”，或反过来把 `HDEL`/`answered` 归到暂停阶段：暂停保存的是暂停前消息，`notifyUser` 的用户应答、`HDEL` 与 `answered` 都在之后的 `OrderAgent#resume`（3.4、5.3、5.4）。
- 把“删 `break` 抛异常”当成“`break` 一定还是 `resuming`”：只有明确未执行才能这样推断；响应丢失时实际状态未知。
- 把 `Flux.defer` 里的锁/归属检查说成“在订阅之前执行”：supplier 在订阅时才运行，异常在该 supplier 内被转成 `Flux.error`；只有 `UUID.fromString` 在返回 `Flux` 之前同步执行（4.6、5.1）。
- 把预览轨迹清理当成工具失败文本的来源：它的 `RuntimeException` 被 catch 只记日志（6.6）。
- 把“展示字段读取失败”当成“会话一定未锁”：本次流程没走到 `lockChat`，不代表此前没有 `ChatStatusTool#lockSession`、`POST /lock` 或 `restore` 改过 `locked`（7.4）。
- 把 `ToolResponseMessage` 当成“用户消息”：用户的确认只以工具应答文本进入历史。
- 把 `confirm` 事件的 payload 当成对象：它是 JSON 字符串，要二次解析。
- 把 `tryEmitNext`/`tryEmitComplete` 当成客户端一定收到：返回值没检查，事件是否送达、HTTP 是否已提交都未实测。
- 把 `unlockAsync` 当成锁一定释放：它是异步尝试，可能失败/被看门狗续期；看门狗实际配置未验证。
- 把 Lua 原子当成跨 Redis/MySQL 事务：它只保证 Redis 内不被穿插，不回滚、不覆盖 MySQL。
- 把“抛异常”当成“订单一定不存在”：当前无外层事务的入口下 `insert` 可能已提交；有外层事务时边界另论。
- 把直接 `POST /order` 当成也走确认，或当成“确认协议没用”：两者都不对。
- 把 `SecurityRedisIntegrationTest` 的 `suffix` 清理当成“只删自己的 key”：它是后缀模式，且节点测试还删固定 `id:node:1:1`。
- 把 `llm_judge_eval.py` 当成确认链的验证：它的 `/r` 不发 `confirmationId/confirmed`，按当前代码走不通。
- 把测试源码阅读说成“测试通过”：本章没有运行任何测试。

### 16.2 导航与源码入口

- **上一章**：[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)——`notifyUser` 特判、工具分派、`ToolContext`。
- **下一章**：[第 19 章 三层记忆、消息窗口与工具结果反查](04-memory-and-tool-results.md)——完整记忆链、窗口配对、`getPersisted` 的上下文（已通过复审）。
- **本章另一条主线**：[§十五 客服取消预检](#十五客服取消预检)——与下单确认令牌并列的另一套一次性令牌，含成功线与失败线；不要与 §二～§六的下单确认链混用字段。
- **相关专题**：[C5 外部工具与提示词边界](05-external-tools-and-prompts.md) §八；本章只把提示词当作行为约束，不展开地图/天气实现。
- **已通过的前置**：[第 16 章](01-chat-round-and-sse.md)、[第 17 章](02-routing-and-tool-loop.md)、[第 11 章](../02-orders/05-pricing-and-payment.md)、[第 7 章](../02-orders/01-order-lifecycle.md)。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；API 文档、配置与测试写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 报价生成/失效 | `service/base/OrderConfirmation.java#quoted`；`agentbase/tool/OrderTool.java#getEstRouteAndPrice/#saveNewOrderParam` |
| 暂停与事件 | `agents/OrderAgent.java#handleToolCalls/#handleNotifyUser/#saveNewMessages`；`agentbase/tool/OrderTool.java#notifyUser`；`domain/dto/AgentEvent.java#comfirm` |
| 确认脚本 | `service/base/OrderConfirmation.java#confirm` + `CONFIRM` Lua；`service/impl/ChatServiceImpl.java#resume` |
| 恢复与配对 | `agents/OrderAgent.java#resume`；`agentbase/memory/MessageMemory.java#getPersisted/#save`；`agentbase/memory/MysqlMemory.java#append`；`agentbase/memory/MessageParser.java#unparseHistory`；`agentbase/memory/MessageWindow.java#tail` |
| 消费与创建 | `service/base/OrderConfirmation.java#consume/#isConfirmed` + `CONSUME` Lua；`agentbase/tool/OrderTool.java#createOrder/#markOrderReadyForCreate`；`service/impl/RideOrderServiceImpl.java#createOrder` |
| 入口与锁 | `controller/ChatController.java#resume`；`service/base/ChatRequestRunner.java#run`；`agents/AgentStreams.java#guard` |
| 会话锁定/恢复 | `agentbase/chatinfo/ChatManager.java#lockChat/#isLocked/#getRestorableChat/#restoreChat`；`service/base/ChatInfoService.java#lockChat/#checkLock/#restoreChat`；`agentbase/tool/ChatStatusTool.java#lockSession`；`controller/ChatController.java#lockChat` |
| key 与字段 | `constant/RedisKeyConstants.java#chatInfoKey`；`domain/enums/OrderInfoEnum.java` |
| 直接下单 API | `controller/RideOrderController.java#create`；`service/impl/RideOrderServiceImpl.java#createOrder` |
| 接口文档 | `src/main/resources/api/chat-security.md`、`order-security.md` |
| 测试（未运行） | `src/test/java/com/fancy/rideagenthub/SecurityRedisIntegrationTest.java`、`SecurityRepairRegressionTest.java`、`ChatServiceRegressionTest.java`；`src/test/llm_judge_eval.py` |
