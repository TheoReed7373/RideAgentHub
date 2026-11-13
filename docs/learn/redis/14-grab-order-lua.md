# 司机抢单：Lua 原子预检

> **Redis 考点**：秒杀模型——「判断可抢」与「占住司机」必须在同一次执行内完成，否则两个并发请求可以同时通过判断；正确性由数据库行锁与条件更新最终裁决。
> **来源**：`docs/01-redis-application-points.md` §2.5（订单与司机）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/OrderGrabService.java`、`src/main/java/com/fancy/rideagenthub/service/impl/RideOrderServiceImpl.java`（`driverAcceptOrder`）

---

## 一、业务场景

司机抢单与电商秒杀是同构问题：

| 秒杀 | 抢单 |
|---|---|
| 优惠券库存（=1） | 待接单订单（`order_status=CREATED` 且 `driver_id IS NULL`） |
| 抢购用户 | 接单司机 |
| 一人一单校验 | 一个司机同时只能有一个进行中订单 |
| Lua 原子扣库存 | Lua 原子「订单可抢 + 司机空闲」判断与占位 |

两个约束**必须在同一临界区**：订单唯一（多人抢一单）与司机唯一（一人抢多单）。分开校验的话，同一司机并发请求两个订单可能双双通过「司机空闲」的判断。

Redis 只承担**快速预检与占位**，正确性由数据库兜底——即使 Redis 缓存过期或丢失（`driver:active` / `order:status` 是 5 分钟 TTL 的缓存语义），DB 的行锁与条件更新仍然保证约束成立。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `driver:active:{driverId}` | String | 5 分钟（`CACHE_TTL`） | `__IDLE__` = 空闲；订单号 = 被该订单占用（占位） |
| `order:status:{orderId}` | String | 5 分钟 | 订单状态码缓存 |

占位值是**订单号**：释放时 `RELEASE_LOCK_IF_MATCH` 校验「值等于本订单」才删——司机随后接到的新订单占位不会被旧订单的回滚误删。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `OrderGrabService.tryGrab` | `@Transactional(MANDATORY)`：司机账号行锁 → 在途订单行锁 + **顺带修复占位缓存** → 订单状态缓存惰性预热 → `GRAB_ORDER_ATOMIC` 抢占 → 注册回滚回调 |
| `OrderGrabService.syncOrderStatus` | 提交后 `SYNC_ORDER_STATUS` 单调推进状态缓存 |
| `OrderGrabService.refreshOrderStatus` | 提交后删除状态缓存（下次重建） |
| `OrderGrabService.rollbackGrab` / `releaseDriver` | `RELEASE_LOCK_IF_MATCH` 校验令牌后释放占位 |
| `RideOrderServiceImpl.driverAcceptOrder` | `withOrderLock`（订单锁）内调 `tryGrab` → DB 条件更新 → 成功后同步状态 / 失效 GEO 池 / 刷新司机位置 / 改期延迟队列 |
| `RedisScripts.GRAB_ORDER_ATOMIC` | 原子预检 + 占位 |
| `RedisScripts.SYNC_ORDER_STATUS` | 状态单调推进 |

---

## 四、实现拆解

### 4.1 `GRAB_ORDER_ATOMIC`：判断与占位一步完成

```lua
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return -1 end    -- 订单状态 ≠ CREATED
if redis.call('GET', KEYS[2]) ~= ARGV[2] then return -2 end    -- 司机占位 ≠ __IDLE__
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])             -- 占位：写入订单号，5 分钟
return 1
```

- 返回 `-1`（订单不可抢）/ `-2`（司机忙）/ `1`（占位成功）——失败原因语义化；
- 「判断」与「占位」之间**没有间隙**：两个并发请求只有一个能把 `__IDLE__` 换成自己的订单号。

### 4.2 事务内的两道 DB 兜底：`tryGrab` 为什么标 `MANDATORY`

`tryGrab` 必须在调用方的事务内执行（无事务直接抛异常），事务内做两件事：

1. **司机账号行锁**：`userAuthMapper.selectOne(... FOR UPDATE)`——同一司机的所有接单事务在账号行上串行化。这是**跨订单**的一人一单约束（订单锁只锁单笔订单，见 [13 篇](13-distributed-lock.md) 4.5）；
2. **在途订单行锁 + 缓存修复**：查司机当前在途订单（`FOR UPDATE`），把 `driver:active` **修复**为真实状态（`__IDLE__` 或在途订单号）——缓存过期或丢失时，行锁查询顺带重建（「事务内预热」，见 [06 篇](06-null-sentinel-and-prewarm.md) 4.2）。

**占位是 Redis 的，约束是 DB 的**：`tryGrab` 先查询并锁定司机账号及在途订单，再执行 Redis 预检；不能描述成「无效请求都在访问 DB 前被挡住」。最终是否接单成功由订单条件更新裁决。

### 4.3 DB 条件更新：最终裁决

`driverAcceptOrder` 在 `tryGrab` 成功后执行条件更新：

```java
rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, orderId)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())   // 仍是待接单
        .isNull(RideOrder::getDriverId)                                      // 仍无司机
        .set(RideOrder::getDriverId, driverIdLong)
        .set(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
        ...);
```

`updated == 0`（预检通过但 DB 已被抢走 / 取消）时：`rollbackGrab` 归还占位 + `refreshOrderStatus` 删缓存，返回失败——**预检只是加速，DB 才是最终裁决者**。

### 4.4 回滚与释放：只还本订单的占位

`tryGrab` 抢占成功后注册 `afterCompletion` 回调：事务**回滚**时 `rollbackGrab`——`RELEASE_LOCK_IF_MATCH` 校验占位值等于**本订单**才删。`releaseDriver`（订单正常终态时调用）同样语义：**只释放该订单持有的占位，保护司机随后接到的新订单**。

### 4.5 `SYNC_ORDER_STATUS`：状态单调推进

```lua
local current = redis.call('GET', KEYS[1])
if current == false or tonumber(current) == nil or tonumber(current) <= tonumber(ARGV[1]) then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 1
end
return 0
```

在缓存 key 存续期间，`SYNC_ORDER_STATUS` 只允许状态码不降低：一个迟到提交的旧状态回调（例如网络延迟的「已接单」同步）不能把「行程中」的缓存改回「已接单」。注意 `current == false or tonumber(current) == nil` 的双重判断——**不存在的 key 在 Lua 里返回 false 而非 nil**（[10 篇](10-lua-scripts-overview.md) 的陷阱）。

### 4.6 接单成功后的联动

`driverAcceptOrder` 成功路径一次串起四件事：

- `syncOrderStatus(DRIVER_ACCEPTED)`——提交后单调推进缓存；
- `orderGeoPool.remove(orderId)`——订单离开待接单，提交后失效附近订单索引；
- `driverGeoIndex.refreshIfExist(driverId, lng, lat)`——用请求里携带的坐标**刷新仍在线的司机位置**（`refreshIfExist` 而非 `goOnline`：未上线的司机不该因为接单就凭空出现在在线池）；
- `orderDelayQueue.schedule(到达超时)`——覆盖「无人接单」待办，改为盯「司机是否按时到达」。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Lua 预检 + DB 兜底 | 纯 DB（行锁 + 条件更新） | 当前预检前已经查询 DB 并加司机行锁；Lua 提供状态与占位的原子检查，不能宣称使失败请求免于访问 DB |
| 占位值 = 订单号 | `__LOCKED__` 固定标记 | 释放可校验「是不是本订单占的位」；固定标记无法区分、会误删新占位 |
| 同步落库（事务内） | Redis Stream 异步落库（削峰） | 异步化让「接口成功」与「订单可见」出现窗口，需补消息表 / 补偿 / 幂等消费；行锁 + 条件更新已把热点请求串行化，削峰收益抵不过正确性成本 |
| 缓存 5 分钟统一 TTL | 更长 TTL | 占位与状态都是「短生命周期」语义，长 TTL 会放大「缓存与 DB 不一致」的窗口；5 分钟内 DB 兜底接管一切 |
| `refreshIfExist` 刷新位置 | `goOnline` | 接单不应使未上线司机凭空上线 |

---

## 六、边界与已知问题

1. **Redis 故障时不能抢单**：订单锁与预检都依赖 Redis，当前没有绕过这些步骤的降级分支；不能仅凭实现断言作者的取舍原因，也不能说正常路径不访问 DB。
2. **占位 5 分钟后自动失效**：极端时序（接单事务卡 5 分钟以上）下占位过期，key 缺失本身不等于 `__IDLE__`，其他接单事务会先加 DB 行锁并依据在途订单修复占位——但 DB 行锁仍然串行化同一司机，不会产生双订单。
3. **`tryGrab` 必须在事务内**（`MANDATORY`）：无事务调用直接抛异常——行锁与缓存修复需要事务边界。
4. **`driverAcceptOrder` 的 `currentLat/currentLng`** 只用于刷新在线司机位置，不做「按距离匹配订单」（那是 [15 篇](15-nearby-order-geo.md) 的职责）。

---

## 七、如何验证

```bash
# 1. 司机占位与状态缓存
redis-cli GET driver:active:<driverId>       # 空闲：__IDLE__；接单后：订单号
redis-cli TTL driver:active:<driverId>       # 期望 ≤ 300（5 分钟）
redis-cli GET order:status:<orderId>         # 期望：状态码（数字）
redis-cli TTL order:status:<orderId>         # 期望 ≤ 300

# 2. 状态缓存单调推进：接单前 10（CREATED）→ 接单后 20（DRIVER_ACCEPTED）
#    手工把缓存改成 40（IN_TRIP）再触发迟到的 20 同步 → 缓存保持 40（单调性）

# 3. 占位释放：订单取消 / 完成后
redis-cli GET driver:active:<driverId>       # 期望：key 消失或下次接单时被修复为 __IDLE__

# 4. 并发抢单：两个司机同时抢同一单 → 一个成功，另一个 409
# 5. 同一司机并发抢两单 → 后者被拒（行锁串行 + 占位非 __IDLE__）
```

---

## 八、延伸阅读

- [`13-distributed-lock.md`](13-distributed-lock.md) —— 订单锁（withOrderLock）与本篇司机行锁的分工
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— `__IDLE__` 状态哨兵与事务内预热
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 接单成功后的 GEO 池失效
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 接单后的延迟队列改期
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `GRAB_ORDER_ATOMIC` / `SYNC_ORDER_STATUS` 速查
