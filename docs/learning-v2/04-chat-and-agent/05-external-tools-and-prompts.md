# 第 20 章 外部工具与提示词边界：从“西湖边叫车去机场”到 HTTP、Redis 与模型文字

> 虚构乘客林小雨（用户 `7000001`，会话 `8f3c...` 为示意值）在西湖边打开 App，输入“我在西湖边，帮我叫车去机场”。她看到的是一句回答，服务端却要跨过好几道边界：**她的原话**变成**模型要调用的工具参数**，工具参数由**服务端分派**成一次**对高德/和风的同步 HTTP 调用**，外部返回的**不稳定字段**被反序列化，结果变成一段**工具回执文本**回到模型，模型再生成**给用户看的文字**；与此同时，`userId`/`chatId` 从不由模型给出，而是服务端从令牌和 URL 注入，归属和确认由**服务端守卫**判定，不是由提示词“说了算”。
>
> 这一章是 C2《分类路由与工具循环》里“工具内部做了什么”的展开，是 O3《位置与发现》、O5《计价与支付》里“地图服务从哪来”的闭合，也是全书 AI 侧的收尾。C4 已讲清工具结果如何保存与反查，本章只解释**工具与外部 API 的边界**，不重复三层记忆。
>
> **本章没有启动服务、运行测试或脚本、连接 Redis/MySQL/MongoDB/Redisson、调用模型或外部 HTTP，也没有读取凭据。** 除明确标注“测试源码阅读”的地方，结论都是源码静态推导；人物、坐标、城市、key、请求与响应 JSON 均为虚构示意，不是实测输出。R1《知识库检索》见 [第 21 章](../05-rag/01-retrieval-and-grounding.md)（已通过复审）。

## 学习目标

读完本章，你应该能：

1. 用一条真实用户消息，分清四件常被混为一谈的事：**提示词要求模型调用**、**注册给模型的工具 schema**、**`executeTool` 里代码允许分派**、**服务端基于 `ToolContext` 的授权**；指出每一层的源码入口。
2. 至少独立追踪两条链：一条地理编码/逆地理编码链（含城市码读取）与一条路径规划或天气链，说清入参来源、经纬度顺序、HTTP 客户端与配置键、响应解析、失败分支、工具回执和客户端可见性的阶段。
3. 给定虚构 JSON，按三个真实反序列化器与字段注解推导 `String`、`List<String>`、对象字段在 null / 空数组 / 非空数组 / 错误类型下的结果，并区分哪些分支静默返回 null、哪些分支显式或可能抛异常。
4. 说明外部调用是同步还是异步、超时来自哪个配置键与默认值、HTTP 错误与业务错误如何区分、异常在哪里被捕获、哪些工具重试、是否存在降级；源码不能证明的地方明确写“未知”。
5. 对照 `ChatProperties` 的真实提示词与服务端代码，至少给出三组“流程约束 vs 实际校验”：用户身份与归属、订单报价/确认/消费、位置参数或工单权限；逐条说明代码守卫的入口、状态与例外，不把提示词当鉴权。
6. 核查城市码缓存、乘客位置与墓碑在当前工具链中是否真正接线，说清 Redis key、TTL、回源与失败边界；阅读本章相关测试，逐类逐方法说出门控、真实/替身、断言与清理范围，并把“未运行”写进结论。

## 前置知识与阅读时间

- 硬必读：[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)——分类、`ToolCallbacks.from`、手写 `executeTool`、工具回执与 `ToolContext` 身份。
- 硬必读：[第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)——乘客位置 Hash、司机 GEO、位置数据存在哪里。
- 硬必读：[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)——服务端公式与 `getEstRouteAndPrice` 的金额来源。
- 推荐先读：[第 19 章 三层记忆、消息窗口与工具结果反查](04-memory-and-tool-results.md)——工具结果的两次存储与 `callId` 反查；本章只引用其结论。
- 按需回顾：[第 16 章 一次对话与事件流](01-chat-round-and-sse.md)（SSE 事件与工具回执展示）、[第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)（墓碑与空值哨兵）。
- 后续主讲：[R1 知识库检索](../05-rag/01-retrieval-and-grounding.md)（已通过复审）——`RagTool` 在 SupportAgent 的调用位置本章只给边界，不展开检索原理。
- 阅读时间：约 130～160 分钟（含练习与自测）。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置、脚本与测试写完整仓库相对路径。
- **证据约定**：源码事实 = 读到实现；调用方核对 = 还追了谁调用；测试源码阅读 = 只读断言、未运行；静态推导 = 按代码顺序推演；实际运行 = 本章**无**。

---

## 零、先记住十五条事实

1. **提示词、schema、分派、授权是四层。** 提示词写在 `config/ChatProperties.java`，是给模型的行为指引；schema 由 `ToolCallbacks.from(...)` 从 `@Tool` 方法生成；`executeTool` 的 `switch` 决定代码实际允许调用哪个 Java 方法；`ToolContext` 与工具内的 `assertOwned` / `USER_ID` 才是服务端身份来源。任何一层都不能代替另一层。
2. **全仓库只有一个工具显式命名**：`ToolRepPointerTool.java` 的 `@Tool(name = "getCalling", ...)`。其他 `@Tool` 只写 `description`，模型看到的名称按 Spring AI 默认规则取 Java 方法名。代码里没有 `defaultToolNames()`。
3. **`internalToolExecutionEnabled(false)` 关闭了框架自动执行**。三个有工具的 Agent（Order/Daily/Support）各自构造 `toolContext(Map.of(...))` 并手写 `executeTool` 分派；`FallbackAgent` 的 options 里没有 `toolCallbacks`、没有 `toolContext`、没有 `executeTool`、没有工具循环。普通工具与 `notifyUser` 的路径也不同：前者走 `switch`，后者由 `handleToolCalls` 前置特判。
4. **`ToolContext` 不是本仓库的类**。它是 `org.springframework.ai.chat.model.ToolContext`，由 Spring AI 提供；三个有工具的 Agent 往里放三个 key（常量在 `constant/ToolContextKeyConstants.java`）：`toolListener`、`userId`、`chatId`。`userId` 来自登录令牌，`chatId` 来自 URL 路径变量，二者都不是模型参数。`switch` 在执行点**不重新核对**名称是否在本次注册名单里；模型正常情况下按注册 schema 生成工具名，但“代码收到某名称后是否分派”是另一件事，不能把 schema、prompt、case 说成必须同时满足才可能执行，也不能断言未注册名称绝不可能被模型写出。
5. **提示词里的工具名和代码参数名可能不一致。** `ChatProperties` 写 `getCalling(call_id)` / `getCalling()`，真实 schema 参数是 `callId`，三个 `executeTool` 都读 `rootNode.get("callId")`。`notifyUser` 在 ORDER 提示词里被要求调用、也注册为 `@Tool`，但 `executeTool` 里**没有** `case "notifyUser"`，它在 `handleToolCalls` 里被前置拦截。
6. **两个工具名映射到不同权限的对象。** `listUserPOIs`/`getUserPOIDetailByTag`/`searchUserPOIDetail` 在 `OrderAgent` 指向只读的 `UserPOISearchTool`，在 `DailyAgent` 指向可写的 `UserPOITool`（含 `createNewPOI`/`updateUserPOIInfo`/`deleteUserPOI`）。schema 名相同不等于权限相同。
7. **坐标顺序有两套约定。** 高德服务写的是 `String.format("%.6f,%.6f", longitude, latitude)`——**经度在前**；和风 `QweatherWeatherService#*ByLatLon` 写 `lon2f + "," + lat2f`，也是**经度在前，但只保留两位小数**。工具方法的参数顺序却是 `(... lat, ... lon)`，由 `executeTool` 解析后传给服务。
8. **外部 HTTP 是同步阻塞的 RestClient。** 高德与和风各有一个 `RestClient` Bean，底层是 `SimpleClientHttpRequestFactory`，连接超时与读取超时取同一个配置：`amap.timeout` / `qweather.timeout`，Java 默认都是 5000 毫秒。`application.yaml` 中的 key 值本章不展开。
9. **业务失败与 HTTP 失败是两种分支。** 4xx/5xx 或 `RestClientException` 由服务抛 `BusinessException(502, ...)`；HTTP 200 但响应里 `status != "1"`（高德）或 `code != "200"`（和风）只记 warn 并返回 `Optional.empty()`。工具再把 `empty()` 翻译成固定中文文本，如 `"未查询到坐标"`、`"工具未能获取天气数据"`。
10. **重试/循环只出现在两处，但条件各异。** `AmapRouteService#getDrivingRouteEst` 是 `for(attempt=1..3)` 循环：业务 `BusinessException` 仅 `code>=500` 且未到最后一次才重试、第 3 次原样抛；普通 `RuntimeException` 第 3 次才包装“重试3次仍失败”；key/参数校验在 HTTP 前，可能零 HTTP。`CurWeatherTool#getWeatherByLatLon` 只在首次返回 `Optional.empty()` 时再调一次，抛异常不进入该分支。其余服务与工具**没有重试，也没有降级数据源**；`TrafficTool` 不发 HTTP，是 8 个航班号的本地 `switch`。
11. **三类自定义反序列化器只在高德响应上。** `StringOrEmptyArrayToNullDeserializer`（String 字段，`[]`→null，对象→null）、`StringOrArrayToStringListDeserializer`（唯一用于 `AmapStep.tollRoad`，对象/数字显式抛 `JsonMappingException`，空串→空 List）、`ObjectOrEmptyArrayDeserializer`（`biz_ext`/`indoor_data`，空数组/非对象→null，非空数组取首元素，对象 `convertValue` 不兼容时也可能抛）。和风 `pojo/` 下 0 处 `@JsonDeserialize`。
12. **城市码缓存是读路径已接线、写路径未接线。** `GeoRegeoTool#geo` 会调 `CityCodeUtil#getCityCode`；`CityCodeUtil#invalidate`（写墓碑）在 `src/main/java` 中只有定义，没有调用点，只在测试里被调用。缓存 7 天、墓碑 5 秒都是代码常量，不是配置项。
13. **乘客位置只有 Redis 一份。** `user:loc:{userId}` 是 Hash，字段 `latitude`/`longitude`/`address`，没有 TTL、没有 MySQL 副本；`POST /user/loc` 没有 `@Valid`。位置注入按 Agent 逐一核对源码：`OrderAgent#invoke`（`:74`）、`DailyAgent#invoke`（`:71`）、`SupportAgent#invoke`（`:68`）都调用本类的 `buildLocationString(userId)`，分别把结果填入 `ORDER_AGENT_SYS_PROMPT`/`DAILY_AGENT_SYS_PROMPT`/`SUPPORT_AGENT_SYS_PROMPT` 的“用户位置/%s”占位；`FallbackAgent#invoke`（`:36`）只用 `OTHER_CLIENT_SYS_PROMPT.formatted(TimeUtil.getDetailCurrentTime())`，不读位置。**源码中三个有工具的 Agent 都读位置**（审查意见 C5-R2 曾说只有 Order/Daily，与当前 `SupportAgent.java:68,204` 不符，以源码为准，已在证据 C5-R2 记录）；但具体工具（天气/POI）的经纬度仍来自模型参数，不是这个 Hash。
14. **提示词不能替代守卫。** `ChatProperties` 写“不要编造”“得到明确确认后才取消”，但源码里没有 Bean Validation、没有坐标范围校验；真正的边界是 `ChatRequestRunner`/`ChatServiceImpl` 的 `assertOwned`、`OrderConfirmation` 的 `begin`/`CONFIRM`/`consume`、工具内的 `USER_ID` 归属检查，以及 `GlobalExceptionHandler` 把 `IllegalArgumentException` 映射为 400。直接 `POST /order` 根本不走 Agent 的报价确认链（见第 18 章）。
15. **确认状态与 15 分钟是两件事。** `begin` 只检查当前 `quoteId` 与 `ReadyforConfirm` 存在，写入 `pendingToolCall` 与 `break=yes`；`CONFIRM` Lua 在 `break=yes` 且编号/年龄条件满足时才写 `break=resuming` 与决定/许可（`confirmedQuote`/`ReadyForCreate`），`resuming` 且同一决定可重放且不重写。15 分钟不是给 `break` 设的 TTL：`CONFIRM` 只在接受（`yes`）时比较 JVM 当前时间与 `quoteCreatedAt`，`CONSUME` 在消费时再比较一次，拒绝分支不查年龄。整个 `chat:info:{chatId}` 的 TTL 会被 `quoted`（24h）、`lockChat`、`restore` 等操作改写，且取决于命令结果。`markOrderReadyForCreate` 只读 `isConfirmed` 返回文本，**不是** `createOrder` 必须先调用的守卫；真实消费在 `createOrder` 内的 `consume`（见第 18 章）。

---

## 一、业务场景：一句话穿过四道边界

把林小雨那句话放到一次请求里看。她的消息先进 `ChatController`，分类为 `ORDER` 后进入 `OrderAgent`；下面按**“谁持有数据、谁做决定”**拆成四道边界。后续小节会逐一给出源码入口。

```text
用户输入（不可信文本）
  "我在西湖边，帮我叫车去机场"
        │ 分类：ChatServiceImpl#resolveClassification → ORDER
        ▼
模型看到的提示词（行为指引）          ① 提示词边界
  ORDER_AGENT_SYS_PROMPT：
  "信息不足就追问；不要编造地点、经纬度、时间、价格"
  "调用 searchPOIsByKeyword / geo / regeo 获取坐标"
        │ 模型产出 toolCalls（JSON 参数，仍是文本）
        ▼
注册给模型的 schema（能力清单）      ② schema 边界
  ToolCallbacks.from(orderTool, poiSearchTool, ... geoRegeoTool)
  → searchPOIsByKeyword / geo / regeo / getCalling … 的名字与参数说明
        │ 手写 executeTool 解析 JSON
        ▼
服务端分派与授权（可信门禁）         ③ 分派/授权边界
  OrderAgent#executeTool: case "geo" → geoRegeoTool.geo(address, city, toolContext)
  ToolContext: userId 来自 Token，chatId 来自 URL；模型给不了这两个值
  getCalling 前还有 chatManager.assertOwned(userId, chatId, false)
        │ 工具内部决定是否发外部请求
        ▼
外部服务与本地状态（事实来源）       ④ 外部/存储边界
  城市名 → CityCodeUtil（Redis → MySQL bus_citycode）
  地址+城市码 → AmapGeoRegeoService（高德 /geocode/geo，同步 HTTP）
  结果流向：工具回执文本 → Assistant 消息 → SSE "message" 事件
  另一份：recordCalling → ToolResponseMemory（Redis/MySQL，供第 19 章反查）
```

读这张图时最容易犯的错，是把①当成③。提示词写“必须先搜索候选点再让用户选择”，这只影响模型会不会去调 `searchPOIsByKeyword`；**代码并没有一个守卫检查“模型是否真的搜索过”**。反过来，`ToolContext` 的 `userId` 即使模型在参数里塞了一个别人的值也改不了，因为它根本不在模型可见参数里。

“不可信输入”在这里有两类：一是用户的自然语言文本，二是**外部 API 的响应**。前者可能诱导模型越权，后者可能给出缺字段、`[]` 而非字符串、 HTTP 200 却业务失败的数据；两者都不能直接当成已验证的事实。可信的是服务端从令牌解析出的身份和由服务端生成的状态。

---

## 二、最少概念：几个词需要先分清

- **工具 schema**：Spring AI 从 `@Tool` 方法反射生成的 JSON Schema，告诉模型“有个叫什么名字、收什么参数、返回什么”的函数。`@ToolParam` 的 `description` 只是给模型看的说明，不是校验规则。返回类型在本章涉及的工具里全是 `String`，没有 `Result<T>` 包装。
- **手写分派**：模型并不直接执行 Java 方法。它输出一段带工具名与 JSON 参数的 `toolCalls`，由 `OrderAgent`/`DailyAgent`/`SupportAgent` 的 `executeTool(String toolName, String jsonArgs, ToolContext toolContext)` 里 `switch` 决定调用哪个对象的方法；未知名字返回 `"Unknown tool: " + toolName`，异常兜底返回 `"Tool execution failed: " + e.getMessage()`。**这个 `switch` 不在执行点复查名字是否属于本次注册的 schema**，所以“模型正常按 schema 生成名字”与“代码收到某名字后如何分派”是两件事。
- **`ToolContext`**：Spring AI 的上下文对象，三个有工具的 Agent 用它传三个值。`toolListener` 是一个 `Consumer<String>`，工具调用 `ToolNotifySupport.notifyToolListener` 会调它，Agent 的 listener 再 `sink.tryEmitNext(AgentEvent.tool(...))` **尝试**推 SSE 的 `tool_start` 事件（返回值未检查）；`userId`/`chatId` 用于归属与 Redis key。监听器异常被 `ToolNotifySupport` 自己 catch，只记 warn，不影响工具继续执行。`FallbackAgent` 不构造 `ToolContext`。
- **同步 HTTP 与降级**：本章所有对高德/和风的调用都是 `RestClient` 的同步 `get()`，调用线程会阻塞到连接/读取超时。源码里没有 `RestTemplate`、没有 `@Timeout`、没有熔断器，也没有“高德失败改查和风”这类跨服务降级；“失败”在各工具里表现为固定文本或 `Optional.empty()`。

`AgentEvent`（`domain/dto/AgentEvent.java`）的 `type` 共五种：`message`、`tool_start`、`error`、`confirm`、`notify`。工具开始执行时推的是 `tool_start`（载荷为工具名字符串），而工具返回的文本作为 `ToolResponseMessage` 进历史、由模型后续生成 `message`；这两件事不在同一时刻，也不保证一一对应。

---

## 三、工具注册与手写分派：四个 Agent 对照

本节给“模型被要求调用什么 → 代码实际分派什么 → 服务端授权什么”一张对照表。行号会漂移，定位以类 + 方法为准。

### 3.1 OrderAgent：下单链上的外部能力

`OrderAgent` 注入 `orderTool`、`poiSearchTool`、`toolRepPointerTool`、`trafficTool`、`userPOISearchTool`、`geoRegeoTool`、`orderConfirmation`，在构造 Prompt 时调用：

```java
// agents/OrderAgent.java（注册片段，节选）
ToolCallbacks.from(orderTool, poiSearchTool, toolRepPointerTool,
                   trafficTool, userPOISearchTool, geoRegeoTool);
// ...
.internalToolExecutionEnabled(false)
```

`executeTool` 中与本章相关的分派（完整分支见源码 `OrderAgent#executeTool`）：

| 模型看到的 schema 名 | `case` 实际调用 | 入参来源 |
|---|---|---|
| `geo` | `geoRegeoTool.geo(address, city, toolContext)` | 模型给的 `address`、可选 `city`；`city` 缺失时为 `""` |
| `regeo` | `geoRegeoTool.regeo(longitude, latitude, toolContext)` | 模型的 `longitude`/`latitude`，`Double.parseDouble` |
| `searchPOIsByKeyword` | `poiSearchTool.searchPOIsByKeyword(keyword, city, toolContext)` | 模型的 `keyword`、可选 `city` |
| `getCalling` | `toolRepPointerTool.getToolResponse(callId, toolContext)` | 模型的 `callId`；`chatId`/`userId` 来自 `ToolContext` |
| `getEstRouteAndPrice` | `orderTool.getEstRouteAndPrice(noRoute, toolContext)` | 模型只给 `noRoute`；经纬度从 Redis 读 |
| `getFlightInfo` | `trafficTool.getFlightInfo(number, toolContext)` | 模型的航班号；本地 `switch`，不查外部 |

注意两点：**`notifyUser` 没有 `case`**，它在 `OrderAgent#handleToolCalls` 中被前置识别：同一批次里它之后的工具调用会被填成“尚未执行：请等待用户确认后按需重新调用。”，随后 `handleNotifyUser` 依次做 `begin`、序列化订单参数、`saveNewMessages`、写 `break=yes`、**尝试**推 `confirm` 事件并结束 sink；任一步失败可能留下部分状态（详见 §8.2）。**`RegeoTool` 与 `ChatStatusTool` 虽然有 `@Tool`，但没有注册进任何 Agent 的 `ToolCallbacks.from`，`executeTool` 也没有对应 `case`**；这不等于模型绝不可能写出其名字，若写出会落入 `default` 的 `"Unknown tool: ..."`。

### 3.2 DailyAgent：天气与地理编码

`DailyAgent` 注册 `curWeatherTool`、`geoRegeoTool`、`poiSearchTool`、`userPOITool`、`dailyTool`、`toolRepPointerTool`、`poiAroundTool`。天气五个方法、地理编码两个、附近 POI、用户 POI 六个、出行估算两个都在它的 `executeTool` 里逐条分派；`case "getWeatherByLatLon"` 读取 `lat`、`lon` 后调用 `curWeatherTool.getWeatherByLatLon(lat, lon, toolContext)`。

关键差异： DailyAgent 的 `listUserPOIs` 等三个 schema 指向**可写**的 `UserPOITool`，`createNewPOI`/`updateUserPOIInfo`/`deleteUserPOI` 也只在它的 `executeTool` 里分派。同名工具在两个 Agent 中由不同对象实现；应同时检查注册、手写分派及工具内的权限检查，不能只凭注册推断授权。

### 3.3 SupportAgent 与 FallbackAgent

`SupportAgent` 注册 `orderSearchTool`、`ticketTool`、`toolRepPointerTool`、`ragTool`；没有天气、地图、`OrderTool`。`case "cancelOrder"` 调用 `orderSearchTool.cancelOrder(...)`，`case "createTicket"` 调用 `ticketTool.createTicket(...)`；工具内部用 `ToolContext` 的 `USER_ID` 查归属，返回 `"无权限操作该订单"` / `"您无权查看此工单。"`。`RagTool` 只在这里被注册，检索机制留到 R1。

`FallbackAgent` 没有 `ToolCallbacks.from`、没有 `ToolContext`、没有 `executeTool`、没有工具循环；即使模型返回 toolCalls，它也只把调用复制进 `AssistantMessage` 后结束。

### 3.4 四个 Agent 的 options、循环与身份来源

先用一张表区分四个 Agent 的真实差异（均按 `invoke`/`runLoopStep`/`handleToolCalls` 源码核对）：

| Agent | 系统提示来源 | 位置读取 | Prompt options | 工具循环与分派 |
|---|---|---|---|---|
| `OrderAgent` | `ORDER_AGENT_SYS_PROMPT.formatted(当前时间, buildLocationString(userId))` | 是（`:74`） | `toolCallbacks` + `toolContext(Map.of(三 key))` + `internalToolExecutionEnabled(false)` | 有 `runLoopStep`、手写 `executeTool`、`notifyUser` 前置特判、`resume` |
| `DailyAgent` | `DAILY_AGENT_SYS_PROMPT.formatted(当前时间, buildLocationString(userId))` | 是（`:71`） | 同上 | 有 `runLoopStep`、手写 `executeTool`；无 `notifyUser` 特判、无 `resume` |
| `SupportAgent` | `SUPPORT_AGENT_SYS_PROMPT.formatted(当前时间, buildLocationString(userId))` | 是（`:68`） | 同上 | 有 `runLoopStep`、手写 `executeTool`；无 `notifyUser` 特判、无 `resume` |
| `FallbackAgent` | `OTHER_CLIENT_SYS_PROMPT.formatted(TimeUtil.getDetailCurrentTime())` | 否 | 只有 `model`/`topP`/`temperature`/`internalToolExecutionEnabled(false)`，**没有 toolCallbacks、没有 toolContext** | 无 `runLoopStep`、无 `executeTool`、无工具；模型若返回 toolCalls 也只被复制进 `AssistantMessage` 后结束 |

写身份边界时按对象说话：三个有工具的 Agent 在 options 中把 `Map.of("toolListener", listener, "userId", userId, "chatId", chatId)` 交给 `.toolContext(...)`，并在 `handleToolCalls` 里另建 `new ToolContext(Map.of(...))`；`FallbackAgent` 两者都没有。以聊天入口为例（详见第 16 章）：`ChatController#/chat/v2/c/{id}` 取 `userId = UserTokenContext.getUserIdInString()`、`chatId = @PathVariable id`；`ChatRequestRunner#run` 先 `UUID.fromString(chatId)` 校验，再 `chats.assertOwned(userId, chatId, !resume)`；`ChatServiceImpl` 内再校验一次并路由。**模型参数里没有 `userId`/`chatId` 这两个入口**；工具内 `toolContext.getContext().get(CHAT_ID)` 拿到的是服务端写进去的值。

普通 `switch` 与特判要分开：`executeTool` 收到名字后直接分支，不在执行点重新检查该名字是否属于本次 `ToolCallbacks.from` 的注册名单（因此“模型正常按 schema 调用”与“代码收到名字后分派”是两个事实，不能互推）。`notifyUser` 是唯一在 `handleToolCalls` 里被前置拦截的名字，不走 `switch`；`RegeoTool`/`ChatStatusTool` 虽有 `@Tool` 但当前未注册、也无 case，不能断言模型绝不可能写出这个名字，只能说当前代码收到后会落入 `default` 的 `"Unknown tool: ..."`。

---

## 四、完整链路 A：地理编码——“西湖边”怎么变成经纬度

这条链回答三个问题：城市名从哪来、坐标顺序怎么写、外部失败时用户看到什么。为便于推导，下面所有坐标、城市码、响应 JSON 都是**虚构示意**，不是任何真实服务的输出。

### 4.1 入参来源与分派

模型在 ORDER 提示词引导下调用 `geo`，产出类似：

```json
{"address": "西湖边", "city": "杭州"}
```

`OrderAgent#executeTool` 的 `case "geo"` 取出 `address` 与 `city`（缺省为 `""`），调 `GeoRegeoTool#geo(address, city, toolContext)`。工具先 `ToolNotifySupport.notifyToolListener(toolContext, "地理编码 (geo)")`：它从 `ToolContext` 取 `toolListener`（一个 `Consumer<String>`），调用 `listener.accept("地理编码 (geo)")`；三个 Agent 传入的 listener 都是 `msg -> sink.tryEmitNext(AgentEvent.tool(msg))`，即**尝试**推一条 `tool_start` 事件。回调本身被 `ToolNotifySupport` 用 `try/catch` 包住，异常只记 warn；但 `tryEmitNext` 的返回值没有被检查，所以“尝试发事件”不等于“客户端一定收到”。接着：

```java
// agentbase/tool/GeoRegeoTool.java（完整方法，节选掉日志）
public String geo(String address, String city, ToolContext toolContext) {
    ToolNotifySupport.notifyToolListener(toolContext, "地理编码 (geo)");
    String cityCode = cityCodeUtil.getCityCode(city);            // 城市名 → 城市码
    Optional<Geocode> geo = amapGeoRegeoService.getGeo(address, cityCode);
    if (geo.isEmpty()) {
        return "未查询到坐标";
    }
    Geocode g = geo.get();
    return "location=" + g.location() + ", level=" + g.level()
           + ", formatted_address=" + g.formattedAddress();
}
```

注意工具只接收模型给的 `address`/`city`；**它不读 `UserService`**，也不读乘客位置 Hash。整份 `agentbase/tool/` 目录检索 `UserService` 为 0 命中。位置进入提示词是另一条线：OrderAgent 的 `buildLocationString(userId)` 调 `userService.getUserLocation(userId)`，把结果格式化成 `ORDER_AGENT_SYS_PROMPT` 的第二个 `%s`，那是“上下文信息”，不是这个工具的入参。

### 4.2 城市码：先查 Redis，再回 MySQL

`CityCodeUtil#getCityCode(city)` 的流程（常量：空值哨兵 `__NULL__`、墓碑 `__TOMBSTONE__`、缓存 7 天、墓碑 5 秒）：

1. `cityName == null || isBlank()` → 返回 `null`。
2. 规范化一次：`cityName.trim().toLowerCase(Locale.ROOT)`。对中文“杭州”来说，`toLowerCase(Locale.ROOT)` 不改变字符，规范化后仍是“杭州”，**不会转成拼音 `hangzhou`**；本文虚构的城市码 `571` 也只是示意值，不代表任何真实编码。源码注释说明，早期版本 key 做了 trim 而 DB 查询用原始入参，在 MySQL 8 的 `NO PAD` 排序规则下会造成缓存污染；现在 cache key 与查询条件共用同一值。
3. key 为 `amap:city_code:{normalized}`，版本 key 为 `amap:city_code:version:{normalized}`（定义见 `constant/RedisKeyConstants.java#amapCityCodeKey/#amapCityCodeVersionKey`）；源码先 `GET` 版本 key（`:68`），再 `GET` 数据 key（`:69`），**是顺序调用不是并行**（`CityCodeUtil.java:66-69`）。
4. 命中 `__TOMBSTONE__` → 只查 DB，**不回填**（回填会覆盖墓碑、续成 7 天条目，重新打开竞态窗口）。
5. 命中 `__NULL__` → 返回 `null`；命中普通值 → 返回该城市码。
6. 未命中 → `CityCodeMapper.selectOne` 按 `name` 或 `simple_name` 精确匹配 `bus_citycode` 表，再用 Lua `CACHE_CITY_CODE_IF_VERSION_MATCH` 回填：`KEYS=[key, versionKey]`，`ARGV=[version 或 "0", code 或 "__NULL__", 604800]`；只有版本一致且 `SET NX` 成功才写入。

**写路径边界**：`CityCodeUtil#invalidate` 用 Lua `INVALIDATE_CITY_CODE` 做 `INCR 版本 + SET key __TOMBSTONE__ EX 5`，但 `src/main/java` 只有定义，没有调用方；测试里通过 mock `CityCodeMapper` 的回调调用它。因此不要写“城市码一旦变更就会写墓碑”——当前源码的写路径没有接线。这一结论与第 14 章《缓存问题与并发窗口》对墓碑读/写路径的核查一致。

### 4.3 HTTP 调用、配置与响应解析

`AmapGeoRegeoService#getGeo(address, cityCode)`：

```java
// agentbase/amap/service/AmapGeoRegeoService.java（方法骨架；方法体细节以注释摘要，非逐字源码）
public Optional<Geocode> getGeo(String address, String city) {
    if (properties.getKey() == null || properties.getKey().isBlank()) {
        throw new BusinessException(500, "amap.key 未配置");
    }
    if (address == null || address.isBlank()) {
        throw new BusinessException(400, "address 不能为空");
    }
    // GET /geocode/geo，query: key / address / output=json，city 非空才加
    // 4xx/5xx → BusinessException(502)；RestClientException → BusinessException(502)
    // response == null → 直接 Optional.empty()（不记日志）
    // !response.isSuccess() → warn + Optional.empty()
    // geocodes == null/empty → Optional.empty()；否则 geocodes.getFirst()
}
```

配置与客户端：`AmapProperties` 的前缀是 `amap`，Java 默认值 `url = "https://restapi.amap.com/v3"`、`timeout = 5000`（毫秒），`key` 来自 `application.yaml` 的 `amap.key`（占位符，本文不展开值）。`AmapConfig#amapRestClient` 用 `SimpleClientHttpRequestFactory` 把同一个 `timeout` 同时设为连接超时与读取超时，并有一个把 query 中 `key` 替换为 `***` 的日志拦截器；HTTP 层没有重试或熔断。

响应先映射成 `AmapGeoResponse`（record，带 `@JsonIgnoreProperties(ignoreUnknown = true)`，`isSuccess()` 是 `"1".equals(status)`），再取 `geocodes[0]` 的 `Geocode`。这里就有本章最典型的不稳定字段：`status`、`info`、`infocode`、`count` 以及 `formatted_address`、`location` 等 String 字段都标了 `@JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)`，而 `city`、`street`、`number` 直接声明为 `Object`，靠 record 内的辅助方法取值。第六节用虚构 JSON 逐项推导。

### 4.4 失败分支与客户端可见性

按源码能静态追踪到的分支（**未实测**）：

| 阶段 | 条件 | 工具返回 / 后续 |
|---|---|---|
| 城市码 | `city` 空白 | `getCityCode` 返回 `null`，`getGeo` 不加 `city` 参数，全国检索 |
| 城市码 | Redis 命中墓碑 | 查 DB、不回填；DB 无记录则 `cityCode=null` |
| 配置 | `amap.key` 空白 | 服务抛 `BusinessException(500,"amap.key 未配置")`；工具内未 catch，冒泡到 `executeTool` 的异常兜底 → `"Tool execution failed: amap.key 未配置"` |
| HTTP | 4xx/5xx | `BusinessException(502,"高德 API HTTP 请求失败: ...")`，同样走 `executeTool` 异常兜底 |
| HTTP | 传输异常/读超时 | `RestClientException` → `BusinessException(502,"高德 API 调用异常")` |
| 业务 | HTTP 200 但 `status != "1"` | 记 warn，返回 `Optional.empty()` → 工具返回 `"未查询到坐标"` |
| 业务 | `geocodes` 为空 | `Optional.empty()` → `"未查询到坐标"` |

客户端可见性分三步，且不保证客户端收到：① 工具进入时 `tool_start` 经 `toolListener` 的 `sink.tryEmitNext(...)` 尝试推送，返回值未检查；② 工具返回后先尝试 `recordCalling` 写反查索引，再构造回执，整批处理后将 `ToolResponseMessage` 加入内存 history；索引或后续保存可能失败，详见第 19 章；③ 模型基于回执生成文本时，Agent 在流回调里尝试推送 `message`——模型可能不生成文本、流可能报错或客户端可能已断开。**外部 API 的原始 JSON 不直接发给客户端**；模型也可能在拿到失败文本后自己改口，这属于提示词约束范围，不是代码保证的“一定告知失败”。

还有一个必须诚实写出的边界：**“超时/异常”不证明对端没执行**。如果高德已受理请求、响应在回程超时，服务同样抛 `BusinessException(502, "高德 API 调用异常")`；它与“对端未收到请求”在工具回执文本这一层可能相同（都经 `executeTool` 兜底为 `"Tool execution failed: ..."`），但客户端最终是否收到 `error`/`message` 取决于事件推送与模型流，不能由此反推对端状态。源码没有请求 ID 或幂等键能把二者区分开。

---

## 五、完整链路 B：路径规划与天气

两条链分别代表“服务端状态驱动”和“模型参数驱动”两种外部调用方式。

### 5.1 路径规划：`OrderTool#getEstRouteAndPrice` → 高德 `/direction/driving`

与 `geo` 不同，这个工具**不接收起终点坐标参数**，只有 `boolean noRoute`。经纬度来自 Redis hash `chat:info:{chatId}`：`OrderTool` 从 `ToolContext` 取 `CHAT_ID`，读 `START_LAT/START_LNG/END_LAT/END_LNG`、`IS_RESERVATION`、`IS_EXPEDITED`、`VEHICLE_TYPE`；这些字段是前面 `saveNewOrderParam` 写入的。前置条件不满足时返回固定文本：`"请使用isNewOrderReady()先检查订单参数是否完备"`（无 `ReadyforRoute` 标记）。

```java
// agentbase/tool/OrderTool.java（路径分支，节选）
String mongoId = UUID.randomUUID().toString();
EstRouteVO estRoute = amapRouteService.getDrivingRouteEst(
        Double.parseDouble(startLng), Double.parseDouble(startLat),
        Double.parseDouble(endLng),   Double.parseDouble(endLat));
PriceEstimateVO estPrice = getEstPrice(estRoute.getEstKm(), estRoute.getEstTime(), ...);
orderRouteService.addOrder(mongoId, estRoute);            // MongoDB 轨迹
// …写回 Redis 的 EST_PRICE / EST_DISTANCE_KM / EST_TIME / MONGO_TRACE_ID
orderConfirmation.quoted(toolContext.getContext().get(CHAT_ID).toString()); // HITL 标记
return String.format("新订单完成路径规划和算价，预计里程%sKm，预计全程耗时%s分钟，预计价格%s元。请使用notifyUser()通知用户确认。", ...);
```

服务层 `AmapRouteService#getDrivingRouteEst(originLon, originLat, destLon, destLat)`：

- 用 `formatLngLat(%.6f,%.6f)` 把四个 `double` 格式化成 `origin`/`destination` 两个字符串，**经度在前**。
- 固定 `strategy=10`、`Extension.ALL`、`roadAggregation=false`；请求 `GET /direction/driving`，query 含 `key`、`origin`、`destination`、`extensions`、`output=json`。
- 私有 `fetchDrivingRouteResponse` 先做 key 缺失→`BusinessException(500)`、参数空→`BusinessException(400)`，这些**发生在发出 HTTP 之前**；然后才 `GET`，4xx/5xx→502、`RestClientException`→502。业务失败、空响应、`route`/`paths`/`polyline` 空都抛 `BusinessException(502, ...)`。
- **循环最多三次，但不等于三次 HTTP，也不等于统一文案**：`for (attempt = 1; attempt <= 3)` 包住整个调用与解析。`BusinessException` 若 `code < 500`（如上面的 key 缺失 500 之外的 400/502 需按实际 code 判断；本方法内多为 502），在第 1/2 次且 `code >= 500` 才 `continue`，第 3 次无论 code 都 `throw e` **原样抛出**；普通 `RuntimeException` 第 1/2 次 `continue`，第 3 次才包装为 `BusinessException(502,"高德路径规划失败(重试3次仍失败)")`。因此：key/参数校验失败可能一次 HTTP 都没有；业务 `BusinessException` 第三次抛的是原始消息，不是“重试3次仍失败”；只有普通运行时异常耗尽才出现那句包装文案。这是本章追踪到的唯一服务端重试，且无备用数据源。
- 只用第一条 path：`estKm` = `distance/1000`（`BigDecimal` 3 位、`HALF_UP`），`estTime` = `firstPath.duration()`（秒字符串），`estPolyline` = 收集 `step.polyline` 后用 `;` 拼接，`estRoute` = 只含第一条路径的 `AmapRoute#toString()`。

`noRoute=true` 分支要按源码拆开看，**它不是“只调本地计价、无副作用”**：

- 复用前提（全部满足才保留 `true`）：Redis hash 里已有 `MONGO_TRACE_ID`，且 `EST_TIME` 与 `EST_DISTANCE_KM` 都存在。缺任一项，工具把 `noRoute` 强制改回 `false`，走 HTTP 重新规划。
- 满足前提时确实**不请求路线 HTTP**，只调本地 `getEstPrice`（内部 `RidePricing.calculate`，金额公式见第 11 章）。但紧接着仍执行：`putPricePara(...)` 写价格拆分字段、`redisPut(EST_PRICE, ...)` 写价格、`redisDel("ReadyforRoute")` 删除就绪标记、`orderConfirmation.quoted(chatId)` 生成**新的** `quoteId`/`quoteCreatedAt` 并删旧 `confirmedQuote`/`ReadyForCreate`。所以 `quoted`/许可状态会变化，复用分支也会影响后续确认，不是纯读。

**失败与副作用边界**：路径 HTTP/业务异常没有在 `OrderTool#getEstRouteAndPrice` 内 catch，冒泡到 `executeTool` 的 `try` 异常兜底才成为 `"Tool execution failed: ..."` 文本；在此之前（如 `quoted` 之前）失败不会写 Mongo 轨迹与 Redis 预览。重试的每一次循环是否真的发出 HTTP 取决于上一次在哪个阶段失败（key/参数校验在 HTTP 前）。成功路径分支写 MongoDB 轨迹、Redis 预览字段并调用 `quoted`；清理旧轨迹失败只记 warn（`catch RuntimeException`）。这条链是 O5 里“报价由服务端产生”的地图侧来源；确认令牌如何消费见第 18 章。

### 5.2 天气：`CurWeatherTool#getWeatherByLatLon` → 和风 `/v7/weather/now`

天气工具的参数直接来自模型：`getWeatherByLatLon(@ToolParam("纬度") double lat, @ToolParam("经度") double lon, ToolContext)`。`executeTool` 读 `lat`/`lon` 后调用它，工具再调服务：

```java
// agentbase/qweather/service/QweatherWeatherService.java（ByLatLon 重载，完整）
public Optional<QweatherNow> currentWeatherByLatLon(double lat, double lon){
    String lat2f = String.format(Locale.ROOT, "%.2f", lat);
    String lon2f = String.format(Locale.ROOT, "%.2f", lon);
    return currentWeather(lon2f + "," + lat2f);   // 经度在前，两位小数
}
```

`currentWeather(String location)` 校验 `qweather.key`/`qweather.url`/`location`（空值分别对应 500/500/400），`GET /v7/weather/now`，query 为 `key` 与 `location`。HTTP 4xx/5xx → `BusinessException(502, "和风天气 API HTTP 请求失败: ...")`；`RestClientException` → `BusinessException(502, "和风天气 实时天气 调用异常")`；HTTP 200 但 `code != "200"` → warn + `Optional.empty()`。配置前缀 `qweather`，Java 默认 `timeout = 5000` 毫秒，`url`/`key` 来自 `application.yaml`（值不展开）；`QweatherConfig#qweatherRestClient` 同样是 `SimpleClientHttpRequestFactory`，connect/read 共用这个 `timeout`，并挂了一个 Gzip 解压拦截器。

工具层的“重试一次”有**严格条件**：只有第一次 `currentWeatherByLatLon` 正常返回 `Optional.empty()`（即 HTTP 成功但业务 `code != "200"`、`now` 为空等）才再调一次；若第一次抛 `BusinessException`（4xx/5xx、key/url/location 校验失败、`RestClientException` 包装），异常直接从工具方法冒泡，**不进入这个重试分支**，最终由 `executeTool` 的异常兜底变成 `"Tool execution failed: ..."`。第二次仍 `empty()` 才返回 `"工具未能获取天气数据"`；成功则格式化为“当前位置气温：X℃，体感温度Y℃，天气Z，W级风。”。

**另外四个天气方法（三天、降水、预警、空气质量）没有这层重试**，且失败文本不能一概而论。`getWeatherAlertsByLatLon` 在 `Optional.empty()`、或 `resp.alerts()==null`、或 `alerts.isEmpty()` 时返回 **`"当前无天气预警"`**——这既可能是“真实无预警”，也可能是数据不可用/响应为空，两者在工具文本里不可区分，不能把它当成“工具失败”的固定文本。其余方法返回各自的固定文本（如三天 `"工具未能获取未来三天天气数据"`、降水 `"工具未能获取降水预报数据"`、空气质量 `"工具未能获取空气质量数据"`）。这个差异说明“不能拿一个工具的行为概括所有工具”。

单位与字段：温度取 `QweatherNow#temp/feelsLike`（℃），`windScale` 是风级（字符串），`text` 是天气现象；三天预报用 `tempMin/tempMax`（℃）与 `precip`（mm）。和风响应 record 没有自定义 `@JsonDeserialize`，字段缺失时由 Jackson 默认处理为 null，工具模板输出可能出现 “null”——这是按源码可推导的风险，不是实测现象。

### 5.3 两条链的对照

| 维度 | 地理编码 `geo` | 路径规划 `getEstRouteAndPrice` | 实时天气 `getWeatherByLatLon` |
|---|---|---|---|
| 入参来源 | 模型 `address`/`city` | Redis hash（模型只给 `noRoute`） | 模型 `lat`/`lon` |
| 城市/位置读取 | `CityCodeUtil`：Redis→MySQL | 不涉及城市码 | 不涉及城市码/乘客位置 |
| 坐标顺序 | 无坐标入参（输出 `location`） | 经纬度，`%.6f` | 经纬度，`%.2f` |
| HTTP 客户端 | 高德 `RestClient` | 高德 `RestClient` | 和风 `RestClient` |
| 超时来源 | `amap.timeout`（默认 5000ms） | 同左 | `qweather.timeout`（默认 5000ms） |
| 重试/降级 | 无 | 循环最多 3 次，按异常类型与时机决定是否重试和文案；无降级 | 仅首次 `Optional.empty()` 再试 1 次；异常不重试；无降级 |
| 业务失败表现 | `"未查询到坐标"` | `BusinessException` 冒泡为 `"Tool execution failed: ..."`（文案按异常类型） | 实时天气 `"工具未能获取天气数据"`；预警空结果 `"当前无天气预警"` |
| 副作用 | 只读 + 城市码缓存回填 | 成功写 Mongo 轨迹/Redis 预览/`quoted`；`noRoute` 复用分支仍写价格字段、删 `ReadyforRoute`、调 `quoted` | 无持久化副作用 |

---

## 六、反序列化：三种“不稳定字段”怎么落地

外部 API 同一个字段名可能返回字符串、空数组 `[]` 或对象。项目自定义了三个反序列化器，全部在 `agentbase/amap/util/jackson/`。下面用**虚构 JSON**逐项推导；左列是字段收到的 JSON 片段，右列是按 `deserialize` 分支得到的 Java 值。**这是源码静态推导，没有运行 Jackson 或测试。**

### 6.1 `StringOrEmptyArrayToNullDeserializer`：String 字段

作用于大量 String 字段，例如各响应 record 的 `status`/`info`/`infocode`/`count`，`Geocode#formattedAddress`（`@JsonProperty("formatted_address")`）、`AmapRoute#taxiCost`、`AmapSearchPoi#gridcode` 等。核心逻辑（源码完整片段）：

```java
// agentbase/amap/util/jackson/StringOrEmptyArrayToNullDeserializer.java
JsonNode node = parser.getCodec().readTree(parser);
if (node == null || node.isNull()) return null;
if (node.isTextual()) return node.asText();
if (node.isArray()) {
    if (node.isEmpty()) return null;
    List<String> parts = new ArrayList<>();
    for (JsonNode child : node) {
        if (child != null && !child.isNull()) {
            String text = child.asText();
            if (text != null && !text.isBlank()) parts.add(text);
        }
    }
    if (parts.isEmpty()) return null;
    return String.join(",", parts);
}
if (node.isNumber() || node.isBoolean()) return node.asText();
return null;
```

| 虚构输入片段 | Java 结果（静态推导） | 依据分支 |
|---|---|---|
| `"formatted_address":"虚构地址A"` | `"虚构地址A"` | `isTextual()` |
| `"formatted_address":[]` | `null` | `isArray() && isEmpty()` |
| `"formatted_address":["虚构地址A","虚构地址B"]` | `"虚构地址A,虚构地址B"` | 非空数组，逗号拼接 |
| `"formatted_address":[null,"  ","虚构地址A"]` | `"虚构地址A"` | 跳过 null/空白子元素 |
| `"count":12` | `"12"` | `isNumber()`，`asText()` |
| `"status":true` | `"true"` | `isBoolean()` |
| `"formatted_address":{"name":"虚构"}` | `null` | 其余类型静默返回 null，不抛异常 |

注意：这个反序列化器把**对象**静默变成 null，而不是报错。因此 `isSuccess()` 里 `"1".equals(status)` 在 `status` 被填成 `null` 时只会得到 `false`（业务失败分支），不会抛反序列化异常。

同一批 POJO 里还有**未加注解、直接声明为 `Object`** 的动态字段：`Geocode#city/street/number`、`AddressComponent#city/neighborhood/building`、`AmapPoi#tel`。它们靠 record 内的辅助方法取值，且方法不同：

- `Geocode#getCityName`、`Geocode#getStreetString`、`Geocode#getNumberString`：只有字段是 `String` 才原样返回，否则返回 `""`（源码 `georegeo/Geocode.java:32-51`）。
- `AddressComponent#getCityName`：同样只认 `String`，否则 `""`（`:33-36`）；而 `getNeighborhoodName`/`getBuildingName` 才调用私有 `extractNameFromDynamicField`，只从 `Map` 取 `"name"` 并 `toString()`，非 Map 返回 `""`（`:39-54`）。

所以“这个字段是 Object”和“这个字段用了反序列化器”是两回事；同为 `getCityName()` 也不能照搬“从 Map 取 name”的结论，必须按具体 record 的方法体推导。

### 6.2 `StringOrArrayToStringListDeserializer`：可变长度数组

唯一使用处是 `agentbase/amap/pojo/route/AmapStep.java` 的 `tollRoad`：

```java
@JsonProperty("toll_road")
@JsonDeserialize(using = StringOrArrayToStringListDeserializer.class)
List<String> tollRoad,
```

逻辑：token 为 `VALUE_NULL` → `null`；`VALUE_STRING` 且 trim 后为空 → `Collections.emptyList()`，否则 `List.of(trimmed)`；`START_ARRAY` → 跳过 null 元素、trim、过滤空串后收集；**其他 token 抛 `JsonMappingException("Expected string or array for List<String>, but got token: ...")`**。

| 虚构输入片段 | Java 结果（静态推导） |
|---|---|
| `"toll_road":null` | `null` |
| `"toll_road":"虚构高速"` | `List.of("虚构高速")` |
| `"toll_road":"   "` | `Collections.emptyList()`（**不是 null**） |
| `"toll_road":["虚构高速A",null,"  ","虚构高速B"]` | `["虚构高速A","虚构高速B"]` |
| `"toll_road":[]` | 空 `ArrayList`（**不是 null**） |
| `"toll_road":{"name":"虚构"}` | 抛 `JsonMappingException` |

这个反序列化器**在自身代码里显式抛 `JsonMappingException`**：对象或数字填给 `List<String>` 字段时进入 `throw`。但不能由此推出“只有它会抛异常”：`ObjectOrEmptyArrayDeserializer` 的 `ObjectMapper.convertValue(...)` 遇到不兼容的对象结构也可能抛（`IllegalArgumentException`/Jackson 转换异常），只是它的代码**没有显式 throw**；`readTree(parser)` 读取坏 JSON 时三个反序列化器都可能抛 IO/Jackson 异常。至于这些异常最终被 `RestClient` 包装成什么类型、如何呈现给模型和客户端，源码没有对应 catch，本章**未运行、不推断**，标为未知。

### 6.3 `ObjectOrEmptyArrayDeserializer`：对象或空数组

使用处是 `AmapSearchPoi.java` 的 `indoorData`（`@JsonProperty("indoor_data")`）与 `bizExt`（`@JsonProperty("biz_ext")`）。它实现 `ContextualDeserializer`，`createContextual` 把字段的目标 `JavaType`（`AmapIndoorData`/`AmapBizExt`）带进实例；`deserialize` 里：null→null；空数组→null；非空数组**取第 0 个元素**（第 0 个为 null→null），其余忽略；非对象（字符串/数字/布尔）→null；对象→`ObjectMapper.convertValue(node, targetType)`。

| 虚构输入片段 | Java 结果（静态推导） |
|---|---|
| `"biz_ext":null` | `null` |
| `"biz_ext":[]` | `null` |
| `"biz_ext":[{"rating":"4.8"},{"ignored":true}]` | 仅用第一个元素转成 `AmapBizExt` |
| `"biz_ext":{"rating":"4.8"}` | 转成 `AmapBizExt` |
| `"biz_ext":"虚构文本"` | `null` |

Unsupported/空数组/非对象这几个分支返回 null，不抛异常；但**对象分支**调用 `ObjectMapper.convertValue(effective, t)`（codec 非 ObjectMapper 时走 `ctxt.readTreeAsValue`），这里若字段结构与 `AmapIndoorData`/`AmapBizExt` 不兼容，转换可能抛异常——所以不能说“对象一定转换成功”，也不能说“只有 List 反序列化器会抛”。把“字段解析成功”说成“字段一定非空”同样错误；POI 工具在拼 VO 时会过滤空值，但那属于工具侧处理，不是反序列化保证。

### 6.4 教学边界

这三种反序列化器都只处理**高德** POJO；`agentbase/qweather/pojo/` 下 0 处 `@JsonDeserialize`。`docs/learn` 旧文里若有“外部字段一律这样处理”的概括，不能照搬。上述推论基于读到的方法体与字段注解；本章没有运行 Jackson，也没有真实高德/和风响应样本，因此“真实线上哪种字段会出现 `[]`”“错误类型实际出现频率”均标**未知**。

---

## 七、失败、超时、可见性：从 HTTP 错误到 SSE 事件

### 7.1 同步、超时与重试矩阵

| 调用 | 同步/异步 | 超时来源 | HTTP/业务错误处理 | 重试 | 降级 |
|---|---|---|---|---|---|
| 高德 `getGeo`/`getRegeo`/`searchByKeyword`/`searchAround`/`inputTips` | 同步 `RestClient.get()` | `amap.timeout`，默认 5000ms；connect=read | 4xx/5xx 与 `RestClientException`→502；业务失败→`Optional.empty()` | 无 | 无 |
| 高德 `getDrivingRouteEst` | 同步 | 同上 | key/参数在 HTTP 前校验；空响应/空 route/空 polyline→502 | 循环最多 3 次：业务 `code>=500` 前两次重试、第三次原样抛；普通 `RuntimeException` 第三次才包装文案；`code<500` 更早抛 | 无 |
| 和风天气/生活预警与空气/POI 三个服务类 | 同步 `RestClient.get()` | `qweather.timeout`，默认 5000ms；connect=read | 4xx/5xx 与 `RestClientException`→502；业务 code 非 200→`Optional.empty()`（预警/空气只判 response==null） | 服务层无 | 无 |
| `CurWeatherTool#getWeatherByLatLon` | 同步 | 同上 | 首次 `empty()`→再试一次→文本；抛异常直接冒泡 | 仅 `Optional.empty()` 分支再试 1 次 | 无 |
| `TrafficTool` | 本地 | 无 | 航班号不匹配→固定文本 | 无 | 无（无 HTTP） |

`pom.xml` 没有 `spring-boot-starter-validation`，`src/main/java` 检索 `@Valid`/`@NotNull`/`jakarta.validation` 为 0；工具入参只有 `@ToolParam(description=...)`。`Agent#executeTool` 直接 `objectMapper.readTree(jsonArgs)` 后 `.asDouble()`/`.asText()`，字段缺失或类型不对依赖 `try/catch` 分支，**没有坐标范围校验**。`GlobalExceptionHandler` 把 `IllegalArgumentException` 映射到 400、`BusinessException` 用自带 code、`Exception` 兜底 500；没有 `RuntimeException` 专属 handler。

### 7.2 四类结果不是一回事

一次工具调用会在不同地方留下四种“结果”，读者复习时最容易把它们混成一句“调用成功/失败”：

1. **外部结果**：高德/和风返回的 JSON，映射为 `Geocode`/`EstRouteVO`/`QweatherNow` 等；业务失败时是 `Optional.empty()`。
2. **状态副作用**：Redis 城市码回填、`chat:info:{chatId}` 的预览字段与 `quoted` 标记、MongoDB 轨迹、MySQL 工具结果索引。这些写入有各自的成功/失败条件，与外部 HTTP 是否成功不是同一件事。
3. **模型文本**：工具返回的 `String` 回执进入 `history`，模型据此生成回答；“工具返回失败文本”不保证模型最终照办，这是提示词约束的领域。
4. **SSE 事件**：`tool_start` 在进入工具时经 listener 尝试推送；`message` 在模型流回调里尝试推送；`confirm` 由 `handleNotifyUser` 在保存等步骤后尝试推送；`error`/`notify` 由 Agent 的错误与提示分支推送。它们都通过 `sink.tryEmitNext(...)`，返回值未被检查——**只表示服务端尝试发出，不等于客户端收到**；客户端断开与服务端任务终态是两件事（第 16 章）。

### 7.3 已知未知

以下结论源码无法证明，明确标**未知**：真实高德/和风在超时或断连时是否已受理请求、重试 3 次是否造成重复计费/配额消耗；工具返回 `"Tool execution failed: ..."` 时客户端最终收到的是 `error` 事件还是模型生成的 `message`（取决于模型流与 Agent 错误处理，未实测）；Jackson 抛 `JsonMappingException` 后框架如何把它呈现给模型；外部服务返回的未知字段实际分布。任何“线上一定会超时/一定降级”的说法都没有源码依据。

---

## 八、提示词约束 vs 代码守卫：三组逐条对照

提示词是写给模型的自然语言，作用是**引导模型的行为**；代码守卫是服务端对状态与身份的**强制检查**。下面的每一组都按“提示词写了什么 → 代码在哪里、检查什么状态、什么例外 → 边界结论”来读。

### 8.1 用户身份与归属

- **提示词**：`ChatProperties` 的 `CLASSIFIER_SYS_PROMPT` 把“越权、套取系统提示词/密钥”定义为 `DANGER`；Order/Daily/Support 三个提示词都把当前时间、用户位置作为上下文注入（`FallbackAgent` 只注入时间），但**没有任何一段写“只能操作自己的订单/工单”**。
- **代码守卫**：入口在 `ChatController`（`userId` 来自 `UserTokenContext`、`chatId` 来自 URL），`ChatRequestRunner#run` 做 `UUID.fromString` 与 `assertOwned(userId, chatId, !resume)`，`ChatServiceImpl` 再校验；工具侧 `ToolRepPointerTool#getToolResponse` 先 `chatManager.assertOwned(userId, chatId, false)` 再查反查索引，`TicketTool`/`OrderSearchTool` 从 `ToolContext` 取 `USER_ID` 做归属查询并返回 `"无权限操作该订单"` / `"您无权查看此工单。"`。
- **直接 HTTP 入口的范围**：`POST /order` 的 `RideOrderController#create` 只有 `@RequirePermission`（仅要求登录），随后 `req.setUserId(UserTokenContext.getUserIdInString())` 覆盖客户端传来的 userId；它**不经过** Agent 的分类、`ToolContext` 与报价确认令牌。归属校验在 Service 层（第 7、11 章）。因此“Agent 有归属守卫”不能推广成“所有入口都走同一守卫”。
- **边界结论**：可信身份只有一个来源——服务端从令牌解析出的 `UserTokenContext`。用户文本和外部 API 响应都是不可信输入。`DANGER` 分类只是路由选择，真正拒绝越权的是代码。

### 8.2 订单报价、确认与消费

- **提示词**：`ORDER_AGENT_SYS_PROMPT` 的 D 段写“调用 `notifyUser()` 输出订单摘要并请用户确认 → 用户确认后调用 `markOrderReadyForCreate()`，随后 `createOrder()`”；另有“不要编造价格”“价格由系统规则计算”。`SUPPORT_AGENT_SYS_PROMPT` 的取消 SOP 写“Step 3 告知费用并询问是否确认 → Step 4 得到明确‘是/确认’后调用 `cancelOrder`”。
- **代码里的状态与门禁（逐行核对 `service/base/OrderConfirmation.java`）**：
  1. `quoted(chatId)`：删 `ReadyForCreate`/`confirmedQuote`，写新的 `quoteId`（`UUID.randomUUID()`）、`ReadyforConfirm=true`、`quoteCreatedAt=System.currentTimeMillis()`，随后 `EXPIRE key 24h`。三条命令不是事务，`EXPIRE` 失败则整个 `chat:info:{chatId}` 不按这次调用续期。
  2. `begin(chatId, toolCallId)`（`OrderAgent#handleNotifyUser` 调用）：先读当前 `quoteId` 与 `ReadyforConfirm`，任一为 `null` 抛 409“请先完成有效报价”；通过后 `putAll` 写 `pendingToolCall=toolCallId`、`break=yes`，并返回当前 `quoteId` 作为下发编号。**`begin` 不写 `resuming`，也不校验年龄。**
  3. `CONFIRM` Lua（`confirm(chatId, confirmationId, accepted)` 执行）：先比当前 `quoteId` 是否等于传入编号；`accepted=yes` 且 `now - quoteCreatedAt > 900000` 才返回 0（`now` 是 JVM 侧 `System.currentTimeMillis()` 作为 ARGV 传入，不是 Redis 时间，也不是给 `break` 设 TTL）。`break=resuming` 且 `confirmationAccepted` 等于本次决定则直接返回 1（同一决定可重放、不重写）；`break=yes` 时写 `break=resuming`、`confirmationAccepted`，`yes` 再写 `confirmedQuote`/`ReadyForCreate`，拒绝则删这两个字段。**拒绝分支不查年龄**；`confirm` 方法拿到非 1 抛 409。
  4. `isConfirmed(chatId)`：只读比较 `quoteId`、`confirmedQuote`、`ReadyForCreate` 三者相等。`markOrderReadyForCreate` 只调它并返回 `"订单已取得用户确认，可以创建"` / `"尚未取得用户对当前报价的确认"`，**是一个返回文本的观察方法，不是 `createOrder` 的必需前置守卫**。
  5. 真实消费在 `createOrder` 内：先快速检查 `ReadyForCreate` 是否存在（无则返回 `"订单非可创建态"`），组装 DTO 后调 `consume(chatId)`；`CONSUME` Lua 再次用 JVM 当前时间比 `quoteCreatedAt`（>900000 返回 0），并要求 `confirmedQuote`、`ReadyForCreate` 都等于当前 `quoteId`，通过后 `HDEL` 两个字段；非 1 抛 409，之后才 `rideOrderService.createOrder(dto)`。
- **15 分钟与 key 的 TTL 要分开看**：900000ms 是接受（`CONFIRM` 的 `yes` 分支）与消费（`CONSUME`）两个阶段各自的年龄检查，均以 `quoteCreatedAt` 为锚点、以 JVM 当前时间为输入；它不是 `break` 字段自身的 TTL，也没有对 `break` 单独设置过期。`chat:info:{chatId}` 这个哈希整体的 TTL 由 `quoted`（设为 24h）、`lockChat`、`restore` 等操作改写，且都取决于相应命令是否成功；未实测真实过期时刻。完整状态机见[第 18 章 §二～§六](../04-chat-and-agent/03-order-confirmation.md#二报价quoted-到底写了什么)。
- **例外与范围**：`notifyUser` 在 `executeTool` 里没有 `case`——它由 `handleToolCalls` 前置拦截，`handleNotifyUser` 依次做 `begin`、序列化订单参数、`saveNewMessages`、再写一次 `break=yes`、最后**尝试**发 `confirm` 事件并 `tryEmitComplete()`；任一步失败会记日志/发 error 并可能留下部分状态（例如 `break=yes` 但客户端没拿到摘要）。即便模型不按要求调用 `notifyUser`，代码里也没有“必须先通知才能报价”的检查。`markOrderReadyForCreate` 与 `createOrder` 的守卫都读服务端状态，不看模型是否真的“说了请确认”；用户反馈由服务端 `ChatServiceImpl#resume` 写成固定文本。直接 `POST /order` 的 `RideOrderController#create` 不走 `quoted`/`begin`/`CONFIRM`/`consume` 协议（仅 `@RequirePermission` 且覆盖 `userId` 为令牌值）。`cancelOrder` 的确认令牌在 `OrderSearchTool` 侧，完整推导（身份来自 `ToolContext`、`readyForCancel`/`cancelVerifiedAt`/`cancelFee`、CANCEL Lua 的订单号匹配与严格 >300000、令牌消费与业务事务分离）见[第 18 章 §十五 客服取消预检](../04-chat-and-agent/03-order-confirmation.md#十五客服取消预检)；它也不是 Agent 提示词——提示词只要求 Step 3 告知费用、Step 4 得到用户明确确认后再调用，脚本不校验这两步。
- **边界结论**：提示词负责“让模型按人类的流程说话”，代码负责“缺少有效确认就拿不到创建许可”。这里没有“参数快照绑定”，也不能保证用户一定看到摘要；把提示词当鉴权，等于把“模型应该做”误写成“服务端保证做”。

### 8.3 位置参数与工单权限（二选一深读，这里都给最小对照）

**位置参数**：

- **提示词**：ORDER 提示词要求“坐标字段为十进制小数”“不要编造经纬度”“涉及历史工具结果必须 `getCalling()` 查询真实结果”；DAILY 提示词写得更强——“未查询真实结果，则不得在回答中使用该工具返回的信息（包括坐标）”“不得编造、推断或假设”。
- **代码**：没有 Bean Validation，没有范围校验；`executeTool` 的 `case "regeo"` 直接 `Double.parseDouble(longitude/latitude)`，缺字段或非法文本落到异常兜底。`GeoRegeoTool`/`POISearchTool`/`CurWeatherTool` 的坐标来自模型参数，工具方法本身只做空值/业务结果检查，不检查纬度是否越界。`UserPOITool#createNewPOI` 的经纬度是 `BigDecimal`，同样无范围校验。服务端在提示词里注入的“用户位置”来自 `UserServiceImpl#getUserLocation`（Redis `user:loc:{userId}`），这是**上下文**，不是这些工具的入参。
- **结论**：提示词的“不得编造”是行为约束；代码对坐标只保证“能解析成数字”，不保证“在地球上是合理位置”。`CityCodeUtil` 的规范化与版本 Lua 保证缓存一致性，也不校验城市是否真实存在——那是 MySQL `bus_citycode` 的内容。

**工单权限**：

- **提示词**：SUPPORT 提示词说 `escalateTicket` 在报警/车祸等关键词时“立即/静默调用”，并列出工单工具。
- **代码**：`TicketTool` 的每个方法从 `ToolContext` 取 `USER_ID` 再查库；`queryTicketProgress`/`appendUserMessage` 有“您无权查看此工单。”分支，`createTicket` 用当前用户作为发起人；角色与归属是两层（第 6 章《权限、归属与防护》）。
- **结论**：提示词决定模型“遇到关键词是否升级”，代码决定“谁能看、谁能改”。`escalateTicket` 被调用不等于工单一定升级成功，写库是否成功要看服务返回。

### 8.4 双向不可信，单向可信

把上面三组抽象成一条规则：**外部数据（地图/天气响应）与用户文本都不可信**，前者可能缺字段/类型漂移，后者可能诱导越权；**服务端注入的身份与服务端生成的状态**（`ToolContext` 的 `userId`/`chatId`、`quoted`/`ReadyForCreate`、工单归属）才是守卫依据。提示词是“模型该怎么表现”的文档，不是这两类信息之间的信任边界。

---

## 九、城市码缓存、乘客位置与墓碑：只讲工具链相关的部分

### 9.1 当前接线情况

| 机制 | 来源章节 | 当前工具链中的接线 | 关键事实 |
|---|---|---|---|
| 城市码缓存 | 第 14 章（墓碑主讲） | **读已接线**：`GeoRegeoTool#geo` → `CityCodeUtil#getCityCode` | key `amap:city_code:{normalized}`，TTL 7 天；空值哨兵 `__NULL__`；版本 key `amap:city_code:version:{normalized}`；回源 `bus_citycode` 按 `name`/`simple_name` |
| 城市码墓碑写入 | 第 14 章 | **写未接线**：`invalidate` 在 `src/main/java` 只有定义，测试里被 mock 回调调用 | Lua `INVALIDATE_CITY_CODE`：`INCR` 版本 + `SET __TOMBSTONE__ EX 5`；回填 Lua 校验版本并 `SET NX EX 604800` |
| 乘客位置 | 第 9 章 | **提示词接线**：三个 Agent 的 `buildLocationString(userId)` 读取，注入系统提示 | Redis `user:loc:{userId}` Hash，三个 String 字段，无 TTL、无 MySQL 副本；`POST /user/loc` 无 `@Valid` |
| 司机 GEO/心跳 | 第 9 章 | 与本章工具链无关 | 共享 GEO key + 成员级心跳，不是城市码也不是乘客位置 |

墓碑的读路径行为要按源码写清：`getCityCode` 命中 `__TOMBSTONE__` 时查 DB 但**不回填**，目的是不让手持旧快照的并发读把旧值续成 7 天；墓碑 5 秒后过期，若无写路径触发，缓存会回到正常 miss→回源→按版本回填的流程。由于写路径未接线，不能写“墓碑覆盖所有写路径”；第 14 章对这一结论有完整推导。

### 9.2 失败边界与配置

城市码的 TTL 与墓碑 TTL 是 `CityCodeUtil` 的代码常量（`CACHE_TTL_DAYS=7`、`TOMBSTONE_TTL=5s`），`application.yaml` 没有 city-code 配置键，不能通过配置改。`invalidate` 分别用 `cityName.trim()` 与原始 `cityName` 调 `amapCityCodeKey`/`amapCityCodeVersionKey`，但两个构造方法内部都做 `trim().toLowerCase(Locale.ROOT)`，所以产出的 key 与读路径一致，不存在 key 规范化差异。写路径真正的缺口只有一个：`invalidate` 在 `src/main/java` 没有调用方，本章不顺手改代码。

把“读不到”按来源拆开，结论才不会写成跨库原子：

- **Redis 返回 null（正常读）**：版本/数据 key 都不存在 → 走 DB 回源。
- **Redis 命令抛异常/超时**：`getCityCode` 没有 catch，异常向外传播到工具，再冒泡到 `executeTool` 异常兜底（整段回源不执行）；此时不能断言任何缓存值。
- **DB 回源失败（`CityCodeMapper.selectOne` 抛异常）**：同样没有 catch，向外传播；不会执行 Lua 回填。
- **Lua 回填返回 0 或响应丢失**：`CACHE_CITY_CODE_IF_VERSION_MATCH` 在版本不符或 `SET NX` 未成功时返回 0，`getCityCode` 忽略该返回值、仍返回本次查到的 `code`；若命令已执行但响应丢失，Java 侧只看到异常/未知，**不能断言缓存没写**。这条 Lua 只在 Redis 内原子比较版本与 `SET NX`，不覆盖 MySQL 查询，也不是 MySQL↔Redis 的跨库事务。
- **DB 无记录**：`code == null`，回源参数写 `__NULL__`，本次返回 null。

上述多种情况的**方法返回值可能相同**（如都返回 null 或都返回 code），但 Redis 键与版本状态不同，**不能用返回结果反推缓存状态**；真实超时点、Lua 是否已执行均未实测。

### 9.3 RAG 的边界

`RagTool#searchKnowledgeBase` 只在 `SupportAgent` 注册，失败文本是“知识库未找到有效的相关信息，请明确告知用户并按需转人工处理。” / “知识库暂时不可用。”。本章只说明它是 SupportAgent 的一个工具、与地图/天气同属“外部/检索结果经工具回执进入模型”；问答数据模型、双路检索与 RRF 留到 R1（计划中），此处不建链接、不提前下结论。

---

## 十、三条带条件的时间线

三条时间线都用虚构数值，起点/调用/状态/返回/副作用/未知逐项列出。T1/T2 表示不同请求或线程；“未实测”适用于全部。**每条时间线的正常推进都附带前提**：模型确实选择了所列工具且参数可被解析、外部/缓存/保存/事件相关命令都按其成功分支执行；任一前提不成立，后续行就不能照读。

### 10.1 正常：从“西湖边”到“已报价待确认”

前提（逐行都要成立）：分类为 `ORDER`；模型依次选择 `geo`、`saveNewOrderParam`、`isNewOrderReady`、`getEstRouteAndPrice`、`notifyUser`，且参数格式符合 `executeTool` 的解析；Redis/MySQL/高德相应命令成功；`tryEmit*` 被调用且客户端连接仍在。

| 时刻 | 起点与调用 | 状态变化 | 返回与可见性（服务端尝试） | 可能副作用 / 未知 |
|---|---|---|---|---|
| t0 | 林小雨发消息；`ChatRequestRunner` 校验 UUID 与归属 | 若成功写入单轮执行锁 | 限流/分类缓存可能命中（第 16、17 章） | 限流拒绝则整轮不到 t1；UUID/归属失败在入口结束 |
| t1 | 分类 `ORDER`，`OrderAgent#invoke` 用 `buildLocationString(userId)` 填提示词 | 无外部调用 | 模型**选择** `geo(address="西湖边", city="杭州")` 的 toolCalls | 位置来自 Redis `user:loc:{7000001}`；模型是否照提示词选工具属于行为，不是代码保证 |
| t2 | `executeTool` → `GeoRegeoTool#geo` | listener 调 `sink.tryEmitNext(AgentEvent.tool(...))` | **尝试**推 `tool_start("地理编码 (geo)")`；返回值未检查，不保证客户端收到 | 监听器异常只记 warn，工具继续 |
| t3 | `CityCodeUtil#getCityCode("杭州")` | 先 `GET` 版本 key，再 `GET` 数据 key（顺序） | key 为 `amap:city_code:杭州`（中文不转拼音）；虚构命中码 `571`（示意） | 未命中才回源 `bus_citycode` 并按 Lua 条件回填；回源/Lua 失败见 §9.2 |
| t4 | `AmapGeoRegeoService#getGeo("西湖边","571")` | 同步 HTTP `/geocode/geo` | 假设 HTTP 200、`status="1"` 且有可解析的首个 `Geocode`；具体字段如何处理 `[]` 要看字段类型和反序列化器 | 超时/业务失败分支见 4.4；对端是否受理未知 |
| t5 | 工具返回 `"location=虚构经度,虚构纬度, level=..., formatted_address=..."` | 先尝试 `recordCalling` 写索引，再构造回执并在批次处理后加入内存 history | 模型拿到回执后可继续抽槽位；`message` 由后续模型流回调**尝试**推送 | 索引写入失败只记日志；历史在此未落库，后续终态或暂停时才尝试 `saveNewMessages`（第 19 章） |
| t6 | 模型选择 `getEstRouteAndPrice(noRoute=false)` | 读 Redis 起终点；高德 `/direction/driving` 成功；写 Mongo 轨迹与 Redis 预览；`quoted` 写新 `quoteId` | 工具返回“预计里程/耗时/价格…请使用 notifyUser” | 价格由本地公式算（第 11 章）；`quoted` 的 `EXPIRE 24h` 取决于命令成功 |
| t7 | 模型选择 `notifyUser`，`handleToolCalls` 前置拦截 | `begin` 检查 `quoteId`/`ReadyforConfirm` 后写 `pendingToolCall`、`break=yes`；随后序列化参数、`saveNewMessages`、再写 `break=yes` | 尝试推 `confirm(orderJson)` 并 `tryEmitComplete()`；**不保证客户端收到摘要**；本轮暂停等待 `/r` | 任一步失败可能留下部分状态（如 `break=yes` 但无摘要）；`break` 无独立 TTL，15 分钟是 `CONFIRM`/`CONSUME` 对 `quoteCreatedAt` 的年龄检查（第 18 章） |

任一行前提不成立时，本表后续步骤不一定执行；下面两条时间线仅是另外的示例，不覆盖所有失败。

### 10.2 故障：路径规划循环失败（按异常类型分两种）

起点：模型已选择 `getEstRouteAndPrice(noRoute=false)`，起终点参数可解析；失败发生在 `AmapRouteService#getDrivingRouteEst` 内部。**假设同批只有这一个工具调用、后续模型流正常结束**，才可能谈“客户端最终看到 message”；否则不能下此断言。

**情形 A（业务 502，虚构）**：三次 `fetchDrivingRouteResponse` 都成功发出 HTTP，但响应 `status != "1"`，每次抛 `BusinessException(502,"高德 API 业务失败: ...")`（`code=502>=500`）。

| 时刻 | 调用 | 状态 | 返回 | 副作用 / 未知 |
|---|---|---|---|---|
| t0 | 第 1 次 attempt | 无预览字段写入 | `BusinessException(502)`，`attempt<3 && code>=500` → `continue` | 一次真实 HTTP 已发出 |
| t1 | 第 2 次 attempt | 同上 | 同上 → 再 `continue` | 对端是否受理/重复受理未知 |
| t2 | 第 3 次 attempt | 同上 | `attempt==3` → `throw e` **原样抛出**业务异常（不是“重试3次仍失败”文案） | 三次 HTTP 都已发出，无备用数据源 |
| t3 | 异常穿过 `OrderTool#getEstRouteAndPrice`（未 catch） | `quoted`/Mongo 轨迹/Redis 预览**未**发生（写在调用成功之后） | 进入 `executeTool` 的 `catch` → `"Tool execution failed: 高德 API 业务失败: ..."` | 只有 `executeTool` 内捕获的异常才变成工具失败文本 |
| t4 | `handleToolCalls` 尝试 `recordCalling`、构造回执，再递归模型循环 | 文本回执加入 history；是否落库取决于后续步骤 | 若模型产生文本，`tryEmitNext(message)` **尝试**推送；`tryEmit*` 返回值未检查，不能保证收到 | 若同批还有其他工具、模型流报错或断连，结果可能改变；`confirm` 事件是否出现取决于历史状态与模型后续调用，**不能断言绝无 confirm** |

**情形 B（第三次普通 `RuntimeException`，虚构）**：前两次抛普通运行时异常（如反序列化异常）各 `continue`，第三次仍抛 → 包装为 `BusinessException(502,"高德路径规划失败(重试3次仍失败)")`，之后 t3/t4 与情形 A 相同，只是异常文本不同。

**情形 C（HTTP 前失败，虚构）**：`amap.key` 空白或参数校验失败，`fetchDrivingRouteResponse` 在发 HTTP 前抛 `BusinessException(500/400)`。若 `code>=500` 且处于第 1/2 次会继续循环（但每次都在 HTTP 前失败），第 3 次原样抛 500；`code<500`（如 400）则第一次就 `throw e`。**这种情形可能一次 HTTP 都没发出**，也不能把文案写成“重试3次仍失败”。

边界：这条时间线只适用于**路径规划**；天气异常直接冒泡、不进入 `Optional.empty()` 重试，地理编码失败返回 `"未查询到坐标"`，预警空结果返回 `"当前无天气预警"`，不能混用。“循环三次”不证明对端收到三次请求，也不证明没有产生配额消耗。

### 10.3 并发/边界：城市码回源与墓碑（条件性）

起点：假设且仅假设城市码写路径已接线（当前 `src/main/java` 未接线，故此场景当前主链不可达；测试里通过 mock 触发 `invalidate`）。T1 是读请求，T2 是城市码变更后的失效请求。假设双方对同一个规范化城市 key 操作、各命令正常执行，且 T1 的数据库读取取得变更前的旧快照。

| 时刻 | T1（读） | T2（写/失效） | 可见状态 | 结论与未知 |
|---|---|---|---|---|
| t0 | `getCityCode("杭州")` 先读版本 `v`，再读数据 key，结果 miss；开始查询 MySQL | — | Redis 数据 key 不存在，version=`v` | 命中旧码会直接返回，不会进入本行的回源分支 |
| t1 | MySQL 查询取得旧码，尚未执行回填 | 假设城市码更新已完成，`invalidate`：`INCR` 版本→`v+1`，`SET key __TOMBSTONE__ EX 5` | key=`__TOMBSTONE__`，version=`v+1` | 两个 key 构造方法均做 trim/lowercase；本例使用相同城市名 |
| t2 | DB 返回旧码，执行 `CACHE_CITY_CODE_IF_VERSION_MATCH(ARGV=v, 旧码, 604800)` | — | Lua 比较当前版本 `v+1 ≠ v` → 返回 0，**不回填** | 旧值不会借这次回源续命 7 天 |
| t3 | 返回旧码给 `getGeo`（读请求拿到的是查询时点的值） | 墓碑 5 秒后过期 | key 消失，后续读重新 miss→回源 | 若 DB 已更新，下一次回源拿到新码；具体时间未知 |

若 T1 在 T2 之前完成回填，且版本匹配、`SET NX` 成功，旧码会暂时写入；随后 T2 的墓碑覆盖它。墓碑存在期间读请求查数据库但不回填，只有数据库查询确实读到新值时才返回新值。上述均为源码静态推导：测试 `RedisCacheRegressionTest` 的两个城市码用例覆盖“命中墓碑不回填”和“空值哨兵 + Locale 无关”，但用了 mock `CityCodeMapper`、未运行，也没有覆盖真实 MySQL 事务提交后触发 `invalidate` 的链路。

---

## 十一、动手验证：四组只读源码追踪

四组练习都不要求启动服务或连接外部资源；请在隔离环境里按给出的检查点逐项定位。答案是“应看到什么源码事实”，不是运行结果。

### 练习 A：工具从注册到分派

前置：已读第 17 章的 `ToolCallbacks.from` 与 `executeTool` 概念。步骤：打开 `agents/OrderAgent.java`，找到 `ToolCallbacks.from(...)` 的对象列表；打开 `agentbase/tool/POISearchTool.java`，找到 `searchPOIsByKeyword` 的 `@Tool`/`@ToolParam`；回到 `OrderAgent#executeTool` 找 `case "searchPOIsByKeyword"`；再打开 `DailyAgent#executeTool` 比较 `listUserPOIs` 指向的工具类；最后打开 `FallbackAgent#invoke` 看它的 options 有没有 `toolCallbacks`/`toolContext`。预期观察：能列出至少 5 个注册对象，并指出 `getCalling` 是唯一显式 `name`、`notifyUser` 没有 `case`、同名 `listUserPOIs` 在两个 Agent 指向不同类、Fallback 两者都没有；`executeTool` 的 `switch` 不在执行点复查注册名单。若失败：说明你把提示词里的工具清单当成了分派表——回到 §三、§3.4。清理：无。

### 练习 B：按反序列化器推字段值

前置：读过 §六。给定虚构 JSON 片段：`{"formatted_address":[],"count":12,"toll_road":"  ","biz_ext":[{"rating":"4.8"},{}]}`。步骤：先确定每个字段所在 POJO 与注解（`AmapGeoResponse`/`AmapStep`/`AmapSearchPoi`），再逐个走 `deserialize` 的 token 分支。预期观察：`formatted_address=null`，`count="12"`，`toll_road=Collections.emptyList()`，`biz_ext` 用第一个对象转成 `AmapBizExt`。失败解释：若把空数组统一当 `null`，说明漏看了 `StringOrArrayToStringListDeserializer` 对空串返回空 List 的分支。清理：无。

### 练习 C：三组“提示词 vs 代码守卫”定位

前置：读过 §八。步骤：在 `config/ChatProperties.java` 搜索“必须”“确认”“编造”，各抄一句；然后逐方法读 `service/base/OrderConfirmation.java` 的 `quoted`/`begin`/`CONFIRM`/`CONSUME`/`isConfirmed`，再看 `OrderTool#markOrderReadyForCreate/#createOrder` 与 `OrderAgent#handleNotifyUser`；最后在 `ChatRequestRunner`、`TicketTool`、`OrderSearchTool` 找到 `assertOwned`/`USER_ID` 归属检查。预期观察：能填一张三列表（提示词句、代码方法、守卫的状态/例外），并说清 `begin` 写 `break=yes`、`CONFIRM` 才写 `resuming`、15 分钟是对 `quoteCreatedAt` 的年龄检查而非 break TTL、`markOrderReadyForCreate` 只读文本、`consume` 才是真门禁、`POST /order` 不走该协议。失败解释：若把“确认后调用 markOrderReadyForCreate”当成守卫，说明把提示词流程误当实现。清理：无。

### 练习 D：城市码读/写接线核查

前置：已读第 14 章关于墓碑的结论。步骤：在 `GeoRegeoTool#geo` 找到 `getCityCode` 调用；在 `CityCodeUtil#getCityCode` 看 key 如何由 `trim().toLowerCase(Locale.ROOT)` 得到、确认“杭州”不会被转成拼音，并标出**先 GET 版本 key 再 GET 数据 key**；列出 7 天/`__NULL__`/墓碑 5 秒与回源 Lua；全仓检索 `invalidate(` 与 `CityCodeMapper` 的主代码调用点（注意与 `OrderGeoPool#invalidate` 同名方法区分）。预期观察：能写出“读已接线、写仅测试调用”、“中文 key 仍是中文”、“GET 顺序是版本→数据”，并区分 Redis miss、Redis/DB 异常、Lua 返回 0 与响应丢失——条件回填只在 Redis 内原子、不是跨库事务。失败解释：若把“杭州”写成 `hangzhou` 或把测试调用写成生产调用，回到 §4.2、§9.1 核对。清理：无。

### 11.1 测试阅读卡（逐类逐方法，实际运行：无）

下列卡片均为**只读测试源码**的结论，不代表执行通过。检索范围为 `src/test/java/com/fancy/rideagenthub/`；测试目录里**没有**直接测三个反序列化器、`CurWeatherTool`、`POISearchTool` 或三个 Agent `executeTool` 的类。

| 测试类 | 门控 | 真实/替身 | 与本章相关的方法与主要断言 | 资源与清理 | 未覆盖 / 未知 |
|---|---|---|---|---|---|
| `RedisCacheRegressionTest` | 类级 `@EnabledIfSystemProperty(named="test.redis.port")`，不设属性整类跳过 | 真实 Redis（Lettuce）、真实 Lua；`CityCodeMapper`/`MysqlMemory`/`ChatManager` 为 Mockito | `cityMissCannotOverwriteTombstoneOrRefillAfterTombstoneExpires`：mock `selectOne` 回调 `cities.invalidate(city)`，断言返回 `"old"`、数据 key 值为 `__TOMBSTONE__`、模拟墓碑过期后删 key 再查仍返回 `"old"` 且不回填；`cityCachePreservesNullSentinelAndIsIndependentOfDefaultLocale`：默认 Locale 改 `tr-TR`，断言 key 一致、带空格/小写输入都返回 null 且 `verify(mapper,times(1))` 只查一次 DB、数据 key 写 `__NULL__`，`finally` 恢复 Locale | `@BeforeAll` 建连接、`@AfterAll` 销毁、`@AfterEach` 按实例 `keys` 列表 `redis.delete(keys)`；两个城市码用例各自把**数据 key 与版本 key**加入清单（`:200-202`、`:226-228`）；`CityCodeUtil` 只用这两个 key（`__NULL__`/`__TOMBSTONE__` 是 value 不是独立 key），**不存在遗漏的暂存 key** | 真实 `CityCodeMapper`/MySQL 回源、7 天/5 秒 TTL 数值本身、版本 key 内容、并发写入竞争、`invalidate` 主代码调用链；未运行 |
| `RideAgentHubApplicationTests` | `@SpringBootTest`，无属性门控 | 真实 Spring 上下文 | 唯一方法 `getLastN` 只 `log.info(messageMemory.get(...))`，**无断言**；导入了 Amap/和风 POJO 与天气服务但未使用 | 无生命周期注解 | 任何 amap/qweather 调用、反序列化、工具调用；未运行 |
| `SecurityRepairRegressionTest` | 无类级门控 | 依赖全 Mockito（含 `AmapRouteService`/`AmapGeoRegeoService`，Redisson 客户端来自 `OrderLockTestSupport.availableClient()` 的 mock，**非真实 Redis**）；`RideOrderServiceImpl` 手工 new | `missingRouteDoesNotBreakOrderDetail`（真实 service + mock mapper，路线缺失时详情 `estRoute` 为 null）、`missingPoiIsNormalBusinessResult`（**真实 `UserPOITool`** + mock `UserPOIService`，返回含“未找到”）、`actualToolSchemaUsesDispatchName`（**真实 `ToolRepPointerTool`** + mock 存储，`ToolCallbacks.from(tool)[0]` 的 name 为 `getCalling`）；其余为订单/记忆安全断言 | `@BeforeAll` 初始化四个实体表元数据、`@BeforeEach` 设 `UserTokenContext(userId=1,role=USER)` 并反射注入 mock、`@AfterEach` `UserTokenContext.clear()`；无 Redis key 产生 | 真实 HTTP、`executeTool` 各 case（本类只断言 schema 名，未调用工具执行）、提示词解析；未运行 |
| `ChatServiceRegressionTest` | 无门控 | 协作者全 mock（三个 Agent、`MessageMemory`、`ChatModel`、`ClassificationCache`、`ChatRateLimiter` 等）；唯一真实对象是 `new ChatServiceImpl(...)` 与 Reactor Sink | 12 个 `@Test`：限流拦截、命中缓存跳过模型、模型异常仍完成、空/非法分类不缓存、标签规范化后缓存、非法缓存回源、活跃订单守卫、resume 限流、空 prompt error、null resume、空历史提示词、活跃订单保持路由 | `@BeforeEach` 全量构造 mock 并 stub `tryAcquire`/`memory.get`，**无 `@AfterEach`**，无 `UserTokenContext` 设置/清理 | 外部工具调用、Agent `executeTool` 分派、真实 Redis/模型/记忆；未运行 |
| `ChatGuardRegressionTest` / `ChatGuardRedisIntegrationTest` | 前者无门控；后者 `@EnabledIfSystemProperty("test.redis.port")` | 前者 mock `StringRedisTemplate`/`ValueOperations`（HashMap 模拟）；后者真实 Redis | 前者 7 法：`\0` 不合并 context/prompt、null/空 context 分键、路由/上下文/prompt 三元组隔离、TTL=0 禁用不访问 Redis、空分类不写、Redis 异常降级、匿名/禁用不访问 Redis；后者 3 法：并发配额 `accepted==7,zCard==7,TTL>0`、窗口过期后拒绝、分类缓存 `21500<ttl<=21600` 且 key 隔离 | 集成类 `@BeforeAll`/`@AfterAll` 连接管理、`@AfterEach` 按 `keys` 列表删除；**仅分类缓存用例用 `keys(CHAT_CLASSIFY_CACHE_PREFIX+"*")` 前缀收集并删除全部匹配 key**，其余两用例加入显式 key | 外部工具、Agent；TTL 数值仅在集成类断言；前缀删除的影响范围；未运行 |
| `GeoPoolRegressionTest`、`OrderLockRegressionTest`、`OrderGrabRegressionTest`、`OrderTimeoutRegressionTest`、`OrderGrabIntegrationTest` | 前四无门控；末者 `@EnabledIfSystemProperty("test.mysql.port")`（`test.redis.port` 被读取但不作为门控） | Amap 服务仅作为构造器 mock 参数，**无 `when`/`verify`、无 Amap 行为断言** | 分别测附近订单回退/分页与司机定位边界（`locationValidationUsesRedisLatitudeBounds` 实际校验 `DriverLocationServiceImpl` 的纬度边界、driver mock 无交互）、锁事务回调、抢单并发、超时重排；抢单集成测试真实 MySQL/Redis 下并发唯一胜者、固定 4 个 key 的清理 | 各类生命周期：部分无 `@AfterEach`；`OrderGrabIntegrationTest` `@BeforeAll` 建独立 schema+执行 init.sql 两表、`@AfterAll` 关连接并 `DROP DATABASE`、`@BeforeEach` 清两表并造 2 司机/2 订单、`@AfterEach` 按固定 4 个 status/active key 删除（非前缀扫描） | 高德/和风真实调用与反序列化；`locationValidationUsesRedisLatitudeBounds` 名称含 Redis 但无 Redis 交互；未运行 |

结论：能证明的只是测试源码里写了哪些断言、门控与清理范围；**没有测试直接覆盖三个反序列化器、`CurWeatherTool`、`POISearchTool` 或任一 Agent 的 `executeTool`**（全测试目录精确检索 0 命中；`@Disabled` 亦为 0），唯一近似命中是 `SecurityRepairRegressionTest` 对 `getCalling` schema 名的断言与对 `OrderAgent#handleToolCalls` 的反射调用，均非 `executeTool` 直接测试。不能由测试源码推出断言实际通过、真实外部服务行为或超时/并发下的真实结果。**本章没有运行任何测试**。

---

## 十二、常见误区

1. **“提示词写了就必须调用”**：错。提示词是行为指引；`notifyUser` 没有 `executeTool` case、模型是否调用 `getCalling` 没有代码保证。
2. **“注册了就一定能调到 / 没注册绝不会被写出”**：都错。模型正常按注册 schema 生成名字，但代码 `switch` 不在执行点复查注册名单；未注册名字若被写出会落入 `default` 的 `"Unknown tool: ..."`，不能断言模型绝不可能猜到。`notifyUser` 有注册却无 case、由前置特判，也说明“三层齐备才执行”不成立。
3. **“同一个工具名就是同一个权限”**：错。`listUserPOIs` 在 OrderAgent 是只读工具，在 DailyAgent 是可写工具。
4. **“经纬度顺序反正差不多”**：错。高德两位/六位都是经度在前；天气工具方法参数是 `(lat, lon)`，服务层再拼成 `lon,lat`；写反会查错位置。城市码 key 里“杭州”不会变成 `hangzhou`。
5. **“外部返回 `[]` 就是空字符串/null 随便处理；只有 List 反序列化器会抛异常”**：错。三个反序列化器行为不同；`tollRoad` 空串得到空 List 而非 null；`StringOrArrayToStringList` 对对象显式抛错，`ObjectOrEmptyArray` 的 `convertValue` 也可能抛，坏 JSON 三者都可能抛。
6. **“502 就是对端没执行；循环三次就是三次 HTTP；三种失败同一文案”**：错。key/参数在 HTTP 前校验可能零 HTTP；业务 502 第 3 次原样抛，普通运行时异常第 3 次才包装“重试3次仍失败”；失败与超时都无法证明对端状态。
7. **“高德失败就用和风顶上；所有天气失败都是同一种文本”**：错。源码没有跨服务降级；天气实时只在首次 `Optional.empty()` 后再试一次、异常不重试；预警空结果返回“当前无天气预警”，与数据不可用不可区分。
8. **“`break` 15 分钟过期 / `markOrderReadyForCreate` 是创建守卫”**：错。`begin` 写 `break=yes`，`CONFIRM` 才写 `resuming`；15 分钟是 `CONFIRM`(仅 yes)/`CONSUME` 对 `quoteCreatedAt` 的年龄检查，不是 break TTL；`markOrderReadyForCreate` 只读 `isConfirmed` 返回文本，真实消费在 `createOrder#consume`。
9. **“事件发出即客户端收到 / 失败后必然有 message、绝无 confirm”**：错。所有事件都是 `tryEmitNext` 尝试，返回值未检查；同批工具、流错误、断连与历史确认状态都可能改变结果。
10. **“`noRoute=true` 是纯本地无副作用”**：错。复用分支仍写价格与拆分、删 `ReadyforRoute`、调 `quoted`，会改变后续确认状态。
8. **“城市码墓碑在所有写路径都会写”**：错。当前 `invalidate` 无主代码调用点，只在测试中出现。
11. **“乘客位置是工具入参”**：错。天气/POI 工具的经纬度来自模型参数；乘客位置只进系统提示词上下文。
12. **“Agent 的确认守卫覆盖直接下单”**：错。`POST /order` 只要求登录、`userId` 由服务端覆盖，不走 Agent 报价确认；订单归属由 Service 校验。
13. **“测试里 mock 了高德，就等于验证了高德集成”**：错。mock 只证明调用方在缺失结果时不崩，不证明真实 HTTP、超时或字段漂移行为。

---

## 十三、一页复习

**四层边界**：提示词（引导模型）≠ schema（`ToolCallbacks.from` 生成）≠ 分派（`executeTool` 的 `switch`）≠ 授权（`ToolContext` 的 `userId`/`chatId` + `assertOwned` + 状态守卫）。

**三个有工具的 Agent 构造 `toolContext` 并手写 `executeTool`，Fallback 两者都没有**；`OrderAgent`/`DailyAgent`/`SupportAgent` 都读位置注入提示词（`buildLocationString`），Fallback 只读时间。`notifyUser` 特判不走 `switch`；普通 `switch` 不复查注册名单。

**三条链一句话版**：
- `geo`：模型给地址/城市 → `CityCodeUtil`（先 GET 版本再 GET 数据；杭州仍写作 `amap:city_code:杭州`；7 天 / `__NULL__` / 墓碑 5 秒 / MySQL 回源）→ 高德 `/geocode/geo`（经度无入参）→ `Geocode` → `"location=..."`；`tool_start`/`message` 都是 `tryEmitNext` **尝试**发出。
- `getEstRouteAndPrice`：Redis hash 起终点 → 高德 `/direction/driving`（`%.6f`，经度在前；循环最多 3 次，按异常类型决定重试与文案）→ `EstRouteVO` → 本地算价 → 成功写 Mongo/Redis/`quoted`；`noRoute=true` 复用分支仍写价格、删 `ReadyforRoute`、调 `quoted`，不是无副作用。
- `getWeatherByLatLon`：模型给 `(lat,lon)` → 和风 `lon,lat`（`%.2f`）→ `QweatherNow` → 仅首次 `Optional.empty()` 再试 1 次（异常不重试）→ 温度/风级文本；预警空结果返回 `"当前无天气预警"`。

**反序列化速记**：`StringOrEmptyArrayToNull`（String，`[]`→null，对象→null）；`StringOrArrayToStringList`（`tollRoad`，空串→空 List，对象→显式抛 `JsonMappingException`）；`ObjectOrEmptyArray`（`biz_ext`/`indoor_data`，空数组→null，非空数组取首元素，对象 `convertValue` 不兼容时**也可能**抛）。`Geocode`/`AddressComponent#getCityName` 只认 String，`AddressComponent` 的 `neighborhood`/`building` 才从 Map 取 `name`。和风 POJO 无自定义反序列化器。

**失败速记**：key/参数校验在 HTTP 之前；HTTP/传输错 → 502；业务码失败 → `Optional.empty()` → 固定文本；业务 502 第三次循环**原样抛**，普通运行时异常第三次才包装“重试3次仍失败”；无跨服务降级；失败文本/502 不证明对端未执行；`tryEmit*` 未检查返回值，工具失败文本 ≠ 客户端一定看到 `error`/`message`。

**提示词 vs 代码**：身份归属靠 `assertOwned`/`USER_ID`；`begin` 写 `break=yes`，`CONFIRM` 才写 `resuming` 与许可，15 分钟是 `CONFIRM`(仅 yes)/`CONSUME` 对 `quoteCreatedAt` 的年龄检查、不是 break TTL；`markOrderReadyForCreate` 只读文本、`consume` 才是真实门禁；工单权限靠 Service 归属查询；`POST /order` 不走 Agent 确认。外部数据与用户文本都不可信，服务端身份与状态才可信。

**必须记住的常量**：`amap.timeout`/`qweather.timeout` 默认 5000ms；城市码 7 天、墓碑 5 秒；`callId` 是 `getCalling` 的真实参数名；`ToolContext` 三 key 为 `toolListener`/`userId`/`chatId`。

---

## 十四、自测与答案

先自己写推导，再展开对照。答案给出理由与源码入口，不只给结论。

<details>
<summary>1. 提示词要求、schema 注册、executeTool 有 case、服务端授权——这几层是什么关系？“三层都具备才可能执行”对吗？</summary>

几层不能互相替代，但也**不是“同时具备才可能执行”的合取条件**。提示词（`config/ChatProperties.java`，如 ORDER 的 D 段要求 `notifyUser`）只引导模型；schema（`ToolCallbacks.from(...)` 生成）告诉模型有哪些名字与参数；`executeTool` 的 `switch` 是代码收到某名字后的普通分派，它**不在执行点重新核对名字是否在注册名单里**；授权来自 `ToolContext` 与工具内检查。

正常路径是模型按注册 schema 生成名字、代码在 `switch` 中分派，但不能反推：① `notifyUser` 有提示词、有注册，却没有 case，它由 `handleToolCalls` 前置特判，说明“有 case”不是必要条件；② `RegeoTool`/`ChatStatusTool` 有 `@Tool` 却未注册也无 case，不能断言模型绝不可能写出这个名字——它若被写出，当前代码会落入 `default` 的 `"Unknown tool: ..."`。所以准确说法是“名字从哪来、代码收到后怎样、服务端信谁”三件事分开看，而不是“三层齐备才执行”。参见 §三、§3.4。
</details>

<details>
<summary>2. `geo` 工具的 `city` 参数、`getCalling` 的 `callId`、`ToolContext` 的 `userId` 分别来自哪里？如果模型在参数里塞了别人的 userId 会怎样？</summary>

`city` 和 `callId` 来自模型的工具参数 JSON；`userId` 来自服务端 `UserTokenContext` 经 `ChatController` → Agent → `ToolContext` 注入，不是模型参数。`getCalling` 还用 `ToolContext` 的 `chatId` 做 `assertOwned(userId, chatId, false)`，再按 `callId` + `chatId` 双条件查 `ToolResponseMemory`。模型在参数里塞“别人的 userId”没有对应入口——工具只读 `ToolContext` 里的服务端值；提示词要求“必须查真实历史”只是行为约束，真正的跨会话隔离由 `assertOwned` + 双条件查询完成（第 19 章）。参见 §3.4、§8.1。
</details>

<details>
<summary>3. 虚构响应里 `formatted_address: []`、`count: 12`、`toll_road: ""`、`biz_ext: [{"rating":"4.8"},{}]` 分别得到什么 Java 值？为什么不能统一当成 null？</summary>

`formatted_address` 用 `StringOrEmptyArrayToNullDeserializer`：空数组→null；`count` 同反序列化器，数字→`asText()` 即 `"12"`；`toll_road` 用 `StringOrArrayToStringListDeserializer`，空字符串 trim 后→`Collections.emptyList()`（**不是 null**）；`biz_ext` 用 `ObjectOrEmptyArrayDeserializer`，非空数组取第 0 个元素转成 `AmapBizExt`。不能统一当 null，因为三个反序列化器契约不同。关于抛错要分清：`StringOrArrayToStringListDeserializer` 对对象/数字**显式** `throw JsonMappingException`；`ObjectOrEmptyArrayDeserializer` 的非对象分支静默返回 null，但对象分支的 `convertValue` 遇到不兼容结构也可能抛；`readTree` 读坏 JSON 三者都可能抛。Spring/RestClient 最终包装成什么都不推断、未实测。依据：三个反序列化器源码与 `AmapStep`/`AmapSearchPoi` 注解。均为静态推导，未运行。参见 §六。
</details>

<details>
<summary>4. 高德地理编码返回 HTTP 200 但 `status="0"`，和返回 HTTP 504，客户端看到的文本一样吗？</summary>

不一样（按源码静态推导）。`status="0"` 使 `isSuccess()` 为 false，服务记 warn 返回 `Optional.empty()`，`GeoRegeoTool#geo` 返回 `"未查询到坐标"`；HTTP 504 由 `.onStatus` 抛 `BusinessException(502,"高德 API HTTP 请求失败: ...")`，工具没有 catch，冒泡到 `executeTool` 的 `catch` 兜底，返回 `"Tool execution failed: ..."`。两者都只是**工具回执文本**：进入 history 后由模型生成 `message`（`tryEmitNext` 尝试推送，返回值未检查），客户端是否收到、文案如何都不保证；若同批工具、流错误或断连不同，结果也会不同。504 也不证明对端没执行。依据：`AmapGeoRegeoService#getGeo`、`GeoRegeoTool#geo`、`OrderAgent#executeTool`。参见 §4.4、§7.2。
</details>

<details>
<summary>5. 路径规划“失败重试 3 次”和天气工具“失败再调一次”分别在哪个类？为什么不能说“外部调用都有重试和降级”？</summary>

路径循环在 `AmapRouteService#getDrivingRouteEst`：`for (attempt=1..3)`，`BusinessException` 只在 `code>=500` 且 `attempt<3` 时 `continue`，第 3 次原样 `throw e`（不是统一文案）；普通 `RuntimeException` 第 3 次才包装为 `BusinessException(502,"高德路径规划失败(重试3次仍失败)")`；`code<500` 更早抛出；key/参数校验在 HTTP 之前，可能一次 HTTP 都没有。天气重试点在 `CurWeatherTool#getWeatherByLatLon`：只有首次返回 `Optional.empty()` 才再调一次，抛异常不进入该分支。其余服务无重试，也没有跨服务降级（高德失败不改查和风）；`TrafficTool` 纯本地 `switch`。所以“重试”是按异常类型/时机的局部行为，不是统一策略。参见 §五、§7.1、§10.2。
</details>

<details>
<summary>6. 提示词说“未确认不得创建订单”，代码里哪三道检查真正阻止未确认创建？直接 `POST /order` 受它们约束吗？</summary>

按真实门禁回答，而不是按提示词流程回答：

1. `notifyUser` 由 `handleNotifyUser` 前置处理：`orderConfirmation.begin` 检查当前 `quoteId` 与 `ReadyforConfirm` 存在，写 `pendingToolCall` 与 `break=yes`（**不是 resuming**），返回编号；随后序列化、`saveNewMessages`、再写 `break=yes`、**尝试**发 `confirm` 事件。任一步失败可能留下部分状态。
2. 用户点确认后 `ChatServiceImpl#resume` → `OrderAgent#resume`（要求 `break=resuming`）——注意 `break=resuming` 是 `CONFIRM` Lua 在接受/拒绝时写入的：`CONFIRM` 比编号，`yes` 且 `now - quoteCreatedAt > 900000` 才拒，拒绝不查年龄；`resuming` 且同决定可重放不重写；接受写 `confirmedQuote`/`ReadyForCreate`。
3. 真正阻止未确认创建的是 `createOrder`：先快速看 `ReadyForCreate` 是否存在，再 `orderConfirmation.consume()`——`CONSUME` Lua **再次**用 JVM 当前时间比 `quoteCreatedAt`（>900000 返回 0），且要求 `confirmedQuote`、`ReadyForCreate` 都等于当前 `quoteId`，通过才 `HDEL` 两个字段并继续建单。15 分钟是这两处对 `quoteCreatedAt` 的年龄检查，不是 `break` 字段自己的 TTL。

`markOrderReadyForCreate` 只调 `isConfirmed` 返回文本，**不是** `createOrder` 的必需前置守卫；提示词写“确认后调用它再 createOrder”只是流程约束。直接 `POST /order` 的 `RideOrderController#create` 只有 `@RequirePermission`（仅登录）并覆盖 `userId` 为令牌值，不走 `quoted`/`begin`/`CONFIRM`/`consume`；归属由 Service 层校验。这里没有参数快照绑定，也不保证用户一定看到摘要。参见 §8.2、第 18 章。
</details>

<details>
<summary>7. 乘客位置在城市码/天气/POI 工具链里是什么角色？它是这些工具的坐标入参吗？</summary>

不是。`UserServiceImpl#getUserLocation` 从 Redis `user:loc:{userId}` Hash 读 `latitude`/`longitude`/`address`；按源码 `OrderAgent#invoke`（`:74`）、`DailyAgent#invoke`（`:71`）、`SupportAgent#invoke`（`:68`）都调用本类 `buildLocationString(userId)` 格式化进各自系统提示词的位置占位，这是**上下文**；`FallbackAgent#invoke` 只格式化当前时间、不读位置（审查意见曾说只有两个 Agent，与 `SupportAgent.java:68,204` 的源码不符，已在证据 C5-R2 记录，以源码为准）。`GeoRegeoTool`/`POISearchTool`/`CurWeatherTool`/`POIAroundTool` 的经纬度与城市名都来自模型工具参数；整份 `agentbase/tool/` 目录检索 `UserService` 为 0 命中。乘客位置 Hash 没有 TTL、没有 MySQL 副本、`POST /user/loc` 无 `@Valid`（第 9 章）。参见 §4.1、§3.4、§9.1。
</details>

<details>
<summary>8. 城市码缓存“读已接线、写未接线”分别指什么？墓碑 5 秒与缓存 7 天冲突吗？</summary>

读已接线：`GeoRegeoTool#geo` 调 `CityCodeUtil#getCityCode`，规范化 `trim().toLowerCase(Locale.ROOT)`（中文“杭州”仍是“杭州”，虚构码 `571` 仅为示意），**先 GET 版本 key、再 GET 数据 key**，走空值哨兵、MySQL `bus_citycode` 回源与 Lua 条件回填。写未接线：`CityCodeUtil#invalidate`（`INCR` 版本 + `SET __TOMBSTONE__ EX 5`）在 `src/main/java` 只有定义，测试里由 mock 回源回调触发，主链无调用方。两者不冲突：`__NULL__` 是稳定的“DB 确实没有”结果，存 7 天；墓碑是“此刻有写入、缓存不可信”的瞬态，只存 5 秒，命中时查 DB 但不回填，防止旧快照把值续成 7 天。TTL 是代码常量，不是配置项。还要区分 Redis 正常 miss / Redis 异常 / DB 失败 / Lua 返回 0 / 响应丢失——方法返回值可能相同但键状态不同，且那条 Lua 只在 Redis 内原子比较版本与 `SET NX`，不是跨库事务。参见 §4.2、§9.1/§9.2、第 14 章。
</details>

---

## 十五、源码核验入口与导航

- **本章主讲源码**：`config/ChatProperties.java`；`agents/{Order,Daily,Support,Fallback}Agent.java`；`constant/ToolContextKeyConstants.java`；`agentbase/tool/{GeoRegeoTool,POISearchTool,POIAroundTool,CurWeatherTool,TrafficTool,OrderTool,ToolRepPointerTool,ToolNotifySupport,UserPOITool,UserPOISearchTool,RagTool}.java`；`agentbase/amap/service/{AmapGeoRegeoService,AmapRouteService,AmapSearchService}.java`；`agentbase/amap/config/{AmapProperties,AmapConfig}.java`；`agentbase/qweather/service/{QweatherWeatherService,QWeatherLifeService,QweatherPOIService}.java`；`agentbase/qweather/config/{QweatherProperties,QweatherConfig,GzipDecompressingInterceptor}.java`；`agentbase/amap/util/jackson/{StringOrEmptyArrayToNullDeserializer,StringOrArrayToStringListDeserializer,ObjectOrEmptyArrayDeserializer}.java`；`agentbase/amap/util/citycode/CityCodeUtil.java`；`util/RedisScripts.java`；`constant/RedisKeyConstants.java`；`service/impl/UserServiceImpl.java`；`controller/UserController.java`；`exception/GlobalExceptionHandler.java`；`src/main/resources/application.yaml`（只看键名与默认值，不输出密钥）。
- **调用方与守卫**：`controller/ChatController.java`、`controller/RideOrderController.java`、`service/base/ChatRequestRunner.java`、`service/impl/ChatServiceImpl.java`、`service/base/OrderConfirmation.java`、`agentbase/chatinfo/ChatManager.java`、`agentbase/memory/ToolResponseMemory.java`、`domain/dto/AgentEvent.java`。
- **测试源码**：见 §11.1 逐类阅读卡，全部只读、**未运行**。
- **上一章**：[第 19 章 三层记忆、消息窗口与工具结果反查](04-memory-and-tool-results.md)——工具回执的保存与 `callId` 反查。
- **相关专题**：[第 17 章 分类路由与工具循环](02-routing-and-tool-loop.md)、[第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)、[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)、[第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)。
- **下一章**：[第 21 章 RAG 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md)（R1，已通过复审）——`RagTool` 的检索原理与答案核对；[R2 索引同步与预算](../05-rag/02-index-sync-and-budget.md) 再讲索引同步与预算（已通过复审）。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、脚本与测试写完整仓库相对路径。
