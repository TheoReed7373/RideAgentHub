# 订单超时：ZSet 延迟队列

> **Redis 考点**：ZSet 的 score 存「到期时间戳」——「扫出所有已到期任务」就是一次 `ZRANGEBYSCORE`；多实例领取靠 Lua「读取 + 逐条移除」原子去重；任务丢失由数据库补偿扫描兜底。
> **来源**：`docs/01-redis-application-points.md` §2.5（订单与司机）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/OrderDelayQueue.java`、`src/main/java/com/fancy/rideagenthub/service/base/OrderTimeoutJob.java`、`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（`CLAIM_DUE_ORDERS`）

---

## 一、业务场景

订单有三个超时场景，都需要「到点自动处理」：

| 场景 | 超时后动作 |
|---|---|
| 无人接单（创建后 N 分钟） | 自动取消，释放司机位 / 失效 GEO 池 |
| 司机接单后未按时到达 | 清除司机与接单时间，状态退回 CREATED，重新入池并排接单超时 |
| 行程结束未支付 | 系统关单，设 CANCELLED 与 cancelRole=3，保留 realPrice 等账单字段 |

通用形态：**「将来某时刻要处理 X」**。ZSet 的 score 天然按数值有序——score 存到期毫秒时间戳，`ZRANGEBYSCORE 0 now` 就取出全部已到期任务，无需遍历、无需额外索引。

多实例部署的额外约束：两个实例同时扫描，同一次到期领取不会返回给两个实例——**领取无需额外分布式锁，业务处理仍需订单锁，补偿或重排可能再次处理同一订单**，靠 `ZREM` 的原子性（见 4.2）。

兜底要求：Redis 故障、进程退出、上线前的历史订单都可能让任务丢失——**数据库补偿扫描**是最终防线。

---

## 二、Redis 结构选型

| 项 | 值 | 说明 |
|---|---|---|
| Key | `DELAY_ORDER_TIMEOUT_KEY`（`RedisKeyConstants`） | 单一全局队列 |
| 结构 | ZSet | score = 到期时间戳（毫秒），member = orderId |
| 领取 | `CLAIM_DUE_ORDERS` Lua | `ZRANGEBYSCORE 0 now LIMIT 0 batch` + 逐条 `ZREM` |

**member 只用 orderId 的理由**（源码注释）：一个订单任一时刻只应有一条待办——进入下一个状态时上一条已失去意义。`ZADD` 按 member 覆盖旧 score，天然「一单一待办」；若把场景编码进 member（`accept:123`、`pay:123`），反而留下需要逐个清理的僵尸条目。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `OrderDelayQueue.schedule(orderId, delay)` | 排期 / 改期：`Math.addExact` 防溢出计算 deadline，挂 `afterCommit` 后 `ZADD` |
| `OrderDelayQueue.cancel(orderId)` | 终态取消：挂 `afterCommit` 后 `ZREM` |
| `OrderDelayQueue.claimDue(batchSize)` | `CLAIM_DUE_ORDERS` Lua：一次执行内读取并移除到期任务 |
| `OrderTimeoutJob.pollTimeoutOrders` | `@Scheduled(fixedDelay = 5000ms)`：认领 → 逐条交给 `rideOrderService.handleTimeoutOrder`；单条失败按轮询间隔重排 |
| `OrderTimeoutJob.recoverOverdueOrders` | `@Scheduled(fixedDelay = 60000ms)`：DB 三类锚点补偿扫描 |
| `RideOrderService.handleTimeoutOrder` | 真正的超时处理（重读订单状态判定，属订单状态机） |

---

## 四、实现拆解

### 4.1 排期：提交后生效 + 溢出防护

```java
long deadline = Math.addExact(System.currentTimeMillis(), Math.max(0L, delay.toMillis()));
afterCommit(() -> redisTemplate.opsForZSet().add(key, orderId, deadline));
```

- **`afterCommit`**：事务回滚时订单根本不存在，不能留下它的待办；提交后 `ZADD` 保证「DB 可见」与「待办生效」同序；
- **`Math.addExact`**：延迟极大时 `now + delay` 会静默溢出成过去的时间戳（立即触发）；显式抛出溢出异常好过静默错排。

改期语义：同一 orderId 再次 `schedule`，`ZADD` 覆盖旧 score——接单后把「无人接单」待办改成「到达超时」待办就是一次覆盖，不需要先取消再排。

### 4.2 领取：`CLAIM_DUE_ORDERS` 的原子去重

```lua
local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
for _, orderId in ipairs(orders) do
    redis.call('ZREM', KEYS[1], orderId)
end
return orders
```

「读取候选 + 逐条移除」在同一次执行内完成——**两个实例同时 `claimDue`**，Redis 单线程保证其中一个先执行完毕（读取并移除全部），另一个读到的是移除后的集合，**无交集**。无需分布式锁。

「读后移除」一体还防了另一个竞态：读取候选后、`ZREM` 之前，订单恰好被改期（score 更新）——先读后删会把「已改期的任务」误删。Lua 内两步无缝，误删不可能发生。

### 4.3 处理：只叫醒，不裁决

`OrderTimeoutJob` 的职责刻意收窄（源码注释）：

> 只做三件事：认领到期任务、交给业务处理、吞掉单条异常。

超时判定（当前状态是否真的该处理）依赖**重读订单**的当前状态与时间戳——这段逻辑属于订单状态机（`handleTimeoutOrder`，与其它状态流转共用 `withOrderLock`），放轮询类里会分叉出第二套口径。**单条失败不中断整批**（否则一条坏数据让队列停摆），失败任务按 `pollIntervalMs` 重新入队。

### 4.4 补偿：每分钟扫数据库

Redis 是**易失**的任务载体（故障、重启、上线前都丢任务）。`recoverOverdueOrders` 每分钟直查 DB，按三类锚点找「应当处理但队列里可能没有」的订单：

- `CREATED` 且（`updateTime` / `createTime` 早于接单超时线）且非「未到时间的预约单」；
- `DRIVER_ACCEPTED` 且 `driverAcceptTime` 早于到达超时线；
- `FINISHED_WAIT_PAY` 且 `finishTime` 早于支付超时线。

`LIMIT batchSize` 限制单批；按 `updateTime` 升序处理最旧优先。**预约单的改期不影响补偿判定**（锚点条件里排除未到 `scheduledTime` 的单）；条件更新带 `update_time` 快照（处理期间订单又被改过则更新不生效，且不覆盖 `real_price`）。

`fixedDelay`（而非 `fixedRate`）：本轮处理完再计时，避免慢轮次把后续轮次挤成背靠背执行。

### 4.5 开关与启动校验

`ride-order.timeout.enabled=false` 同时关闭轮询与补偿；`@PostConstruct` 的 `validate()` 让非法配置（超时为非正数等）**启动即失败**——兜底机制配错了不能等到线上超时才发现。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| ZSet score = 到期时间 | 每任务一个 key + TTL 过期事件 | Redis 的 keyspace 通知不保证送达（需开配置、fire-and-forget）；ZSet 主动拉取可靠 |
| member = orderId | 场景编码 member | 一单一待办天然成立；场景化 member 留僵尸条目 |
| Lua 原子领取 | `ZRANGEBYSCORE` + `ZREM` 两条命令 | 两实例并发领取会重复；且先读后删会误删已改期任务 |
| 处理失败重排 + DB 补偿 | 只重试 / 只补偿 | Redis 易失（补偿覆盖丢失），单条失败（重排覆盖抖动）——两层兜不同性质的失败 |
| 轮询类不写业务逻辑 | 轮询里直接改库 | 状态判定属订单状态机，两套口径迟早分叉 |

---

## 六、边界与已知问题

1. **任务精度受轮询间隔限制**（默认 5 秒）：低负载且调度正常时一般在下一轮领取；fixedDelay、批量积压、锁等待及处理时长都会增加延迟，并非最多 5 秒。兜底场景足够；需要秒级精度应换专门的延迟中间件。
2. **Redis 故障期间无法保证完成状态流转**：轮询失败跳过，DB 补偿仍会扫描并尝试处理，但业务订单锁仍依赖 Redis；恢复后可继续补偿。fixedDelay、积压与处理耗时意味着不能承诺最多滞后 1 分钟。
3. **`claimDue` 的 batchSize 限制单轮吞吐**：积压（如长时间停机后恢复）靠多轮消化；补偿扫描同样限批，两者节流是刻意的（避免恢复瞬间打挂 DB）。
4. **`cancel` 不调也无业务错误**：终态订单被认领后处理时会发现「无需处理」，只是队列里多一条无意义条目（源码注释明确的取舍）。

---

## 七、如何验证

```bash
# 1. 排期与改期
redis-cli ZSCORE delay:order:timeout <orderId>        # 期望：到期毫秒时间戳
#    接单后（改期）：score 变为新的到达超时时间
#    同一 member 只有一条（ZRANK / ZSCORE 不会出现两个）

# 2. 领取原子性：两个终端同时执行（模拟多实例）
#    redis-cli EVAL "...CLAIM_DUE_ORDERS 脚本..." 1 delay:order:timeout <now> 10
#    → 两次返回的订单列表无交集

# 3. 到期处理：score 已过期的订单在下一个 5 秒轮询内被处理并从队列消失
redis-cli ZSCORE delay:order:timeout <orderId>        # 处理后期望 (nil)

# 4. 补偿：手工删掉队列里一个已到期订单的条目（模拟丢失）
redis-cli ZREM delay:order:timeout <orderId>
#    → 后续 DB 补偿轮次仍会尝试处理；实际时间还受调度、积压与订单锁可用性影响

# 5. 失败重排：处理抛异常的订单会以 pollIntervalMs 重新入队（score 回到 ~5 秒后）
```

---

## 八、延伸阅读

- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 超时处理走 `withOrderLock`，与状态机共用口径
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 超时取消后的 GEO 池失效
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— ZSet 的另一种用法（复合排序 vs 延迟队列）
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `CLAIM_DUE_ORDERS` 速查
