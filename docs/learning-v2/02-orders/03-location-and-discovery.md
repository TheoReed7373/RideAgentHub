# 第 9 章 位置与发现：司机心跳、附近订单池与乘客位置

> O2 的接单成功会把订单从“附近订单池”失效，并可选刷新司机在线位置；本章把“位置从哪来、附近列表怎么算”讲清。这里有三套容易混淆的数据：**司机在线 GEO + 成员级心跳**、**待接单订单 GEO 池**、**乘客保存的当前位置 Hash**。它们都不是订单表，也不是彼此的副本。
>
> 继续使用虚构司机陈师傅（userId 占位 `7002002`）、乘客林小雨（`9001001`）。坐标、距离、分页结果均为示意；本章没有启动服务、运行测试、连接 Redis/MySQL 或调用真实地图。

## 学习目标

读完本章，你应该能：

1. 追踪司机上线/心跳、下线、查询在线状态、获取附近订单，以及乘客保存/读取位置的完整端点与调用链。
2. 解释司机在线为什么需要 GEO 和心跳 Hash 两个结构；说明 Redis TIME、60 秒窗口、逐成员清理、共享 key 不设 TTL 的原因。
3. 解释附近订单池的冷启动、重建锁、版本/ready 标记、全量重建、DB 权威校验、距离排序与分页；说明失效失败、并发重建、Redis 故障和 DB 不一致的边界。
4. 区分事务内登记、提交后执行、直接 Redis 写；列出各 key 的 TTL/无 TTL 与 GEO 成员有效期。
5. 对照 `GeoPoolRegressionTest`、`GeoPoolRedisIntegrationTest` 的门控、mock 和断言，知道本章结论哪些只是静态阅读。
6. 给出至少两条故障/并发时间线：接单失效与池重建竞争、司机下线与接单刷新竞争。

## 前置知识与阅读时间

- 必读：[第 7 章 订单生命周期](01-order-lifecycle.md)——订单创建/接单成功后对 GEO 池的失效调用点。
- 推荐：[第 8 章 抢单与并发](02-grab-and-concurrency.md)——`refreshIfExist` 的调用时机与不把离线司机重新上线的约束；本章已内联解释 refresh 语义，O2/O3 可互换阅读。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——事务同步、提交后回调；本章的池失效会用到。
- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——Hash、GEO、Lua、TTL 的最小概念。
- 可选：[第 6 章 权限、归属与防护](../01-auth/02-authorization-and-defense.md)——`@RequirePermission({"DRIVER"/"USER"})`。
- 阅读时间：约 100～120 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**：除标注“测试阅读（未运行）”外，正文结论来自源码静态阅读；没有运行应用/测试/Redis/MySQL，也没有故障注入。

---

## 零、先记住六个事实

1. **司机在线位置不是 Hash 里的一个 TTL key**。它由共享 GEO key `driver:geo:online` 和共享心跳 Hash `driver:online:beat` 组成；两个 key 都不设 Redis TTL，过期判定下沉到成员级。
2. **心跳时间以 Redis `TIME` 为准**。`UPDATE_DRIVER_LOCATION`/`READ_DRIVER_LOCATION` 在 Lua 里调用 `TIME`，避免应用服务器时钟偏差直接决定在线与否。
3. **司机在线状态是读时判定 + 读时清理**。`READ_DRIVER_LOCATION` 发现心跳缺失/过期/非法、或 GEO 坐标缺失时，会原子删除该司机的两个成员；不会影响其他司机。
4. **附近订单池是整池失效、按需重建**。`OrderGeoPool.add()` 实际调用 `remove()`；不把单个订单增量写入。GEO 和 ready 标记固定 2 小时，版本 key 不设 TTL。
5. **Redis 池只是加速，DB 校验仍是权威**。距离池候选回表后要求候选全部仍为 `CREATED(10)`，且版本未变化；否则整页降级为 DB 时间排序。
6. **乘客位置和司机位置是两套数据**。乘客位置是 `user:loc:{userId}` Hash，只有 Redis 一份，没有 TTL 和删除接口；司机在线是 GEO + 心跳，用于附近查询的圆心。

---

## 一、正常旅程：从上线到看到附近订单

### 1.1 司机上线与心跳

陈师傅上线/每次心跳调用：

```http
POST /order/driver/location
Authorization: Bearer <DRIVER_TOKEN>
Content-Type: application/json

{
  "lng": 120.000000,
  "lat": 30.000000
}
```

入口：

```java
// controller/RideOrderController.java（真实节选）
@PostMapping("/driver/location")
@RequirePermission({"DRIVER"})
public Result reportDriverLocation(@RequestBody DriverLocationReqDTO req) {
    Long driverId = UserTokenContext.getUserIdInLong();
    Boolean ok = driverLocationService.reportLocation(
            String.valueOf(driverId), req.getLng(), req.getLat());
    return Result.ok(ok);
}
```

`DriverLocationReqDTO` 是控制器内部类，只有 `BigDecimal lng/lat`。注意参数语义是**先经度后纬度**；Redis `GEOADD` 也按“经度、纬度、成员”写入。

`DriverLocationServiceImpl#reportLocation`：

- `driverId` 空、经纬度空 → `IllegalArgumentException` → 400。
- 经度不在 `[-180,180]`，或纬度不在 `[-85.05112878,85.05112878]` → `IllegalArgumentException` → 400。上界 85.05112878 来自 Redis GEO 使用的 Web Mercator 纬度范围，不是常规 ±90。
- 校验通过后调用 `DriverGeoIndex#goOnline(driverId, lng, lat)`。**首次上线和后续心跳共用这个方法**；源码没有单独的“心跳”端点。

`DriverGeoIndex#goOnline` 执行 `UPDATE_DRIVER_LOCATION`：

```lua
-- util/RedisScripts.java#UPDATE_DRIVER_LOCATION（真实 Lua）
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if ARGV[5] == 'refresh' then
    local beat = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
    if not beat or now < beat or now - beat >= tonumber(ARGV[4])
            or not redis.call('ZSCORE', KEYS[2], ARGV[1]) then
        redis.call('HDEL', KEYS[1], ARGV[1])
        redis.call('ZREM', KEYS[2], ARGV[1])
        return 0
    end
end
redis.call('GEOADD', KEYS[2], ARGV[2], ARGV[3], ARGV[1])
redis.call('HSET', KEYS[1], ARGV[1], string.format('%.0f', now))
return 1
```

key/参数映射：

| Lua 变量 | Java 值 | 含义 |
|---|---|---|
| `KEYS[1]` | `driver:online:beat` | Hash，field=driverId，value=最后心跳 epoch 毫秒 |
| `KEYS[2]` | `driver:geo:online` | GEO，member=driverId |
| `ARGV[1]` | driverId | 成员/field |
| `ARGV[2]` | lng 字符串 | Redis GEO 经度 |
| `ARGV[3]` | lat 字符串 | Redis GEO 纬度 |
| `ARGV[4]` | `"60000"` | 心跳窗口毫秒 |
| `ARGV[5]` | `"online"` 或 `"refresh"` | 模式 |

- `online` 模式不读旧心跳，直接 `GEOADD` + `HSET now`；所以上线请求能把司机写进在线池。
- `refresh` 模式先检查旧心跳：缺失、心跳未来于 now（`now < beat`）、`now - beat >= 60000`（恰好 60 秒也过期）、或 GEO 成员缺失，都清理两个成员并返回 0；只有 `beat <= now` 且差值小于 60 秒才更新。O2 的接单路径用 `refresh`，这次刷新不会复活离线/过期司机；后续 `online` 心跳仍可上线。
- Lua 使用 Redis `TIME`，不是应用服务器时间。
- `DriverGeoIndex#update` catch Redis `RuntimeException`，记 warn 并返回 false；接口可能返回 `Result.ok(false)`。

### 1.2 查询在线状态

```http
GET /order/driver/location
Authorization: Bearer <DRIVER_TOKEN>
```

`DriverLocationServiceImpl#getStatus` 调用 `DriverGeoIndex#location(driverId)`，后者执行：

```lua
-- util/RedisScripts.java#READ_DRIVER_LOCATION（真实 Lua）
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local beat = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
local positions = redis.call('GEOPOS', KEYS[2], ARGV[1])
if beat and now >= beat and now - beat < tonumber(ARGV[2]) and positions[1] then
    return positions[1]
end
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return {}
```

- 有效在线：返回 `[lng, lat]`，Java 装配 `DriverLocationVO(online=true, lng, lat)`。
- 无心跳/心跳过期/非法心跳/无 GEO 坐标：原子清理该司机两个成员，返回空列表；Java 判为 `OFFLINE`，返回 `online=false`。
- Redis 返回 null 或抛异常：Java 判为 `UNAVAILABLE`；`getStatus` 抛 `BusinessException(503)`“司机位置服务暂时不可用”。不能把 Redis 故障误报成司机离线。

### 1.3 主动下线

```http
POST /order/driver/offline
Authorization: Bearer <DRIVER_TOKEN>
```

`REMOVE_DRIVER_LOCATION`：

```lua
-- util/RedisScripts.java#REMOVE_DRIVER_LOCATION（真实 Lua）
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
```

- 同一 Lua 原子删除心跳 field 和 GEO member。
- 即使本来不在线也返回 1；Redis 异常时 `DriverGeoIndex#goOffline` 返回 false。
- 下线不会改 MySQL，也不会删除乘客位置 Hash；它只影响司机在线池。

### 1.4 获取附近订单

```http
GET /order/driver/pool/page?page=1&size=10
Authorization: Bearer <DRIVER_TOKEN>
```

`RideOrderServiceImpl#getDriverOrderPool`：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
int p = page == null ? 1 : page;
int s = size == null ? 10 : size;
if (p <= 0 || s <= 0) throw new IllegalArgumentException("page/size必须为正数");

DriverGeoIndex.Location location = driverGeoIndex.location(driverId);
if (location.status() == DriverGeoIndex.OnlineStatus.OFFLINE) {
    return emptyPool(p, s);
}
if (location.status() != DriverGeoIndex.OnlineStatus.ONLINE || location.point() == null) {
    return legacyTimeSortedPool(p, s);
}
Optional<OrderGeoPool.GeoSlice> candidates = orderGeoPool
        .search(location.point().getLng(), location.point().getLat());
if (candidates.isEmpty()) {
    return legacyTimeSortedPool(p, s);
}
return buildDistanceSortedPool(candidates.get(), p, s);
```

三个分支：

1. **司机离线**：返回空池，不查订单池，也不查 DB。
2. **Redis 不可用/读到 UNAVAILABLE**：降级为 DB 时间排序；不能因为 Redis 故障就断定司机离线。
3. **在线且有坐标**：以司机当前 GEO 坐标为圆心查附近订单池；池不可用/未就绪时也降级 DB 时间排序。

### 1.5 乘客当前位置（另一套数据）

读取：

```http
GET /user/loc
Authorization: Bearer <USER_TOKEN>
```

保存：

```http
POST /user/loc
Authorization: Bearer <USER_TOKEN>
Content-Type: application/json

{
  "latitude": "30.000000",
  "longitude": "120.000000",
  "address": "虚构起点"
}
```

`UserController` 两个端点都要求 `@RequirePermission({"USER"})`；`userId` 来自 token。`UserServiceImpl#saveUserLocation`：

```java
// service/impl/UserServiceImpl.java（真实节选）
String key = RedisKeyConstants.userLocKey(userId);
stringRedisTemplate.opsForHash().put(key, "latitude", location.getLatitude());
stringRedisTemplate.opsForHash().put(key, "longitude", location.getLongitude());
stringRedisTemplate.opsForHash().put(key, "address", location.getAddress());
```

`getUserLocation` 读取整个 Hash；空 Hash 返回 null，Controller 转 404“用户定位数据不存在”。

要点：

- key 是 `user:loc:{userId}`；Hash fields 是 `latitude/longitude/address`。
- 没有设置 TTL；仓库中也没有删除该 key 的业务方法。清空 Redis 后需重新上报。
- 保存时三次 `HPUT` 不是 Lua/事务；中途失败可能留下部分字段。`UserLocation` 三个字段都是 `String`，Service 不校验经纬度范围/数字格式。
- 它与司机 GEO 无关；Agent 工具可以读取它作为提示词上下文，但读取不等于位置可信或已校准。

---

## 二、司机在线：GEO 与成员级心跳

### 2.1 为什么不直接给共享 key 设 TTL

`RedisKeyConstants` 的注释和实现解释了这个设计：

- `driver:geo:online` 是所有司机共享的 GEO key。Redis `EXPIRE` 作用于整个 key；若每次心跳刷新 TTL，只要有一个人在心跳，整个 key 永不过期，离线成员永远留下。
- `driver:online:beat` 记录每个司机的最后心跳 epoch 毫秒。过期判定在读取/刷新时逐成员比较。
- 两个 key 都不设 TTL；成员被 `READ_DRIVER_LOCATION` 或 `UPDATE_DRIVER_LOCATION(refresh)` 原子清理。

### 2.2 成员生命周期

| 动作 | Lua/模式 | 对心跳 Hash | 对 GEO | 返回 |
|---|---|---|---|---|
| 上线 | `UPDATE_DRIVER_LOCATION`，`online` | `HSET now` | `GEOADD lng lat driverId` | 1；Redis 异常时 Java false |
| 心跳 | 同一端点，`online` 模式 | `HSET now` | 更新坐标 | 1/false |
| 接单更新成功后刷新 | `refreshIfExist`，`refresh`；在事务提交前直接执行 | 只有仍在线才 `HSET now` | 只有仍在线才更新坐标 | 0/false 表示这次刷新不能复活；后续 `online` 心跳仍可上线 |
| 主动下线 | `REMOVE_DRIVER_LOCATION` | `HDEL driverId` | `ZREM driverId` | 1；Redis 异常 Java false |
| 查询状态 | `READ_DRIVER_LOCATION` | 有效则读；无效/过期清理 | 有效则 `GEOPOS`；无效清理 | 在线坐标 / OFFLINE / UNAVAILABLE |

### 2.3 时间与原子边界

- 有效窗口：`ONLINE_TTL=60s`；`refresh`/读取要求 `beat <= now` 且 `now - beat < 60000`。差值恰好等于 60000 已过期。
- `now` 来自 Redis `TIME`，单位秒转毫秒；不是 Java `System.currentTimeMillis()`。
- `now < beat`（心跳在未来）视为非法并清理；心跳缺失或非法也清理。
- `online` 模式没有检查旧心跳，所以能上线；`refresh` 模式检查旧状态，这次刷新不会上线。
- `REMOVE_DRIVER_LOCATION` 与 `UPDATE_DRIVER_LOCATION` 都是单次 Lua；并发“接单刷新 vs 主动下线”不会一个只删 GEO、另一个只删心跳。
- Lua 的原子性是“不被其他命令插入”，不是“运行时错误自动回滚”。例如脚本中前一条 `GEOADD` 成功、后一条 `HSET` 出错，已发生的写入不会被 MySQL 式 rollback 撤销；Java 拿到 false/异常也不能证明 Redis 端没有写。
- 在 Redis Cluster 中，多 key Lua 的所有 key 必须在同一 slot。本章涉及的多个 key 没有显式 hash tag，实际部署是否同槽/是否可用必须核验；仓库没有声明 Cluster 配置。

### 2.4 接单位置刷新、主动下线与事务边界

先看接单方法的真实语句顺序：

```text
driverAcceptOrder 事务内：
  1. tryGrab
  2. MySQL 条件 UPDATE 影响未提交修改
  3. syncOrderStatus：只登记 afterCommit 写状态缓存
  4. orderGeoPool.remove：只登记 afterCommit 失效池
  5. 若请求有经纬度，driverGeoIndex.refreshIfExist 直接执行（仍在事务提交前）
  6. orderDelayQueue.schedule：计算 deadline 并登记 afterCommit
  7. 方法返回后代理提交
```

因此接单位置刷新发生在**成功条件更新之后、事务提交之前**，不是 afterCommit。若代理随后回滚，已成功的 Redis 位置刷新不会自动撤销；但数据库里的接单可能回到 10。调用/响应丢失时，Redis 是否已写、是否部分写入都不能凭 Java 返回值断言。

下面画两个 Lua 顺序。T1 是接单事务，已通过 DB 条件更新并带经纬度；T2 是主动下线。

**顺序 1：refresh 先执行，下线后执行。**

| 时刻 | T1 | T2 | 状态 |
|---|---|---|---|
| c0 | DB UPDATE 成功但未提交；登记状态/GEO afterCommit | — | 司机仍在线；order10 未提交 |
| c1 | `refresh` Lua 成功，更新 GEO/心跳 | — | 在线，坐标新；事务尚未提交 |
| c2 | — | `REMOVE` Lua 成功，删心跳/GEO | 离线 |
| c3 | 代理提交或回滚；afterCommit 只在提交后执行 | — | 若提交：order10=20，司机离线；若回滚：order10 回到 10，但刚刷新的位置已被下线删除 |

**顺序 2：下线先执行，refresh 后执行。**

| 时刻 | T1 | T2 | 状态 |
|---|---|---|---|
| d0 | DB UPDATE 成功但未提交；登记状态/GEO afterCommit | — | 司机仍在线；order10 未提交 |
| d1 | — | `REMOVE` Lua 成功 | 离线，两成员清理 |
| d2 | `refresh` Lua：心跳/GEO 缺失 → 清理并返回 0 | — | 这次刷新不能复活 |
| d3 | 代理提交或回滚 | — | 离线；回滚不撤销已发生的刷新，但这里刷新返回 0，没有新增位置 |

两种顺序都不会因为本次 `refresh` 单独复活离线成员。**但不能笼统说“永远稳定离线”**：司机随后调用 `POST /order/driver/location` 的 `online` 模式仍可重新上线。若 T1 事务回滚，已成功的 refresh 也不撤销；这是跨资源窗口。若 Lua 抛异常或响应丢失，两个 Java 方法都可能返回 false/异常，Redis 端实际状态未知，必须查询。

---

## 三、附近订单池：失效、重建与 DB 校验

### 3.1 key 与常量

| key | 类型 | TTL | 内容 |
|---|---|---|---|
| `order:geo:pool` | GEO | `PUBLISH` 后 `PEXPIRE 2h` | member=orderId，score/坐标为订单起点 |
| `order:geo:pool:ready` | String | `PUBLISH` 后 `SETEX/PX 2h` | `"version:count"` |
| `order:geo:pool:version` | String | 无 TTL | 当前版本 UUID |
| `order:geo:pool:rebuild:lock` | String | 10 秒 | RedisLock 的唯一 token |

`OrderGeoPool` 常量：`INDEX_TTL=2h`、`REBUILD_LOCK_TTL=10s`、`SEARCH_RADIUS_KM=3.0`、`SCAN_LIMIT=200`。

### 3.2 写后失效：不是增量写入

```java
// service/base/OrderGeoPool.java（真实节选）
public void add(String orderId, BigDecimal startLng, BigDecimal startLat) {
    remove(orderId);
}

public void remove(String orderId) {
    if (orderId == null || orderId.isBlank()) return;
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate();
            }
        });
    } else {
        invalidate();
    }
}

private void invalidate() {
    try {
        redisTemplate.execute(RedisScripts.INVALIDATE_ORDER_GEO_POOL,
                List.of(ORDER_GEO_POOL_VERSION_KEY, ORDER_GEO_POOL_READY_KEY, ORDER_GEO_POOL_KEY),
                UUID.randomUUID().toString());
    } catch (RuntimeException e) {
        log.warn("订单地理池失效失败，等待固定 TTL 校准", e);
    }
}
```

`INVALIDATE_ORDER_GEO_POOL`：

```lua
-- util/RedisScripts.java（真实 Lua）
redis.call('SET', KEYS[1], ARGV[1])
redis.call('DEL', KEYS[2], KEYS[3])
return 1
```

- `add()` 的名字容易误解：它不把新订单加进池，而是调用 `remove()` 触发整池失效。
- `remove` 的时间点要分清：接单路径在 DB 条件更新成功后、事务提交前调用 `remove`；因为有事务同步，它只是登记 afterCommit，真正 `INVALIDATE` 在提交后执行。当前 `createOrder` 调用链无事务，`remove` 立即执行。
- 失效调用失败只记日志。Redis Lua 有原子隔离，但客户端异常/响应丢失时不能证明“已执行”或“未执行”；若 Lua 在 Redis 端发生运行时错误，已发生的命令也不会自动回滚。因此不能说旧池一定继续存在 2 小时，也不能说它一定已删除；实际状态需查询。
- 若失效成功，新版本写入且 ready/pool 被删除；后续搜索可能尝试重建。锁竞争、事务活跃、Redis/DB 错误或发布校验失败都可能使尝试不成功，不能无条件保证下一次重建成功。

### 3.3 冷启动读取与重建

`OrderGeoPool#search(lng, lat)` 的核心顺序：

```java
// service/base/OrderGeoPool.java（真实节选）
if (!DriverGeoIndex.validCoordinates(lng, lat)
        || TransactionSynchronizationManager.isActualTransactionActive()) {
    return Optional.empty();
}
try {
    Optional<GeoSlice> existing = read(lng, lat);
    if (existing.isPresent()) return existing;

    String lockKey = RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY;
    String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
    if (token == null) return Optional.empty();
    try {
        existing = read(lng, lat);
        if (existing.isPresent()) return existing;

        redisTemplate.opsForValue().setIfAbsent(ORDER_GEO_POOL_VERSION_KEY, UUID.randomUUID().toString());
        String version = redisTemplate.opsForValue().get(ORDER_GEO_POOL_VERSION_KEY);
        if (version == null) return Optional.empty();

        List<RideOrder> orders = rideOrderMapper.selectList(
            new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode()));

        List<String> args = new ArrayList<>();
        args.add(version);
        args.add(token);
        args.add(String.valueOf(INDEX_TTL.toMillis()));
        for (RideOrder order : orders) {
            if (order.getOrderId() == null || order.getOrderId() <= 0
                    || !DriverGeoIndex.validCoordinates(order.getStartLng(), order.getStartLat())) {
                continue;
            }
            args.add(order.getStartLng().toPlainString());
            args.add(order.getStartLat().toPlainString());
            args.add(order.getOrderId().toString());
        }
        Long published = redisTemplate.execute(RedisScripts.PUBLISH_ORDER_GEO_POOL,
                List.of(ORDER_GEO_POOL_VERSION_KEY, lockKey,
                        ORDER_GEO_POOL_READY_KEY, ORDER_GEO_POOL_KEY),
                args.toArray());
        return Long.valueOf(1).equals(published) ? read(lng, lat) : Optional.empty();
    } finally {
        redisLock.unlock(lockKey, token);
    }
} catch (RuntimeException e) {
    log.warn("读取订单地理池失败，降级为数据库时间排序", e);
    return Optional.empty();
}
```

重建锁由 `RedisLock` 提供：

- `tryLock` 用 `SET key token NX PX 10s`，竞争失败返回 null；不重入。
- `unlock` 用 `RELEASE_LOCK_IF_MATCH` 匹配 token 后删除；释放失败只记日志，由 TTL 兜底。

`PUBLISH_ORDER_GEO_POOL`：

```lua
-- util/RedisScripts.java（真实 Lua）
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[2] then
    return 0
end
redis.call('DEL', KEYS[3], KEYS[4])
for i = 4, #ARGV, 3 do
    redis.call('GEOADD', KEYS[4], ARGV[i], ARGV[i + 1], ARGV[i + 2])
end
local count = redis.call('ZCARD', KEYS[4])
redis.call('PEXPIRE', KEYS[4], ARGV[3])
redis.call('SET', KEYS[3], ARGV[1] .. ':' .. count, 'PX', ARGV[3])
return 1
```

- `KEYS[1]`=版本，`KEYS[2]`=重建锁，`KEYS[3]`=ready，`KEYS[4]`=GEO 池。
- `ARGV[1]`=快照版本，`ARGV[2]`=锁 token，`ARGV[3]`=池 TTL 毫秒；之后每 3 个参数是一条订单的 `lng,lat,orderId`。
- 先验证版本和锁 token；不匹配返回 0，不发布。
- 发布时先删 ready/pool，再全量 `GEOADD`，最后按 `ZCARD` 写 ready 并给池/ready 2 小时 TTL。
- 无有效订单也会发布空池：`count=0`，ready 是 `"version:0"`。

`SEARCH_ORDER_GEO_POOL`：

```lua
-- util/RedisScripts.java（真实 Lua）
local version = redis.call('GET', KEYS[1])
local ready = redis.call('GET', KEYS[2])
local count = redis.call('ZCARD', KEYS[3])
if not version or ready ~= version .. ':' .. count then
    return {}
end
local orders = redis.call('GEORADIUS', KEYS[3], ARGV[1], ARGV[2],
    ARGV[3], 'km', 'ASC', 'COUNT', ARGV[4], 'WITHDIST')
local result = {version}
for _, order in ipairs(orders) do
    table.insert(result, order[1])
    table.insert(result, order[2])
end
return result
```

- 只有 `ready == version:ZCARD(pool)` 才认为池就绪；能区分空池与索引丢失。
- `GEORADIUS` 按公里、距离升序、最多 200 个、带距离。源码注释说明选择 `GEORADIUS` 以兼容本地 Redis 5；实际部署 Redis 版本/命令可用性未验证。
- 返回 `[version, id1, dist1, id2, dist2, ...]`；Java `read()` 校验长度、id>0、距离有限且 >=0，坏结构返回 empty。

### 3.4 回表校验、排序与分页

`buildDistanceSortedPool`：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
List<RideOrder> alive = rideOrderMapper.selectList(new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
        .in(RideOrder::getOrderId, orderIds));

Map<Long, RideOrder> orderById = new HashMap<>();
for (RideOrder order : alive) orderById.put(order.getOrderId(), order);

if (orderById.size() != orderIds.size() || !orderGeoPool.isCurrent(slice)) {
    return legacyTimeSortedPool(p, s);
}

List<RideOrderVO> ordered = new ArrayList<>();
for (Long orderId : orderIds) {
    RideOrder order = orderById.get(orderId);
    if (order == null) continue;
    RideOrderVO vo = toVO(order);
    vo.setDistanceKm(BigDecimal.valueOf(distanceById.get(orderId))
            .setScale(2, RoundingMode.HALF_UP));
    ordered.add(vo);
}

int from = (int) Math.min((long) (p - 1) * s, ordered.size());
int to = (int) Math.min((long) from + s, ordered.size());
return PageResult.<RideOrderVO>builder()
        .page(p).size(s).total((long) ordered.size())
        .records(new ArrayList<>(ordered.subList(from, to)))
        .build();
```

实现的顺序不是按方法名猜：

1. **筛选 1（Redis）**：`SEARCH_ORDER_GEO_POOL` 只返回 3 公里内、距离升序、最多 200 个候选。如果有超过 200 个，距离更远的订单不在这份 slice 中；因此 `total` 是候选数，不是 3 公里内全部订单数。
2. **筛选 2（DB）**：回表查询这批候选，SQL 本身只返回 `is_deleted=0/order_status=10` 的行。
3. **一致性校验**：`buildDistanceSortedPool` 比较 `DB 行数 == 候选数`，并调用一次 `isCurrent(slice)`。任一缺失、多出候选或版本不一致，就调用 `legacyTimeSortedPool` **整页**改为 DB 时间排序；不是把坏候选过滤掉后继续返回原距离页。
4. **排序**：校验通过后按 Redis 返回的候选顺序重排；SQL `IN` 不保证顺序。
5. **分页**：在内存中对最多 200 个候选切片；`total` 是候选数，不再 `selectCount`。
6. **距离单位**：Redis 返回公里，Java 设置两位小数 `HALF_UP`。

并发窗口：回表、`isCurrent` 检查、构造 VO 和响应返回不是同一个原子操作。订单 10 可能在检查通过后、响应返回前被接走；这页仍可能显示它。稳定空池只在候选为空且 `isCurrent` 为 true 时直接返回空池，不回表，因此不能发现漏单、坐标缺失或 Redis 内容损坏。

降级路径 `legacyTimeSortedPool`：

- 查询 `is_deleted=0`、`order_status=10`。
- `orderByDesc(create_time, order_id)`。
- SQL `limit offset,size`，`offset=(p-1)*s`。
- 先 `selectCount` 得 total；没有距离字段，`RideOrderVO.distanceKm` 为 null。

### 3.5 失效、并发重建与不一致窗口

| 场景 | 顺序 | 结果 | 剩余窗口 |
|---|---|---|---|
| 新订单创建成功 | `createOrder` 插入 MySQL；当前无事务，`orderGeoPool.add` 立即失效整池 | 若失效成功，后续重建查询可能包含仍为待接单且坐标有效的新订单 | 锁竞争、错误、订单状态或坐标变化均可能影响结果；失效失败时旧池可能继续存在 |
| 接单成功 | `driverAcceptOrder` 先条件更新（未提交），在事务内调用 `orderGeoPool.remove` **登记** afterCommit；代理提交后执行失效 | 提交后失效整池 | 登记到 afterCommit 执行前，池里可能有刚被接走的订单；若此时读到 slice，是否降级取决于回表与 `isCurrent` 的时序 |
| 接单 afterCommit 失效失败/结果未知 | MySQL 已是 20；Redis 失效调用异常或响应丢失 | Redis 端可能旧池保留、部分保留或未按预期失效 | 只能实际查询确认；不能断言旧池一定保留到 2 小时，也不能断言已失效；旧候选被读到且回表数量或版本校验失败时才会触发相应降级 |
| 系统取消/无人接单超时 | `systemCancel` 后按事务/无事务路径失效池 | 若失效及后续重建成功，DB 已取消的订单不会进入该快照 | 同上 |
| 到达超时退回车池 | 20→10 后 `orderGeoPool.add`；O4 细化 | 若失效及后续重建成功，且订单仍待接单、坐标有效，快照可包含它 | 同上 |
| Redis 故障 | `location` 返回 UNAVAILABLE；`search` 异常返回 empty | 司机池查询降级 DB 时间排序 | 无距离排序；不能断言司机离线 |
| 重建锁竞争失败 | 另一个请求正在重建 | `search` 返回 empty；`getDriverOrderPool` 走 DB `selectCount` + 时间倒序列表查询 | 不等待重建完成；**会带来这次 DB count+list 查询**，不能说“不增加 DB 压力” |
| 重建期间 DB 变更且失效成功 | T2 在 T1 提交前持锁并查到旧数据；T1 提交后 afterCommit 把版本改成 V2 | T2 以旧版本 V1 发布被拒 | 本次 search 返回 empty 降级；不能保证下一次重建一定成功或一定发布 |
| 重建发布后才失效 | T2 先 `PUBLISH(V1,L1)` 成功，随后 T1 afterCommit `INVALIDATE(V2)` | 旧快照被删；如果调用方已拿到旧 slice，`isCurrent` 为 false 时整页降级 | 若版本检查/响应在失效前完成，客户端可能看到旧页；不能说 `isCurrent` 一定发现变化 |
| 锁过期竞争 | 旧持有者查询慢，锁过期/被新 token 接管 | `PUBLISH` 校验版本/token 不匹配则返回 0；旧者 finally 解锁 token 不匹配，不会删新锁 | 是否有新持有者发布、何时发布取决于后续 `search`；不能无条件保证旧池/新锁最终状态 |

### 3.6 一致性结论

- 不是“写入 Redis 后立即完整”：创建/接单/取消通过失效整池，通常下一次成功搜索才重建；但锁竞争失败、事务活跃、Redis/DB 错误、锁过期、发布校验失败都不会自动保证下一次重建成功。
- 不是“Redis 池一定完整”：失效失败/结果未知时，旧池可能继续存在。若旧池未被外部延长 TTL，其固定 TTL 上限是 2 小时；若有人设置过期时间，这个上限不适用。
- Redis 候选不是“过滤后返回”：只有候选数量与回表行数一致、版本一致才返回距离页；任一条件失败改为整页 DB 时间排序。回表/版本检查之后到响应返回之间仍可变化。
- 版本不变不能证明没有漏单、坐标完整或 Redis 内容正确；稳定空池只是当前缓存快照一致，不能发现漏掉的新订单。
- 不是“任何 Redis 故障都让司机离线”：司机位置 Redis 故障返回 UNAVAILABLE，附近池降级 DB。
- 不是“强一致分页”：失效后、重建前的窗口，或响应在失效前完成返回，都可能看到旧结果；服务端下一次请求会重新判定，但客户端这一页可能已经显示。

---

## 四、故障与并发时间线

### 4.1 时间线 A：接单失效与池重建竞争

可达前提：

- 池缺失或未就绪，但版本 key 已有 V1；司机 2 的 `search` 已经取得重建锁 L1。
- T2 是一次独立的 `getDriverOrderPool`，**当前没有 Spring 事务活跃**（`OrderGeoPool#search` 在实际事务活跃时直接 empty），所以不存在“T2 挂着外层事务旧快照”的假设。
- T1 在 T2 的 DB 查询尚未返回/尚未发布时执行接单：DB 更新未提交时 T2 普通读看不到 20；T1 提交后 T2 才能看到 20。

| 时刻 | T1：司机 1 接单 | T2：司机 2 查附近池 | Redis/DB |
|---|---|---|---|
| e0 | T1 `tryGrab` 成功，DB UPDATE order10=20 未提交 | T2 已持重建锁 L1；先 double-check 仍无池，再读取当前版本 V1 | V1；锁 L1；池未就绪 |
| e1 | T1 调用 `syncOrderStatus`、`orderGeoPool.remove`，只登记 afterCommit | T2 查询 DB：此刻 T1 未提交，普通读看到 order10=10 | V1；T1 有未提交修改 |
| e2 | T1 可选刷新位置并登记到达超时；方法返回后代理提交 | T2 仍在处理 DB 结果 | T1 已提交后 order10=20 |
| e3 | T1 afterCommit：`INVALIDATE` 写 V2、DEL ready/pool | T2 准备 `PUBLISH(V1,L1)` | 当前版本 V2；无 ready/pool |
| e4 | — | `PUBLISH(V1,L1)` 校验失败，返回 0 | 不发布旧快照 |
| e5 | — | T2 search 返回 empty；finally 释放锁；`getDriverOrderPool` 走 DB count+list 降级 | 无 ready/pool |
| e6 | — | 另一次 `search` 成功持锁并重建 | DB 已能看到 order10=20，新池不含 10 |

注意：这条时间线要求 T2 的 DB 查询发生在 T1 提交前；若 T2 在 T1 提交后才查询 DB，它会直接读到 20，重建的新池本来就不含 10。不能把“读旧快照”扩大成整个 search 都读提交前快照。

**发布先于失效的路径**：若 T2 在 T1 afterCommit 执行前就 `PUBLISH(V1,L1)` 成功，会短暂发布含 10 的旧快照。随后 T1 失效设置 V2 并删除 ready/pool。结果分三种：

1. T1 失效发生在 T2 调用 `buildDistanceSortedPool#isCurrent` 前：版本不一致，整页 DB 时间排序降级。
2. T1 失效发生在版本检查后、响应返回前：`isCurrent` 已经通过，这一页可能仍返回给客户端。
3. T1 失效发生在响应已经返回后：客户端可能短暂看到旧页；下一次请求重新判定。
   不能说 `isCurrent` 必定发现变化，也不能保证后续重建一定成功。

**失效在版本检查之前/之后**：`INVALIDATE` 设置新版本和删除 ready/pool 是一次 Lua。若它先执行，`PUBLISH(V1)` 被版本校验拒绝；若它在发布后执行，旧池被删除/版本被替换；若它发生在调用方版本检查后，当前响应不受该检查保护。

### 4.2 时间线 B：接单位置刷新与主动下线

前提：司机 1 在线；`driverAcceptOrder` 的 DB 条件更新成功，请求带经纬度；一个主动下线竞争。

| 时刻 | T1：接单事务 | T2：主动下线 | 结果 |
|---|---|---|---|
| f0 | DB UPDATE 成功；登记状态/GEO afterCommit；尚未提交 | — | 司机在线；order10 未提交 |
| f1 | `refreshIfExist` 在提交前直接执行成功 | — | 司机在线，坐标更新 |
| f2 | — | `REMOVE` Lua 成功 | 司机离线 |
| f3 | 代理提交/回滚；afterCommit 只在提交后执行 | — | 提交：order10=20 且司机离线；回滚：order10 回 10，但刷新结果已被下线删除 |

反向：T2 先下线，T1 的 `refresh` 后执行会返回 0；不会把离线司机重新上线。**后续 `online` 心跳仍可重新上线**，所以不能写“永远稳定离线”。若 Redis 异常或响应丢失，两边都只能返回 false/异常，Redis 端是否已写未知，需要重新查询。

---

## 五、key、TTL 与原子范围

### 5.1 汇总表

| key | 类型 | TTL / 生命周期 | 写入者 | 重建/删除 |
|---|---|---|---|---|
| `driver:geo:online` | GEO | key 无 TTL；成员由心跳判定 | `UPDATE_DRIVER_LOCATION` | `REMOVE`/读时清理/refresh 清理 |
| `driver:online:beat` | Hash | key 无 TTL；field 值为 epoch 毫秒 | 同上 | 同上 |
| `user:loc:{userId}` | Hash | 无 TTL；仓库未见业务删除 | `saveUserLocation` 三次 HPUT | 无业务删除；Redis 清空后需重新上报 |
| `order:geo:pool` | GEO | 发布后 2 小时 | `PUBLISH_ORDER_GEO_POOL` | `INVALIDATE` DEL；过期后重建 |
| `order:geo:pool:ready` | String | 发布后 2 小时 | `PUBLISH` 写 `"version:count"` | 同上 |
| `order:geo:pool:version` | String | 无 TTL | `INVALIDATE`/`search` 的 `setIfAbsent` | 被新版本覆盖 |
| `order:geo:pool:rebuild:lock` | String | 10 秒 | `RedisLock.tryLock` | 匹配 token 释放；过期后由新 token 接管 |

### 5.2 原子范围与故障语义

- 司机上线/刷新/读取/下线：各自一次 Lua，原子范围是该脚本内的 Hash/GEO 操作；不包含 MySQL。
- 池失效：一次 Lua 设置版本并删除 ready/pool；不包含 MySQL 提交。
- 池发布：一次 Lua 校验版本/token、删除旧池、全量 GEOADD、写 ready/TTL；不包含 DB 查询。
- 池搜索读取：一次 Lua 检查 ready、`GEORADIUS`；不包含回表 DB 查询。
- 乘客位置保存：三次独立 `HPUT`，不是 Lua/事务；部分成功可能留下不完整 Hash。
- Lua 的原子隔离表示其他命令不会插到同一脚本中间；脚本运行时错误不自动回滚已执行命令。例如上线脚本 `GEOADD` 成功后 `HSET` 出错，GEO 可能已有成员。Java 收到 false/异常时，不能证明 Redis 端没有任何写入。
- 多 key Lua 在 Redis Cluster 中要求所有 key 同一 slot。本章这些 key 没有显式 hash tag；是否满足、是否使用 Cluster 需核验部署条件，本章未连接 Redis。

---

## 六、测试阅读：覆盖什么、没覆盖什么

### 6.1 `GeoPoolRegressionTest`

- 不需要外部端口；mock `RideOrderMapper`、`OrderGeoPool`、`DriverGeoIndex`、`RedisLock` 等。
- 覆盖：
  - 离线司机返回空池且不查 DB/池。
  - Redis 位置故障（UNAVAILABLE）降级 DB，而不是判为离线。
  - 距离池结果按 Redis 距离排序，SQL 乱序也不影响；分页不查 `selectCount`。
  - 不可用/缺行/版本变化/空池版本变化都整页降级。
  - 稳定空池不查 DB；超大分页不溢出。
  - 参数非法先拒绝，不触缓存/DB。
  - 司机上报纬度 86、经度 181 被拒绝；状态接口把 Redis 故障报 503。
  - `OrderGeoPool.add/remove` 等待提交，回滚不发布；事务内不能 search。
  - 只有接单 DB 更新 1 行才失效池并刷新司机位置；0 行都不调用。
  - 到达超时退回车池调用 `add`；系统取消调用 `remove`。
  - GEO 缓存失败不破坏已提交业务。

### 6.2 `GeoPoolRedisIntegrationTest`

门控：`@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")`；需要显式独立 Redis。

- mock `RideOrderMapper`；使用真实 Redis、Lua、TTL、RedisLock。
- 每轮清理 `order:geo:pool*` 和 `driver:geo:online`、`driver:online:beat`。
- 覆盖：
  - 冷启动变更后能重建；3 公里内按距离排序；第二次搜索不重复查 DB。
  - 空 warm 池不反复重建。
  - 成员变化使旧版本失效，重建整池。
  - GEO key 缺失但 ready 残留会触发重建。
  - 部分损坏池被替换而不是追加。
  - DB 变更使旧快照不能发布。
  - 重建锁过期后，旧 token 不能发布或误删新 token。
  - 重建竞争时第二次 `OrderGeoPool.search` 返回 empty；该测试使用 mock Mapper，仅验证池重建查询次数，不覆盖上层 HTTP 路径的 DB count+列表降级成本。
  - 池与 ready 的 TTL 对齐约 2 小时。
  - 非法订单坐标不会破坏有效订单。
  - 司机上线/下线同时清理两个成员；共享 key `TTL=-1`。
  - 接单 refresh 不能复活离线/过期司机；在线 refresh 更新位置且不创建其他司机。
  - 过期/孤儿/非法心跳逐成员清理，不影响另一个司机。
  - 非法坐标不会留下心跳。

**注意**：这些测试没有由我们运行；只在显式提供独立 Redis 时执行。

### 6.3 未覆盖

- 真实 HTTP/权限切面、真实 token → driverId/userId。
- `UserLocation` 保存/读取没有找到专门测试；三次 HPUT 的部分失败、非法坐标输入未验证。
- 真实 MySQL 连接与并发；测试只 mock Mapper。
- 多实例部署、Redis 主从切换、真实时钟偏差、网络分区。
- `GEORADIUS` 在目标 Redis 版本的真实可用性；仓库只在源码注释中说明兼容本地 Redis 5。

---

## 七、动手验证

### 练习 A：只读追踪司机上线与附近查询

前提：陈师傅调用 `POST /order/driver/location` 上传 `lng=120,lat=30`，随后调用 `GET /order/driver/pool/page?page=1&size=10`。

请按源码写出：校验、Lua key/ARGV、Redis 结构变化、`getDriverOrderPool` 的分支、附近池未就绪时的降级。

<details>
<summary>练习 A 参考答案</summary>

上线：`@RequirePermission({"DRIVER"})` 通过后，`DriverLocationServiceImpl` 校验非空和坐标范围；`DriverGeoIndex#goOnline` 执行 `UPDATE_DRIVER_LOCATION`。`KEYS[1]=driver:online:beat`，`KEYS[2]=driver:geo:online`；ARGV 为 driverId、`"120"`、`"30"`、`"60000"`、`"online"`。Lua 用 Redis `TIME` 得 now，`GEOADD driver:geo:online 120 30 7002002`，`HSET driver:online:beat 7002002 now`，返回 1。两个 key 本身无 TTL。

附近查询：`GET /order/driver/pool/page` 通过 DRIVER 切面；`getDriverOrderPool` 校验 page/size，读取司机位置。若 `READ_DRIVER_LOCATION` 返回坐标，状态 ONLINE；若离线返回空池；若 Redis 故障返回 UNAVAILABLE，附近池降级 DB 时间排序。在线时把坐标交给 `OrderGeoPool.search`。若 `SEARCH_ORDER_GEO_POOL` 发现 ready 不等于 `version:ZCARD`，返回 empty；`search` 尝试拿 10 秒重建锁，重建失败/拿不到锁也返回 empty；`getDriverOrderPool` 再降级 DB。若池就绪，先 Redis 筛选 3 公里内最多 200 个候选，再回表校验 `is_deleted=0/status=10`，版本一致才按距离排序并分页。
</details>

### 练习 B：接单失效与重建竞争

前提：池未就绪但版本 key 已有 V1；司机 2 的 `search` 已取得重建锁 L1，并在司机 1 的接单提交前查询到订单 10 仍是 10；司机 1 接单 10 成功后提交并执行 afterCommit 失效。

请写出 T1/T2 的时序，说明旧快照为什么不会发布；再说明若旧快照已发布，后续是否一定被发现。

<details>
<summary>练习 B 参考答案</summary>

T1 在事务内成功条件更新 order10=20（未提交），随后调用 `orderGeoPool.remove`；因为有事务同步，这只是登记 afterCommit，真正的 `INVALIDATE_ORDER_GEO_POOL` 等代理提交后执行，设置 V2 并删除 ready/pool。T2 是独立请求，没有 Spring 事务；它已持锁 L1、读 V1，并在 T1 提交前的普通读中看到 order10=10。当 T1 失效完成后，T2 的 `PUBLISH(V1,L1)` 检查当前版本是 V2，与 ARGV V1 不匹配，返回 0；旧快照不会发布。T2 finally 释放锁，`getDriverOrderPool` 收到 empty 后走 `legacyTimeSortedPool`，发生一次 `selectCount` + 时间倒序列表查询。下一次重建是否成功取决于后续 `search` 是否能持锁、Redis/DB 是否可用、版本/token 是否匹配。

若 T2 的 `PUBLISH(V1,L1)` 在 T1 afterCommit 之前成功，旧快照会短暂发布。随后 T1 失效设置 V2 并删除 ready/pool。只有当 T1 失效发生在 T2 调用 `isCurrent` 之前，T2 才会整页降级；若失效发生在版本检查后，或响应已经返回，客户端可能看到旧页。不能说 `isCurrent` 必定发现变化。
</details>

### 练习 C：接单位置刷新与主动下线

前提：司机 1 在线；`driverAcceptOrder` 的条件更新已成功且请求带经纬度；一次主动下线与接单位置刷新竞争。

请说明两种 Redis 命令顺序的结果、事务提交/回滚的影响；若 Redis 异常会怎样。

<details>
<summary>练习 C 参考答案</summary>

`refreshIfExist` 不是 afterCommit；它在条件更新成功后、事务提交前直接执行。若 refresh 先执行，它更新心跳/GEO；随后下线 Lua 原子删除两成员。若下线先执行，refresh 发现心跳/GEO 缺失，清理并返回 0；不会因为这次刷新复活。若接单事务随后提交，订单是 20 且司机离线；若回滚，order10 回到 10，但已成功的 Redis 刷新不会被撤销。后续司机调用 `POST /order/driver/location` 的 `online` 模式仍可重新上线，所以不能说“永远稳定离线”。若 Redis 异常/响应丢失，Java 只能看到 false/异常，Redis 是否已写未知，需实际查询。
</details>

### 练习 D：六个判断

判断“正确 / 错误 / 取决于条件”，并说明理由。

1. 司机心跳 Hash key 设置了 60 秒 TTL。
2. `OrderGeoPool.add(orderId, lng, lat)` 会把该订单增量加入 GEO 池。
3. Redis GEO 返回的候选一定还能接。
4. 附近池 Redis 故障时司机一定显示离线。
5. 乘客位置保存会校验经纬度范围。
6. `order:geo:pool:version` 有 2 小时 TTL。

<details>
<summary>练习 D 参考答案</summary>

1. 错误。`driver:online:beat` 这个 key 无 TTL；60 秒是成员心跳窗口，通过比较 field 里的 epoch 毫秒判定。
2. 错误。`add()` 调用 `remove()`，触发整池失效；不是增量 `GEOADD`。
3. 取决于检查结果与时序。回表要求候选数量等于 DB 查到的 `is_deleted=0/status=10` 行数，且 `isCurrent` 通过；否则整页降级。即使通过，响应前仍可能并发变化。
4. 错误。位置读取 Redis 故障返回 UNAVAILABLE；`getDriverOrderPool` 会降级 DB 时间排序，不判离线。状态接口才会 503。
5. 错误。`saveUserLocation` 只把 `latitude/longitude/address` 字符串写入 Hash；没有范围/数字校验。司机上报接口才有校验。
6. 错误。版本 key 无 TTL；GEO 池和 ready 标记发布后有 2 小时 TTL。
</details>

> 可选实验：`GeoPoolRedisIntegrationTest` 需要 `-Dtest.redis.port` 指向一次性专用 Redis。它每轮用 `KEYS order:geo:pool*` 找 key 并删除，还删除固定 key `driver:geo:online`、`driver:online:beat`；需要可执行这些删除的权限。MySQL Mapper 被 mock，不建库，但 Redis 清理范围如上。不满足隔离条件时只做静态阅读。本章没有运行任何实验。

---

## 八、一页复习

- **司机在线**：GEO `driver:geo:online` + 心跳 Hash `driver:online:beat`；两个 key 无 TTL，成员级 60 秒判定；Redis `TIME` 为准。
- **上线 vs 心跳**：`POST /order/driver/location` 同一端点，`online` 模式可上线；接单更新成功后、提交前的 `refreshIfExist` 只刷新仍在线司机，这次不复活，但后续 `online` 心跳可上线。
- **下线/过期**：`REMOVE` 或读时清理都会同时删 Hash field 和 GEO member；不会影响其他司机。
- **位置坐标**：司机上报先 lng 后 lat；lng `[-180,180]`，lat `[-85.05112878,85.05112878]`。乘客位置是字符串 Hash，未校验。
- **附近池**：`add()` 实为失效；失效设新版本并删除 ready/pool；下一次**成功持锁且发布校验通过**的搜索才全量重建 `CREATED(10)` 订单。
- **池一致性**：`ready == version:count` 才就绪；GEO/ready 2 小时；版本无 TTL；重建锁 10 秒；发布校验版本/token。
- **DB 权威**：距离路径最多 200 候选，`total` 是候选数；回表比较候选数量与 `is_deleted=0/status=10` 行数，版本不一致任一失败整页 DB 时间排序，不是继续过滤分页；检查后仍有并发窗口。
- **故障语义**：司机位置 Redis 故障是 UNAVAILABLE/503；附近池 Redis 故障或未就绪降级 DB 时间排序，不等于司机离线。
- **乘客位置**：`user:loc:{userId}` Hash，无 TTL、无业务删除；保存是三次 HPUT，部分失败可能不完整。

---

## 九、自测与答案

**1. 为什么司机 GEO key 不能设置整体 TTL？**

<details>
<summary>参考答案</summary>

`driver:geo:online` 是所有司机共享的 GEO key。如果给它设置 TTL，任何一个人的心跳刷新都可能延长整个 key 的寿命；只要还有一个司机在线，离线成员就永远不会因 key 过期消失。因此 key 不设 TTL，把存活判定放到 `driver:online:beat` 的成员级 epoch 毫秒，读取/刷新时逐成员清理。
</details>

**2. 司机 1 的心跳过期时，会影响司机 2 吗？**

<details>
<summary>参考答案</summary>

不会。`READ_DRIVER_LOCATION` 只读/删 `KEYS[1]` 中 ARGV[1] 这个 field 和 `KEYS[2]` 中同一个 member。清理司机 1 的过期心跳和坐标不会删除司机 2 的 field/member。集成测试也断言了“过期司机清理不影响另一个司机心跳”。
</details>

**3. 接单成功后的 `refreshIfExist` 为什么不会把离线司机上线？**

<details>
<summary>参考答案</summary>

它在 DB 条件更新成功后、事务提交前直接调用 `UPDATE_DRIVER_LOCATION` 的 `refresh` 模式。该模式要求旧心跳存在、`beat <= now`、`now - beat < 60000`（恰好 60 秒已过期）且 GEO 成员存在；缺失、未来时间、过期都清理两个成员并返回 0。这次刷新不会上线；后续司机用 `reportLocation` 的 `online` 模式仍可上线。
</details>

**4. `OrderGeoPool.add()` 为什么不直接 GEOADD？**

<details>
<summary>参考答案</summary>

它的实现是 `remove(orderId)`，触发整池失效。这样做避免“单笔入池”与并发接单/取消/重建交错造成残缺索引；下一次成功持锁且发布校验通过的搜索会从 MySQL 查询全部 `CREATED(10)` 订单并全量发布。缺点是新订单要等重建才可见；失效失败/结果未知时旧池可能保留，固定 TTL 上限只在旧 key 未被外部延长时适用。
</details>

**5. Redis 池里的候选为什么还要回表？**

<details>
<summary>参考答案</summary>

池是缓存，失效与重建之间有时间差；候选可能已被接走、取消或逻辑删除。`buildDistanceSortedPool` 回表查询这批候选中 `is_deleted=0/status=10` 的行，然后比较 DB 行数与候选数，并调用一次 `isCurrent`。任一失败就调用 `legacyTimeSortedPool` 整页改为 DB 时间排序，不是过滤后继续返回原距离页。回表和版本检查之后到响应返回之间仍可变化，因此不能保证页面绝不显示已变化订单。
</details>

**6. 重建锁过期后，旧持有者还能发布旧快照吗？**

<details>
<summary>参考答案</summary>

不能。`PUBLISH_ORDER_GEO_POOL` 先校验当前版本和当前锁 token；锁已过期时旧 token 不匹配，锁被新持有者取代时 token 也不匹配，都会返回 0。旧持有者 finally 的 `RedisLock.unlock` 只删除 token 匹配的锁，不会误删新持有者的锁。若旧持有者的锁仍然有效，那就不属于“过期后竞争”的前提，可以正常发布。
</details>

**7. 附近池 Redis 故障时，为什么还能看到订单？**

<details>
<summary>参考答案</summary>

`DriverGeoIndex#location` 返回 UNAVAILABLE 时，`getDriverOrderPool` 不把它当 OFFLINE，而是走 `legacyTimeSortedPool` 查询所有 `CREATED(10)` 订单，按 `create_time DESC, order_id DESC` 分页。此时没有距离字段，`distanceKm=null`。状态接口才会把 Redis 故障映射为 503。
</details>

**8. 乘客位置和司机在线位置有什么区别？**

<details>
<summary>参考答案</summary>

乘客位置是 `user:loc:{userId}` Hash，保存 `latitude/longitude/address` 字符串；无 TTL、无业务删除、保存时不校验。司机在线位置是共享 GEO + 成员级心跳，用于附近查询圆心；有 60 秒窗口、坐标范围校验、原子清理和下线接口。两者不是同一数据，也不能互相替代。
</details>

---

## 十、导航与源码入口

- **上一章**：[第 8 章 抢单与并发](02-grab-and-concurrency.md)——接单成功后为什么失效 GEO 池、为什么 `refreshIfExist` 不复活离线司机。
- **下一章**：[第 10 章 超时与恢复](04-timeouts-and-recovery.md)——ZSet 待办、三条超时迁移、到达超时退回车池与数据库兜底。
- **相关专题**：
  - 锁令牌、租约与版本租约横向对比 → [第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)
  - Agent 提示词读取乘客位置 → [第 20 章 外部工具与提示词边界](../04-chat-and-agent/05-external-tools-and-prompts.md)

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 司机位置端点 | `controller/RideOrderController.java#reportDriverLocation/#driverOffline/#driverLocation`；内部类 `DriverLocationReqDTO` |
| 司机位置服务 | `service/impl/DriverLocationServiceImpl.java#reportLocation/#goOffline/#getStatus`；`domain/vo/DriverLocationVO.java` |
| 司机在线结构 | `service/base/DriverGeoIndex.java#validCoordinates/#goOnline/#refreshIfExist/#goOffline/#location`；`domain/dto/Point.java` |
| 司机 Lua | `util/RedisScripts.java#UPDATE_DRIVER_LOCATION/#REMOVE_DRIVER_LOCATION/#READ_DRIVER_LOCATION`；`constant/RedisKeyConstants.java#DRIVER_GEO_ONLINE_KEY/#DRIVER_ONLINE_BEAT_KEY` |
| 附近池入口 | `controller/RideOrderController.java#driverPool`；`service/impl/RideOrderServiceImpl.java#getDriverOrderPool/#buildDistanceSortedPool/#legacyTimeSortedPool/#emptyPool` |
| 池失效/重建 | `service/base/OrderGeoPool.java`；`service/base/RedisLock.java`；`util/RedisScripts.java#INVALIDATE_ORDER_GEO_POOL/#PUBLISH_ORDER_GEO_POOL/#SEARCH_ORDER_GEO_POOL/#RELEASE_LOCK_IF_MATCH` |
| 池 key/常量 | `constant/RedisKeyConstants.java#ORDER_GEO_POOL_KEY/#ORDER_GEO_POOL_READY_KEY/#ORDER_GEO_POOL_VERSION_KEY/#ORDER_GEO_POOL_REBUILD_LOCK_KEY` |
| 订单起点字段 | `domain/entity/RideOrder.java`；`src/main/resources/sql/init.sql#sys_ride_order`（`start_lng/start_lat`） |
| 乘客位置 | `controller/UserController.java#getUserLoc/#saveUserLoc`；`service/impl/UserServiceImpl.java#saveUserLocation/#getUserLocation`；`constant/RedisKeyConstants.java#userLocKey`；`domain/dto/UserLocation.java` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/GeoPoolRegressionTest.java`、`GeoPoolRedisIntegrationTest.java` |
