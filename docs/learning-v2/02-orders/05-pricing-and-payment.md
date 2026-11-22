# 第 11 章 计价与支付：服务端公式、金额来源与验单门禁

> O1 把一单从创建走到支付，O4 解释了未支付超时；本章拆开金额：**里程/秒数从哪来、公式怎么算、哪些字段被服务端覆盖、哪些业务输入仍会影响价格、支付接口到底能证明什么**。核心结论先给出：客户端预估价不是最终价；但车型、加急、坐标、真实轨迹和结束时间等输入会影响服务端计算；当前默认支付配置没有可信渠道，只能拒绝确认支付。
>
> 继续使用虚构乘客林小雨、司机陈师傅。订单号、坐标、里程、时间和金额都是示意；本章没有调用高德、支付渠道、模型，也没有启动服务或运行测试。

## 学习目标

读完本章，你应该能：

1. 手算一次报价和一次结算费用，写出起步、超起步、远途、秒向上取分钟、车型倍率、加急固定费/动态倍率的计算顺序。
2. 区分预估、下单重算、修改目的地、结束行程四条金额来源链，说出 DTO、高德、Mongo 轨迹、真实轨迹和时间字段的落点。
3. 解释 `est_price`、`real_price`、四个明细字段、`price_radio` 的含义，以及 BigDecimal/DECIMAL 的舍入边界。
4. 追踪支付的身份归属、状态守卫、渠道参数、订单锁、verifier、条件更新、状态缓存和队列取消；区分默认 503 门禁与真实支付能力。
5. 计算取消费，说明它与行程账单在字段上的归属差异。
6. 阅读相关测试的 mock 和断言，区分源码推导、测试阅读与实际运行。

## 前置知识与阅读时间

- 必读：[第 7 章 订单生命周期](01-order-lifecycle.md)——estimate/create/finish/pay/cancel 端点、状态机、事务/锁总表。
- 推荐：[第 8 章 抢单与并发](02-grab-and-concurrency.md)——订单锁与条件更新；本章支付/取消也使用它，O1 已建立锁的基础。
- 推荐：[第 10 章 超时与恢复](04-timeouts-and-recovery.md)——支付超时与支付竞争的完整时序；本章 §4.4 给出自足的可达简表。
- 可选：[第 9 章 位置与发现](03-location-and-discovery.md)——修改目的地后附近池为何不需要因终点变更失效。
- 阅读时间：约 90～110 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**：正文是源码静态阅读；测试部分是“读过断言，未运行”；没有外部服务联调。

---

## 零、先记住八个事实

1. **共享公式**：`service/base/RidePricing.java#calculate` 是预估、下单重算、Agent 算价、修改目的地、结束行程的同一计价入口。
2. **总价计算顺序**：`(基础里程费 + 时长费 + 远途费 + 固定加急费) × (车型倍率 + 加急动态倍率)`；车型/加急倍率在分项加总后乘。
3. **明细字段**：`estPriceBase` 是“起步价 + 超起步里程费”，不是乘倍率后的金额；`priceExpedited` 只存固定 5.00，不包含加急 +0.20 倍率。
4. **中间值先算，最后 setScale**：`RidePricing` 各分项最后按 2 位 `HALF_UP` 输出，但总价用未舍入中间值乘倍率后再舍入；因此“明细相加”不一定等于总价。
5. **距离来源**：预估/创建/改目的地用高德第一条驾车路径 `distance/1000`（3 位 HALF_UP）；结束行程解析真实轨迹，只有没有两个可解析点形成线段时才回退起终点直线；可解析不等于地理有效或可信。
6. **结束时间**：`FinishRideReqDTO.arriveTime` 实际被 `finishRide` 用作 `finishTime`；为空才用 `BusinessTime.now()`。源码不校验它与 `pickup_time` 的先后。
7. **支付参数只有渠道和交易号**：`PayOrderReqDTO` 没有金额字段；verifier 收到的是 DB 中订单 id、userId、渠道、交易号和 `realPrice`。
8. **默认无真实支付**：`PaymentConfig#unavailablePaymentVerifier` 直接抛 503；仓库没有 `implements PaymentVerifier` 的渠道实现，也没有退款、对账、幂等实现可描述。

---

## 一、业务故事：报价到账单

### 1.1 阶段总览

| 阶段 | 入口 | 距离/时间来源 | 车型/加急来源 | 结果落点 |
|---|---|---|---|---|
| 报价 | `POST /order/estimate` | 高德驾车规划，不落库 | 请求 DTO | 返回 `PriceEstimateVO`；不写 DB |
| Agent 报价 | `OrderTool#getEstRouteAndPrice` | `ReadyforRoute` 未就绪先拒绝；缺 trace/距离/时间才强制规划；否则可 `noRoute` 复用缓存 | Redis 订单槽位 | Redis 价格字段；重规划分支另写 Mongo 预览；两个分支都触发 `quoted` |
| 创建订单 | `POST /order` 或 `OrderTool#createOrder` → `RideOrderServiceImpl#createOrder` | 重新调高德 | DTO/Redis | MySQL 订单 + Mongo 新轨迹；客户端价格/倍率/轨迹被覆盖 |
| 修改目的地 | Agent 工具 `OrderSearchTool#modifyOrderDestination` | 用原起点到新终点重新调高德 | DB 订单的车型/加急 | MySQL 目的地/预估金额 + Mongo 新轨迹；旧轨迹 best-effort 删除 |
| 结束行程 | `POST /order/driver/finish` | `realPolyline` 逐段 Haversine；无两个可解析点形成线段时才用起终点直线 | DB 订单的车型/加急 | Mongo `realPolyline` + MySQL 50/账单 + Redis 状态/队列 |
| 支付 | `POST /order/{orderId}/pay` | 不重新计价 | 不重新计价 | verifier 通过且条件更新后 `50→60` |

### 1.2 HTTP 报价

```http
POST /order/estimate
Authorization: Bearer <TOKEN>
Content-Type: application/json

{
  "startLng": 120.000000,
  "startLat": 30.000000,
  "endLng": 120.090000,
  "endLat": 30.008000,
  "vehicleType": 1,
  "isExpedited": 0
}
```

入口 `RideOrderController#estimate` 只要求登录（`@RequirePermission` 无 value），把请求坐标装进两个 `Point`。`estimatePrice` 校验：

- 起终点和四个经纬度非空；
- 纬度 `[-90,90]`、经度 `[-180,180]`；
- 调用 `AmapRouteService#getDrivingRouteEst`；
- 用返回的 `estKm/estTime` 和请求的 `vehicleType/isExpedited` 调 `RidePricing.calculate`。

报价结果不写 MySQL，也不写 Mongo。高德返回值受实时路况、路线策略、服务可用性影响；同一坐标两次报价可能不同，且本章未调用真实高德验证。

### 1.3 创建订单重算

`CreateOrderDTO` 有 `estPrice/estDistance/radio/mongoTraceId`，HTTP 和 Agent 都可能传入，但 `createOrder`：

- HTTP Controller 先用 token 覆盖 `userId`；Service 在已登录时再覆盖一次。
- `vehicleType` 为空设 1；`isExpedited` 为空设 0；`isReservation` 为空设 0；普通单清空 `scheduledTime`。
- 调 `validateAndSupplement` 补地址、校验必填和坐标/枚举。
- 重新生成 UUID `traceId`，重新调高德，把 `estDistance` 覆盖为高德返回的 `estKm`。
- 重新 `calculatePrice`，覆盖 `estPrice` 和 `radio`。
- 写 Mongo 新轨迹，再插 MySQL；insert 失败时尝试删除新 Mongo（O1 已审）。

因此“客户端输入完全不可影响订单价格”不准确：坐标、车型、加急来自 DTO/Agent 槽位，会影响路线或价格；预约时间影响预约业务，不进入当前计价公式。客户端的预估金额、倍率、轨迹 ID 不会成为最终字段。

### 1.4 结束行程生成账单

司机调用 `POST /order/driver/finish`，DTO 是 `FinishRideReqDTO(orderId,endLat,endLng,endAddress,realPolyline,arriveTime)`。`finishRideUnderLock`：

1. 订单锁、订单存在、司机归属、状态 40；
2. `realPolyline` 必填；
3. 调 `OrderRouteService#updateRealPolyline` 更新 Mongo（O1 已述：不检查 matched/modified）；
4. `finishTime = arriveTime != null ? arriveTime : BusinessTime.now()`；
5. `realDistance = calcPolylineDistanceKm(realPolyline)`；只有返回 null（通常是没有两个可解析点形成线段）且起终点坐标完整时，才用 Haversine 直线距离并 2 位 HALF_UP；
6. `durationSec = Duration.between(pickup_time, finishTime).getSeconds()`；
7. `RidePricing.calculate(realDistance, durationSec, order.vehicleType, order.isExpedited)`；
8. 条件更新 `40→50`，写 `finish_time/real_price/四个明细/price_radio/real_distance`，可选更新终点；
9. 成功后同步状态 50、释放司机、登记支付超时（`pay-timeout-minutes` 默认 30 分钟，可配置覆盖）。

字段来源必须区分：`realPolyline` 来自请求，能直接决定里程；`arriveTime` 名字容易误导，实际决定的是结束/计费结束时间；`endLat/endLng/endAddress` 可选，只在真实轨迹缺失时参与直线距离兜底或更新目的地文本/坐标。

---

## 二、计价公式与手算

### 2.1 真实公式

```java
// service/base/RidePricing.java（真实规则节选）
BigDecimal startPrice = new BigDecimal("8.00");
BigDecimal startDistanceKm = new BigDecimal("2.0");
BigDecimal perKmFee = new BigDecimal("2.20");

BigDecimal overStartKm = distanceKm.subtract(startDistanceKm);
if (overStartKm.signum() < 0) overStartKm = BigDecimal.ZERO;
BigDecimal priceBase = startPrice.add(overStartKm.multiply(perKmFee));

BigDecimal longOverKm = distanceKm.subtract(new BigDecimal("20.0"));
if (longOverKm.signum() < 0) longOverKm = BigDecimal.ZERO;
BigDecimal longDistanceSurchargePerKm = perKmFee.multiply(new BigDecimal("0.50"));
BigDecimal priceDistance = longOverKm.multiply(longDistanceSurchargePerKm);

BigDecimal minutes = durationSec.divide(new BigDecimal("60"), 0, RoundingMode.CEILING);
BigDecimal priceTime = minutes.multiply(new BigDecimal("0.50"));

BigDecimal priceExpedited = expedited ? new BigDecimal("5.00") : BigDecimal.ZERO;

BigDecimal vehicleMultiplier = switch (vehicleTypeCode) {
    case 2 -> new BigDecimal("1.60");
    case 3 -> new BigDecimal("2.50");
    default -> BigDecimal.ONE;
};
BigDecimal expediteMultiplier = expedited ? new BigDecimal("0.20") : BigDecimal.ZERO;
BigDecimal finalMultiplier = vehicleMultiplier.add(expediteMultiplier);

BigDecimal subTotal = priceBase.add(priceTime).add(priceDistance);
BigDecimal estPrice = subTotal.add(priceExpedited).multiply(finalMultiplier);
```

规则：

| 项 | 值 | 说明 |
|---|---|---|
| 起步价 | 8.00 元 | 含 2.0 公里 |
| 超起步里程费 | 2.20 元/公里 | 仅超出 2 公里部分 |
| 远途/空驶费 | 超出 20 公里部分 × 1.10 元/公里 | 1.10 是 2.20×0.50 |
| 时长费 | 0.50 元/分钟 | 秒 ÷ 60，`CEILING` 向上取整 |
| 加急固定费 | 5.00 元 | 加急为 1 时加入小计 |
| 车型倍率 | 1=1.00，2=1.60，3=2.50 | null 按 1 |
| 加急动态倍率 | +0.20 | 与车型倍率相加 |
| 总价 | `(base + time + distance + expedited) × finalMultiplier` | 最后 2 位 `HALF_UP` |

字段名容易误解：

- `estPriceBase/priceBase` = 起步价 + 超起步里程费。
- `estPriceDistance/priceDistance` = 远途/空驶费。
- `estPriceExpedited/priceExpedited` = 固定加急费 5.00，不含 +0.20 倍率。
- `estPriceRadio/priceRadio` = 最终倍率，例如“专车加急”是 2.70。

### 2.2 例 1：2 公里、60 秒、快车、不加急

输入 `estKm="2.000"`、`estTime="60"`、vehicleType=1、isExpedited=0。

| 步骤 | 计算 | 中间值 |
|---|---|---|
| 超起步 | `2.000 - 2.0 = 0` | 0 |
| 基础里程费 | `8.00 + 0 × 2.20` | 8.00 |
| 远途 | `max(0, 2.000 - 20.0) × 1.10` | 0 |
| 分钟 | `60 ÷ 60`，`CEILING` | 1 |
| 时长费 | `1 × 0.50` | 0.50 |
| 加急固定费 | 非加急 | 0 |
| 倍率 | 快车 1.00 + 加急 0 | 1.00 |
| 总价 | `(8.00 + 0.50 + 0 + 0) × 1.00` | 8.50 |

输出：`estPrice=8.50`、`base=8.00`、`time=0.50`、`distance=0.00`、`expedited=0.00`、`radio=1.00`。这是“恰好 2 公里不收超起步，恰好 60 秒收 1 分钟”的边界。

如果秒数是 0，`0 ÷ 60 = 0`，时长费 0；如果秒数是 1，向上取整为 1 分钟。

### 2.3 例 2：25 公里、3599 秒、专车、加急

输入 `estKm="25.000"`、`estTime="3599"`、vehicleType=3、isExpedited=1。

| 步骤 | 计算 | 中间值 |
|---|---|---|
| 超起步 | `25.000 - 2.0 = 23.000` | 23.000 |
| 基础里程费 | `8.00 + 23.000 × 2.20` | 58.60 |
| 远途 | `(25.000 - 20.0) × (2.20 × 0.50)` | `5.000 × 1.10 = 5.50` |
| 分钟 | `3599 ÷ 60 = 59.983...`，`CEILING` | 60 |
| 时长费 | `60 × 0.50` | 30.00 |
| 加急固定费 | 加急 | 5.00 |
| 倍率 | 专车 2.50 + 加急 0.20 | 2.70 |
| 总价 | `(58.60 + 30.00 + 5.50 + 5.00) × 2.70` | `99.10 × 2.70 = 267.57` |

输出：`estPrice=267.57`、`base=58.60`、`time=30.00`、`distance=5.50`、`expedited=5.00`、`radio=2.70`。注意 `267.57` 不等于“明细直接相加”，因为明细尚未乘 2.70；总价先加固定加急费再乘倍率。

### 2.4 20 公里边界与远途费

- `D=20.000`：远途为 0；`base = 8 + 18×2.20 = 47.60`。
- `D=20.001`（高德可返回 3 位公里）：远途 `0.001 × 1.10 = 0.0011`，还未舍入；总价会用这个中间值。
- `D=60.000`：超起步 `58 × 2.20 = 127.60`，基础里程费 135.60；远途 `40 × 1.10 = 44.00`；快车不加急、0 秒时总价 179.60。
- 没有距离/时间上限校验；极大值会放大 BigDecimal 结果，但 DB 列是 `DECIMAL(10,2)`，插入可能溢出/失败。实际边界未运行验证。

### 2.5 舍入顺序与明细

`RidePricing` 的返回字段都 `setScale(2, HALF_UP)`，但总价不是“先把每个分项 setScale 后相加”。例如高德距离有 3 位小数时，超起步费和远途费会先保留更多位参与总价；返回的每个明细再各自舍入。加上倍率后，`estPriceBase + estPriceTime + estPriceDistance + estPriceExpedited` 通常不等于 `estPrice`；`priceRadio` 只告诉你倍率，不把明细还原成乘后金额。不要在客户端用明细相加校验总价。

### 2.6 无效输入

| 输入 | 行为 |
|---|---|
| `vehicleType` null | `RidePricing` 按 1；创建订单在调用前也设 1 |
| `vehicleType` 0/4 | `BusinessException(400,"车型无效")` |
| `isExpedited` null | 视为不加急 |
| `isExpedited` 非 0/1 | `BusinessException(400,"加急标记无效")` |
| `estKm/estTime` null、空白、非数字、负数 | `BusinessException(502,"路径规划返回的里程/时长无效")` |
| 距离/时间 0 | 有效；距离 0 收起步价，时间 0 无时长费 |
| 距离/时间科学计数法字符串 | `new BigDecimal` 可解析；是否会出现取决于高德/调用方，未实测 |

---

## 三、四条金额来源链

### 3.1 HTTP 报价：不落库

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
var estRoute = amapRouteService.getDrivingRouteEst(
        startPoint.getLng().doubleValue(),
        startPoint.getLat().doubleValue(),
        endPoint.getLng().doubleValue(),
        endPoint.getLat().doubleValue()
);
return calculatePrice(estRoute.getEstKm(), estRoute.getEstTime(), vehicleType, isExpedited);
```

- 输入：Controller 的四个坐标、车型、加急。
- 外部：高德；`getDrivingRouteEst` 只取第一条路径；`estKm = path.distance()/1000`，3 位 `HALF_UP`；`estTime = path.duration()` 字符串，代码按秒使用。
- 落点：无 DB/Mongo/Redis；仅返回 VO。
- 错误要按来源分：
  - Service 入参坐标非法：`IllegalArgumentException` → 400。
  - `AmapRouteService#fetchDrivingRouteResponse` 缺少 `amap.key`：`BusinessException(500,"amap.key 未配置")`；`getDrivingRouteEst` 会把它当作 `code>=500` 重试 3 次后抛出。
  - 高德 HTTP 4xx/5xx 或 `RestClientException`：`BusinessException(502)`。
  - 高德响应为空/业务失败/route 或 polyline 为空：502；`distance` 缺失或非数字也 502。
  - `getDrivingRouteEst` 内部对 `BusinessException(>=500)` 和普通 `RuntimeException` 最多重试 3 次；其他业务码按原样传播。`estimatePrice` 直接读取返回对象的字段；若异常情况下对象为 null，会发生空指针，而不是由该方法显式抛 502。返回对象非 null、里程或时间为空时，`RidePricing` 抛 502；创建订单另有显式空值检查。

### 3.2 创建订单：客户端价格被覆盖

`createOrder` 在调用公式前重新调高德，并覆盖**补充后的 DTO 副本**的 `estDistance/estPrice/radio/mongoTraceId`。这里的对象要分清：

1. Service 先直接修改入参 DTO：登录时覆盖 `userId`，设置车型/加急/预约默认值，普通单清空预约时间。
2. `CreateOrderDTO#validateAndSupplement` 不修改地址后返回原对象，而是构建 `supplemented` 副本并放进 `ValidationResult`；逆地理编码补充的地址也在副本上。
3. Service 使用 `validatedDto = result.supplementedDTO()`，随后覆盖这个副本的 trace/距离/价格/倍率。
4. Agent 场景中，`OrderTool#createOrder` 保存的 `orderDTO` 仍是预览 DTO；服务成功返回后，工具尝试删除 `orderDTO.getMongoTraceId()` 指向的**预览轨迹**，不是服务新生成的订单轨迹。

`SecurityRepairRegressionTest#creationOverwritesClientIdentityPriceAndTrace` 断言客户端传 `userId=2/estPrice=0.01/radio=0.01/mongoTraceId=untrusted` 后，保存的用户是 1、价格等于 `RidePricing.calculate("10","600",1,0)`、trace 不等于 untrusted。

仍由客户端/Agent 槽位影响价格的部分：

- 起终点坐标决定路线；
- `vehicleType/isExpedited` 决定倍率和固定加急费；
- 地址可为空并由逆地理编码 best-effort 补齐，失败可能留下空地址；
- 预约时间影响预约业务，不影响 `RidePricing.calculate` 的费率。

### 3.3 Agent 报价、确认与创建

`OrderTool#getEstRouteAndPrice(noRoute, toolContext)` 的判断顺序：

```java
// agentbase/tool/OrderTool.java（节选，省略中间语句）
if (redisGetObj(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name()) == null) {
    noRoute = false;
}
if (redisGetObj(chatInfoKey, "ReadyforRoute") == null) {
    return "请使用isNewOrderReady()先检查订单参数是否完备";
}
...
if (redisGetObj(chatInfoKey, "EST_TIME") == null
        || redisGetObj(chatInfoKey, "EST_DISTANCE_KM") == null) {
    noRoute = false;
}
```

因此：

- `ReadyforRoute` 不存在时**直接返回提示**，不会因为缺距离/时间就先规划。
- `MONGO_TRACE_ID` 缺失时先设 `noRoute=false`；过了 `ReadyforRoute` 检查后，`EST_TIME/EST_DISTANCE_KM` 缺失也设为 false。只有通过准备检查才会进入后面的规划分支。
- `noRoute=true` 且 trace、距离、时间都存在时，只对缓存中的里程/秒数调用 `RidePricing.calculate`；更新 Redis 价格字段、删除 `ReadyforRoute`、调用 `orderConfirmation.quoted`。这个分支不调高德、不写新 Mongo。
- 重新规划分支调高德、`addOrder(mongoId, estRoute)` 写 Mongo 预览，更新 Redis 的价格/距离/时间，删除 `ReadyforRoute`、调用 `quoted`，然后 best-effort 删除旧 trace。
- **两个报价分支都会调用 `quoted`**。`quoted` 会生成新的 `quoteId`、写 `ReadyforConfirm=true` 和 `quoteCreatedAt`，删除旧的 `ReadyForCreate/confirmedQuote`，并给会话 key 续 24 小时。`consume` 要求当前 `quoteId` 同时等于 `confirmedQuote` 和 `ReadyForCreate`，且距 `quoteCreatedAt` 不超过 15 分钟；成功后删除后两个标记。用户确认链路在 C3 展开；这里只看它不重新计算价格。

可达差异例子：

| 时刻 | 动作 | Redis 预览 / Mongo / MySQL |
|---|---|---|
| 10:00 | Agent 重新规划（假设快车、不加急） | 高德返回 10.000km/600s；Mongo 预览 A；Redis 写 `EST_DISTANCE_KM=10.000`、`EST_TIME=600`、`EST_PRICE=30.60`；`quoted` 生成 quoteId |
| 10:01 | 用户确认 | 确认 Lua 校验 quoteId 和 15 分钟，把 `confirmedQuote`、`ReadyForCreate` 设为该 quoteId |
| 10:02 | `OrderTool#createOrder` | 校验 `ReadyForCreate`，组装预览 DTO，`consume` 删除确认标记，再调服务 |
| 10:02+ | `RideOrderServiceImpl#createOrder` | 高德第二次返回 10.500km/600s；写 Mongo 订单轨迹 B；MySQL 订单 `est_distance=10.50`、`est_price=31.70`、`price_radio=1.00` |
| 10:02++ | 工具返回 | 文案读取 Redis：预计 10.000km、10 分钟、30.60 元；用户看到的报价与订单落库的 31.70 元不同 |

源码没有看到“服务重算价格与确认价不同就自动重新确认”的分支。确认保证的是“当前 quoteId 已被确认且未使用”，不是“第二次高德结果一定等于确认价”。`consume` 在服务调用前执行；若随后创建订单失败，本轮确认已被消费，工具不会重新触发确认，也不会执行工具里的预览轨迹清理。服务内部的 insert 失败清理针对新生成的订单轨迹，边界见 O1。

### 3.4 修改目的地：当前只有 Agent 工具入口

`SupportAgent` 的 `modifyOrderDestination` 分支调用 `OrderSearchTool#modifyOrderDestination`；没有找到 HTTP Controller 方法。下面是两段连续的真实源码；两者之间还有解析经纬度、查订单、归属检查、起点校验、调高德、生成新 trace 和计算价格的语句，正文中另行解释。

```java
// agentbase/tool/OrderSearchTool.java#modifyDestinationUnderLock（真实节选）
Integer status = order.getOrderStatus();
if (status == null) {
    return "订单状态异常";
}
if (status == RideOrderStatus.CANCELLED.getCode()) {
    return "订单已取消，无法修改目的地";
}
if (status >= RideOrderStatus.FINISHED_WAIT_PAY.getCode()) {
    return "订单已进入结算/支付阶段，无法修改目的地";
}
```

```java
// agentbase/tool/OrderSearchTool.java#modifyDestinationUnderLock（真实节选）
orderRouteService.addOrder(newTraceId, route);
int updated;
try {
    updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
            .eq(RideOrder::getOrderId, oid)
            .eq(RideOrder::getUserId, Long.valueOf(userIdStr))
            .eq(RideOrder::getIsDeleted, 0)
            .eq(RideOrder::getOrderStatus, status)
            .eq(oldTraceId != null, RideOrder::getMongoTraceId, oldTraceId)
            .isNull(oldTraceId == null, RideOrder::getMongoTraceId)
            .set(RideOrder::getEndAddress, normalizeText(newDestAddress))
            .set(RideOrder::getEndLat, lat)
            .set(RideOrder::getEndLng, lng)
            .set(RideOrder::getMongoTraceId, newTraceId)
            .set(RideOrder::getEstDistance, distanceKm)
            .set(RideOrder::getEstPrice, est.getEstPrice())
            .set(RideOrder::getPriceBase, est.getEstPriceBase())
            .set(RideOrder::getPriceTime, est.getEstPriceTime())
            .set(RideOrder::getPriceDistance, est.getEstPriceDistance())
            .set(RideOrder::getPriceExpedited, est.getEstPriceExpedited())
            .set(RideOrder::getPriceRadio, est.getEstPriceRadio())
            .set(RideOrder::getUpdateTime, now));
} catch (RuntimeException failure) {
    try {
        orderRouteService.deleteByTraceId(newTraceId);
    } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
    }
    throw failure;
}
```

要点：

- 身份来自 `ToolContext` 的 userId；订单必须存在且属于该用户。
- 状态判断是：非空、不等于 90、小于 50。它不是枚举白名单；若 DB 里出现未映射的状态码且满足 `<50`，这个判断也会放行。源码常规状态 10/20/30/40 可改；50/60/90 拒绝。
- 条件更新包含订单号、用户、`is_deleted=0`、原状态；旧 trace 非空时等值匹配，旧 trace 为 null 时用 `IS NULL` 守卫。
- 路线始终是**原订单起点 → 新终点**，不使用司机当前位置；已出发（40）只是返回文案不同，价格公式没有额外规则。`calcEstPriceByOrderToolRule` 的 `existingRadio/alreadyDeparted` 参数实际未参与公式。
- 距离先 `new BigDecimal(route.getEstKm()).setScale(2, HALF_UP)`，再传给共享公式；更新 `end_address/end_lat/end_lng/mongo_trace_id/est_distance/est_price/四个预估明细/price_radio/update_time`；不更新 `real_*`。
- 先写 Mongo 新轨迹，再条件更新 MySQL；成功后 best-effort 删除旧 Mongo。由于附近订单 GEO 池按起点构建，终点变更不触发 `OrderGeoPool` 失效；重建语义见 O3。

跨库结果按下表区分：

| 结果 | MySQL | Mongo 新轨迹 | Mongo 旧轨迹 |
|---|---|---|---|
| 更新 1 行 | 已按语句结果更新，通常提交（工具无 MySQL 事务注解） | 新订单轨迹存在 | best-effort 删除；失败可能残留 |
| 明确 0 行 | 本次条件未匹配，未更新 | best-effort 删除；删除失败则残留 | 不删除 |
| 更新抛异常/响应丢失 | 调用方重抛异常，**服务端是否已提交未知** | catch 中 best-effort 删除；若删除也失败则残留 | 不删除 |
| Mongo `addOrder` 抛异常/结果未知 | 该方法尚未调用条件更新 | 可能已写、未写或未知；没有本方法内的清理 | 不动 |
| 清理新/旧轨迹异常 | 不改变已成功更新 | 新轨迹可能残留 | 旧轨迹可能残留 |

不能把更新异常等同“MySQL 一定未更新”。若服务端实际已提交而响应丢失，可能出现订单指向已被清理的新轨迹；必须实际查询订单与 Mongo 判断，本章未做故障注入。

### 3.5 结束行程：轨迹解析、金额输入与跨库边界

`calcPolylineDistanceKm/parseCoordinate/haversineKm` 的真实行为：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
String[] points = normalized.split(";");
for (String point : points) {
    if (point == null || point.isBlank()) continue;
    String[] parts = point.trim().split(",");
    if (parts.length != 2) continue;
    BigDecimal lng = parseCoordinate(parts[0]);
    BigDecimal lat = parseCoordinate(parts[1]);
    if (lng == null || lat == null) continue;
    if (prevLat != null && prevLng != null) {
        total = total.add(haversineKm(prevLat, prevLng, lat, lng));
        hasSegment = true;
    }
    prevLat = lat;
    prevLng = lng;
}
return hasSegment ? total.setScale(2, RoundingMode.HALF_UP) : null;
```

- `parseCoordinate` 只做 trim 和 `new BigDecimal`；`null/空白/非数字` 返回 null 并跳过该点。
- 跳过的点只有三类：分号为空段、一个点不是“恰好两段”、至少一个坐标无法解析为 BigDecimal。
- **没有经纬度范围校验、顺序校验、轨迹连续性校验或真实性校验**。`120,30;121,99999` 的两个点都能解析，就会计算线段；重复点、倒退点也一样。
- 跳过坏点后，前一个有效点会与下一个有效点直接相连。示例 `120,30;abc,30;121,30`：中间 `abc` 被跳过，计算的是 `120,30 → 121,30` 的直线距离，不会因为缺了中间轨迹而返回 null。
- `hasSegment=true` 只要求至少两个可解析点；一个可解析点会返回 null。
- `haversineKm` 用 `BigDecimal.doubleValue()` 和三角函数计算，`calcPolylineDistanceKm` 不捕获它的运行时异常。因此“解析失败被跳过”和“计算异常向上传播”是不同路径；后者不会自动改用直线回退。

因此距离回退只在 `calcPolylineDistanceKm` 返回 null 时发生：输入为空/空白，或没有两个可解析点形成线段。解析失败点被跳过不算“返回 null”；地理无效也不算“非法”。返回的距离只说明客户端给的字符串可解析，不证明路线真实。

输入对金额和跨库副作用的影响：

| 输入 | 影响 |
|---|---|
| `realPolyline` | 先写入 Mongo `realPolyline`；可解析点决定 `realDistance`，进而影响 `real_price/明细`。可解析不等于地理有效或可信。 |
| `arriveTime` | 非空时作为 `finishTime`，影响时长、时长费、支付超时锚点；服务端不校验它是否晚于 `pickup_time` 或外部事实。 |
| `endLat/endLng` | 真实轨迹无有效线段且起终点完整时参与直线距离；非空时更新订单终点。 |
| `endAddress` | 非空时更新终点文本，不参与计价。 |

时间来源：

- `finishTime = req.arriveTime`（若非空）或 `BusinessTime.now()`；
- `durationSec = pickup_time → finishTime`；`pickup_time=null` 时 0；
- 没有校验 `finishTime >= pickup_time`，负 `Duration.getSeconds()` 会作为负时长传入 `RidePricing`，再因 `requiredNonnegative` 抛 502。

顺序与边界：

- Mongo `updateRealPolyline` 在 MySQL 条件更新前调用；O1 已说明 `updateFirst` 不检查 matched/modified，不能仅凭正常返回证明轨迹已修改。
- MySQL 更新 0 行抛 409；若 Mongo 已实际修改，不会回滚。
- 若终点可选字段非空，会更新终点；若轨迹没有两个可解析点且直线距离不可用，即使有终点也无法计价。
- 轨迹计算抛出的运行时异常不会触发直线回退；只有返回 null 才判断回退。

---

## 四、支付链与验单门禁

### 4.1 请求与身份

```http
POST /order/{orderId}/pay
Authorization: Bearer <TOKEN>
Content-Type: application/json

{
  "payChannel": 1,
  "tradeNo": "PLACEHOLDER_TRADE_NO"
}
```

`PayOrderReqDTO` 只有 `payChannel/tradeNo`，没有金额。金额来自订单 DB 的 `realPrice`。`payChannel` 只要求非 null，没有枚举校验；真实含义取决于部署方 verifier。

`payOrder`：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
String userId = UserTokenContext.getUserIdInString();
if (payChannel == null || tradeNo == null || tradeNo.isBlank()) {
    throw new BusinessException(400, "支付渠道和交易号不能为空");
}
return withOrderLock(orderId, () -> {
    RideOrder order = getRequiredOrder(orderId);
    if (!Objects.equals(order.getUserId(), Long.valueOf(userId))) {
        throw new BusinessException(403, "只能支付自己的订单");
    }
    if (!Objects.equals(order.getOrderStatus(), RideOrderStatus.FINISHED_WAIT_PAY.getCode())) {
        throw new BusinessException(409, "当前订单状态不允许支付");
    }
    if (paymentVerifier == null) throw new BusinessException(503, "支付核验尚未配置");
    paymentVerifier.verifyPaid(orderId, userId, payChannel, tradeNo, order.getRealPrice());
    return payOrderUnderLock(orderId);
});
```

检查顺序：参数 400 → 订单锁 → 订单存在 404 → 归属 403 → 状态非 50 409 → verifier。注意归属在状态前：非本人即使订单已取消也是 403。

### 4.2 默认 verifier

```java
// config/PaymentConfig.java（真实节选）
@Bean
@ConditionalOnMissingBean(PaymentVerifier.class)
PaymentVerifier unavailablePaymentVerifier() {
    return (orderId, userId, channel, tradeNo, amount) -> {
        throw new BusinessException(503, "支付渠道尚未接入，无法核验支付结果");
    };
}
```

- Spring 正常上下文没有其他 `PaymentVerifier` Bean 时注入它；verifier 在 MySQL 更新前抛 503。
- 该默认行为只说明“没有可信来源确认支付”，不能推断第三方渠道状态、退款、对账或幂等。
- 若部署方提供实现，它会收到 DB 订单 id、userId、渠道、交易号、`realPrice`；实现是否查渠道、校验商户/币种/金额/终态，取决于部署方，仓库无法验证。

### 4.3 条件更新与副作用

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, orderId)
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
        .set(RideOrder::getOrderStatus, RideOrderStatus.PAID.getCode())
        .set(RideOrder::getPayTime, now)
        .set(RideOrder::getUpdateTime, now));

if (updated > 0) {
    orderGrabService.syncOrderStatus(orderId, RideOrderStatus.PAID.getCode());
    orderDelayQueue.cancel(orderId);
}
return updated > 0;
```

- 条件更新不比较金额；verifier 需要负责金额核验。默认 verifier 直接拒绝。
- `50→60` 不改 `real_price/明细`；金额在结束行程已写。
- 当前 `payOrder` 无本地事务注解，HTTP/Agent 当前调用链也没有外层事务；条件更新提交后 `syncOrderStatus(60)`、`queue.cancel` 立即执行，finally 释放订单锁。若未来加入外层事务，两者登记 afterCommit，锁释放在 afterCompletion，方法体返回不代表物理提交。
- `syncOrderStatus`/`queue.cancel` 失败只告警；订单可能已 60，但缓存仍旧或队列有旧待办。
- 更新 0 行返回 `false`，Controller 返回 `Result.ok(false)`；HTTP 200。并发/外部写入下的最终状态以 DB 为准。

### 4.4 支付与超时竞争（可达简表）

前提：订单 5003 状态 50，支付超时任务已到期且被领取；林小雨本人支付；Redis/MySQL/verifier 可用；等待方都在 3 秒内取得订单锁。`pay-timeout-minutes` 是配置值，`application.yaml` 默认 30 分钟，可覆盖。O4 有完整时序。

| 谁先拿 `order:lock` | 结果 |
|---|---|
| 支付先 | 支付方在锁内归属通过、verifier 通过、条件更新影响 1 行后，当前无事务路径立即同步 60、取消队列，finally 解锁。超时随后在锁内重读 60，default 分支；已领取条目已从 ZSet 移除，default 不会重建 member。 |
| 超时先 | 超时事务内 `systemCancel` 条件更新 50→90；代理提交、afterCommit 执行、afterCompletion 释放锁之后，支付方才可能拿到锁；支付锁内读到 90，归属通过后抛 409“当前订单状态不允许支付”，不调用 verifier。 |
| 支付条件更新 0 行 | verifier 已通过但 `status=50` 等条件不再匹配。支付返回 false；本次不更新状态/队列。订单最终状态以 DB 为准；O4 讨论超时补偿的后续可能。 |
| 等待超 3 秒 | 等待方在 `withOrderLock` 抛 409，不进入自己的锁内业务；表中“重读状态”不发生。 |

### 4.5 支付失败分支

| 失败 | 发生点 | 当前 DB/Redis |
|---|---|---|
| 渠道/交易号缺失 | 参数校验 | 无 DB/Redis 写 |
| 订单不存在 | 锁内 `getRequiredOrder` | 无 DB 写；锁 afterCompletion/finally 释放 |
| 非本人 | 状态检查前 | 无 DB 写 |
| 状态非 50 | verifier 前 | 无 DB 写；HTTP 409 |
| 默认 verifier | verifier | 抛 503；本方法尚未执行条件更新；在没有并发支付/取消或其它外部写入且本次确实到达 verifier 的前提下订单仍 50，最终以 DB 为准 |
| 自定义 verifier 异常 | verifier | 本方法未更新 DB；外部核验是否已发生未知，不能断言渠道已扣款或未扣款 |
| 条件更新 0 行 | `payOrderUnderLock` | 返回 false；本次不更新状态/队列 |
| 更新后 Redis 失败 | `syncOrderStatus/queue.cancel` | MySQL 可能已 60；缓存/队列可能旧/缺；O2/O4 讨论后续边界 |

不要把 `true` 写成“外部清算成功”。它只表示 verifier 返回且本地条件更新影响 1 行；当前仓库没有渠道实现。

---

## 五、取消费与金额字段

### 5.1 取消费规则

取消入口和守卫在 O1 已审；这里只看金额：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
long minutes = Duration.between(acceptTime, BusinessTime.now()).toMinutes();
if (minutes > 5) cancelFee = calcCancelFee(minutes);

private BigDecimal calcCancelFee(long acceptedMinutes) {
    long over = Math.max(0, acceptedMinutes - 5);
    BigDecimal fee = new BigDecimal("5.00")
            .add(new BigDecimal(over).multiply(new BigDecimal("0.50")));
    if (fee.compareTo(new BigDecimal("20.00")) > 0) fee = new BigDecimal("20.00");
    return fee.setScale(2, RoundingMode.HALF_UP);
}
```

- 触发：`cancelRole=1` 且订单快照 `driver_id`、`driver_accept_time` 非空且整分钟数 > 5。
- 对正时段，`Duration.toMinutes()` 截断秒数：5 分 59 秒算 5 分钟，不收费；6 分 00 秒收费 5.50。`Duration` 的负数向零截断（例如 -90 秒是 -1 分钟）；若接单时间晚于当前时间，该负值不会满足 `minutes > 5`，但这也说明输入/时钟异常需另行核查。
- 公式：`5.00 + max(0, minutes-5) × 0.50`，上限 20.00。
- 不触发时 `cancelFee=BigDecimal.ZERO`；响应 `toPlainString()` 为 `"0"`。
- 司机取消不收违约金；系统取消走 `systemCancel`，不执行普通取消的费用逻辑。

### 5.2 字段归属

| 场景 | `real_price` | `price_base/time/distance/expedited` | `price_radio` |
|---|---|---|---|
| 行程结束 50/60 | 行程账单总价 | 行程账单明细 | 行程倍率 |
| 用户/司机取消 | 取消费（用户接单后取消可能 >0；司机/接单前为 0） | 四项清零 | 不在该 UPDATE 的 set 中 |
| 系统超时取消 | 不修改 | 不修改 | 不修改 |

因此不能把取消订单的 `real_price` 与行程账单混在一起；取消后订单也没有 `50→60` 支付流程。A2/O1 的取消矩阵仍然适用。

### 5.3 类型、存储与 JSON

- 计算侧：金额 `BigDecimal`，`RidePricing` 和取消费都 `setScale(2, HALF_UP)`。
- DB：`est_distance DECIMAL(10,2)`、`real_distance DECIMAL(10,3)`、金额字段 `DECIMAL(10,2)`、`price_radio DECIMAL(10,2)`。
- VO：`PriceEstimateVO` 和 `OrderBillVO` 的金额都是 `BigDecimal`；`RideOrderVO` 也用 `BigDecimal`，其自定义 `toString()` 只对金额/里程类字段走 `plain()`（`stripTrailingZeros().toPlainString()`），时间等字段走 `safe()`（`String.valueOf`），不要理解成所有字段都去尾零。
- JSON 序列化：仓库未找到针对这些 VO 的自定义 Jackson BigDecimal 配置；Spring Boot 默认 Jackson 预期将 BigDecimal 序列化为 JSON 数字，但实际是否保留 `8.50` 的 scale、是否输出字符串，没有真实 HTTP 或序列化测试证据。本章标为未知，不编造响应示例。

---

## 六、失败与并发补充

### 6.1 高德失败

- `estimatePrice` 直接向上抛；HTTP 报价不产生订单。
- `createOrder` 在写 Mongo 前失败，无 MySQL；写 Mongo 后、插 MySQL 前的失败边界见 O1。
- `modifyOrderDestination` 捕获后返回字符串；不更新 MySQL，也不写新 Mongo。

### 6.2 结束行程轨迹失败

| 前提 | 结果 |
|---|---|
| `realPolyline` 空 | 400/`IllegalArgumentException`，不调用 Mongo/MySQL |
| Mongo 调用异常 | MySQL 未更新；Mongo 实际是否写入结果未知 |
| Mongo 正常返回但未匹配/未修改 | O1：方法不检查 matched/modified，订单仍会继续计价 |
| 轨迹没有两个可解析点形成线段 | 有起终点时用直线距离；否则计价抛 502 |
| 负时长 | `RidePricing` 抛 502；Mongo 可能已实际修改 |
| MySQL 0 行 | 409；若 Mongo 已修改，不回滚 |

### 6.3 修改目的地失败

- 路径规划失败：返回业务字符串；无 MySQL/Mongo 写。
- Mongo 新轨迹写成功后 MySQL 明确 0 行：本次未更新，best-effort 删除新轨迹；更新异常时服务端是否提交未知，若已提交且清理成功，订单可能指向被删的新轨迹。删除失败无统一补偿。
- Mongo 新轨迹写入异常时尚未执行 MySQL 更新，但 Mongo 是否已写入未知，可能残留。
- MySQL 成功后旧 Mongo 删除失败：订单指向新轨迹，旧轨迹可能残留。
- 该方法无 MySQL 本地事务注解；Mongo/MySQL 不共同回滚。

### 6.4 支付与外部写入

- 支付先拿锁可以避免与超时/取消在锁内交错；锁外外部 DB 写不受此保护。
- verifier 若是外部渠道调用，调用已发生但响应丢失时，本地不知道渠道结果；本仓库没有重试/对账实现。
- `payChannel/tradeNo` 不是支付凭据；没有默认渠道去查询它们。

---

## 七、测试阅读：覆盖什么、没覆盖什么

### 7.1 `SecurityRepairRegressionTest`

- 门控：不需要外部端口；mock `AmapRouteService/OrderRouteService/PaymentVerifier/Mapper` 等；设置 token、注入 verifier 和雪花 ID。
- 与本章相关断言：
  - 他人支付在 verifier 前返回 403，且不调 verifier、不 update。
  - verifier 抛 503 时不 update。
  - 未登录支付抛 `UnauthorizedException`，不触 Mapper。
  - 创建订单覆盖客户端身份、价格、倍率和 trace；保存价格等于 `RidePricing.calculate("10","600",1,0)`。
  - `RidePricing.calculate("bad",...)`、`("10","-1",...)` 抛异常，不按 0 计价。

### 7.2 `OrderLockRegressionTest`

- mock Redisson/RLock/Mapper/组件；验证解锁失败不把已提交支付报成失败：订单 50、`realPrice=10`、update 1、解锁抛异常，`payOrder` 仍 true，并验证 `queue.cancel` 和 `syncOrderStatus(60)`。
- 结束行程测试只断言返回 orderId、支付超时登记、释放司机；不校验具体金额。

### 7.3 `OrderGrabRegressionTest` / `OrderTimeoutRegressionTest`

- 前者验证结束行程成功后 `syncOrderStatus(50)`、`releaseDriver`。
- 后者验证支付超时 UPDATE 不包含 `real_price`，并验证快照字段。
- 不验证计价公式数值。

### 7.4 未覆盖

- 没有专门 `RidePricing` 数值表驱动测试；
- 没有修改目的地测试；
- 没有 Mongo/MySQL 真实联调、JSON 序列化测试、真实高德/支付；
- mock verifier 不代表真实渠道；测试未运行。

---

## 八、动手验证

### 练习 A：手算并追踪报价

输入：起点 `120,30`，终点 `120.09,30.008`，快车，不加急；假设高德返回 `estKm="8.000"`、`estTime="1200"`。

请手算金额并说明这个结果会不会写入订单。

<details>
<summary>练习 A 参考答案</summary>

超起步 `8-2=6` 公里，基础里程费 `8 + 6×2.20 = 21.20`；未超 20 公里，远途 0；`1200÷60=20` 分钟，时长费 10.00；非加急固定费 0；快车倍率 1.00；总价 `(21.20+10.00)×1.00=31.20`。输出还包括 `radio=1.00`。`POST /order/estimate` 只返回 VO，不写 MySQL/Mongo/Redis；高德返回是服务端来源，客户端没有传金额字段。
</details>

### 练习 B：手算远途加急

输入：`estKm="25.000"`、`estTime="3599"`、专车、加急。

<details>
<summary>练习 B 参考答案</summary>

基础里程费 `8 + 23×2.20 = 58.60`；远途 `(25-20)×1.10=5.50`；`3599` 秒向上取整 60 分钟，时长费 30.00；固定加急 5.00；倍率 `2.50+0.20=2.70`；总价 `(58.60+5.50+30.00+5.00)×2.70=267.57`。`priceExpedited` 只写 5.00，加急带来的 +0.20 体现在 `radio=2.70`。不要用四个明细直接相加得到总价。
</details>

### 练习 C：确认价、服务重算价与工具返回价

Agent 按快车、不加急在 10:00 重新规划得到 10.000km/600s，Redis 预览价 30.60 元，用户确认。10:02 `OrderTool#createOrder` 时服务第二次高德返回 10.500km/600s。

请说明确认保证了什么、订单落库什么、工具文案显示什么，以及 DTO/轨迹对象分别指向谁。

<details>
<summary>练习 C 参考答案</summary>

确认链路消费的是当前 `quoteId`：`quoted` 生成 quoteId/ReadyforConfirm/quoteCreatedAt；确认后 `confirmedQuote` 和 `ReadyForCreate` 都等于该 quoteId；`consume` 校验这两个等值和 15 分钟后删除标记。它不保证第二次高德返回相同。服务会用第二次 10.500km 计算 `est_price=31.70`，写 Mongo 订单轨迹 B 和 MySQL。工具随后从 Redis 读旧 `EST_DISTANCE_KM=10.000/EST_TIME=600/EST_PRICE=30.60` 并返回给用户。`CreateOrderDTO#validateAndSupplement` 操作副本；服务覆盖的是副本，工具清理的是原 `orderDTO.mongoTraceId` 指向的预览轨迹 A。若创建失败，确认已被消费，预览轨迹的删除代码也不会执行。
</details>

### 练习 D：修改目的地

订单 6001 状态 20，原起点 `120,30`，用户通过 Agent 请求新终点 `120.10,30.01`；高德返回 12.345 公里、900 秒；车型快车、不加急。

请写状态判断、更新守卫、金额来源和落点；再分别说明 0 行、更新异常和 Mongo 写异常。

<details>
<summary>练习 D 参考答案</summary>

状态判断是 `status` 非空、不等于 90 且小于 50；不是枚举白名单。更新条件包含订单号、ToolContext 用户、`is_deleted=0`、原状态；旧 trace 非空时等值匹配，旧 trace 为 null 时用 `IS NULL`。它从订单起点到新终点调用高德；距离 `12.345` 先 `setScale(2)=12.35`；`RidePricing.calculate` 计算：基础里程费 `8+10.35×2.20=30.77`，15 分钟 7.50，总价 38.27。方法先写 Mongo 新轨迹，再更新 MySQL 的终点、`est_distance`、预估总价/明细/倍率和更新时间；成功后 best-effort 删除旧轨迹，不更新 `real_*`，不使用司机当前位置。

明确 0 行：本次条件未匹配；best-effort 删除新轨迹，删除失败则新轨迹残留。更新异常：代码删除新轨迹后重抛，但 MySQL 是否已在服务端提交未知；若实际已提交且清理成功，可能订单指向已删除的新轨迹。Mongo `addOrder` 异常：方法尚未条件更新，但 Mongo 是否已写未知，可能残留记录。
</details>

### 练习 E：六个支付/取消判断

判断“正确 / 错误 / 取决于条件”。

1. 支付请求中的 `payChannel/tradeNo` 会用于计算订单金额。
2. 默认配置下提供任意交易号可把订单改成 60。
3. 支付成功返回 true 等于外部渠道清算成功。
4. 司机取消接单后 12 分钟的订单要付 8.50 元取消费。
5. 取消订单的 `real_price` 与行程账单是同一个业务含义。
6. `OrderBillVO` 的金额一定以两位字符串返回。

<details>
<summary>练习 E 参考答案</summary>

1. 错误。它们只是传给 verifier 的参数；金额来自 DB `realPrice`。
2. 错误。默认 `PaymentConfig#unavailablePaymentVerifier` 在条件更新前抛 503。
3. 错误。仓库没有真实渠道；`true` 最多表示部署方 verifier 返回且本地条件更新影响 1 行。
4. 取决于取消者是订单本人、状态/锁前提。若是 USER 本人在 20 且接单已 12 分钟，费用 `5+(12-5)×0.5=8.50`；司机取消费用 0；非本人先 403。
5. 错误。取消时 `real_price` 是取消费，四个行程明细清零；结束行程时 `real_price` 是行程总价。
6. 错误。Java/DB 类型是 BigDecimal 且计算 setScale(2)，但 JSON 是否保留两位小数或输出字符串未实测；本章不声称具体 JSON 形态。
</details>

> 本章不提供运行实验。计价测试是纯 mock/断言阅读；支付和高德均不能在业务环境试跑。若未来做隔离实验，必须使用一次性专用 MySQL/Redis，并不得调用真实高德或支付渠道；本章没有创建、删除或修改任何数据。

---

## 九、一页复习

- **公式**：`base = 8 + max(0,D-2)×2.20`；`distance = max(0,D-20)×1.10`；`time = ceil(S/60)×0.50`；`exp = 5` 或 0；`radio = 车型倍率 + (加急?0.20:0)`；总价 `(base+distance+time+exp)×radio`。
- **边界**：2 公里内不收超起步；20 公里起收远途；60 秒收 1 分钟；0 秒 0 分钟；负距离/负时长 502。
- **来源**：HTTP 报价不落库；创建重算并覆盖副本中的价格/倍率/轨迹；Agent 确认价与第二次规划价可能不同，工具可能返回旧 Redis 预览；改目的地从原起点重算；结束行程只有无两个可解析点形成线段时才回退直线。
- **客户端影响**：不能直接写入金额/倍率/trace，但坐标、车型、加急、轨迹字符串、结束时间仍会影响服务端结果。
- **明细**：`priceBase` 不含车型/加急倍率；`priceExpedited` 只是 5.00；总价先加后乘再舍入，明细相加不保证等于总价。
- **支付**：参数只有渠道/交易号；金额来自 DB；顺序是 404 → 403 → 409 → verifier；默认 verifier 503；条件更新 50→60；0 行返回 false。
- **支付边界**：`true` 不是外部清算；没有退款/对账/幂等实现；更新后 Redis 失败不回滚 MySQL。
- **取消费**：仅 USER 本人、司机已接单、整分钟 >5；`5+(minutes-5)×0.5`，封顶 20；写入取消订单的 `real_price`，明细清零；与行程账单不是同一含义。

---

## 十、自测与答案

**1. `D=20.000`、`S=0`、快车、不加急，总价和明细？**

<details>
<summary>参考答案</summary>

超起步 `18×2.20=39.60`，基础里程费 47.60；未超 20 公里，远途 0；0 秒 0 分钟，时长费 0；倍率 1.00；总价 47.60。输出明细 `base=47.60/time=0.00/distance=0.00/expedited=0.00/radio=1.00`。这里是远途阈值边界：远途费为 0。
</details>

**2. 高德返回 `estKm="20.001"`，快车 0 秒。远途费和总价怎么算？**

<details>
<summary>参考答案</summary>

`RidePricing` 用字符串 `20.001` 计算：基础里程费 `8+18.001×2.20=47.6022`；远途 `0.001×1.10=0.0011`；总价 `47.6033`，最后 2 位 HALF_UP 为 47.60。返回的 `base` 舍入为 47.60，`distance` 舍入为 0.00。这里能看到中间值未先舍入；更复杂倍率下不能只用明细相加还原总价。
</details>

**3. 创建订单时客户端传了 `estPrice/radio/mongoTraceId`，为什么还说客户端输入影响价格？**

<details>
<summary>参考答案</summary>

因为这三个字段会被服务端覆盖：重新生成 trace、重新调高德、重新计价。但客户端/Agent 槽位仍提供起终点坐标、车型、加急、预约时间；坐标决定路线，车型/加急决定倍率和固定加急费。因此“金额字段不可信”不等于“所有影响金额的输入都不可信”。
</details>

**4. `finishRide` 的 `arriveTime` 字段会影响什么？**

<details>
<summary>参考答案</summary>

源码把它赋给 `finishTime`：非空用它，空则用 `BusinessTime.now()`。时长是 `pickup_time` 到 `finishTime`。它不校验是否晚于 `pickup_time`；若产生负秒数，`RidePricing` 抛 502。字段名“到达时间”容易误导，实际在结束行程链路中是结束/计费结束时间。
</details>

**5. 支付时订单已被超时取消，用户本人请求结果是什么？**

<details>
<summary>参考答案</summary>

用户先要拿到订单锁。若在 3 秒内拿到且订单是 90，`payOrder` 先通过归属检查，再因状态不是 50 抛 `BusinessException(409,"当前订单状态不允许支付")`；不会调用 verifier 或 `payOrderUnderLock`。HTTP 层由 `GlobalExceptionHandler` 返回 409。若 3 秒内没拿到锁，`withOrderLock` 先 409“订单正在处理中”。
</details>

**6. verifier 通过后条件更新 0 行，意味着什么？**

<details>
<summary>参考答案</summary>

`payOrderUnderLock` 返回 false，Controller 返回 HTTP 200 + `data=false`。本次没有设置 60/pay_time，也不执行状态缓存/队列取消。0 行说明状态/删除标志等条件已不匹配；最终订单状态必须重读 DB，不能凭异常或 true/false 推断外部渠道结果。
</details>

**7. 用户取消一个接单 5 分 59 秒的订单，费用是多少？接单 6 分 00 秒呢？**

<details>
<summary>参考答案</summary>

前提都是订单本人、锁可用、状态允许且 `driver_accept_time` 非空。对正时段，`Duration.toMinutes()` 截断秒数：5 分 59 秒是 5 分钟，不满足 `minutes > 5`，费用 0。6 分 00 秒是 6 分钟，费用 `5.00+(6-5)×0.50=5.50`。注意这不是“四舍五入”；负时长向零截断，但不属于本题正常前提。
</details>

---

## 十一、导航与源码入口

- **上一章**：[第 10 章 超时与恢复](04-timeouts-and-recovery.md)——未支付超时与支付竞争的锁/条件更新时序。
- **下一章**：[第 12 章 工单生命周期与权限](../03-support-and-cache/01-ticket-lifecycle.md)——从费用争议追踪工单状态、角色归属和提交后缓存失效。
- **相关专题**：
  - Agent 报价确认令牌与 HITL → [第 18 章 订单确认](../04-chat-and-agent/03-order-confirmation.md)

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 共享计价规则 | `service/base/RidePricing.java#calculate/#requiredNonnegative` |
| HTTP 报价 | `controller/RideOrderController.java#estimate`（内部 `PriceEstimateReqDTO`）；`RideOrderServiceImpl#estimatePrice/#calculatePrice` |
| 高德估算 | `agentbase/amap/service/AmapRouteService.java#getDrivingRouteEst/#calcKm`；`domain/vo/EstRouteVO.java` |
| 创建重算 | `RideOrderServiceImpl#createOrder`；`domain/dto/CreateOrderDTO.java#validateAndSupplement` |
| Agent 报价/确认/创建 | `agentbase/tool/OrderTool.java#getEstRouteAndPrice/#createOrder/#getEstPrice`；`service/base/OrderConfirmation.java#quoted/#begin/#confirm/#isConfirmed/#consume` |
| 修改目的地 | `agents/SupportAgent.java#executeTool` 的 `modifyOrderDestination` 分支；`agentbase/tool/OrderSearchTool.java#modifyOrderDestination/#modifyDestinationUnderLock/#calcEstPriceByOrderToolRule` |
| 结束行程 | `controller/RideOrderController.java#finishRide`（内部 `FinishRideReqDTO`）；`RideOrderServiceImpl#finishRide/#finishRideUnderLock/#calcPolylineDistanceKm/#haversineKm` |
| Mongo 轨迹 | `service/base/OrderRouteService.java#addOrder/#updateRealPolyline/#deleteByTraceId`；`domain/entity/OrderRoute.java` |
| 支付 | `controller/RideOrderController.java#pay`（内部 `PayOrderReqDTO`）；`RideOrderServiceImpl#payOrder/#payOrderUnderLock`；`service/PaymentVerifier.java`；`config/PaymentConfig.java` |
| 取消费 | `RideOrderServiceImpl#cancelOrder/#cancelOrderUnderLock/#calcCancelFee` |
| 类型/存储 | `domain/vo/PriceEstimateVO.java`、`OrderBillVO.java`、`RideOrderVO.java`；`domain/entity/RideOrder.java`；`src/main/resources/sql/init.sql#sys_ride_order` |
| 异常映射 | `exception/BusinessException.java`；`exception/GlobalExceptionHandler.java` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/SecurityRepairRegressionTest.java`、`OrderLockRegressionTest.java`、`OrderGrabRegressionTest.java`、`OrderTimeoutRegressionTest.java` |
