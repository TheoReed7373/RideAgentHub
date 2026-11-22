# 第 8 章 抢单与并发：两道约束与一个裁决者

> 上一章把"订单能从 10 走到 20"写成生命周期；这一章回答：两个司机抢同一单，或同一个司机抢两个单时，谁赢、为什么另一个人不会也赢。核心是三层协作：**Redisson 订单锁串行同一订单，司机行锁串行同一司机，MySQL 条件更新最终裁决**。Redis Lua 只做快速预检和占位，不是订单真值。
>
> 继续使用虚构人物：陈师傅（`DRIVER`，userId 占位 `7002002`）、老赵（`DRIVER`，`7002003`）。订单号 `100/101` 是测试和源码里的简化 ID；生产雪花 ID 更长。所有响应、状态码和竞态结果都是源码静态推导，未实测。

## 学习目标

读完本章，你应该能：

1. 区分"同单多司机"和"同司机多单"两类竞态，指出订单锁、司机行锁、Redis 占位、数据库条件更新分别保护什么。
2. 沿真实 `POST /order/driver/accept` 调用链，写出一次接单的完整时间线：身份与角色、ID 规范化、事务/锁边界、MySQL 查询/行锁、Redis 读写/Lua、条件更新、提交后动作、释放锁。
3. 解释代理新建事务与加入外层事务的区别，以及方法体返回 `true` 与物理提交的区别。
4. 分析缓存缺失/陈旧、Redis 异常、Lua 拒绝、DB 0 行、事务回滚/未知、补偿失败时的结果和剩余窗口。
5. 读出 `GRAB_ORDER_ATOMIC`、`SYNC_ORDER_STATUS`、`RELEASE_LOCK_IF_MATCH` 的 key/参数/返回值/原子性范围。
6. 阅读现有测试的门控、mock、断言和覆盖，知道哪些结论是静态推导、哪些测试只在隔离环境成立。

## 前置知识与阅读时间

- 必读：[第 7 章 订单生命周期](01-order-lifecycle.md)——`CREATED(10) → DRIVER_ACCEPTED(20)` 的守卫、副作用、异常映射；本章不重复状态机。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——`@Transactional` 代理、`REQUIRED/MANDATORY`、READ_COMMITTED、`FOR UPDATE`、普通读/锁定读、条件更新、提交后回调。
- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——String、Lua 的原子范围、TTL；本章解释项目里的三段 Lua。
- 可选：[第 6 章 权限、归属与防护](../01-auth/02-authorization-and-defense.md)——`@RequirePermission({"DRIVER"})` 与 401/403。
- 推荐：[第 10 章 超时与恢复](04-timeouts-and-recovery.md)——接单改期与超时释放的竞争时序。
- 阅读时间：约 100～120 分钟（含练习和自测）。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**：正文结论来自源码静态阅读；测试部分是"读过声明和断言，未运行"；没有任何真实并发、服务、Redis、MySQL 或外部服务实验。

---

## 零、先记住五个事实

1. **MySQL `sys_ride_order` 的条件更新是最终裁决**。Redis Lua 返回 1 只表示"预检通过并占住司机位"，不等于接单成功；`UPDATE ... WHERE order_status=10 AND driver_id IS NULL` 返回 1 才是 DB 层事实。
2. **订单锁保护同一订单，司机行锁保护同一司机**。`order:lock:{orderId}` 让同单操作串行；`sys_user.user_id` 行的 `FOR UPDATE` 让同一司机的接单事务串行。
3. **Redis 预检在数据库查询之后，不在所有 DB 访问之前**。`tryGrab` 先锁司机账号行、查该司机未结束订单，必要时还回源订单；然后才执行 Lua。
4. **当前 HTTP 调用链中，事务代理在方法体前开启，方法体返回后物理提交**。如果未来有外层事务，`driverAcceptOrder` 会加入外层；方法体返回不等于提交。
5. **提交后回调失败不会回滚已提交 MySQL**。状态缓存、GEO 失效、延迟队列、占位释放各有自己的失败语义；DB 扫描和后续回源只是部分恢复，不是强一致。

---

## 一、业务问题：两个并发会怎么坏

### 1.1 场景一：两个司机抢同一单

订单 100 是 `CREATED(10)`、`driver_id=NULL`。陈师傅（司机 1）和老赵（司机 2）几乎同时点"接单"。

朴素实现 1——先读后写：

```text
T1 读 status=10
T2 读 status=10
T1 UPDATE driver_id=1, status=20
T2 UPDATE driver_id=2, status=20
T1/T2 都认为成功
```

结果可能是"后写覆盖先写"，甚至两个接口都返回成功，但库里只有最后一位司机的 `driver_id`。用户看到的司机与接口结果可能矛盾。

朴素实现 2——只靠 Redis 占位：

```text
T1 Redis SETNX 抢占成功
T2 Redis SETNX 失败
T1 MySQL UPDATE 抛异常/事务回滚
T1 没把 Redis 占位还回去
```

如果没有匹配订单号释放，司机 1 可能被一个自己没接到的订单锁住；或者反过来，T2 因 Redis 拒绝而不再访问 DB，即使 DB 其实还可抢。

朴素实现 3——没有订单锁：

同一订单的两个请求都进入 `tryGrab` 和条件更新。数据库条件更新仍可能让一个成功、一个 0 行，但失败请求要等到 DB 阶段才知道结果，错误语义不如 `order:lock` 直接给出的"订单正在处理中"清晰，也增加无谓竞争。

### 1.2 场景二：同一个司机抢两个单

司机 1 对订单 100 和订单 101 连续/同时点接单。两个订单是不同 `order:lock`，所以订单锁不会把它们串行。如果只查一次"有没有未结束订单"再更新：

```text
T1 查司机 1 没有未结束订单
T2 查司机 1 也没有未结束订单
T1 UPDATE order100 -> driver_id=1, status=20
T2 UPDATE order101 -> driver_id=1, status=20
```

两个 UPDATE 涉及不同订单行，条件更新都可能影响 1 行；司机 1 同时接到两单。因此还需要**司机维度的串行**：`sys_user` 司机行 `FOR UPDATE`，以及"司机已有未结束订单"的当前读检查。

### 1.3 本章最小概念

#### ① Redisson 订单锁与看门狗

`RideOrderServiceImpl#withOrderLock` 使用 Redisson 的 `RLock`：

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
private static final long ORDER_LOCK_WAIT_SECONDS = 3L;

private <T> T withOrderLock(String orderId, Supplier<T> action) {
    RLock lock = redissonClient.getLock(RedisKeyConstants.orderLockKey(orderId));
    boolean acquired;
    try {
        acquired = lock.tryLock(ORDER_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException(409, "系统繁忙，请稍后重试");
    }
    if (!acquired) {
        throw new BusinessException(409, "订单正在处理中，请稍后重试");
    }
    try {
        return action.get();
    } finally {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseOrderLock(lock, orderId);
                }
            });
        } else {
            releaseOrderLock(lock, orderId);
        }
    }
}
```

最小解释：

- key 是 `order:lock:{orderId}`；它保护**同一订单**的状态操作，不保护"同一司机接两个不同订单"。
- `tryLock(3, TimeUnit.SECONDS)` 最多等 3 秒；没传 leaseTime，Redisson 会使用看门狗续期，避免慢事务期间锁到期。仓库没有覆盖看门狗配置；集成测试把 `lockWatchdogTimeout` 设为 1500ms，这是测试配置。
- 拿不到锁直接 409"订单正在处理中"；被中断时恢复线程中断标志，也 409。
- 当前接单路径有 Spring 事务同步，所以解锁注册在 `afterCompletion`，也就是事务提交/回滚及其回调之后；`releaseOrderLock` 检查 `isHeldByCurrentThread()`，解锁失败只记日志，不覆盖业务异常。

#### ② MySQL 行锁与条件更新

`tryGrab` 里的两个查询带 `FOR UPDATE`：

```sql
-- 语义示意，实际由 MyBatis-Plus 构造；sys_user.user_id 有唯一键
SELECT * FROM sys_user
WHERE user_id = ? AND is_deleted = 0 AND status = 1 AND role = 'DRIVER'
FOR UPDATE;

SELECT * FROM sys_ride_order
WHERE driver_id = ? AND is_deleted = 0
  AND order_status NOT IN (50, 60, 90)
LIMIT 1 FOR UPDATE;
```

- `FOR UPDATE` 是锁定读（当前读），在 READ_COMMITTED 下能看到已提交的最新行，并在事务结束前持锁。
- 司机账号行锁把同一司机的两个接单请求串行。
- 未结束订单查询的当前读让后一个事务看到先一个事务已提交的结果。

最终的条件更新不带 `FOR UPDATE`，但 UPDATE 本身是当前读并加锁：

```sql
-- 语义示意
UPDATE sys_ride_order
SET driver_id = ?, order_status = 20, driver_accept_time = ?, update_time = ?
WHERE order_id = ? AND is_deleted = 0
  AND order_status = 10 AND driver_id IS NULL;
```

影响 1 行才算 DB 层成功；影响 0 行说明订单已经不是 `10` 或已有司机。

#### ③ 事务传播

`driverAcceptOrder` 上是：

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public Boolean driverAcceptOrder(...)
```

Spring 默认传播是 `REQUIRED`：当前 HTTP Controller 调用没有外层事务，代理新建事务；若未来由另一个 `@Transactional` 方法调用，则加入外层。`OrderGrabService#tryGrab` 上是 `Propagation.MANDATORY`：必须已有事务，否则 Spring 抛事务异常；本章调用链总是先经过 `driverAcceptOrder`。

#### ④ 提交后回调

本章出现两类：

- `afterCommit`：事务**成功提交后**执行；回滚不执行。
- `afterCompletion(int status)`：事务提交/回滚/结果未知时执行；`tryGrab` 用它判断 `status != STATUS_COMMITTED` 才释放占位。

它们不是 MySQL 事务的一部分，只把部分 Redis 动作推迟到事务边界之后。

---

## 二、一次真实接单请求的完整时间线

### 2.1 Controller 与身份

```java
// controller/RideOrderController.java（真实节选）
@PostMapping("/driver/accept")
@RequirePermission({"DRIVER"})
public Result driverAccept(@RequestBody DriverAcceptReqDTO req) {
    Long driverId = UserTokenContext.getUserIdInLong();
    Boolean ok = rideOrderService.driverAcceptOrder(
            req.getOrderId(), String.valueOf(driverId), req.getCurrentLat(), req.getCurrentLng()
    );
    return Result.ok(ok);
}
```

- `@RequirePermission({"DRIVER"})` 在参数解析之后、方法体前检查 `UserTokenContext`；非 DRIVER 403，方法体不进入。
- `driverId` 来自 token，不来自请求体；请求体只有 `orderId/currentLat/currentLng`。
- Service 返回 `Boolean`；`Result.ok(true/false)` 都是 HTTP 200，`data=false` 只对应 **DB 条件更新影响 0 行**（`updated <= 0`）这一种正常业务结果。预检失败（订单不存在 / 司机忙 / 不可抢）在 `driverAcceptOrder` 里直接抛 `BusinessException(409)`，不会返回 `false`。

### 2.2 Service 入口与 ID 规范化

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
@Override
@Transactional(isolation = Isolation.READ_COMMITTED)
public Boolean driverAcceptOrder(String rawOrderId, String rawDriverId, BigDecimal currentLat, BigDecimal currentLng) {
    if (rawOrderId == null || rawOrderId.isBlank()) {
        throw new IllegalArgumentException("orderId不能为空");
    }
    if (rawDriverId == null || rawDriverId.isBlank()) {
        throw new IllegalArgumentException("driverId不能为空");
    }

    Long driverIdLong = convertToLong(rawDriverId);
    String orderId = convertToLong(rawOrderId).toString();
    String driverId = driverIdLong.toString();

    return withOrderLock(orderId, () -> { /* 下一节 */ });
}
```

- 空白 ID 抛 `IllegalArgumentException`，HTTP 400；非数字 ID 也由 `convertToLong` 抛同样异常。
- `"0100"` 会被规范化成 `"100"`；Redis key 和 DB 查询都用规范化后的字符串/Long。
- 方法进入时事务代理已经开启；`withOrderLock` 在事务内拿订单锁。

### 2.3 `tryGrab`：司机行锁 + 状态/占位

```java
// service/base/OrderGrabService.java（真实节选）
@Transactional(propagation = Propagation.MANDATORY)
public GrabResult tryGrab(String orderId, String driverId) {
    orderId = normalizeId(orderId);
    driverId = normalizeId(driverId);

    UserAuth driver = userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
            .eq(UserAuth::getUserId, Long.valueOf(driverId))
            .eq(UserAuth::getIsDeleted, 0)
            .eq(UserAuth::getStatus, 1)
            .eq(UserAuth::getRole, "DRIVER")
            .last("FOR UPDATE"));
    if (driver == null) {
        throw new BusinessException(403, "司机账号不可用");
    }

    RideOrder active = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
            .eq(RideOrder::getDriverId, Long.valueOf(driverId))
            .eq(RideOrder::getIsDeleted, 0)
            .notIn(RideOrder::getOrderStatus, List.of(
                    RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                    RideOrderStatus.PAID.getCode(), RideOrderStatus.CANCELLED.getCode()))
            .last("LIMIT 1 FOR UPDATE"));
    redisTemplate.opsForValue().set(RedisKeyConstants.driverActiveKey(driverId),
            active == null ? IDLE : active.getOrderId().toString(), CACHE_TTL);

    String statusKey = RedisKeyConstants.orderStatusKey(orderId);
    if (redisTemplate.opsForValue().get(statusKey) == null) {
        RideOrder order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getOrderId, Long.valueOf(orderId))
                .eq(RideOrder::getIsDeleted, 0));
        if (order == null) {
            return GrabResult.ORDER_NOT_FOUND;
        }
        redisTemplate.opsForValue().setIfAbsent(statusKey, String.valueOf(order.getOrderStatus()), CACHE_TTL);
    }

    Long result = redisTemplate.execute(RedisScripts.GRAB_ORDER_ATOMIC,
            List.of(statusKey, RedisKeyConstants.driverActiveKey(driverId)),
            String.valueOf(RideOrderStatus.CREATED.getCode()), IDLE, orderId,
            String.valueOf(CACHE_TTL.toSeconds()));
    if (result == null) {
        throw new IllegalStateException("抢单预检未返回结果");
    }
    if (result == 1L) {
        String claimedOrderId = orderId;
        String claimedDriverId = driverId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    rollbackGrab(claimedDriverId, claimedOrderId);
                }
            }
        });
        return GrabResult.SUCCESS;
    }
    return result == -2L ? GrabResult.DRIVER_BUSY : GrabResult.ORDER_NOT_GRABBABLE;
}
```

逐步读：

1. **司机账号行锁**：不是只查"角色是否 DRIVER"，而是按 `user_id` 当前读并 `FOR UPDATE`。司机不存在、禁用、已删除、角色不是 DRIVER，都 403"司机账号不可用"。
2. **未结束订单检查**：排除 50/60/90。查到任何一条就说明司机已有未结束订单；查不到则把 `driver:active` 修成 `__IDLE__`。这一步是**先查 DB，再写 Redis**。
3. **修复/设置司机占位**：`SET driver:active:{driverId} = activeOrderId或__IDLE__，EX 300`。它总是覆盖，不是 `SETNX`；因此陈旧的占位会先被 DB 查询结果修复。
4. **订单状态缓存缺失才回源**：如果 `order:status:{orderId}` 不存在，查 `sys_ride_order`；订单不存在返回 `ORDER_NOT_FOUND`；存在则 `SETNX status, EX 300`。如果缓存已有值，直接用缓存，不再读订单。
5. **Lua 原子预检**：只读两个 key 并可能写 `driver:active`；不写订单状态缓存，也不写 DB。
6. **Lua 成功才注册回滚占位**：注意 `tryGrab` 前面已经写了 `driver:active`（根据 DB 查询结果），但"我这次占了这单"的补偿回调只有在 Lua 返回 1 后注册。若 Lua 失败或异常，不会注册这个补偿。

### 2.4 条件更新与提交后动作

```java
// service/impl/RideOrderServiceImpl.java（真实节选）
return withOrderLock(orderId, () -> {
    OrderGrabService.GrabResult grabResult = orderGrabService.tryGrab(orderId, driverId);
    if (grabResult != OrderGrabService.GrabResult.SUCCESS) {
        throw new BusinessException(409, grabFailureMessage(grabResult));
    }

    LocalDateTime now = com.fancy.rideagenthub.util.BusinessTime.now();
    int updated = rideOrderMapper.update(null,
            new LambdaUpdateWrapper<RideOrder>()
                    .eq(RideOrder::getOrderId, orderId)
                    .eq(RideOrder::getIsDeleted, 0)
                    .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
                    .isNull(RideOrder::getDriverId)
                    .set(RideOrder::getDriverId, driverIdLong)
                    .set(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                    .set(RideOrder::getDriverAcceptTime, now)
                    .set(RideOrder::getUpdateTime, now));

    if (updated <= 0) {
        orderGrabService.rollbackGrab(driverId, orderId);
        orderGrabService.refreshOrderStatus(orderId);
        return false;
    }

    orderGrabService.syncOrderStatus(orderId, RideOrderStatus.DRIVER_ACCEPTED.getCode());
    orderGeoPool.remove(orderId);
    if (currentLng != null && currentLat != null) {
        driverGeoIndex.refreshIfExist(driverId, currentLng, currentLat);
    }
    orderDelayQueue.schedule(orderId, Duration.ofMinutes(timeoutProperties.getArriveTimeoutMinutes()));
    return true;
});
```

顺序必须精确：

- `updated <= 0`：**立即**调用 `rollbackGrab`，这是 best-effort；`refreshOrderStatus` 只是登记 afterCommit 删除状态缓存。方法返回 `false`；没有 DB 修改，事务仍可提交。
- `updated == 1`：`syncOrderStatus` **登记** afterCommit 状态缓存；`orderGeoPool.remove` **登记** afterCommit GEO 失效；可选位置刷新是**提交前直接执行**的 Redis Lua；`orderDelayQueue.schedule` 先计算 deadline 再**登记** afterCommit ZSet 写入。
- `true` 返回只表示 lambda 结束。当前 Controller 调用链没有外层事务，代理随后提交；调用方在提交/回调后才拿到结果。

### 2.5 完整成功时间线（当前 Controller 调用，代理新建事务）

前提：token 有效、角色 DRIVER、请求体可解析；订单 100 在 DB 是 `10`、`driver_id=NULL`；司机账号有效且没有未结束订单；Redis 可用。

```text
t0  A2 已通过：PermissionAspect 判断 DRIVER；Controller 从 token 得到 driverId
t1  代理开启 READ_COMMITTED 事务（Spring 默认 REQUIRED；当前无外层）
t2  withOrderLock 获取 order:lock:100（最多等 3 秒；未获取则 409）
t3  tryGrab：
      3.1 SELECT sys_user ... FOR UPDATE，锁司机 1 账号行
      3.2 SELECT sys_ride_order 未结束订单 ... FOR UPDATE；没有则 driver:active:1=__IDLE__
      3.3 若 order:status:100 缺失，SELECT sys_ride_order，再 SETNX 为 10
      3.4 执行 GRAB_ORDER_ATOMIC：status=10、driver:active:1=__IDLE__
          → SET driver:active:1=100，EX 300；返回 1
      3.5 注册 afterCompletion：事务未提交则尽力释放占位
t4  MySQL 条件 UPDATE 影响未提交修改：
      order100 -> status=20, driver_id=1, accept_time/update_time=now
t5  syncOrderStatus：登记 afterCommit 写 order:status:100=20
t6  orderGeoPool.remove：登记 afterCommit 失效附近订单池
t7  currentLat/currentLng 非空时：refreshIfExist 直接写 Redis 司机位置（失败被捕获）
t8  orderDelayQueue.schedule：先算 now+10min 的 deadline，再登记 afterCommit ZADD
t9  lambda 返回 true；withOrderLock 注册 afterCompletion 释放 order:lock:100
t10 代理提交 MySQL
t11 afterCommit 按登记顺序执行：状态缓存 20 / GEO 池失效 / ZSet 到达超时
t12 afterCompletion：tryGrab 的补偿已提交不动作 → 释放 order:lock
t13 Controller 返回 Result.ok(true)
```

加入外层事务时的差别：t9 方法体返回后，物理提交、afterCommit、afterCompletion 都可能推迟到外层事务边界；调用方在 t9 拿到的 `true` 不能证明外层已提交。当前 Controller 没有外层事务，t10 在 t9 后发生。

### 2.6 锁等待、中断、释放失败与提交未知

| 情况 | 发生点 | 结果 | 剩余窗口 |
|---|---|---|---|
| 订单锁未在 3 秒内获得 | `withOrderLock` | 409"订单正在处理中"；没进 `tryGrab`，没查 DB/Redis | 不会释放未持有的锁 |
| `tryLock` 被中断 | `withOrderLock` | 恢复线程中断标志，409"系统繁忙"；没进业务 | 调用方处理中断的后续行为不在源码内 |
| 订单锁释放失败 | `afterCompletion` 后 `releaseOrderLock` | 只记 warn；不覆盖已提交/回滚结果 | 后续同单请求可能继续等锁；不能断言一定很快释放 |
| 司机账号不可用 | `tryGrab` 第一查询 | 403"司机账号不可用"；未写占位、未执行 Lua | 无 |
| 订单不存在 | 状态缓存缺失且回源查不到 | `ORDER_NOT_FOUND` → 409"订单不存在"；未执行 Lua | 不是 404；缓存里旧值存在时不会回源 |
| Lua 明确返回 -1/-2 | `redisTemplate.execute` 正常返回 | 409；未注册本次占位回滚；未执行 DB 条件更新 | Lua 没有执行成功分支的 `SET 当前订单`；此前按 DB 修复写入的 `__IDLE__`/旧订单仍在 |
| Lua 明确返回 1 | `redisTemplate.execute` 正常返回 1 | 注册 afterCompletion 占位补偿；继续 DB 条件更新 | Redis 端已成功 `SET 当前订单占位` |
| Java 收到 null | `redisTemplate.execute` 返回 null | `IllegalStateException`；事务回滚；未进入回调注册；未执行 DB 条件更新 | **Redis 端是否已执行/写入未知**；可能残留当前订单占位，也可能未写；TTL/下一次 DB 修复只是部分兜底 |
| 调用抛异常/响应丢失 | Redis 驱动或网络异常 | 异常向上；事务回滚；未进入回调注册；未执行 DB 条件更新 | 同上：不能凭客户端异常断言 Redis 已写或未写；未做故障注入 |
| 提交结果未知 | 代理提交阶段失败 | afterCommit 不执行；afterCompletion 的 `status != COMMITTED` 会尽力释放占位 | 数据库可能已提交；下一次接单按 DB 修复；不能断言缓存/队列状态 |

---

## 三、两类并发时间线

### 3.1 场景 A：同单多司机

前提：

- 订单 100：DB `order_status=10`、`driver_id=NULL`。
- 司机 1、司机 2 都有效，均无未结束订单。
- 两个 HTTP 请求几乎同时到达；请求体合法；Redis/MySQL 可用。
- T2 能在 3 秒内拿到订单锁。超过 3 秒则 `withOrderLock` 抛 409，根本不进入 `tryGrab`，下表不适用。
- 初始 `order:status:100` 可缺失或为 `10`；`driver:active:1/2` 可缺失或被 DB 查询结果覆盖。

成功主干如下；a8 拆成“释放锁”和“T2 取得锁”两个时点。

| 时刻 | T1：司机 1 抢 order100 | T2：司机 2 抢 order100 | 其他事务可见的 MySQL 已提交状态 | Redis key |
|---|---|---|---|---|
| a0 | 通过切面，进入事务 | 通过切面，进入事务 | order100=10 | `order:lock:100` 空闲 |
| a1 | 拿到 `order:lock:100` | 在 3 秒等待内等锁 | order100=10 | T1 持锁 |
| a2 | 锁司机 1 行；当前读查无未结束订单；`SET driver:active:1=__IDLE__` | 仍在等订单锁 | order100=10 | driver:active:1=__IDLE__ |
| a3 | 若状态缓存缺失，回源并 `SETNX=10`；Lua 检查状态=10、司机=__IDLE__，成功后才 `SET driver:active:1=100` | 仍在等订单锁 | order100=10 | driver:active:1=100 |
| a4 | UPDATE 未提交 | 仍在等订单锁 | 其他事务普通读仍是 10；T1 自己的当前读能看到本事务修改 | driver:active:1=100 |
| a5 | 登记 afterCommit；方法体返回 true | 仍在等订单锁 | order100=10（T1 未提交） | driver:active:1=100 |
| a6 | 代理提交 MySQL | 仍在等订单锁 | order100=20 | driver:active:1=100 |
| a7 | afterCommit：同步状态缓存 20、GEO 失效、到达超时 | 仍在等订单锁 | order100=20 | order:status:100=20 |
| a8 | afterCompletion：占位补偿不动作，释放 `order:lock:100` | — | order100=20 | 订单锁空闲 |
| a9 | — | 3 秒内成功取得 `order:lock:100` | order100=20 | T2 持锁 |
| a10 | — | 锁司机 2 行；当前读查无未结束订单；`SET driver:active:2=__IDLE__` | order100=20 | driver:active:2=__IDLE__ |
| a11 | — | 状态缓存通常已同步为 20；Lua 第一项字符串比较失败 → 返回 -1 | order100=20 | 无新占位 |
| a12 | — | 抛 409"订单已被其他司机接走或已取消" | order100=20 | driver:active:2=__IDLE__ |

**分支 A-1：T2 读到已同步的 20**，上表 a11/a12 就是最终结果，不进入 DB 条件更新。

**分支 A-2：T2 执行 Lua 时读到旧 `10`**。这要求偏离上表 a7 的成功同步假设，例如 a7 的状态同步执行失败，旧缓存仍在有效期内且未被删除。在该时间线中，T2 须等 T1 的 afterCompletion 释放订单锁，不能把 T1 已登记的 afterCommit 尚未执行当作 T2 已拿锁的原因。若此时两条 Lua 检查都通过，`GRAB_ORDER_ATOMIC` 会 `SET driver:active:2=100` 并返回 1；随后 T2 的 DB 条件更新看到已提交的 20，影响 0 行。代码立即 best-effort `rollbackGrab(2,100)`，`refreshOrderStatus` 登记 afterCommit 删除状态缓存；删除可能失败。T2 返回 `Result.ok(false)`。这个分支和 A-1 互斥。

补充：

- T2 的司机数据库查询已经发生（司机 2 行锁、未结束订单查询、`SET __IDLE__`）；“状态旧值命中只是不回源该订单”不等于没有 DB 访问。
- MySQL 可见性要区分：T1 在提交前可以读到自己的未提交修改；T2 在 a6 前的普通读不会看到 20；a6 后的当前读/普通读才看到 20。
- T2 的 `driver:active:2` 与 T1 的 `driver:active:1` 是不同 key；同单竞态主要靠订单锁和 DB 条件更新，不靠两个司机的占位互斥。

### 3.2 场景 B：同一司机抢两单

前提：

- 司机 1 在 DB 没有未结束订单。
- 订单 100、101 都是 `10`、`driver_id=NULL`。
- 司机 1 发起两个请求：T1 抢 order100，T2 抢 order101。
- 两个请求请求体合法；Redis/MySQL 可用。

| 时刻 | T1：司机 1 抢 order100 | T2：司机 1 抢 order101 | MySQL 已提交可见状态 | Redis key |
|---|---|---|---|---|
| b0 | 进入事务，拿 `order:lock:100` | 进入事务，拿 `order:lock:101` | 100/101 都是 10 | 两个订单锁独立 |
| b1 | `SELECT sys_user WHERE user_id=1 FOR UPDATE`，锁司机行 | `SELECT sys_user WHERE user_id=1 FOR UPDATE`，等司机行锁 | 100/101 都是 10 | driver:active:1 可能空 |
| b2 | 查无未结束订单；`driver:active:1=__IDLE__` | 等司机行锁 | 100/101 都是 10 | driver:active:1=__IDLE__ |
| b3 | order100 状态缓存处理；Lua 成功；`driver:active:1=100` | 等司机行锁 | 100/101 都是 10 | driver:active:1=100 |
| b4 | UPDATE order100 未提交 | 等司机行锁 | 100 未提交；101=10 | driver:active:1=100 |
| b5 | 提交 MySQL；afterCommit 写 `order:status:100=20` | 获得司机行锁；当前读看到 order100=20 | order100=20；101=10 | driver:active:1=100 |
| b6 | afterCompletion 释放 `order:lock:100` | 依据 order100 当前读设置 `driver:active:1=100` | order100=20 | driver:active:1=100 |
| b7 | — | order101 状态缓存可为 10；Lua：status=10 但 driver:active:1≠`__IDLE__` → 返回 -2 | order101=10 | 无新占位 |
| b8 | — | 抛 409"司机已有未结束订单，无法接单"；事务回滚；释放 `order:lock:101` | order101=10 | driver:active:1=100 |

结论：

- 两个订单锁不互斥，**司机账号行锁**和"未结束订单当前读"把两个事务串行。
- T2 不会因为订单 101 自己是 `10` 就成功；Lua 的第二道检查 `driver:active:1 == __IDLE__` 会返回 `-2`。
- 若 T1 在 Lua 成功后回滚：afterCompletion 会尽力把 `driver:active:1=100` 释放；T2 稍后拿司机行锁时，未结束订单查询通常看不到已回滚的订单，可把占位修成 `__IDLE__`，再抢 101。
- 若 T1 的条件更新 0 行：它会立即 `rollbackGrab`，事务提交后 afterCommit 删除/刷新状态缓存；T2 仍可能抢 101。
- 如果 T1 提交结果未知，afterCompletion 因状态不是 `COMMITTED` 会尝试释放占位；DB 可能已提交。T2 是否看到忙取决于它与回调、TTL 的时序，不能只凭"异常"断定。

### 3.3 为什么每层都必要

| 去掉哪一层 | 同单多司机 | 同司机多单 | 仍可能的窗口 |
|---|---|---|---|
| 去掉订单锁 | 两个事务都进 `tryGrab`/DB；条件更新仍可能只有一人成功 | 不受影响 | 更早失败点变成 DB/Redis；错误语义变差，DB 压力变大 |
| 去掉司机行锁 | 基本不受影响 | 两个事务都可能查到"无未结束订单"；不同订单的条件更新都影响 1 行，司机接两单 | 这是同司机多单的主要防线 |
| 去掉 Redis Lua | 同单走 DB 条件更新；同司机行走 DB 行锁 | 可能在 DB 阶段拒绝，但缺少快速占位和缓存加速 | 更多无效请求进入 DB |
| 去掉 DB 条件更新 | 若 Redis 有旧缓存/竞态，两个请求可能都被放行；后写覆盖先写 | 同上 | 订单真值失去最终裁决 |
| 去掉 afterCompletion 占位补偿 | 回滚后司机可能被未成功订单占住 | 同上 | 进程崩溃时靠 5 分钟 TTL 和下一次 DB 修复部分恢复 |

---

## 四、失败分支与缓存条件矩阵

### 4.1 `tryGrab` 前半段

| 前提/输入 | 行为 | 返回/异常 | DB/Redis 变化 |
|---|---|---|---|
| `rawOrderId/rawDriverId` 空白 | Service 入口校验 | `IllegalArgumentException` → 400 | 无 |
| ID 不是数字 | `convertToLong` | `IllegalArgumentException` → 400 | 无 |
| 订单锁 3 秒未获得 | `withOrderLock` | `BusinessException(409)`；未进入 `tryGrab` | 未执行本表的后续 DB/Redis 业务 |
| `tryLock` 被中断 | `withOrderLock` | 恢复中断标志；`BusinessException(409)` | 未进入 `tryGrab` |
| 司机不存在/禁用/删除/非 DRIVER | `SELECT ... FOR UPDATE` 无行 | `BusinessException(403)` | 未写 Redis |
| 司机已有未结束订单（20/30/40） | 未结束订单当前读查到；`SET driver:active` 为该订单 | 只有订单状态缓存也等于 `"10"` 时，Lua 才返回 -2；若状态缓存非 `"10"`，Lua 先返回 -1 | `driver:active` 被修复为旧订单 |
| 司机没有未结束订单 | 当前读查不到 | `SET driver:active=__IDLE__`；后续看状态缓存 | 陈旧占位被覆盖 |

### 4.2 订单状态缓存与 Lua 结果

`GRAB_ORDER_ATOMIC` 的第一项是**状态字符串比较**，第二项才是司机空闲。下表把二者都列出来；不要只看状态缓存。

| 状态缓存 | 司机占位 | `tryGrab` 行为 | Lua 返回 | 后续 |
|---|---|---|---|---|
| 缺失，订单不存在 | 任意 | 回源查不到 | 未执行 Lua；Java 返回 `ORDER_NOT_FOUND` | 抛 409"订单不存在"；此前司机查询/占位修复已发生 |
| 缺失，订单存在 | 空闲 | 回源后 `SETNX` 当前状态；并发写入时本次 SETNX 可能不生效 | Lua 实际读取的缓存是 `"10"` 且司机仍空闲时 1 | Lua 看届时 key 的值，不能只凭回源读到的状态推断结果 |
| 缺失，订单存在 | 忙 | 回源后 `SETNX` 当前状态 | 当前状态非 `"10"` 先 -1；`"10"` 且司机忙则 -2 | -1/-2 都抛 409，但消息不同 |
| `"10"`，DB 也是 10 | 空闲 | 命中缓存，不回源该订单；但司机 DB 查询已经发生 | 1 | 继续条件更新 |
| `"10"`，DB 已是 20/90 | 空闲 | 命中旧缓存 | 1，随后 DB 条件更新 0 行 | 立即 best-effort `rollbackGrab`；`refreshOrderStatus` afterCommit 删除可能失败 |
| `"10"`，DB 任意 | 忙 | 命中旧状态 | -2 | 409"司机已有未结束订单”；不进 DB 条件更新 |
| `"20"`/`"90"`，DB 其实仍 10 | 空闲 | 命中旧状态 | -1 | 409"订单已被其他司机接走或已取消”；需等 TTL、后续同步或删除 |
| `"20"`/`"90"`，司机也忙 | 忙 | 命中旧状态 | -1（先检查状态，不检查司机） | 409"订单已被...”；不要断言一定是司机忙导致 |
| 非数字/非法值 | 任意 | 命中缓存 | 字符串不等于 `"10"` → -1；抢单脚本不用 `tonumber` | 409；不会自动修复非法值 |
| Redis 读/写异常 | 任意 | 异常向上传播 | 调用结果可能未知 | 事务回滚；未执行 DB 条件更新；不能据此断言 Redis 是否已写入 |

### 4.3 Lua 成功之后

| 情况 | DB | Redis/队列/GEO | HTTP/内部结果 |
|---|---|---|---|
| DB 条件更新影响 0 行 | 无修改 | `rollbackGrab` 立即 best-effort 删除占位；`refreshOrderStatus` 登记 afterCommit 删除状态缓存 | `Result.ok(false)` |
| DB 条件更新抛运行时异常 | 事务回滚 | afterCommit 动作不执行；afterCompletion 因回滚尽力释放占位；锁释放 | 按异常类型映射；未列出的运行时异常通常 500 |
| DB 条件更新 1 行，事务提交 | order100=20 | afterCommit 状态缓存 20、GEO 失效、到达超时；每个 guarded 失败只告警 | `Result.ok(true)` |
| DB 更新 1 行，提交结果未知 | 可能已提交、也可能回滚 | afterCommit 不执行；afterCompletion 因 `status != COMMITTED` 尽力释放占位 | 调用方收异常；最终以 DB 为准 |
| afterCommit 状态缓存失败 | order100=20 | 旧缓存可能保留到 TTL/下一次回源 | DB 胜；下一次 Lua 可能被旧缓存误拒，或旧 10 放行后被 DB 0 行拒绝 |
| afterCommit GEO 失效失败 | order100=20 | 附近订单池可能仍含旧候选 | 2 小时 TTL 或下次读重建；O3 细化 |
| afterCommit ZSet 写入失败 | order100=20 | 到达超时待办缺失 | O4 数据库补偿扫描部分兜底 |
| 可选位置刷新失败 | order100=20 | `refreshIfExist` 返回 false；不回滚 | 司机位置可能仍为旧值/离线；不影响接单事务 |

### 4.4 Lua 与补偿细节

`rollbackGrab` / `releaseDriver` 最终都走：

```java
// service/base/OrderGrabService.java（真实节选）
public void rollbackGrab(String driverId, String orderId) {
    String key = RedisKeyConstants.driverActiveKey(normalizeId(driverId));
    try {
        redisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(key), normalizeId(orderId));
    } catch (RuntimeException e) {
        log.warn("释放司机订单占位失败: driverId={}, orderId={}", driverId, orderId, e);
    }
}
```

- `RELEASE_LOCK_IF_MATCH` 只在 `GET driver:active:{driverId}` 等于本 orderId 时删除；因此它不会误删司机随后接到的新订单。
- `releaseDriver` 是把 `rollbackGrab` 包在 `afterCommit` 中；`tryGrab` 的回滚补偿直接在 `afterCompletion` 中调用 `rollbackGrab`。
- 两者都是 best-effort：Lua 返回 0 不抛异常；Redis 异常 catch 后只记 warn。
- `SYNC_ORDER_STATUS` 单调推进：只有当前值缺失、非数字或小于等于新值才写。这样旧的 `syncOrderStatus(10)` 不会覆盖已同步的 20/30；`refreshOrderStatus` 则直接删除，用于 20 回退 10 的特殊路径（O4 的到达超时）。

---

## 五、Lua、key 与 TTL

### 5.1 抢单原子预检

```lua
-- util/RedisScripts.java#GRAB_ORDER_ATOMIC（真实 Lua）
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return -1
end
if redis.call('GET', KEYS[2]) ~= ARGV[2] then
    return -2
end
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
return 1
```

调用映射：

| Lua 变量 | Java 值 | 含义 |
|---|---|---|
| `KEYS[1]` | `order:status:{orderId}` | 订单状态缓存 |
| `KEYS[2]` | `driver:active:{driverId}` | 司机当前占位 |
| `ARGV[1]` | `"10"` | 期望订单状态 |
| `ARGV[2]` | `"__IDLE__"` | 期望司机空闲 |
| `ARGV[3]` | `orderId` | Lua 成功后写入司机占位 |
| `ARGV[4]` | `"300"` | 占位 TTL 秒数（`CACHE_TTL=5min`） |

返回值与调用结果要分开：

- 明确 `-1`：第一项字符串比较失败，订单状态缓存不是 `"10"`。Java 返回 `ORDER_NOT_GRABBABLE` → 409"订单已被其他司机接走或已取消"；未进入回调注册和 DB 更新。
- 明确 `-2`：状态是 `"10"`，但司机占位不是 `__IDLE__`。Java 返回 `DRIVER_BUSY` → 409"司机已有未结束订单"；未进入回调注册和 DB 更新。
- 明确 `1`：两项检查都通过，Lua 已执行 `SET driver:active=orderId,EX 300`；Java 才注册 afterCompletion 占位补偿，并继续 DB 条件更新。
- Java 收到 `null`：`tryGrab` 抛 `IllegalStateException`。此时没有注册本次占位回滚，也没有执行 DB 条件更新；但**不能推断 Redis 一定没有执行/写入**。若 Lua 在 Redis 端已经执行到 `SET`，可能出现当前订单占位残留，且没有本次回调；TTL 和下一次 DB 修复只是部分兜底。
- 调用抛异常/响应丢失：同上，客户端看不到 Lua 返回值；不能凭异常断言“已写”或“未写”，本章未做故障注入。

原子性范围只有这一次 Redis 脚本内的两个 GET 和一个 SET；**不包含** MySQL 条件更新，也不包含事务提交。前两项比较都是字符串比较，不是数字比较；`tonumber` 属于 `SYNC_ORDER_STATUS`。脚本报错会作为 Redis 异常抛出，不会自动回滚 MySQL；已经发生的 Redis 写入也不会被 MySQL 回滚自动撤销。

### 5.2 状态缓存单调推进

```lua
-- util/RedisScripts.java#SYNC_ORDER_STATUS（真实 Lua）
local current = redis.call('GET', KEYS[1])
if current == false or tonumber(current) == nil or tonumber(current) <= tonumber(ARGV[1]) then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 1
end
return 0
```

- key：`order:status:{orderId}`；`ARGV[1]` 是新状态码；`ARGV[2]` 是 `"300"`。
- 当前 key 不存在（Lua 中 `false`）、当前值不是数字、或当前值 <= 新值时，写入新值并重置 300 秒。
- 若当前 20，调用 `syncOrderStatus(10)` 会返回 0，不回写。
- 该脚本在 `afterCommit` 中执行；它不参与 MySQL 事务。

### 5.3 匹配订单释放司机占位

```lua
-- util/RedisScripts.java#RELEASE_LOCK_IF_MATCH（真实 Lua）
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

- key：`driver:active:{driverId}`；`ARGV[1]` 是 orderId。
- 只有占位仍是本订单时删除；返回 1 删除成功，返回 0 表示占位已是 `__IDLE__`、其他订单或不存在。
- Java 侧不检查返回值；Redis 异常只记日志。

### 5.4 key 与 TTL 汇总

| key | 类型 | TTL / 生命周期 | 谁写 | 重建/恢复路径 |
|---|---|---|---|---|
| `order:lock:{orderId}` | Redisson lock | `tryLock` 未传 leaseTime，使用看门狗续期；仓库未自定义，集成测试设 1500ms | `withOrderLock` | 解锁失败后依赖 Redisson 释放/过期语义；不能承诺立即释放 |
| `order:status:{orderId}` | String | 300 秒 | `tryGrab` 的 `SETNX`；`syncOrderStatus` 的 Lua；`refreshOrderStatus` 删除 | 缺失时 `tryGrab` 回源；陈旧值可能保留到 TTL 或被显式删除 |
| `driver:active:{driverId}` | String | 300 秒 | `tryGrab` 每次 `SET` 为 DB 查到的活跃订单或 `__IDLE__`；Lua 成功后写 orderId；匹配释放后删除 | 缺失/陈旧时下一次 `tryGrab` 按 DB 修复 |
| `delay:order:timeout` | ZSet | key 无 TTL；member 由 `schedule` 覆盖/`cancel` 删除 | `schedule` afterCommit | O4 数据库补偿扫描部分兜底 |
| 附近订单池相关 key | 版本/ready/GEO | 池与标记固定 2 小时；本节不展开 | `orderGeoPool.remove` afterCommit 失效整池 | 下次搜索按 DB 重建；O3 细化 |
| 司机在线心跳/位置 | Hash/GEO | 逻辑心跳窗口 60 秒；接单链路不给这两个 key 设置 Redis TTL | `refreshIfExist` 直接写 | 已在线成员刷新；不会把离线司机变成在线；O3 细化 |

### 5.5 GEO 与延迟队列在本章的调用点

- `orderGeoPool.remove(orderId)` 在接单成功后调用；当前事务同步活跃，所以登记 afterCommit 失效整池。它不是把订单从 GEO 池单条删除，而是让下次搜索按 DB 重建。
- `driverGeoIndex.refreshIfExist(driverId, lng, lat)` 只在请求带了经纬度时调用；它在提交前直接执行。`refresh` 模式要求司机已有心跳且仍在 GEO 池，否则清理并返回 false；不会因为接单把离线司机上线。
- `orderDelayQueue.schedule(orderId, Duration.ofMinutes(timeoutProperties.getArriveTimeoutMinutes()))` 在接单成功后调用；默认把"到达超时"待办改成 10 分钟。深入领取、补偿、超时迁移留 O4。

---

## 六、测试阅读：覆盖什么、没覆盖什么

### 6.1 `OrderGrabIntegrationTest`

门控：类上只有 `@EnabledIfSystemProperty(named = "test.mysql.port", matches = "\\d+")`；代码另外直接读 `test.redis.port`，但**没有为它加门控**——缺这个属性时不是“整类跳过”，而是 `@BeforeAll` 里 `Integer.parseInt(null)` 直接失败。

```java
// src/test/java/com/fancy/rideagenthub/OrderGrabIntegrationTest.java（真实节选）
@BeforeAll
static void setupServices() throws Exception {
    String serverUrl = "jdbc:mysql://127.0.0.1:" + System.getProperty("test.mysql.port") + "/?...";
    schema = "order_grab_regression_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE DATABASE " + schema);
    // 从 src/main/resources/sql/init.sql 匹配 sys_user/sys_ride_order 建表
    redisConfig.setLockWatchdogTimeout(1500L);
    grabs = transactional(new OrderGrabService(redis, orders, session.getMapper(UserAuthMapper.class)));
    rides = rideService(orders);
}
```

- 建独立随机 schema，只建 `sys_user/sys_ride_order`；结束时 drop。Redis 侧每轮删除固定测试 key（订单 100/101、司机 1/2），因此要求一次性专用实例。
- 使用真实 MySQL、Redis、Redisson 和真实 Mapper；mock 掉 AMAP、Mongo 路线、延迟队列、GEO 池、司机位置。
- 通过 `TransactionInterceptor` 代理服务，使 `@Transactional` 生效；没有 HTTP/token/`PermissionAspect`。
- 关键断言：
  - 冷缓存接单提交后，第二个订单被司机忙拒绝。
  - 同司机并发抢 100/101 只有一个成功；同单 100 两个司机只有一个成功。
  - 成功后 MySQL 有 1 条未结束订单；回滚后订单回到 10、占位被释放、状态缓存仍 10。
  - 数据库异常释放占位；陈旧 `__IDLE__` 不能绕过 DB 已有 assignment；陈旧 10 被 DB 拒绝并释放。
  - 迟到的释放不删除新订单；迟到的同步不会把 90 回写成 20。
  - 接单锁覆盖外层事务，提交后释放；到达请求等待接单事务提交；慢事务期间看门狗续期。

**注意**：这些测试没有由我们运行；它们只在显式提供隔离端口时执行。

### 6.2 `OrderGrabRegressionTest`

- 不需要外部端口；mock `RideOrderMapper`、`OrderGrabService` 等。
- 手工初始化 MyBatis-Plus 表信息和 Spring 事务同步。
- 验证：
  - 回滚接单释放其占位。
  - 状态同步和司机释放等待提交；回滚不发布。
  - 缓存同步失败不使已提交业务失败。
  - 数据库里已有 assignment 会替换陈旧 `__IDLE__`。
  - 订单缺失不占司机；DB 条件更新失败释放占位并刷新状态。
  - 取消/结束只释放对应订单。

这类测试验证的是 Java 编排和回调语义，不是真实 MySQL/Redis 并发。

### 6.3 `OrderLockRegressionTest`

- `RedissonClient/RLock` 被 mock（`OrderLockTestSupport.availableClient()`），所以验证的是调用序列，不是真实 Redisson 锁。
- 断言：
  - 接单在 afterCommit 后仍不解锁，afterCompletion 才解锁。
  - DB 更新抛异常时，先执行 `tryGrab` 的占位补偿，再解锁。
  - accept/arrive/start/finish/cancel/pay/timeout 都先拿 `order:lock`；拿不到 409，后续业务不执行。
  - 中断标志被恢复；解锁失败不替换业务异常。
- 真实看门狗续期由 `OrderGrabIntegrationTest` 的集成配置覆盖，仍未运行。

### 6.4 其他相关测试

- `OrderTimeoutRegressionTest`：mock Redis/队列，验证队列变更等待提交、回滚不发布、失败重入队、DB 补偿、到达超时只释放本单司机。O4 深讲。
- `OrderDelayQueueIntegrationTest`：需要独立 Redis；验证同一到期任务只被并发消费者领取一次、改期后不提前领取。O4 深讲。
- **未覆盖**：真实 HTTP/权限切面、`token → driverId`、真实生产 Redisson 看门狗默认值、真实反向代理/网络分区、多实例部署下的回调时序。

---

## 七、动手验证

### 练习 A：追踪同单多司机

前提：订单 100 是 10 且无司机；司机 1/2 有效；`order:status:100` 缺失；两个请求都到达。

请按 `RideOrderController#driverAccept` → `RideOrderServiceImpl#driverAcceptOrder` → `withOrderLock` → `OrderGrabService#tryGrab` → DB UPDATE → 提交回调，写出 T1/T2 各自可能看到的结果。

<details>
<summary>练习 A 参考答案</summary>

T1 若先拿订单锁：锁司机 1 行 → 查无未结束订单 → `SET driver:active:1=__IDLE__` → 状态缓存缺失时回源并 `SETNX=10` → Lua 先比较状态字符串，再比较司机空闲；两项通过后返回 1 并写 `driver:active:1=100` → DB UPDATE 1 行 → 提交 → afterCommit 状态 20/GEO 失效/到达超时 → afterCompletion 解锁。

T2 的结果需要三个前提：请求体合法、Redis/MySQL 可用、在 3 秒内拿到 T1 释放的订单锁。T1 释放订单锁与 T2 成功取得锁是两个时点；若 T2 等 3 秒仍未拿到，则在 `withOrderLock` 处 409，不会进入 `tryGrab`。

T2 拿到锁后：锁司机 2 行 → 查无未结束订单 → `SET driver:active:2=__IDLE__`。注意，这步司机数据库查询已经发生；状态缓存旧值只是让 `tryGrab` 不回源订单。随后有互斥分支：

- 分支 1：T1 的 afterCommit 已把 `order:status:100` 同步为 20。T2 的 Lua 第一项字符串比较失败，返回 -1，HTTP 409"订单已被其他司机接走或已取消"。
- 分支 2：状态同步失败/尚未执行或旧 `10` 仍在缓存内。T2 的 Lua 两项通过，Redis 端写入 `driver:active:2=100` 并返回 1；随后 `UPDATE WHERE status=10` 影响 0 行。代码立即 best-effort `rollbackGrab(2,100)`，`refreshOrderStatus` 登记 afterCommit 删除缓存；删除和补偿都可能失败。接口返回 `Result.ok(false)`。

若缓存过期/缺失，T2 回源会看到已提交的 20，通常走分支 1。可见性上，T1 提交前只有 T1 自己的当前读能看到未提交修改；T2 的普通读要等 T1 提交后才看到 20。订单最终 20；赢家是 T1。
</details>

### 练习 B：追踪同司机多单

前提：司机 1 没有未结束订单；订单 100/101 都是 10；同一司机同时请求两个订单。

请解释为什么两个订单锁不能解决问题，最终由哪一层拒绝；再说明若 T1 回滚，T2 是否一定能成功。

<details>
<summary>练习 B 参考答案</summary>

`order:lock:100` 和 `order:lock:101` 是不同 key，所以两个事务都能进入各自订单的临界区。`tryGrab` 第一步对 `sys_user.user_id=1` 的司机账号行 `FOR UPDATE`，同一司机两个事务在这里串行。T1 查到没有未结束订单，Lua 成功，DB 更新 100 并提交后；T2 获得司机行锁，当前读会看到 100 已经是 20，把 `driver:active:1` 修成 100。接着 Lua 对 101 检查：状态可以是 10，但司机占位不是 `__IDLE__`，返回 -2，抛 409"司机已有未结束订单"。

若 T1 在 Lua 成功后 DB 抛异常回滚：afterCompletion 因状态不是 COMMITTED 尝试 `rollbackGrab(1,100)`；仅当 Redis 操作成功且占位仍是 100 时才会删除。T2 后续若成功完成查询和写入，读不到未结束订单时会把 `driver:active:1` 修成 `__IDLE__`，随后可能成功抢 101。能否成功还取决于 Redis/DB、订单 101 的状态及其他竞争者；补偿失败也不能断言占位已清除。
</details>

### 练习 C：六个失败分支判断

判断下列说法"正确 / 错误 / 取决于条件"，并说明条件。

1. `GRAB_ORDER_ATOMIC` 返回 1，说明该司机已经接到订单。
2. 订单状态缓存缺失时，`tryGrab` 会直接返回订单不存在。
3. 司机占位是 `__IDLE__`，即使数据库里其实有未结束订单，Lua 也会成功。
4. DB 条件更新影响 0 行时，接口一定返回 409。
5. `rollbackGrab` 会删除司机的任何 `driver:active` 占位。
6. 状态缓存 20 时调用 `syncOrderStatus(10)` 会把缓存改回 10。

<details>
<summary>练习 C 参考答案</summary>

1. 错误。返回 1 只是 Lua 预检成功并写入司机占位；随后 DB 条件更新可能 0 行或异常。
2. 取决于缓存。只有缓存缺失时才回源；订单不存在返回 `ORDER_NOT_FOUND`，HTTP 409。若缓存已有旧值，不会回源该订单；但司机数据库查询和占位修复在此之前已发生。
3. 取决于状态与司机两项检查。正常调用链中 `tryGrab` 会先用未结束订单查询结果修复 `driver:active`；Lua 还必须先比较订单状态字符串等于 `"10"`。若状态非 `"10"`，即使司机空闲也返回 -1。若绕过 `tryGrab` 直接执行 Lua，或该查询与 Lua 之间发生极端变化，才可能不一致。
4. 错误。0 行时方法返回 false，Controller 返回 `Result.ok(false)`，HTTP 200；Lua 拒绝才是 409。
5. 错误。`RELEASE_LOCK_IF_MATCH` 只删除值等于传入 orderId 的占位；`__IDLE__`、其他订单或缺失都不会删除。
6. 错误。`SYNC_ORDER_STATUS` 只在当前缺失、非数字或 <= 新值时写；20 > 10，返回 0，不回写。
</details>

> 可选实验边界：`OrderGrabIntegrationTest` 需要 `-Dtest.mysql.port` 与 `-Dtest.redis.port`，并且**必须指向一次性、专用的 MySQL 实例和 Redis 实例**。MySQL 侧需要建库/删库权限：`@BeforeAll` 创建随机 schema，`@AfterAll` 删除该 schema。Redis 侧不是“随机数据即安全”：`@BeforeEach/@AfterEach#clearCache` 会删除固定 key——`order:status:100`、`order:status:101`、`driver:active:1`、`driver:active:2`。若这些 key 可能属于他人数据，或无法接受上述建删库与删 key 影响，就不要运行，只做本章静态阅读。本章没有运行任何实验；不要对业务库执行清空、批量更新或故障注入。

---

## 八、一页复习

- **同单**：`order:lock:{id}` 串行；DB `WHERE status=10 AND driver_id IS NULL` 裁决。T2 可能 409（Lua）或 200+false（DB 0 行）。
- **同司机**：`sys_user.user_id` 行 `FOR UPDATE` + 未结束订单当前读 + `driver:active` 占位；不同订单的 DB 条件更新不能保证一人一单。
- **顺序**：事务代理 → 订单锁 → 司机行锁/未结束订单查询 → 修复司机占位 → 状态缓存缺失才回源 → Lua → DB 条件更新 → 登记 afterCommit → 可选位置刷新 → 登记 ZSet → 方法返回 → 提交 → afterCommit → afterCompletion。
- **Redis 不是裁决者**：Lua 返回 1 后仍可能 DB 0 行；DB 0 行返回 false，不是 409。
- **状态缓存**：缺失才回源该订单（司机 DB 查询此前已发生）；`GRAB_ORDER_ATOMIC` 先做状态字符串比较，再做司机空闲比较；旧 10 可让 Lua 放行但 DB 拒绝，旧 20 会误拒直到 TTL/刷新；`tonumber` 只属于 `SYNC_ORDER_STATUS`，后者单调不回写。
- **司机占位**：每次 `tryGrab` 用 DB 当前读修复；Lua 只在 `__IDLE__` 时写当前订单；释放必须匹配 orderId。
- **回调**：`afterCommit` 失败不回滚 DB；`afterCompletion` 补偿也是 best-effort；提交未知时可能释放已提交占位，下一次按 DB 修复。
- **锁释放**：接单锁在 afterCompletion 释放；释放失败只记日志，不能保证立即可用。

---

## 九、自测与答案

**1. 为什么 Lua 返回 1 还不够？**

<details>
<summary>参考答案</summary>

Lua 只在 Redis 内原子检查"订单状态缓存=10、司机占位=__IDLE__"并写占位。它不查 MySQL 最新状态，也不提交事务。若缓存是旧 10，Lua 仍可能成功；随后 `UPDATE ... WHERE order_status=10` 在 DB 当前读下影响 0 行。此时代码立即 `rollbackGrab`，`refreshOrderStatus` 登记 afterCommit 删除缓存，接口返回 `Result.ok(false)`。所以 DB 条件更新才是订单归属的最终裁决。
</details>

**2. 同司机抢两单，为什么订单锁不够？**

<details>
<summary>参考答案</summary>

订单 100 和 101 的锁 key 不同，两个事务可以同时进入。要让"一人一单"成立，必须在司机维度串行。`tryGrab` 对 `sys_user.user_id` 行 `FOR UPDATE`，使第二个事务等待；随后未结束订单查询是当前读，能看到第一个事务已提交的 20。占位 `driver:active:1` 会被修成 100。若 T2 执行 Lua 时订单 101 的状态缓存是 `"10"`，Lua 返回 -2，HTTP 409；若该缓存已不是 `"10"`，则第一项状态检查先返回 -1。
</details>

**3. 状态缓存是 10，但 DB 已是 20，会发生什么？状态缓存是 20，但 DB 仍是 10 呢？**

<details>
<summary>参考答案</summary>

前提是司机占位已被 `tryGrab` 前半段修复，Lua 逐项看。缓存 10、DB 20：若司机占位是 `__IDLE__`，`GRAB_ORDER_ATOMIC` 返回 1，但条件更新 0 行；返回 200+false，`rollbackGrab` 尽力释放占位，`refreshOrderStatus` afterCommit 删除旧缓存，删除可能失败。缓存 20、DB 10：Lua 第一项字符串比较失败，返回 -1，HTTP 409；本次不会回源该订单修复，旧缓存只能等 TTL、后续状态变更同步或显式删除。若缓存 20 且司机也忙，仍是先返回 -1。
</details>

**4. Redis Lua 执行返回 null，会怎样？**

<details>
<summary>参考答案</summary>

`tryGrab` 抛 `IllegalStateException`。事务回滚，DB 条件更新不会执行；因为 Java 没拿到 `1`，也没有注册"本次占位回滚"回调。但客户端收到 null 不能证明 Redis 端 Lua 一定没执行或没写入：调用/响应可能丢失，Lua 可能已在 Redis 端执行到 `SET 当前订单占位` 并残留，也可能未执行。此前 `driver:active` 还可能已按 DB 查询结果写成 `__IDLE__` 或已有活跃订单。afterCompletion 只会释放订单锁。TTL 和下一次 `tryGrab` 按 DB 修复只是部分兜底；是否残留必须实际查证，本章未做故障注入。
</details>

**5. DB 条件更新抛异常后，Redis 占位一定会被释放吗？**

<details>
<summary>参考答案</summary>

不一定。Lua 成功时注册了 afterCompletion：状态不是 COMMITTED 会调用 `rollbackGrab`。但 `rollbackGrab` 是 best-effort：Lua 只在占位等于本订单时删除，Redis 异常会被 catch 后只记 warn；进程在回调前退出则回调不执行。此时靠 300 秒 TTL 和下一次 `tryGrab` 按 DB 修复部分恢复，不能承诺立即一致。
</details>

**6. 为什么 `SYNC_ORDER_STATUS` 要比较大小？**

<details>
<summary>参考答案</summary>

接单、取消、结束等路径都可能在事务提交后同步状态。如果网络或线程调度导致旧回调晚到，不比较大小就会把已推进的 20/30 覆盖回 10。`SYNC_ORDER_STATUS` 只有当前缺失、非数字或 <= 新值才写，因此旧状态不会回写；需要真正回退（如到达超时 20→10）时用 `refreshOrderStatus` 删除缓存，下一次回源。
</details>

**7. `orderDelayQueue.schedule` 的异常都被捕获了吗？**

<details>
<summary>参考答案</summary>

不是。`schedule` 先计算 `deadline = Math.addExact(now, delay)`，再调用受保护的 afterCommit 回调；`Math.addExact` 溢出等计算异常发生在 guarded callback 之前，不会被"ZSet 写入失败 catch"覆盖。ZSet 写入异常才会被捕获并记日志，数据库补偿扫描只能部分兜底。
</details>

**8. `releaseDriver(1, 100)` 会误删司机 1 接到的新订单占位吗？**

<details>
<summary>参考答案</summary>

不会。它最终执行 `RELEASE_LOCK_IF_MATCH`，只有 `GET driver:active:1 == 100` 才删除。如果司机随后已接到 101，占位是 101，Lua 返回 0。Java 侧不检查返回值，也不抛异常。
</details>

**9. 订单锁释放失败会怎样？**

<details>
<summary>参考答案</summary>

`releaseOrderLock` catch RuntimeException 后只记 warn，不覆盖业务结果，也不重试。后续同单请求可能继续被锁挡住；Redisson 的看门狗/过期/进程退出语义决定锁最终何时消失。源码只能证明有这个捕获，不能承诺一定立即释放。
</details>

**10. 集成测试能证明生产并发安全吗？**

<details>
<summary>参考答案</summary>

不能直接等价。`OrderGrabIntegrationTest` 在显式给定隔离端口时使用真实 MySQL/Redis/Redisson，验证了两类并发、回滚、缓存陈旧、看门狗等；但它 mock 了 HTTP/权限切面、地图/Mongo/GEO/延迟队列，且只建两张表。生产还有部署、连接池、网络分区、多实例和真实 token/角色链路。本章未运行测试，只能把测试断言作为"设计目标/回归证据"，不能当作实测通过。
</details>

---

## 十、导航与源码入口

- **上一章**：[第 7 章 订单生命周期](01-order-lifecycle.md)——`10 → 20` 的状态迁移、事务/回调总表和取消/支付边界。
- **下一章**：[第 9 章 位置与发现](03-location-and-discovery.md)——司机在线、附近订单 GEO 池与用户位置。
- **相关专题**：
  - 延迟队列、超时迁移与数据库补偿 → [第 10 章 超时与恢复](04-timeouts-and-recovery.md)
  - 锁令牌、租约与版本租约横向对比 → [第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| HTTP 入口与身份 | `controller/RideOrderController.java#driverAccept`（内部类 `DriverAcceptReqDTO`）；`annotation/RequirePermission.java`；`aspect/PermissionAspect.java`；`security/UserTokenContext.java` |
| 事务与订单锁 | `service/impl/RideOrderServiceImpl.java#driverAcceptOrder/#withOrderLock/#releaseOrderLock`；常量 `ORDER_LOCK_WAIT_SECONDS=3` |
| 抢单预检 | `service/base/OrderGrabService.java#tryGrab/#normalizeId`；`Propagation.MANDATORY`；`domain/entity/UserAuth.java`；`mapper/UserAuthMapper.java`（BaseMapper 查询） |
| 条件更新 | `RideOrderServiceImpl#driverAcceptOrder` 的 `LambdaUpdateWrapper`；`src/main/resources/sql/init.sql#sys_ride_order` |
| Lua 与 key | `util/RedisScripts.java#GRAB_ORDER_ATOMIC/#SYNC_ORDER_STATUS/#RELEASE_LOCK_IF_MATCH`；`constant/RedisKeyConstants.java#orderStatusKey/#driverActiveKey/#orderLockKey/#DELAY_ORDER_TIMEOUT_KEY` |
| GEO/位置调用点 | `service/base/OrderGeoPool.java#remove/#invalidate`；`util/RedisScripts.java#INVALIDATE_ORDER_GEO_POOL/#UPDATE_DRIVER_LOCATION`；`service/base/DriverGeoIndex.java#refreshIfExist/#update` |
| 延迟队列调用点 | `service/base/OrderDelayQueue.java#schedule/#afterCommit`；`config/RideOrderTimeoutProperties.java`；`src/main/resources/application.yaml#ride-order.timeout.arrive-timeout-minutes` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/OrderGrabIntegrationTest.java`、`OrderGrabRegressionTest.java`、`OrderLockRegressionTest.java`、`OrderLockTestSupport.java`、`OrderDelayQueueIntegrationTest.java` |
