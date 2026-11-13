# 缓存击穿：互斥锁 + 双检重建热点 key

> **Redis 考点**：热点 key 过期瞬间，并发请求全部回源数据库 —— 用「互斥锁 + 双检 + 有限重试 + 降级直查」把回源收敛为一次。
> **来源**：`docs/01-redis-application-points.md` §2.2（缓存三大经典问题）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/TicketServiceImpl.java`（`getTicketStatistics` 一族方法）、`src/main/java/com/fancy/rideagenthub/service/base/RedisLock.java`

---

## 一、业务场景

工单统计接口（`getTicketStatistics`）面向 B 端客服仪表盘：一次查询要跑 4 次 `selectCount`，返回待分配总数、处理中且有处理人的总数、今日创建数、今日完成数；前两项不限制日期。它是典型的**热点 key**：

- B 端多个管理页面 / 多个客服同时刷新，同一时刻的请求全都落在同一个 key 上；
- 统计数据是「写后可短暂陈旧」的（TTL 20~30 秒，见 [05 篇](05-cache-avalanche-random-ttl.md)）。

击穿的形态：key 到期的那一瞬间，N 个并发请求同时 miss，若不加控制就会同时回源 DB——本来一次 4 次 `selectCount`，瞬间变成 4N 次。**重建只应该发生一次**，其余请求应该等一等、读重建后的缓存。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:statistics:{yyyyMMdd}`（`RedisKeyConstants.ticketStatisticsKey`） | String（`TicketDataVO` 的 JSON） | 20~30 秒随机 | 统计数据缓存 |
| `ticket:statistics:lock:{yyyyMMdd}`（`RedisKeyConstants.ticketStatisticsLockKey`） | String（UUID 令牌） | 固定 5 秒（`TICKET_STATS_LOCK_SECONDS`） | 重建互斥锁 |

重建锁是 `RedisLock` 的手写令牌锁（`setIfAbsent` + `RELEASE_LOCK_IF_MATCH`），不是 Redisson——为什么不用看门狗，见 [13 篇](13-distributed-lock.md) 的双轨分工。

---

## 三、代码落点

| 方法 | 职责 |
|---|---|
| `TicketServiceImpl.getTicketStatistics` | 主流程：读缓存 → 抢锁 → 锁内双检 → 回源 → 写缓存；拿不到锁则有限重试后降级 |
| `TicketServiceImpl.getCachedTicketStatistics` | 读 + 反序列化；**脏 JSON 直接删除并视为未命中**（防脏缓存永久滞留） |
| `TicketServiceImpl.cacheTicketStatistics` | 回源结果写缓存（随机 TTL） |
| `TicketServiceImpl.tryLockTicketStatistics` / `releaseTicketStatisticsLock` | 委托 `RedisLock.tryLock` / `unlock` |
| `RedisLock.tryLock(key, ttl)` | `setIfAbsent(key, UUID, ttl)`，成功返回令牌，失败返回 null |
| `RedisLock.unlock(key, token)` | `RELEASE_LOCK_IF_MATCH` Lua：令牌匹配才 DEL |

---

## 四、实现拆解

### 4.1 主流程：三段式

```
① 读缓存，命中直接返回
② 抢锁（5s TTL）：
   锁内双检（防「排队期间别人已重建」）→ 查 DB → 写缓存 → finally 释放锁
③ 没抢到锁：sleep 60ms → 读缓存，最多重试 3 次
   仍然 miss → log.warn → 直查 DB（降级）
```

三个细节值得注意：

- **锁内双检**：从「拿不到锁」到「拿到锁」之间可能隔了几十毫秒，期间持有锁的线程可能已经重建完成。不双检就会再做一次无用回源。
- **重试是读缓存而不是抢锁**：没抢到锁的请求**不应该排队抢锁**（等锁释放再抢，等于把 DB 压力换成锁竞争压力），而应该假设「持锁者马上会重建好」，间歇性读缓存即可。
- **降级直查 DB**：3 次重试（共 ~180ms）后仍未命中，宁可回源也不无限等待——仪表盘数据短时陈旧可接受，接口不能挂。

### 4.2 令牌校验释放：为什么 `finally` 里不能直接 `DEL`

`unlock` 走 `RELEASE_LOCK_IF_MATCH`（值等于本次令牌才删）。若临界区执行超过锁 TTL（5 秒——例如 DB 慢查询），锁已自动过期且可能被别的线程持有；无条件 `DEL` 会**删掉别人的锁**，让互斥彻底失效。令牌校验保证「只删自己加的锁」。

`unlock` 内部捕获 `RuntimeException` 只记 warn：**释放失败不覆盖临界区的返回值或异常**，剩余锁由固定 TTL 兜底释放（最坏情况：TTL 窗口内其他实例降级直查 DB）。

### 4.3 脏缓存的防御

`getCachedTicketStatistics` 反序列化失败（JSON 结构变更、写入了半截数据）时**直接删除该 key 并视为未命中**——脏缓存的最长寿命是「到下次读取」，而不是「到 TTL 到期」。配合 4.2 的重建，脏数据会被下一次重建自然覆盖。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 互斥锁 + 双检 | 逻辑过期（缓存永不过期，值内嵌过期时间，异步刷新） | 逻辑过期实现复杂（要维护值内时间戳 + 异步刷新线程），且统计允许短暂 miss；互斥锁是最小正确解 |
| 拿不到锁 → 有限重试读缓存 | 自旋抢锁 / 无限等待 | 持锁者重建完成后缓存即可读，无需参与锁竞争；有限重试给出确定的上界（~180ms），超时降级保证接口可用 |
| 手写令牌锁（固定 5s TTL） | Redisson 看门狗 | 统计重建是短临界区，固定 TTL 足够；看门狗给「临界区时长不可预估」的场景续命，这里是可预估的。两类锁的分工见 [13 篇](13-distributed-lock.md) |
| 降级直查 DB | 抛 503 | 击穿的危害是 DB 压力，不是不可用；低频降级直查是可接受的兜底 |

---

## 六、边界与已知问题

1. **重试窗口（~180ms）内缓存仍未就绪会直查 DB**：极端情况下（持锁者 DB 查询超 180ms）多个等待者同时降级，回源次数介于 1 与 N 之间。统计接口可接受；不可接受的场景应该拉长重试或引入逻辑过期。
2. **锁 TTL 是猜的**：5 秒覆盖 4 次 `selectCount` 绰绰有余；若 DB 退化到 5 秒以上，令牌校验会保护锁不被误删，但互斥窗口实际已失效（降级路径兜底）。
3. **本模式只保护「单 key 重建」**：击穿的「批量过期」变体要靠随机 TTL 打散（[05 篇](05-cache-avalanche-random-ttl.md)），两者是互补关系。
4. **Redis 故障不保证降级成功**：getCachedTicketStatistics 的 GET 与 tryLockTicketStatistics 未捕获 Redis 异常，可能直接向上抛出；当前直查 DB 的兜底只针对抢不到锁且有限等待仍 miss 的分支。
5. **无 single-flight 合并**：降级直查的请求没有被合并，各自独立打 DB。低频降级下可接受。

---

## 七、如何验证

```bash
# 1. 正常路径
redis-cli GET ticket:statistics:<yyyyMMdd>      # 期望：TicketDataVO JSON
redis-cli TTL ticket:statistics:<yyyyMMdd>      # 期望：20~30 之间（随机抖动）

# 2. 锁的形态：击穿发生瞬间（DEL 后立即并发请求）
redis-cli GET ticket:statistics:lock:<yyyyMMdd>   # 期望：UUID 令牌（持锁线程写入）
redis-cli TTL ticket:statistics:lock:<yyyyMMdd>   # 期望：≤ 5

# 3. 验证令牌释放：重建完成后锁 key 消失
redis-cli EXISTS ticket:statistics:lock:<yyyyMMdd>   # 期望 0

# 4. 验证降级：手工删除缓存后立即并发 20 个请求，
#    观察应用日志 —— 只应出现 1 次「查询 DB」级别的回源日志，
#    或极端时序下少量「重建等待超时，降级直查DB」的 warn
redis-cli DEL ticket:statistics:<yyyyMMdd>
```

---

## 八、延伸阅读

- [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md) —— 批量过期（雪崩）与单点击穿的互补关系
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 手写锁与 Redisson 的双轨分工；误删他人锁的完整推导
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `RELEASE_LOCK_IF_MATCH` 脚本
- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 击穿解决「重建并发」，双删解决「回填覆盖」，两个不同的问题
