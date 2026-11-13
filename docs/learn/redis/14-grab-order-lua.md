# 司机抢单：Lua 原子预检（秒杀模型）

> **Redis 考点**：用 Lua 脚本把「多次读-判-写」合并成一次原子执行，实现秒杀式的资格校验 + 扣减。
> **来源**：`docs/01-redis-application-points.md` P0-1
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/OrderGrabService.java`
> **代码版本**：当前工作区为 `RideAgentHub` / `com.fancy.rideagenthub`（基准路径 `src/main/java/com/fancy/rideagenthub/`）。本系列撰写于历史快照 `bdc40d7`（旧名 `TaxiAgent`、旧包 `com.fancy.taxiagent`，属已被重写合并的旧分支），文中的 `文件:行号` 与部分代码块是**改造前快照**，整体已漂移；学习时以「类名 + 方法名 + 章节名」定位当前源码，具体行为一律以当前代码为准。

---

## 【当前实现对照】

> **审计阅读约定（2026-11-13 代码反向复核）**：本篇二～八章正文与七章 `redis-cli` 命令写于改造前快照，**作为缺陷推导教材保留**；凡与本节冲突，一律以本节与当前源码为准。

| # | 正文中的旧说法 | 当前代码事实（`service/base/OrderGrabService.java`） |
|---|---|---|
| 1 | 4.3「`rollbackGrab` 读-写非原子，属已知残留窗口」、4.4「`releaseDriver(String driverId)` 写 `__IDLE__`」、六章「`warmUpDriverActive` 窗口 / 状态缓存 2 小时 / 占位 6 小时」 | 当前统一 `CACHE_TTL=5min`、只有 `__IDLE__`（`:29-30`）；回滚经 `afterCompletion → rollbackGrab`（`:87-94,112-119`）并用 `RELEASE_LOCK_IF_MATCH` 原子 CAS（`:115`）；`releaseDriver` 为**双参** `(driverId, orderId)`（`:122-126`）；预热已并入 `tryGrab` 事务 |
| 2 | 七章 `SET order:status:{orderId} 10` 未带 `EX` | 应为 `SET order:status:123 10 EX 300`；`driver:active:{driverId}` 期望 `__IDLE__` 且 TTL ≈300 |
| 3 | 延伸阅读「项目全部 **7 个** Lua 脚本」 | 当前 **22 个主脚本 + 11 个内联 = 33 段**，见 [10 篇 3.1](10-lua-scripts-overview.md) |

### 【补充】当前抢单链路（本篇二～八章未覆盖）

`@Transactional(MANDATORY)` 要求调用方已有事务；**两道行锁**串行化同一司机与同一订单：司机账号 `SELECT ... FOR UPDATE`（`:45-50`）、在途订单 `LIMIT 1 FOR UPDATE`（`:56-62`）。随后按 DB 权威值回填 `driver:active` 与 `order:status`（`:63-75`），再执行 `GRAB_ORDER_ATOMIC`（`RedisScripts.java:238-247`）：`statusKey != '10'` → `-1`；`driverActiveKey != '__IDLE__'` → `-2`；否则 `SET driverActiveKey = orderId EX ARGV[4]` → `1`。

`GrabResult`（`:36-38`）有**四态**：`SUCCESS`（1）、`DRIVER_BUSY`（-2）、`ORDER_NOT_GRABBABLE`（其它返回值）、`ORDER_NOT_FOUND`（回表查无单，`:71-73`，**不执行 Lua**）。提交后由 `afterCommit` 用 `SYNC_ORDER_STATUS` 单调推进状态（`:101-105`；`RedisScripts.java:250-257`，数值回退时先删缓存）；事务回滚由 `afterCompletion` 触发 `rollbackGrab`。真正的 DB 条件更新（`orderStatus=10 && driverId is null → 20`）在 `RideOrderServiceImpl.java:275-284`，**不在本类**。

这套设计的边界：Redis 占位只是**预检与快速失败**，正确性最终由 DB 行锁 + 条件更新保证；5 分钟 TTL 只用于「释放路径因进程崩溃未执行」时防止司机被永久锁为忙。

> 秒杀模型「判断与占位一步完成」、返回码设计、DB 条件更新兜底的推导**全部保留**；以下实现细节已重写。

| 项 | 当前实现 |
|---|---|
| TTL | `OrderGrabService.CACHE_TTL` 统一 **5 分钟（300s）**；`__MISSING__`、2h / 6h 分档已删除，`__IDLE__` 保留 |
| 事务与预热 | 预热合并进 `tryGrab`，标注 `@Transactional(propagation = MANDATORY)`；事务内两处 `FOR UPDATE`（**司机账号行锁**、**在途订单行锁**）→ 写 5 分钟状态 → `GRAB_ORDER_ATOMIC` Lua 预检占位 → DB 条件更新裁决。旧 `warmUpDriverActive` 独立方法已删除 |
| 回滚 | `releaseDriver(driverId, orderId)` 签名含 `orderId`；回滚经 `afterCompletion` 走 `RELEASE_LOCK_IF_MATCH` 校验令牌，不再「读写非原子」✅ 已修复 |
| 状态同步 | 新增 `SYNC_ORDER_STATUS` Lua：**单调推进**，只允许新状态 ≥ 当前状态（相等刷新 TTL）；回池到 `CREATED` 这类数值回退必须先由 `refreshOrderStatus` 删缓存 key，否则脚本拒绝覆盖 |
| 统一订单锁 | 7 个状态变更（接单 / 到达 / 开始 / 结束 / 支付 / 取消 / 超时）共用 `withOrderLock`（Redisson 看门狗），锁竞争 409 时**不触碰** Mapper / 延迟队列 / 缓存 |

**验证修订**：`SET order:status:123 10 EX 300`（不是 7200/21600）；`OrderGrabIntegrationTest` 覆盖两处行锁与回滚，`OrderLockRegressionTest` 覆盖锁竞争不触碰业务。详见 [`13-distributed-lock.md`](13-distributed-lock.md)、[`10-lua-scripts-overview.md`](10-lua-scripts-overview.md)。

---

## 一、业务场景

司机点击「接单」，系统必须同时满足两个条件才允许：

1. **订单可抢** —— 订单处于「待接单」状态；
2. **司机空闲** —— 该司机没有其他进行中的订单（业务上的「一人一单」）。

改造前这段逻辑完全依赖 DB（`RideOrderServiceImpl.driverAcceptOrder`）：

```java
// ① 每个司机抢单都要 selectCount 一次「我有没有未结束订单」
Long activeCount = rideOrderMapper.selectCount(new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getDriverId, driverIdLong)
        .notIn(RideOrder::getOrderStatus, List.of(...)));
if (activeCount != null && activeCount > 0) {
    throw new BusinessException(409, "司机已有未结束订单，无法接单");
}

// ② 再用乐观锁更新订单
int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, orderId)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
        .isNull(RideOrder::getDriverId)
        .set(RideOrder::getDriverId, driverIdLong)
        ...);
```

三个问题：

| # | 问题 | 后果 |
|---|---|---|
| 1 | `activeCount` 是**热点行查询** | 抢单高峰时所有司机的请求压在同一索引上 |
| 2 | ① 与 ② 是**两次独立 DB 往返** | 两次之间存在窗口，同一司机的两个并发请求可能双双通过预检 |
| 3 | 一人一单校验与订单可抢校验**不在同一临界区** | 同一司机理论上可以同时接下一个以上的订单 |

第 2、3 条是**正确性**问题，第 1 条是**性能**问题。Redis 在这里同时解决两者。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `order:status:{orderId}` | String | 2 小时（哨兵 60 秒） | 订单当前状态码，`__MISSING__` 表示订单不存在 |
| `driver:active:{driverId}` | String | 6 小时 | 司机当前进行中的 orderId，`__IDLE__` 表示空闲 |

TTL 常量定义在 `OrderGrabService.java:63`（`ORDER_STATUS_TTL_SECONDS`）、`:71`（`MISSING_SENTINEL_TTL_SECONDS`）、`:80`（`DRIVER_ACTIVE_TTL_SECONDS`）。

**两个哨兵值是本方案的关键设计**（`:48`、`:55`）：

```java
static final String IDLE_SENTINEL = "__IDLE__";       // 司机空闲
static final String MISSING_SENTINEL = "__MISSING__"; // 订单不存在
```

它们让「key 不存在」只剩下**一种**含义 —— *本进程此前没关心过它*，需要回源 DB。否则无法区分：

- 「司机确实空闲」 vs 「key 被淘汰 / Redis 重启导致状态丢失」

后者会让一个正在跑单的司机被误判为空闲。这沿用了 `CityCodeUtil` 中 `__NULL__` 空值哨兵的思路（见 [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md)）。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `OrderGrabService.java:115-160` | `tryGrab` | 抢单原子预检主流程 |
| `OrderGrabService.java:175-181` | `rollbackGrab` | 预检通过但落库失败时，回滚司机占位 |
| `OrderGrabService.java:191-200` | `releaseDriver` | 行程结束 / 订单取消时释放司机 |
| `OrderGrabService.java:211-220` | `syncOrderStatus` | 订单每次流转时写透状态缓存 |
| `OrderGrabService.java:230-244` | `refreshOrderStatus` | 预检与 DB 不一致时，用 DB 权威值覆盖缓存 |
| `OrderGrabService.java:252-275` | `warmUpDriverActive` | 司机占位缺失时回源 DB |
| `RedisScripts.java:144-155` | `GRAB_ORDER_ATOMIC` | Lua 脚本本体 |
| `RideOrderServiceImpl.java:327-368` | `driverAcceptOrder` | 调用方：预检 → 乐观锁落库 → 回滚/同步 |

---

## 四、实现拆解

### 4.1 Lua 脚本：判断与占位合并成一步

`RedisScripts.java:144-155`：

```lua
local status = redis.call('GET', KEYS[1])
if status == false or status ~= ARGV[1] then
    return -1                                  -- 订单不可抢
end
local active = redis.call('GET', KEYS[2])
if active == false or active ~= ARGV[2] then
    return -2                                  -- 司机已是忙 / 状态未预热
end
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])   -- 占住司机位
return 1
```

`KEYS[1] = order:status:{orderId}`、`KEYS[2] = driver:active:{driverId}`、`ARGV[2] = "__IDLE__"`、`ARGV[3] = orderId`。

两点值得注意：

1. **`active ~= ARGV[2]` 而非 `active == false`** —— 只有「明确等于空闲哨兵」才放行。这样 key 缺失（状态未知）会走 `-2` 拒绝，而不是被当成空闲放过去。
2. **判断通过后立刻 `SET` 占位** —— 「检查空闲」与「占住司机」必须是同一步。否则两个并发请求仍可同时通过检查、再先后占位，照样一人两单。

### 4.2 调用方：三步走，Redis 只是预检

`RideOrderServiceImpl.java:327-368`：

```java
return withOrderLock(orderId, () -> {
    // 阶段一：Redis 原子预检
    OrderGrabService.GrabResult grabResult = orderGrabService.tryGrab(orderId, driverId);
    if (grabResult != OrderGrabService.GrabResult.SUCCESS) {
        throw new BusinessException(409, grabFailureMessage(grabResult));
    }

    // 阶段二：DB 乐观锁落库 —— 预检只是加速，DB 才是最终裁决者
    int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
            .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
            .isNull(RideOrder::getDriverId)
            ...);

    if (updated <= 0) {
        // 预检通过但落库失败：缓存态与 DB 不一致，把占位还回去
        orderGrabService.rollbackGrab(driverId, orderId);
        orderGrabService.refreshOrderStatus(orderId);
        return false;
    }
    orderGrabService.syncOrderStatus(orderId, RideOrderStatus.DRIVER_ACCEPTED.getCode());
    ...
});
```

### 4.3 回滚为什么必须带上 `expectedOrderId`

`OrderGrabService.java:175-181`：

```java
public void rollbackGrab(String driverId, String expectedOrderId) {
    String key = RedisKeyConstants.driverActiveKey(driverId);
    String current = redisTemplate.opsForValue().get(key);
    if (expectedOrderId != null && expectedOrderId.equals(current)) {
        redisTemplate.opsForValue().set(key, IDLE_SENTINEL, DRIVER_ACTIVE_TTL_SECONDS, TimeUnit.SECONDS);
    }
}
```

先读回当前值、确认「确实是我这次抢单写入的占位」才释放。**如果没有这个二次校验**，会出现：T1 抢 A 单失败正在回滚，T2 已经让同一司机抢到 B 单 —— T1 的回滚会把 B 单的占位抹掉，司机又被判为空闲。

注意这里还有一个非原子窗口（读 `current` → 写 `IDLE_SENTINEL` 两条命令），理论上极端并发下仍可能误释放。要完全消除需再写一个 Lua 做 CAS。当前属于已知的、代价极低的残留窗口。

### 4.4 释放司机：写哨兵而不是 `DEL`

`OrderGrabService.java:191-200`：

```java
public void releaseDriver(String driverId) {
    ...
    redisTemplate.opsForValue().set(
            RedisKeyConstants.driverActiveKey(driverId),
            IDLE_SENTINEL, DRIVER_ACTIVE_TTL_SECONDS, TimeUnit.SECONDS);
}
```

用 `SET __IDLE__` 而非 `DEL`：删除会让 key 退回「未知」状态，下一次抢单需要多一次 DB 回源才能确认司机空闲。写哨兵则保持「已知的空」，正常路径零 DB 查询。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Redis 只做**预检**，DB 乐观锁仍是唯一裁决 | 让 Redis 直接决定抢单结果 | 缓存「绝不成为正确性依赖」（`OrderGrabService` 类注释）。Redis 丢失/不一致时，结果是「退化成原来的 DB 路径」，而不是「抢单结果错了」 |
| `SET ... EX` 写在 Lua 内 | 脚本外再 `EXPIRE` | 与 `ChatManager` 的 `HSET`+`EXPIRE` 是同一类问题：两步之间崩溃会留下永不过期的占位 key（见 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)） |
| 占位 key 带 6 小时 TTL | 显式释放就够，不要 TTL | 释放路径若因异常未执行（进程崩溃、DB 异常），司机将被永久锁在「忙」。TTL 是兜底，不是主路径 |
| `order:status` 用 String 而非 Hash | 一个 Hash 存所有订单状态 | 单订单粒度可独立设 TTL、独立淘汰，避免大 key |

**为什么不用 BitMap 或 Set 表示「司机忙」**：需要存的是「占住该司机的是哪个订单」，回滚时要校验 orderId，Set/BitMap 存不下这个信息。

---

## 六、边界与已知问题

1. **`rollbackGrab` 的读-写不是原子的**（`OrderGrabService.java:177-180`），极端并发下可能误释放他人的占位，见 4.3。
2. **`warmUpDriverActive` 与 Lua 之间存在窗口**（`:137-139`）—— key 缺失时才回源，回源结果写入后 Lua 才执行。若两次抢单同时命中「key 缺失」，会各自回源一次（多一次 DB 查询，不影响正确性）。
3. **订单状态缓存的 TTL 是 2 小时**（`:63`），正常路径靠每次流转 `syncOrderStatus` 写透，不依赖过期。
4. **`driver:active` 的判定口径**（`:280-285`）与原先的 `selectCount` 保持一致：待支付/已支付/已取消三种终态不再占用司机。

> 曾经的 `driverAcceptOrder` 签名里收了 `currentLat` / `currentLng` 却从不使用 —— 这对参数现在在 P0-2 的 GEO 改造中派上了用场（`RideOrderServiceImpl.java:361-363`），见 [`15-nearby-order-geo.md`](15-nearby-order-geo.md)。

---

## 七、如何验证

```bash
# 1. 观察抢单预检写入的两个 key
redis-cli GET order:status:{orderId}      # 期望 "10"（待接单）
redis-cli GET driver:active:{driverId}    # 期望 "__IDLE__"

# 2. 同一司机并发抢两个订单，只有一个应该成功
#    成功后：driver:active:{driverId} 变为该订单号

# 3. 手工制造「预检通过但落库失败」
redis-cli SET order:status:{orderId} 10        # 让预检认为可抢
# 同时把 DB 中该单状态改掉 → 乐观锁 updated=0
# 期望：日志出现回滚，driver:active 恢复为 "__IDLE__"，order:status 被刷新为 DB 真实值
```

---

## 八、延伸阅读

- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目全部 7 个 Lua 脚本的原子性总表
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 同一路径上并存的 Redisson 订单锁，与本文的 Lua 各管一段
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 抢单成功后会改写延迟队列的待办
- Redis 官方文档：[Scripting with Lua](https://redis.io/docs/manual/programming/lua/)、[EVAL 的原子性保证](https://redis.io/commands/eval/)
