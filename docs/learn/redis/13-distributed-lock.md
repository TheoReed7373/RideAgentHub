# 分布式锁：手写 RedisLock vs Redisson 看门狗

> **Redis 考点**：分布式锁的三类经典事故——误删他人的锁、锁过期而临界区未完成、事务中过早解锁；项目用「手写令牌锁 + Redisson 看门狗」双轨分工规避。
> **来源**：`docs/01-redis-application-points.md` §2.4（原子操作与分布式锁）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/RedisLock.java`、`src/main/java/com/fancy/rideagenthub/service/impl/RideOrderServiceImpl.java`（`withOrderLock`）

---

## 一、业务场景

先看手写锁最容易出的三类事故，再看项目为什么需要**两种**锁：

**事故 1：误删他人的锁。**

```
T1 加锁（key=X，无值校验）→ 临界区执行超过锁 TTL → 锁自动过期
T2 加锁（key=X，成功）
T1 执行完毕，finally 里无条件 DEL X            ← 删掉的是 T2 的锁！
T3 加锁（key=X，成功）→ T2 与 T3 同时进入临界区
```

**事故 2：锁过期而临界区未完成。** 固定 TTL 是猜的：猜短了（事故 1 的前置条件），临界区还在跑锁已经没了；猜长了，持锁实例崩溃后其他实例要干等整个 TTL。

**事故 3：事务中过早解锁。** `@Transactional` 方法里 `finally` 解锁：锁释放了，事务还没提交——下一个拿到锁的请求读到的是**旧数据**（未提交），互斥形同虚设。

项目里的两类加锁场景对这三类事故的敏感度不同，因此双轨：

| 场景 | 特征 | 用锁 |
|---|---|---|
| 订单状态流转（接单 / 到达 / 开始行程 / 结束 / 支付 / 取消 / 超时） | 临界区含 DB 事务与外部调用，时长不可预估 | Redisson `RLock` + 看门狗 |
| 缓存重建（统计 / GEO 池 / 工单池） | 临界区是「查 DB + 写缓存」，时长可预估（秒级） | 手写 `RedisLock`（固定 TTL + 令牌校验） |

---

## 二、Redis 结构选型

| 锁 | Key | 值 | TTL | 释放 |
|---|---|---|---|---|
| 手写 `RedisLock` | 业务定义（如 `ticket:statistics:lock:{date}`、GEO 池 / 工单池的 rebuild lock） | **UUID 令牌**（持有者标识） | 调用方传入（5~10 秒） | `RELEASE_LOCK_IF_MATCH` Lua |
| Redisson 订单锁 | `RedisKeyConstants.orderLockKey(orderId)` | Redisson 内部格式（含 owner） | **看门狗**（默认 30 秒租约，自动续期） | `lock.unlock()` / `unlockAsync(owner)` |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `RedisLock.tryLock(key, ttl)` | 参数校验（空 key / 非正 TTL 抛 `IllegalArgumentException`）→ `setIfAbsent(key, UUID, ttl)` → 成功返回令牌，失败返回 null |
| `RedisLock.unlock(key, token)` | `RELEASE_LOCK_IF_MATCH` Lua：值等于令牌才 `DEL`；异常捕获只 warn |
| `RideOrderServiceImpl.withOrderLock(orderId, action)` | Redisson `tryLock(3, TimeUnit.SECONDS)`（**两参数 = 无 leaseTime = 启用看门狗**）→ 执行 → 事务活跃时挂 `afterCompletion` 解锁 |
| 订单的 7 个状态变更 | 全部包在 `withOrderLock` 内；锁竞争直接抛 409「订单正在处理中」，**不触碰** Mapper / 队列 / 缓存 |
| `OrderGrabService.rollbackGrab` / `releaseDriver` | 手写锁语义复用：`RELEASE_LOCK_IF_MATCH` 校验占位值等于本订单才释放 |

---

## 四、实现拆解

### 4.1 手写锁：令牌 + 原子释放

```java
// tryLock：setIfAbsent 天然原子（SET NX EX 一条命令）
String token = UUID.randomUUID().toString();
return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl)) ? token : null;

// unlock：RELEASE_LOCK_IF_MATCH Lua
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
```

值是**本次持有者的令牌**：释放时校验「当前锁的值 == 我的令牌」——锁已易主（过期被别人抢走）时校验失败，**不会误删他人的锁**（堵住事故 1）。校验与删除在 Lua 内原子，不存在「校验通过、删除前易主」的窗口。

`unlock` 的异常处理是刻意的取舍：

> 不覆盖临界区的返回值或异常，剩余锁由有效期兜底释放。

释放失败（Redis 抖动）只 `log.warn`——业务结果优先；残留锁最多活一个 TTL（5~10 秒），期间其他实例抢不到锁走降级路径（直查 DB / 整页回退）。这是**短临界区 + 固定 TTL** 模式的完整闭环。

### 4.2 Redisson：看门狗续期

```java
RLock lock = redissonClient.getLock(RedisKeyConstants.orderLockKey(orderId));
acquired = lock.tryLock(ORDER_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);   // 两参数版本
```

**`tryLock(waitTime)` 不传 `leaseTime`** → 启用看门狗：锁的租约默认 30 秒，Redisson 后台每 10 秒（租约的 1/3）自动续期，由客户端续期任务维持租约；正常或异常完成需显式 unlock，业务线程退出本身不保证停止续期。客户端进程退出或续期持续失败后，锁在最后一次续期的剩余 TTL 内过期。

这同时化解事故 1 与事故 2 的根源：**锁的寿命与临界区的寿命绑定，而不是与一个猜出来的 TTL 绑定**——在续期正常的前提下可覆盖较长临界区；长时间 GC、网络分区或 Redis 故障仍可能导致租约失效；持锁进程崩溃后锁自动过期，无需人工清理。

测试实证：`OrderGrabIntegrationTest` 里 `setLockWatchdogTimeout(1500)` 后 sleep 2200ms，锁仍被持有——续期在租约到期前发生。

### 4.3 持锁到事务结束：`afterCompletion` 解锁

```java
try {
    return action.get();
} finally {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { releaseOrderLock(lock, orderId); }
        });
    } else {
        releaseOrderLock(lock, orderId);
    }
}
```

解锁挂在 **`afterCompletion`**（事务提交或回滚后执行该同步回调；不表示所有其他 afterCompletion 回调都已执行），不是方法返回时（堵住事故 3）。看门狗保证这段「事务提交 + 同步回调」的额外时长里锁依然有效。

### 4.4 锁竞争 409：不触碰业务数据

`withOrderLock` 抢锁失败直接抛 409「订单正在处理中，请稍后重试」——**不降级、不绕过**。订单状态流转的正确性依赖互斥：让竞争请求与 DB 条件更新硬碰，会拿回误导性的「状态已变化」报错。同一把锁覆盖订单的 **7 个状态变更入口**（接单 / 到达 / 开始 / 结束 / 支付 / 取消 / 超时处理），状态机整体串行化。

### 4.5 一人一单的跨订单约束：为什么订单锁不够

`withOrderLock` 锁的是**单个订单**——同一个司机并发抢两个**不同**订单时，两把订单锁互不冲突，双双通过预检。跨订单约束由 `OrderGrabService` 的司机账号行锁（`FOR UPDATE`）承担，见 [14 篇](14-grab-order-lua.md)。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 双轨（Redisson + 手写） | 统一一种 | 订单临界区时长不可预估（模型 / 外部 API / 事务提交）→ 看门狗；缓存重建秒级可预估 → 固定 TTL 足够且更轻（无后台续期线程） |
| 手写锁值存 UUID 令牌 | 值存固定标记（如 `"1"`） | 释放时校验持有者，堵「误删他人锁」；固定标记无法区分持有者 |
| `RELEASE_LOCK_IF_MATCH` Lua | `GET` 判断 + `DEL` 两条命令 | 两条之间锁可能易主——校验失效 |
| 看门狗（不传 leaseTime） | 显式 leaseTime | 显式租约回到「猜 TTL」；看门狗依靠客户端续期，业务完成后仍须显式解锁 |
| 解锁挂 `afterCompletion` | 方法 finally 里解锁 | 事务未提交时放锁 = 互斥失效（事故 3） |
| 锁竞争 409 | 排队等待 / 降级执行 | 状态机正确性优先；排队放大延迟，降级破坏互斥 |

**手写锁与 Redisson 的差距具体在哪几行代码**：手写 `RedisLock` 全类 42 行做到了「原子加锁 + 令牌校验释放 + 失败容错」，但没有：看门狗续期（临界区超时即失效，只能靠 TTL 兜底）、可重入、等待队列、崩溃后快速接管（要等满 TTL）。这些恰恰是 Redisson 用后台调度与内部状态机补齐的部分——**选择手写的唯一理由是临界区可预估且想要零依赖的轻量实现**。

---

## 六、边界与已知问题

1. **手写锁不可重入**：同一线程嵌套 `tryLock` 同一 key 会失败（第二次 `setIfAbsent` 撞到自己的值）。当前调用点无嵌套场景。
2. **手写锁释放失败后最坏等满 TTL**：5~10 秒内其他实例降级（直查 DB），不致命但存在。
3. **Redisson 看门狗依赖客户端续期**：业务挂起或漏解锁时可能长期占锁。订单锁中的外部调用需各自限制耗时；Agent 的单次模型流 3 分钟限制不包住订单状态变更的整个临界区。
4. **订单锁不覆盖跨订单约束**（4.5）：一人一单由 DB 行锁承担。
5. **Redisson 锁的 key 值格式是内部实现**：不要手工 `SET` / `DEL` 干预 Redisson 锁 key；验证用 `EXISTS` / `TTL` 观察。

---

## 七、如何验证

```bash
# 1. 手写令牌锁：GEO 池重建期间
redis-cli GET <order:geo:pool 重建锁 key>      # 期望：UUID 令牌
redis-cli TTL <重建锁 key>                       # 期望：≤ 10（REBUILD_LOCK_TTL）

# 2. 令牌校验释放：手工换成别人的令牌
redis-cli SET <锁key> other-token EX 5
# 原持有者释放 → 校验失败，锁不被删（EXISTS 仍为 1，直到 TTL 到期）

# 3. Redisson 看门狗：订单操作进行期间反复观察
redis-cli TTL <order:lock:相关key>              # 期望：在 ~30 附近波动（租约被持续续期）
# 若是固定 TTL 会单调下降；看门狗的特征是「回落到 ~30 的稳态」

# 4. 锁竞争：并发对同一订单发起两个状态变更
#    → 若首个请求持锁超过 3 秒等待窗口，另一个收到 409「订单正在处理中」；否则可能取得锁后发现业务状态已变化
```

---

## 八、延伸阅读

- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 手写锁的典型使用场景（统计重建）
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 订单锁与司机行锁的分工；`SYNC_ORDER_STATUS`
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `RELEASE_LOCK_IF_MATCH` 速查
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— 会话单轮执行锁（Redisson 的另一处应用）
