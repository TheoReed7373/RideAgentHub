# 第 10 章 超时与恢复：ZSet 待办、数据库兜底与三条状态回退

> O1 建立了状态机，O2/O3 解释了接单和位置发现；本章回答“谁来推动超时”：订单 10 无人接单、订单 20 司机没到、订单 50 用户没付钱。核心是两条独立路径：**Redis ZSet 到期待办**负责及时叫醒；**MySQL 定时扫描**补偿入队失败、领取后进程退出和历史遗漏。Redis 领取与 MySQL 更新不是同一个事务。
>
> 继续使用虚构订单 5001/5002/5003，乘客林小雨，司机陈师傅。所有时间、状态码、距离、队列内容均为示意；本章没有启动调度器、运行测试、连接 Redis/MySQL，也没有故障注入。

## 学习目标

读完本章，你应该能：

1. 追踪三种超时的安排、领取、重读数据库、条件更新、重排和副作用；说出每个场景的锚点、到期条件、目标状态和保留/清空字段。
2. 解释 `delay:order:timeout` 的 key/member/score、`CLAIM_DUE_ORDERS` 的 KEYS/ARGV/返回值、批量上限和原子范围；说明领取后条目已移除。
3. 区分事务同步活跃/非活跃时 `schedule/cancel` 的登记与执行；说明 deadline 计算发生在事务提交前，回滚时已登记回调不执行。
4. 追踪 Redis 轮询和数据库补偿扫描的配置、过滤条件、排序、批次和调用方；说出哪些订单能被兜底，哪些 Redis/Mongo 数据无法由此恢复。
5. 分析超时与接单、到达、支付/取消竞争，以及领取后进程退出、Redis/DB 故障的时间线和剩余窗口。
6. 阅读 `OrderTimeoutRegressionTest`、`OrderDelayQueueIntegrationTest` 的门控、mock、断言和未覆盖范围。

## 前置知识与阅读时间

- 必读：[第 7 章 订单生命周期](01-order-lifecycle.md)——10/20/50 的状态迁移、`systemCancel` 最小流程、事务/锁与回调。
- 必读：[第 8 章 抢单与并发](02-grab-and-concurrency.md)——订单锁、司机占位、状态缓存单调推进、`schedule` deadline 的计算位置。
- 必读：[第 9 章 位置与发现](03-location-and-discovery.md)——订单退回待接单后 GEO 池如何失效/重建。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——READ_COMMITTED、条件更新、事务同步、afterCommit/afterCompletion。
- 可选：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——ZSet 和 Lua 原子范围。
- 阅读时间：约 90～110 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**：正文结论来自源码静态阅读；测试部分是“读过声明和断言，未运行”；没有真实调度、网络、并发或故障注入。

---

## 零、先记住七个事实

1. **Redis 待办没有 TTL**。`delay:order:timeout` 是共享 ZSet，member 是 orderId，score 是到期毫秒；同一 member 的 `ZADD` 会覆盖旧待办。
2. **领取即移除**。`CLAIM_DUE_ORDERS` 在一次 Lua 中 `ZRANGEBYSCORE` 并 `ZREM`；处理失败必须由调用方重排，否则 Redis 中这条待办消失。
3. **处理前重读 DB**。`handleTimeoutOrder` 只根据“重新读到的状态 + 该状态的锚点时间”判断，不信任入队时的场景。
4. **只有 10/20/50 会被超时处理**。30/40/60/90 走 default 日志分支，不迁移、不重排、不取消。
5. **到达超时是 20→10，不是取消**。它清空 `driver_id/driver_accept_time`，把 `update_time` 作为下一轮接单超时锚点；没有持久化“最多退回几次”的字段。
6. **数据库补偿只筛选符合条件的 10/20/50**。选中并成功处理时可能执行状态缓存、队列、GEO、司机占位动作；但终态后不再入选，也不是 Mongo 或所有 Redis 数据的通用修复器。
7. **业务时间与队列时间不是同一来源**。状态锚点和判断用 `BusinessTime.now()`（`Asia/Shanghai`），队列 deadline 和领取时间用 `System.currentTimeMillis()`；数据库补偿的 `now` 用宿主机默认时区的 `LocalDateTime.now()`。

---

## 一、业务问题与三条正常路径

### 1.1 为什么要两条路径

朴素做法是每个请求立即查“有没有过期订单”。问题：没有请求时订单卡住；多个请求重复扫描；进程重启丢内存任务。当前实现把“到点提醒”和“状态处理”分开：

- `OrderDelayQueue`：ZSet 到期提醒，轻量、及时，但 Redis 丢失/进程退出后待办可能不在。
- `OrderTimeoutJob#recoverOverdueOrders`：每 60 秒查 DB，把符合条件的 10/20/50 再次交给 `handleTimeoutOrder`。
- `RideOrderServiceImpl#handleTimeoutOrder`：重读订单、拿订单锁、按状态处理；Redis/MySQL 不是共同事务。

### 1.2 场景一：订单 5001 无人接单，10→90

前置：订单 5001 创建成功，`order_status=10`、`driver_id=NULL`、`update_time/create_time=10:00`。`createOrder` 成功后调用：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
orderDelayQueue.schedule(createdOrderId,
        Duration.ofMinutes(timeoutProperties.getAcceptTimeoutMinutes()));
```

`OrderDelayQueue#schedule` 先计算 `System.currentTimeMillis() + 15 分钟`，再登记/执行 ZADD。当前 `createOrder` 调用链无事务，所以 ZADD 立即执行。

到期后：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
private void handleAcceptTimeout(RideOrder order, LocalDateTime now) {
    LocalDateTime anchor = order.getUpdateTime() != null ? order.getUpdateTime() : order.getCreateTime();
    if (Integer.valueOf(1).equals(order.getIsReservation()) && order.getScheduledTime() != null
            && (anchor == null || order.getScheduledTime().isAfter(anchor))) {
        anchor = order.getScheduledTime();
    }
    LocalDateTime deadline = plusMinutes(anchor, timeoutProperties.getAcceptTimeoutMinutes());
    if (deadline == null) return;
    if (now.isBefore(deadline)) {
        reschedule(order.getOrderId().toString(), deadline, now);
        return;
    }
    systemCancel(order, RideOrderStatus.CREATED.getCode(),
            "超时无人接单，系统自动取消");
}
```

- 锚点：`updateTime`，为空则 `createTime`。预约单且 `scheduledTime` 晚于该锚点时改用 `scheduledTime`。
- 到期：`now >= anchor + accept-timeout-minutes`；默认 15 分钟。
- 动作：`systemCancel` 条件更新 `order_status=10`、`update_time=读到的快照`，设为 90、`cancel_role=3`、原因、新 `update_time`。
- 保留：账单/价格字段不动；无人接单本来没有真实里程。
- 副作用（提交后）：状态缓存 90、`cancel` 待办、GEO 池失效；无司机占位可释放。

结果示意：`5001: 10→90, cancel_role=3`。

### 1.3 场景二：订单 5002 司机未到达，20→10

前置：陈师傅在 10:05 接单成功，`order_status=20`、`driver_id=7002002`、`driver_accept_time=10:05`。接单成功后在事务提交前登记、提交后执行：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
orderDelayQueue.schedule(orderId, Duration.ofMinutes(timeoutProperties.getArriveTimeoutMinutes()));
```

到期后：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
LocalDateTime deadline = plusMinutes(order.getDriverAcceptTime(),
        timeoutProperties.getArriveTimeoutMinutes());
if (deadline == null) {
    orderDelayQueue.cancel(order.getOrderId().toString());
    return;
}
if (now.isBefore(deadline)) {
    reschedule(order.getOrderId().toString(), deadline, now);
    return;
}
if (order.getDriverId() == null) {
    orderDelayQueue.cancel(order.getOrderId().toString());
    return;
}

int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, order.getOrderId())
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
        .eq(RideOrder::getDriverId, order.getDriverId())
        .eq(RideOrder::getDriverAcceptTime, order.getDriverAcceptTime())
        .set(RideOrder::getDriverId, null)
        .set(RideOrder::getDriverAcceptTime, null)
        .set(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
        .set(RideOrder::getUpdateTime, now));
if (updated <= 0) return;

orderGrabService.releaseDriver(order.getDriverId().toString(), orderIdStr);
orderGrabService.refreshOrderStatus(orderIdStr);
orderGeoPool.add(orderIdStr, order.getStartLng(), order.getStartLat());
orderDelayQueue.schedule(orderIdStr,
        Duration.ofMinutes(timeoutProperties.getAcceptTimeoutMinutes()));
```

- 锚点：重新读到的 `driver_accept_time`。
- 到期：`now >= driver_accept_time + arrive-timeout-minutes`；默认 10 分钟。
- 更新守卫：`status=20`、`driver_id=快照司机`、`driver_accept_time=快照时间`。
- 数据库变化：`20→10`；`driver_id` 和 `driver_accept_time` 清空；`update_time=now`。
- 副作用（提交后）：释放司机占位、删除状态缓存、失效附近订单 GEO 池、安排新的 15 分钟接单超时。
- 可重复：没有持久化退回次数字段。若再次被接单并再次 10 分钟未到达，会再退回一次；每一轮都消耗一个完整阈值。源码注释说明真正的硬上限需要随订单持久化，而不是 Redis 计数器。

结果示意：第一轮 `5002: 20→10`；若 10:25 再次接单、10:35 未到达，第二轮 `20→10`。

### 1.4 场景三：订单 5003 未支付，50→90

前置：行程在 11:00 结束，`order_status=50`，账单字段已写入，`finish_time=11:00`。`finishRide` 成功后登记支付超时；默认 30 分钟。

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
private void handlePayTimeout(RideOrder order, LocalDateTime now) {
    LocalDateTime deadline = plusMinutes(order.getFinishTime(),
            timeoutProperties.getPayTimeoutMinutes());
    if (deadline == null) {
        orderDelayQueue.cancel(order.getOrderId().toString());
        return;
    }
    if (now.isBefore(deadline)) {
        reschedule(order.getOrderId().toString(), deadline, now);
        return;
    }
    systemCancel(order, RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
            "超时未支付，系统自动关单");
}
```

- 锚点：`finish_time`；为空则撤待办，避免空转。
- 到期：`now >= finish_time + pay-timeout-minutes`。
- 动作：`systemCancel` 条件更新 `status=50`、`update_time=快照`，设为 90、`cancel_role=3`、原因、新 `update_time`。
- 保留：`real_price` 和四项计价字段不被该 UPDATE 修改；账单留给对账/申诉。
- 副作用（提交后）：状态缓存 90、撤待办、GEO 池失效；若快照里有 `driver_id`，释放司机占位。

结果示意：`5003: 50→90`，账单字段保留。

### 1.5 三个场景对照

| 场景 | 起点 | 锚点 | 默认阈值 | 正常迁移 | 清空/保留 | 后续队列 |
|---|---|---|---|---|---|---|
| 无人接单 | `CREATED(10)` | `update_time`（空则 `create_time`）；未来预约可用 `scheduled_time` | 15m | `10→90` | 不改账单；`cancel_role=3` | `cancel(90)` |
| 司机未到达 | `DRIVER_ACCEPTED(20)` | `driver_accept_time` | 10m | `20→10` | 清空司机/接单时间；`update_time=now` 成为新接单锚点 | 重新 `schedule(15m)` |
| 未支付 | `FINISHED_WAIT_PAY(50)` | `finish_time` | 30m | `50→90` | 保留账单字段；`cancel_role=3` | `cancel(90)` |

---

## 二、ZSet 待办：安排、领取与重排

### 2.1 key、member、score

| 项 | 值 | 说明 |
|---|---|---|
| key | `delay:order:timeout` | 共享 ZSet；常量 `RedisKeyConstants#DELAY_ORDER_TIMEOUT_KEY` |
| member | orderId 字符串 | 一单一条；`ZADD` 按 member 覆盖 |
| score | 到期时间戳毫秒 | `schedule` 用 `System.currentTimeMillis()` 计算 |
| TTL | 无 | key 不设过期；member 只被覆盖/移除 |

```java
// service/base/OrderDelayQueue.java（真实节选）
public void schedule(String orderId, Duration delay) {
    if (orderId == null || orderId.isBlank() || delay == null) return;
    long deadline = Math.addExact(System.currentTimeMillis(),
            Math.max(0L, delay.toMillis()));
    afterCommit(() -> redisTemplate.opsForZSet()
            .add(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId, deadline));
}

public void cancel(String orderId) {
    if (orderId == null || orderId.isBlank()) return;
    afterCommit(() -> redisTemplate.opsForZSet()
            .remove(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId));
}
```

- deadline 在方法调用时计算，不是事务提交后计算。事务越慢，实际剩余延迟越短。
- `Math.addExact` 在 guarded callback 之前；溢出会抛出，不属于“ZSet 写入失败被捕获”。
- `afterCommit` 只包裹 ZADD/ZREM；Redis `RuntimeException` 被捕获并告警。

事务同步活跃时（如接单、超时处理），`schedule/cancel` 只登记 afterCommit；无事务同步时立即执行。回滚时已登记的动作不执行；已成功执行的直接 Redis 写也不会被 MySQL 回滚撤销。

### 2.2 领取 Lua

```lua
-- util/RedisScripts.java#CLAIM_DUE_ORDERS（真实 Lua）
local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
for _, orderId in ipairs(orders) do
    redis.call('ZREM', KEYS[1], orderId)
end
return orders
```

| Lua 变量 | Java 值 | 含义 |
|---|---|---|
| `KEYS[1]` | `delay:order:timeout` | ZSet |
| `ARGV[1]` | `System.currentTimeMillis()` | 当前毫秒 |
| `ARGV[2]` | `properties.getBatchSize()`，默认 `"100"` | 本批上限 |

- 返回值：本次领取的 orderId 列表；Redis 返回 null 时 Java 返回空列表。
- 原子范围：同一次 Redis Lua 内的 `ZRANGEBYSCORE + ZREM`；多实例同时领取同一 member 时，只有一个实例能移除它。
- 领取后条目已从 ZSet 移除。此后进程退出、事务回滚、处理抛异常、Redis 故障都不会自动把它放回；必须靠代码重排或数据库补偿。
- Lua 不涉及 MySQL；`ZRANGEBYSCORE/ZREM` 也不与 `handleTimeoutOrder` 的数据库事务一起回滚。

### 2.3 未到期为什么重排

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
private void reschedule(String orderId, LocalDateTime deadline, LocalDateTime now) {
    orderDelayQueue.schedule(orderId, Duration.between(now, deadline));
}
```

触发原因包括：任务被领取时订单状态匹配，但按 DB 锚点计算尚未到期（例如预约单的锚点更晚、迟到/早领的任务、旧待办被新锚点改变）。因为领取已删除条目，所以要按新的剩余时间放回。

但重排不是免费保证：

- `reschedule` 调用 `schedule`；当前超时处理事务有同步器，所以它登记 afterCommit。
- 若事务回滚、进程在提交后回调前退出、afterCommit 的 ZADD 失败或响应丢失，这条待办可能不再在 Redis。
- 若 deadline 计算抛异常，事务回滚，已领取条目也不会补回。
- 这些窗口由数据库补偿扫描兜底；但补偿只覆盖符合条件的 10/20/50，不保证下一次扫描一定成功。

---

## 三、轮询、处理与数据库补偿

### 3.1 调度入口与配置

```java
// service/base/OrderTimeoutJob.java（真实节选）
@Scheduled(fixedDelayString = "${ride-order.timeout.poll-interval-ms:5000}")
public void pollTimeoutOrders() {
    if (!properties.isEnabled()) return;
    List<String> dueOrders;
    try {
        dueOrders = orderDelayQueue.claimDue(properties.getBatchSize());
    } catch (Exception e) {
        log.warn("订单超时队列扫描失败，本轮跳过", e);
        return;
    }
    if (dueOrders.isEmpty()) return;
    processOrders(dueOrders);
}

@Scheduled(fixedDelay = 60000L)
public void recoverOverdueOrders() {
    if (!properties.isEnabled()) return;
    // ... DB 查询并 processOrders
}
```

`RideAgentHubApplication` 有 `@EnableScheduling`。`fixedDelay` 表示上一轮结束后再计时；不是 `fixedRate`。

`RideOrderTimeoutProperties` / `application.yaml`：

| 配置 | 默认值 | 单位 | 作用 |
|---|---|---|---|
| `ride-order.timeout.enabled` | `true` | 布尔 | 两条路径的开关 |
| `accept-timeout-minutes` | `15` | 分钟 | 10 状态锚点偏移 |
| `arrive-timeout-minutes` | `10` | 分钟 | 20 状态锚点偏移 |
| `pay-timeout-minutes` | `30` | 分钟 | 50 状态锚点偏移 |
| `poll-interval-ms` | `5000` | 毫秒 | Redis 扫描 fixedDelay；也用于失败重排 |
| `batch-size` | `100` | 条 | Redis 领取上限；DB 补偿 LIMIT |

`@PostConstruct#validate` 要求上述阈值/间隔/批量都为正数；否则启动阶段抛 `IllegalArgumentException`。数据库补偿的 60 秒在代码中硬编码，不是该配置类字段。

### 3.2 Redis 轮询后的处理

```java
// service/base/OrderTimeoutJob.java（真实节选）
private void processOrders(List<String> orderIds) {
    for (String orderId : orderIds) {
        try {
            rideOrderService.handleTimeoutOrder(orderId);
        } catch (Exception e) {
            log.error("订单超时处理失败: orderId={}", orderId, e);
            orderDelayQueue.schedule(orderId,
                    Duration.ofMillis(properties.getPollIntervalMs()));
        }
    }
}
```

- 测试断言单条失败后继续下一条；源码中 catch 只包住 `handleTimeoutOrder`，随后调用 `schedule`。
- `handleTimeoutOrder` 抛异常后，事务代理通常已回滚并结束；`processOrders` 本身没有事务，所以这里的 `schedule` 立即执行，重排间隔是配置的 `poll-interval-ms`（默认 5000ms）。
- `schedule` 的 deadline 计算（`Math.addExact`）在 guarded callback 之外；若它抛异常，这个异常发生在 catch 块内且未被再次捕获，可能中断当前循环。默认 5 秒延迟不会触发该溢出，但不能无条件保证同批必然继续。
- 多实例可各自领取不同任务；同一订单也可能同时被 Redis 路径和 DB 补偿路径选中，最终由订单锁和 DB 条件更新收敛。

### 3.3 处理入口

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
@Override
@Transactional(isolation = Isolation.READ_COMMITTED)
public void handleTimeoutOrder(String rawOrderId) {
    if (!timeoutProperties.isEnabled() || rawOrderId == null || rawOrderId.isBlank()) return;
    String orderId = convertToLong(rawOrderId).toString();
    withOrderLock(orderId, () -> {
        handleTimeoutOrderUnderLock(orderId);
        return null;
    });
}
```

- 只有 `rawOrderId` 非空且开关开启才继续；非法 ID 抛 `IllegalArgumentException` → `processOrders` 捕获并重排。
- 订单锁 key 是 `order:lock:{orderId}`，最多等 3 秒；等待失败 409 会被 `processOrders` 捕获并重排。
- `handleTimeoutOrder` 有 `@Transactional(READ_COMMITTED)`；`withOrderLock` 在事务同步活跃时于 afterCompletion 释放。
- 领取发生在进入这个事务之前；领取的 ZREM 不属于该事务。

`handleTimeoutOrderUnderLock`（简化示意，省略非法状态检查）：

```java
RideOrder order = rideOrderMapper.selectOne(...eq(orderId).eq(isDeleted,0));
if (order == null || order.getOrderStatus() == null) return;
RideOrderStatus status = RideOrderStatus.fromCode(order.getOrderStatus());
LocalDateTime now = BusinessTime.now();
switch (status) {
    case CREATED -> handleAcceptTimeout(order, now);
    case DRIVER_ACCEPTED -> handleArriveTimeout(order, now);
    case FINISHED_WAIT_PAY -> handlePayTimeout(order, now);
    default -> log.debug("订单状态已前进，无需超时处理: ...");
}
```

- 订单不存在、状态为 null：静默返回；已领取条目不会重排，也不存在 DB 兜底对象。
- 状态非法：记 warn 后返回；同样不重排。
- 30/40/60/90：只记 debug；不 `cancel`/`reschedule`。对**已成功领取**的条目，它已从 ZSet 移除，不会因 default 重新出现；只有该 orderId 后续被并发 `schedule`、存在未被领取的未来条目或 Redis 结果未知时，才可能观察到 member。

### 3.4 数据库补偿扫描

```java
// service/base/OrderTimeoutJob.java（真实节选）
LocalDateTime now = LocalDateTime.now();
LocalDateTime acceptCutoff = now.minusMinutes(properties.getAcceptTimeoutMinutes());
List<RideOrder> orders = rideOrderMapper.selectList(new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getIsDeleted, 0)
        .and(states -> states
                .nested(created -> created
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
                        .and(anchor -> anchor
                                .le(RideOrder::getUpdateTime, acceptCutoff)
                                .or(fallback -> fallback
                                        .isNull(RideOrder::getUpdateTime)
                                        .le(RideOrder::getCreateTime, acceptCutoff)))
                        .and(reservation -> reservation
                                .isNull(RideOrder::getIsReservation)
                                .or().ne(RideOrder::getIsReservation, 1)
                                .or().isNull(RideOrder::getScheduledTime)
                                .or().le(RideOrder::getScheduledTime, acceptCutoff)))
                .or(arrival -> arrival
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                        .le(RideOrder::getDriverAcceptTime,
                                now.minusMinutes(properties.getArriveTimeoutMinutes())))
                .or(payment -> payment
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
                        .le(RideOrder::getFinishTime,
                                now.minusMinutes(properties.getPayTimeoutMinutes()))))
        .orderByAsc(RideOrder::getUpdateTime)
        .last("LIMIT " + properties.getBatchSize()));
processOrders(orders.stream().map(order -> order.getOrderId().toString()).toList());
```

语义：

- 只查 `is_deleted=0`，状态为 10/20/50。
- 10 的普通单：`update_time <= acceptCutoff`；若 `update_time IS NULL`，用 `create_time <= acceptCutoff`。
- 10 的预约单：只有 `is_reservation IS NULL`、`is_reservation != 1`、`scheduled_time IS NULL` 或 `scheduled_time <= acceptCutoff` 之一成立才入选。未来预约（`scheduled_time > acceptCutoff`）被排除。
- 20：`driver_accept_time <= now - arrive-timeout-minutes`。
- 50：`finish_time <= now - pay-timeout-minutes`。
- 排序：`update_time ASC`；LIMIT `batch-size`。
- 查询抛 `RuntimeException` 只记 warn，本轮跳过；不抛给调度线程。这是固定延迟扫描，不是“到点必然执行成功”。
- 查询到的订单仍交给 `handleTimeoutOrder`，由事务代理开启事务，拿锁后重读 DB 并按当前状态判断；扫描候选只是粗筛，不是最终裁决。

### 3.5 兜底范围与无法恢复

| 丢失/失败 | Redis 轮询 | DB 补偿（启用且本轮成功） | 剩余边界 |
|---|---|---|---|
| 入队失败/afterCommit 失败 | 没有该待办 | 订单仍满足 10/20/50 条件时，**可能在后续扫描被选中** | 60 秒固定延迟；查询失败本轮跳过；不保证某轮处理成功 |
| 领取后进程退出 | 领取成功时条目已删 | 订单仍满足条件时可能在后续扫描被选中 | 进程已执行到一半的 DB 结果以 DB 为准；无法保证下一轮一定成功 |
| 处理抛异常 | `processOrders` catch 后按配置的 `poll-interval-ms` 调用 `schedule` 重排 | 也可能在后续 DB 扫描被选中 | 默认 5000ms；没有重试计数。`schedule` 的 deadline 计算在 guarded callback 外，若其抛异常，catch 块本身可中断本批；不能无条件保证同批继续或无限重试 |
| Redis 扫描失败 | 本轮跳过 | 独立执行 | Redis 和 DB 都失败时本轮无法恢复 |
| 订单不存在/状态非法 | 已领取条目已从 Redis 移除 | 10/20/50 条件查不到 | 无自动补偿对象；非法状态只记 warn |
| 30/40/60/90：已成功领取 | 已移除；default 只记日志，不会让它重新出现 | 状态不在扫描范围 | 除非之后同 orderId 的 `schedule`/其他写入再次创建 member，否则 Redis 中不会有这条已领取条目 |
| 30/40/60/90：仍有未来/旧 score 条目 | 未领取或并发再次写入时可能存在 | 状态不在扫描范围 | 无 TTL 不代表已成功领取的条目留存；需按具体写入判断 |
| 非终态副作用失败 | 后续扫描选中且成功处理时可能再次执行相关动作 | 到达超时成功可 `releaseDriver/refreshOrderStatus/geoPool.add/schedule`；系统取消成功可 `syncOrderStatus/queue.cancel/pool.remove/releaseDriver` | 每个动作可能失败；只是“可能”，不是通用修复器 |
| 终态副作用已失败 | DB 已是 90，不再入选 | 不再入选 | 本章不会因终态副作用失败而重新扫描；需按 O2/O3 各机制或后续同 orderId 写入判断 |
| Mongo 轨迹孤儿/未写 | 不由本扫描恢复 | 不恢复 Mongo | O1/O5 的跨库边界 |

“数据库补偿有界”指：启用、查询成功且订单仍满足筛选条件时，它可能在后续扫描选中并调用同一个超时状态机；不是“到点必然处理成功”，不是“所有超时副作用都能恢复”，也不能修复已被终态跳过的历史副作用。

---

## 四、失败与并发时间线

### 4.1 时间线 A：支付超时与用户支付竞争

前提：订单 5003 状态 50，`finish_time=11:00`，支付超时任务已到期；林小雨本人同时调用支付；T2 的 `tryLock` 在 3 秒等待窗口内。两个请求都竞争 `order:lock:5003`。`handleTimeoutOrder` 有本地事务；`payOrder` 当前没有本地事务注解。

**超时先拿锁（当前无外层事务）：**

| 时刻 | T1：支付超时 | T2：用户支付 | MySQL/Redis/锁 |
|---|---|---|---|
| a0 | T1 通过事务代理进入 `READ_COMMITTED` 事务，拿到 `order:lock`；重读 50，`finish_time+30m <= now` | 调用 `tryLock`，最多等 3 秒 | 50；T1 持锁 |
| a1 | `systemCancel` 条件更新 `status=50 AND update_time=快照` → 90，未提交；登记 afterCommit | 等锁 | 90 未提交 |
| a2 | T1 代理提交 MySQL | 继续等锁 | 90 已提交 |
| a3 | T1 afterCommit：同步状态缓存 90、`queue.cancel`、GEO 失效、按快照释放司机；每个动作失败只告警 | 继续等锁 | Redis 动作各自结果可能不同 |
| a4 | T1 afterCompletion：释放 `order:lock` 成功 | 在 3 秒窗口内取得锁 | 锁从 T1 转给 T2 |
| a5 | — | 锁内 `getRequiredOrder` 读到 90；归属检查通过；状态不是 50 | `payOrder` 自身抛 `BusinessException(409,"当前订单状态不允许支付")`；不会进入 `payOrderUnderLock` |
| a6 | — | 异常向上 | `GlobalExceptionHandler` 映射 409 |

若 T2 等锁超过 3 秒仍未拿到，`withOrderLock` 抛 409“订单正在处理中”；若 `tryLock` 被中断，恢复中断标志并抛 409。这两种都不读订单 90。

**支付先拿锁（当前无外层事务）：**

| 时刻 | T1：支付超时 | T2：用户支付 | MySQL/Redis/锁 |
|---|---|---|---|
| b0 | T1 已领取到期条目；等锁 | T2 拿到 `order:lock`；无本地事务，锁内重读 50，归属通过，verifier 正常 | 50 |
| b1 | 等锁 | 条件更新 `status=50` → 60；当前无事务同步，更新提交后 `syncOrderStatus(60)` 和 `queue.cancel` 立即执行 | 60 |
| b2 | 等锁 | finally 释放锁 | 锁空闲 |
| b3 | T1 已通过代理开启事务，在锁释放后拿到锁并重读 60 | — | default 分支只记 debug |
| b4 | T1 事务提交；本次没有重排/取消动作 | — | 已领取条目此前已移除，default 不会让它重新出现 |

假设差异：若未来 `payOrder` 被外层 `@Transactional` 调用，Mapper 加入外层，`syncOrderStatus/queue.cancel` 会登记 afterCommit，锁释放在 afterCompletion；方法体返回不代表物理提交。当前 HTTP 调用链没有外层事务，不能把 b1 写成“提交/afterCommit”。

若 T1 `systemCancel` 的 `update_time` 快照被其他外部写入改变但订单仍是 50，条件更新 0 行；T1 让位，已领取条目不会自动重现。若状态仍是 50 且锚点过期、筛选条件满足，数据库补偿可能在后续扫描选中。

### 4.2 时间线 B：到达超时与“到达、开始”竞争

前提：订单 5002 状态 20，司机陈师傅 10:05 接单，10:15 到达超时任务已领取；陈师傅在 10:15:01 调用接口。`driverArriveStart` 的合法条件更新是 `20→30`；`startRide` 必须在订单已经 30 后才可能 `30→40`，不能把初始 20 直接当作开始成功前提。

**到达超时先拿锁：**

| 时刻 | T1：到达超时 | T2：司机到达 | 结果 |
|---|---|---|---|
| c0 | T1 拿锁，事务内重读 20 和 `driver_accept_time` | 等 3 秒内锁 | 20 |
| c1 | 条件更新 `20→10`，清空司机/接单时间，未提交；登记 afterCommit | 等锁 | 10 未提交 |
| c2 | T1 提交；afterCommit 释放司机、删除状态缓存、GEO 失效、安排 15m；afterCompletion 释放锁 | 继续等锁 | 10 已提交 |
| c3 | — | 拿锁，条件更新要求 `status=20` | 影响 0 行；返回 false，不能改成 30 |

若 T2 等 3 秒未拿到锁，先在 `withOrderLock` 得 409，不读 DB。

**司机到达先拿锁，再开始行程：**

| 时刻 | T1：到达超时 | T2a：司机到达 | T2b：司机开始 | 结果 |
|---|---|---|---|---|
| d0 | T1 已领取到期条目，等锁 | T2a 拿锁；无本地事务，条件更新 `20→30`；当前无事务同步，更新后同步 30 并调用 `queue.cancel` | 等锁 | 30；领取已移除待办，取消调用另行执行，结果可能失败 |
| d1 | 已通过代理开启事务，拿到锁后重读 30 | finally 释放锁 | — | default 只记 debug |
| d2 | 已领取条目此前已移除，default 不会重新创建 member | — | — | 本例若无后续写入则无该 member；并发再次 `schedule` 等才可能重新出现 |
| d3 | — | — | T2b 拿锁；条件更新要求 `status=30`，成功 `30→40` | 40 |

这里要分清三类 Redis 条目：

1. **已成功领取的条目**：`claimDue` 脚本内已经 `ZREM`；之后 default 不重排/不 cancel，也不会让它重新出现。
2. **尚未领取的未来条目**：score 未到，会留在 ZSet；后续可能被领取。
3. **并发再次写入**：同 orderId 的 `schedule` 可新建/覆盖 member；`cancel` 可删除；响应丢失时执行结果未知。不能凭“default 不 cancel”断言旧 member 残留。

若 T1 的条件更新 0 行（例如 `driver_accept_time` 被外部修正但状态仍 20），它不释放司机、不登记本章副作用；已领取条目消失。若状态仍是 20 且 `driver_accept_time` 过期，DB 补偿可能在后续扫描选中。

### 4.3 时间线 C：领取后进程退出/Redis 或 DB 故障

前提：订单 5001 状态 10，10:15 到期；调度开关开启。

| 时刻 | 动作 | Redis ZSet | MySQL | 后续可能 |
|---|---|---|---|---|
| e0 | `claimDue` Lua 成功返回 5001 | 5001 已移除 | 仍是 10 | 需重排或 DB 兜底 |
| e1 | 进程在调用 `handleTimeoutOrder` 前退出 | 无 5001 | 仍是 10 | 应用重启或其他实例仍在运行时，后续 60 秒固定延迟扫描若成功且筛选条件满足，可能选中；不保证某轮成功 |
| e2 | DB 补偿查询也失败 | 无 5001 | 10 | 下一轮 Redis 仍无该已领取待办；DB 若成功再筛 |
| e3 | DB 补偿选中并处理成功 | 处理内 `systemCancel` 成功后登记 cancel；处理前无 member | 90 | 状态缓存/GEO/队列/司机动作按各方法执行，可能失败 |
| e4 | 状态缓存/GEO/队列 afterCommit 失败 | 可能缺 | 90 已提交 | 终态后 DB 补偿不再筛选；不能保证这些副作用被再次执行 |

补偿调用处理时并非只改 DB：若选中的 10 处理成功，会登记状态缓存/队列/GEO 动作；若选中的 20 到达超时成功，会释放司机、刷新状态缓存、GEO 失效、重新安排队列。但这些动作各有 guarded catch，可能失败；扫描不是通用 Redis/Mongo 修复器，也不会因终态副作用已失败而重新扫描终态。

若 Redis 扫描本身失败，`pollTimeoutOrders` 本轮跳过；DB 补偿仍可独立执行。若 DB 补偿查询抛 `RuntimeException`，本轮跳过。若领取调用响应丢失，客户端不知道 Lua 是否在 Redis 成功执行；Redis 中条目可能已移除也可能未移除。不能凭客户端结果断言，DB 补偿只是有界兜底。

### 4.4 时间线 D：未到期重排的失败分支

前提：订单 5002 状态 20，`driver_accept_time=10:20`，阈值 10 分钟（deadline=10:30）；某条旧待办在 10:28 被成功领取；重读 DB 后 `now < deadline`；暂无其他并发写入。

| 时刻 | 动作 | Redis ZSet | MySQL | 说明 |
|---|---|---|---|---|
| f0 | `claimDue` Lua 成功 | 5002 已移除 | 20 | 领取即移除 |
| f1 | `handleArriveTimeout` 计算剩余约 2 分钟，调用 `reschedule` | 尚无新 member | 20 | 事务同步活跃，登记 afterCommit |
| f2 | 事务提交；afterCommit ZADD 成功 | 约 2 分钟后到期 | 20 | 正常路径 |
| f3a | 事务回滚（无并发写入） | 无本次 ZADD；原已领取条目仍不存在 | 通常仍 20 | 不能把“客户端异常”当成 DB 一定回滚；提交结果未知时以 DB 为准 |
| f3b | 事务提交后，afterCommit ZADD 明确未写入并被 catch | 无本次 member | 20 | guarded catch 不抛；本分支已提交，无并发写入 |
| f3c | ZADD 调用响应丢失，客户端不知道服务端是否执行 | 5002 可能已存在、也可能不存在 | 20 | Redis 端结果未知，需查询；不能断言 |
| f3d | 并发 `schedule/cancel/claim` 或 DB 写入 | member 可能被覆盖/删除/领取；DB 状态可能变化 | 需重读 | 不能用单一分支概括 |

如果 f1 前进程退出，同样没有本次 ZADD；原已领取条目仍不存在，除非并发写入重建。不能保证“领取后一定重排成功”。

DB 补偿的后续不是精确时刻：只有调度开关开启、固定延迟扫描本轮成功、订单仍为 20、`driver_accept_time` 已超过 `arrive-timeout-minutes`、排序和 batch 允许时，才**可能**被后续扫描选中；不保证在 10:30 后的某轮查到。

---

## 五、时间、时钟与多实例

### 5.1 三个时间来源

| 位置 | API | 时区/说明 |
|---|---|---|
| `handleAcceptTimeout/handleArriveTimeout/handlePayTimeout/systemCancel` | `BusinessTime.now()` | `ZoneId.of("Asia/Shanghai")` 的 `LocalDateTime` |
| `OrderDelayQueue#schedule` 的 deadline | `System.currentTimeMillis()` | JVM 默认时钟的 epoch 毫秒；与 `Instant` 对应，不受 `LocalDateTime` 时区显示影响 |
| `OrderTimeoutJob#recoverOverdueOrders` | `LocalDateTime.now()` | 宿主机默认时区；与 `BusinessTime` 不一定同 zone |
| `claimDue` 的 now | `System.currentTimeMillis()` | 与 deadline 同为 epoch 毫秒 |

影响：

- 多实例系统时钟偏差会让“何时到期”不一致；订单锁和 DB 条件更新决定最终状态。
- 数据库补偿的 `LocalDateTime.now()` 依赖宿主机默认时区；如果与 `BusinessTime` 不同，候选筛选边界可能偏移。
- MySQL `DATETIME` 没有时区信息；存储值由写入端决定。部署的数据库/驱动时区需实际核验，本章未连接 DB。
- `schedule` 的 deadline 是调用时刻 + delay；事务越慢，实际剩余时间越短。`reschedule` 用 `BusinessTime` 计算剩余，再由 `schedule` 转成 `System.currentTimeMillis()` 偏移；两者之间若有毫秒/时钟差，只是近似，不是 MySQL/Redis 共同事务。

### 5.2 业务超时与 Redis TTL

- 业务超时是“锚点 + 分钟数”，按状态重读计算。
- Redis ZSet 本身没有 TTL；member 到期被领取/移除，或被同 orderId 新 score 覆盖。
- 不要把“订单 15 分钟超时”误认为“key 15 分钟过期”。
- 状态缓存、司机占位等有 TTL 的 key 属于 O2，不是本章的队列。

### 5.3 多实例

- `claimDue` 用 Lua 的 `ZREM` 防止同一 member 被两个实例领取。
- 但 DB 补偿扫描没有 Redis 领取标记，多实例可能同时查到同一订单；`handleTimeoutOrder` 的订单锁串行化处理，后到者重读状态/条件更新 0 行或 default 分支。
- 订单锁最多等 3 秒；等待失败会被 `processOrders` 捕获，并按配置的 `poll-interval-ms`（默认 5000ms）调用 `schedule` 重排；该重排本身也可能失败。锁释放失败、Redisson 看门狗和释放窗口在 O2 已述。
- 多实例调度都会启动；源码没有本章专用的 leader 选举。

---

## 六、测试阅读：覆盖什么、没覆盖什么

### 6.1 `OrderTimeoutRegressionTest`

门控：不需要外部端口；mock Redis、Mapper、`OrderGrabService`、`OrderDelayQueue`、GEO/位置组件；手工初始化 MyBatis-Plus 表信息和 Spring 事务同步。

覆盖的断言：

- `schedule/cancel` 在事务同步活跃时等待提交；回滚不发布；提交后执行。
- Redis 队列失败不使已提交业务失败。
- 一条处理失败时按测试配置的 `pollIntervalMs=5000` 调用 `schedule`，且测试断言同批下一条继续执行。真实运行中，`schedule` 自身异常可中断本批。
- DB 补偿查到订单后调用 `handleTimeoutOrder`；不直接操作队列。
- 开关关闭时两条调度方法都不动。
- 到达超时只释放该司机、删除/回退状态缓存（不调用 `syncOrderStatus(10)`），并安排 15 分钟；更新语句包含 `driver_accept_time` 快照。
- 到达超时条件更新 0 行时不释放司机、不改队列。
- 未来预约不取消，按更晚 deadline 重排。
- 支付超时条件更新包含 `update_time` 快照，且不 set `real_price`；成功后释放司机。
- 配置校验对非法批量/阈值抛异常。

未覆盖：真实 Spring 调度线程、真实 Redis/MySQL、多实例竞争、进程退出、通知设施。

### 6.2 `OrderDelayQueueIntegrationTest`

门控：`@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")`；需要显式独立 Redis。

- 使用真实 Redis 和 `OrderDelayQueue`；不连接 MySQL。
- `@AfterEach#cleanup` 删除固定 key `delay:order:timeout`。因此该 Redis 必须一次性专用；这会删除该实例上的全部订单超时待办。
- 覆盖：两个消费者并发领取 20 个到期任务各一次、未来任务保留；改期后的任务不提前领取；批次上限按 score/插入顺序生效。
- 未覆盖：`OrderTimeoutJob` 的真实 `@Scheduled`、DB 补偿、事务提交/回滚、多实例时钟偏差。

### 6.3 与其他订单测试的关系

- `OrderGrabRegressionTest/OrderLockRegressionTest` 也断言队列等待提交、失败不破坏业务、锁覆盖回调；O2 已述。
- `OrderGrabIntegrationTest` 覆盖接单并发，但 mock `OrderDelayQueue`，不验证本章超时调度。
- 本章没有运行任何测试。

---

## 七、动手验证

### 练习 A：只读追踪无人接单

前提：订单 5001 状态 10，`update_time=10:00`，无预约；Redis 有 member `5001`，score 约为 10:15。

请按源码写出：轮询领取、处理事务/锁、重读 DB、条件更新、副作用登记/执行、失败重排。

<details>
<summary>练习 A 参考答案</summary>

`pollTimeoutOrders` 每 5 秒执行；开关开启后 `claimDue(100)` 执行 `CLAIM_DUE_ORDERS`，Lua 用 `ZRANGEBYSCORE key 0 now LIMIT 0 100` 找到 5001 并 `ZREM`，返回 `["5001"]`。领取与 MySQL 无关。`processOrders` 调用 `handleTimeoutOrder("5001")`；该方法代理开启 `READ_COMMITTED` 事务，规范化 ID 后获取 `order:lock:5001`（最多 3 秒）。锁内重读 DB：状态 10，锚点 `update_time=10:00`，deadline 10:15。若当前业务时间早于 deadline，调用 `reschedule` 登记 afterCommit ZADD；若到期，调用 `systemCancel`。条件更新匹配 `order_id/is_deleted=0/status=10/update_time=快照`，设 90、`cancel_role=3`、原因、新 `update_time`。成功后 `syncOrderStatus(90)`、`orderDelayQueue.cancel`、`orderGeoPool.remove` 都登记 afterCommit；无人接单没有司机占位。事务提交后 afterCommit 执行；afterCompletion 释放订单锁。若处理抛异常，`processOrders` 捕获并按配置的 `poll-interval-ms`（默认 5 秒）重新 `schedule`；该重排可能失败。若事务回滚，已登记 afterCommit 不执行，数据库补偿可能再次选中该 10 状态订单。
</details>

### 练习 B：到达超时可重复退回

前提：订单 5002 在 10:05 被接单，10:15 未到达被退回 10；10:25 再次被接单，10:35 未到达。

请写两轮锚点、条件更新、副作用和可重复性；说明为什么不是紧凑循环。

<details>
<summary>练习 B 参考答案</summary>

第一轮锚点 `driver_accept_time=10:05`，10:15 到期。条件更新匹配 `status=20`、`driver_id=陈师傅`、`driver_accept_time=10:05`，设 `status=10`、`driver_id=NULL`、`driver_accept_time=NULL`、`update_time≈10:15`。提交后释放司机占位、删除状态缓存、失效 GEO 池，并 `schedule(15m)` 作为下一轮无人接单待办。若 10:25 再次接单，第二轮锚点是新的 `driver_accept_time=10:25`；10:35 到期时再次按同样守卫 `20→10`。源码没有持久化退回次数；理论上可重复，但每轮都要先接单并消耗 10 分钟到达阈值，随后还会安排 15 分钟接单超时，所以不是同一次处理内立即循环。若某轮条件更新 0 行，则不释放司机、不改队列；若处理抛异常，`processOrders` 按配置的 `poll-interval-ms`（默认 5 秒）重排，但该重排本身可能失败；DB 补偿也可能再选。
</details>

### 练习 C：领取后进程退出

前提：订单 5001 的待办已被 `claimDue` 领取，进程随后退出；DB 仍是 10。

请说明 Redis、DB 补偿、副作用恢复各自能做什么、不能做什么。

<details>
<summary>练习 C 参考答案</summary>

领取 Lua 已把 5001 从 `delay:order:timeout` 移除，所以这条已领取待办不会因为无人处理而重新出现；没有进程能“记得”要处理它。数据库补偿是启用时的固定延迟扫描：若本轮查询成功，且 5001 仍满足 `is_deleted=0/status=10/update_time<=15分钟前`、排序和 batch 条件，它可能在后续轮被选中，不保证某轮成功。选中后 `handleTimeoutOrder` 会重读并可能系统取消；成功时登记状态缓存 90、cancel 待办、GEO 失效等副作用，单个失败只告警。若订单已终态且这些副作用失败，DB 补偿不再筛选 90，本章不能保证修复；司机占位、GEO、Mongo 各有独立机制或无法由此恢复。若 DB 补偿本身失败，只能等后续轮；不能保证下一次成功。
</details>

### 练习 D：六个判断

判断“正确 / 错误 / 取决于条件”，并说明理由。

1. `delay:order:timeout` 的成员有 15 分钟 TTL。
2. `CLAIM_DUE_ORDERS` 返回的订单一定已经从 ZSet 移除。
3. 到达超时会取消订单并保留账单。
4. 预约单永远不会被无人接单超时取消。
5. 数据库补偿会恢复所有丢失的 Redis 数据。
6. 超时处理失败的订单一定会无限重试。

<details>
<summary>练习 D 参考答案</summary>

1. 错误。key 和 member 都没有 TTL；15 分钟是业务超时阈值，score 是到期时间。
2. 取决于 Lua 是否已在 Redis 端成功执行。源码语义上返回的候选在脚本内已被 `ZREM`；但如果调用异常/响应丢失，客户端结果与 Redis 端实际状态可能不一致，需实际查询。
3. 错误。到达超时是 `20→10`，清空司机和接单时间，不写取消字段；支付/无人接单超时才是 90。
4. 错误。只有 `scheduled_time` 晚于当前接单锚点时才用未来上车时间作锚点；若没有 `scheduled_time`，或它已早于/等于锚点/扫描 cutoff，仍可能按普通锚点取消。
5. 取决于条件。数据库补偿启用且查询成功时，只筛选 `is_deleted=0`、状态 10/20/50、锚点过期的订单；选中并成功处理时可能执行状态缓存、队列、GEO、司机占位动作。它不扫描终态，也不是 Mongo 或所有 Redis 数据的通用修复器。
6. 取决于失败原因和状态。处理抛异常会被 catch 并按配置 `poll-interval-ms` 重排，但 `schedule` 本身异常可中断本批；DB 补偿满足条件时可能在后续选中。状态前进到 30/40/60/90 后 default 分支不再动作，条件更新 0 行也不重排。没有持久化重试次数上限，不能说“一定无限”，也不能保证某次成功。
</details>

> 可选实验：`OrderDelayQueueIntegrationTest` 需要 `-Dtest.redis.port` 指向一次性专用 Redis；它每次清理固定 key `delay:order:timeout`，因此会删除该 Redis 实例上所有订单超时待办。不满足隔离条件时只做静态阅读。`OrderTimeoutRegressionTest` 不需要外部端口，但也是断言阅读，本章未运行。禁止对业务 Redis/MySQL 执行清空、批量更新或故障注入。

---

## 八、一页复习

- **队列**：`delay:order:timeout` 是无 TTL ZSet；member=orderId，score=到期毫秒；同 member 覆盖。
- **领取**：Lua `ZRANGEBYSCORE 0 now LIMIT 0 batch` + `ZREM`；领取即移除；多实例靠 ZREM 归属。default/0 行不会让已领取 member 重现，只有并发 schedule、未领取未来条目或结果未知才可能有 member。
- **安排**：`schedule` 先用 `System.currentTimeMillis()` 算 deadline，再 afterCommit/立即 ZADD；deadline 不是提交后计算。
- **处理**：领取后重读 DB；只有 10/20/50 分支；订单锁 3 秒；READ_COMMITTED；条件更新裁决。
- **无人接单**：10→90；锚点 `update_time/create_time`，未来预约可用 `scheduled_time`；不改账单。
- **到达超时**：20→10；锚点 `driver_accept_time`；清空司机/接单时间；`update_time` 成为新接单锚点；可重复退回，无持久化上限。
- **未支付**：50→90；锚点 `finish_time`；保留账单。
- **重排**：未到期按剩余时间重排；已成功领取的条目已删。事务回滚不执行本次 afterCommit；ZADD 明确失败/响应丢失需分别判断，响应丢失不能断言 Redis 端状态；并发 schedule/cancel 可改变 member。
- **DB 兜底**：启用时每 60 秒查 10/20/50；10 排除未来预约；`update_time ASC LIMIT 100`。选中并成功处理时可能执行状态缓存、队列、GEO、司机占位动作；终态后不再入选，不能保证修复历史失败副作用。
- **时间**：状态判断用 `BusinessTime(Asia/Shanghai)`；队列用 JVM epoch 毫秒；DB 补偿用宿主机 `LocalDateTime.now()`；多实例时钟/时区需核验。

---

## 九、自测与答案

**1. 订单 5001 创建于 10:00，Redis 待办迟到 3 分钟才被领取。10:18 重读仍是 10，锚点 10:00。结果？**

<details>
<summary>参考答案</summary>

`handleAcceptTimeout` 用重读的锚点计算 deadline 10:15；当前业务时间 10:18 已到期，所以不会重排，而是调用 `systemCancel`。条件更新匹配 `status=10` 和 `update_time=快照` 后设 90、`cancel_role=3`、原因、新 `update_time`。迟到本身不改变“已过期”的判定；若 `update_time` 在领取和重读之间被外部改变，条件更新可能 0 行。
</details>

**2. 预约单 `scheduled_time=明天 08:00`，`create_time=今天 10:00`，无人接单。会被今天 10:15 取消吗？**

<details>
<summary>参考答案</summary>

不会。`handleAcceptTimeout` 发现 `is_reservation=1` 且 `scheduled_time` 晚于普通锚点，于是用 `scheduled_time` 作锚点，deadline 是明天 08:15。若重读时还未到期，就重排到该时间。数据库补偿查询也要求 `scheduled_time <= acceptCutoff` 才会把预约单选入；未来预约被排除。若预约单没有 `scheduled_time`，或它不晚于普通锚点，则按普通锚点判断。
</details>

**3. 到达超时把订单退回 10 后，为什么 `refreshOrderStatus` 而不是 `syncOrderStatus(10)`？**

<details>
<summary>参考答案</summary>

`SYNC_ORDER_STATUS` 是单调推进：当前 20 时调用 10 会被拒绝，防止旧回调回写。但 20→10 是业务上真正回退，所以用 `refreshOrderStatus` 登记 afterCommit 删除 `order:status`；下一次需要状态缓存时回源 DB 读到 10。该方法删除可能失败，旧缓存可能保留到 TTL。
</details>

**4. 支付超时任务已领取；`systemCancel` 条件更新 0 行。之后 Redis 待办会怎样？**

<details>
<summary>参考答案</summary>

领取已经移除该 member。`systemCancel` 0 行时直接返回，不执行 afterCommit 副作用，也不调用 `reschedule/cancel`；这条已领取 member 不会因 default/0 行重新出现。如果订单仍处于 50 且 `finish_time` 过期、调度开启且扫描/筛选成功，数据库补偿可能在后续扫描选中；如果状态已被推进到 60/90，本章不再处理。只有并发同 orderId `schedule` 或响应丢失导致实际执行未知时，才可能有 member；不能凭“未重排”断言残留。
</details>

**5. 为什么不能说“数据库补偿会保证最终恢复所有超时”？**

<details>
<summary>参考答案</summary>

它启用时每 60 秒查的只是 `is_deleted=0`、状态 10/20/50、锚点过期的订单；查询和后续处理都可能失败。选中并成功处理时，它可能执行状态缓存、队列、GEO、司机占位动作；但每个动作可能失败，终态后不再入选，非法状态/不存在订单/终态遗留队列成员不会被这条扫描处理。它也不能恢复 Mongo 轨迹。因此它是覆盖范围有界的兜底，不是全量强一致恢复。
</details>

**6. 超时任务和接单任务同时抢同一订单，谁赢？**

<details>
<summary>参考答案</summary>

取决于谁先拿到 `order:lock:{orderId}`。超时先拿：重读 10/20/50，条件更新先提交，接单后到达的接单条件更新通常 0 行。接单先拿：订单变 20；超时随后重读会进入到达超时分支而不是无人接单分支；若是 30/40/60/90 则 default。条件更新和快照条件保证后到者让位。已成功领取的条目已被 `ZREM`，不会因 default 重新出现；只有并发再次 `schedule`、未来条目未被领取或 Redis 响应结果未知时才需要按实际 key 判断。不能预设某一层必然赢。
</details>

---

## 十、导航与源码入口

- **上一章**：[第 9 章 位置与发现](03-location-and-discovery.md)——订单退回待接单后 GEO 池的失效/重建边界。
- **下一章**：[第 11 章 计价与支付](05-pricing-and-payment.md)——服务端公式、金额来源、验单门禁与默认支付边界。
- **相关专题**：
  - 延迟双删等其他延迟机制 → [第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)（S3 的“延迟”与本章 ZSet 待办不是同一机制）

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 调度开关 | `RideAgentHubApplication.java#main`（`@EnableScheduling`）；`config/RideOrderTimeoutProperties.java`；`src/main/resources/application.yaml#ride-order.timeout` |
| Redis 待办 | `service/base/OrderDelayQueue.java#schedule/#cancel/#claimDue/#afterCommit`；`constant/RedisKeyConstants.java#DELAY_ORDER_TIMEOUT_KEY` |
| 领取 Lua | `util/RedisScripts.java#CLAIM_DUE_ORDERS` |
| 轮询/补偿 | `service/base/OrderTimeoutJob.java#pollTimeoutOrders/#recoverOverdueOrders/#processOrders` |
| 超时状态机 | `service/impl/RideOrderServiceImpl.java#handleTimeoutOrder/#handleTimeoutOrderUnderLock/#handleAcceptTimeout/#handleArriveTimeout/#handlePayTimeout/#systemCancel/#reschedule/#plusMinutes` |
| 锁与事务 | `RideOrderServiceImpl#withOrderLock/#releaseOrderLock`；`@Transactional(isolation=READ_COMMITTED)`；F3 事务同步规则 |
| 占位/状态/GEO | `service/base/OrderGrabService.java#releaseDriver/#refreshOrderStatus/#syncOrderStatus`；`service/base/OrderGeoPool.java#add/#remove` |
| 状态/字段 | `domain/enums/RideOrderStatus.java`；`domain/entity/RideOrder.java`；`src/main/resources/sql/init.sql#sys_ride_order` |
| 业务时间 | `util/BusinessTime.java`（`Asia/Shanghai`） |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/OrderTimeoutRegressionTest.java`、`OrderDelayQueueIntegrationTest.java` |
