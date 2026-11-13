# 缓存雪崩：随机 TTL

> **Redis 考点**：同一批 key 在同一时刻集体过期会造成「瞬间全 miss → 集体回源」的洪峰；用 TTL 随机抖动把过期时刻打散。
> **来源**：`docs/01-redis-application-points.md` §2.2（缓存三大经典问题）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/TicketServiceImpl.java`（`cacheTicketStatistics`）、`src/main/java/com/fancy/rideagenthub/service/base/OrderGeoPool.java`、`src/main/java/com/fancy/rideagenthub/service/base/TicketPoolIndex.java`

---

## 一、业务场景

雪崩与击穿的区别在**规模**：击穿是一个热点 key 过期，雪崩是一批 key 同时过期（或 Redis 整体故障）。触发雪崩的典型方式：

- 批量初始化缓存时用了同一个 TTL——所有 key 在同一秒到期；
- 缓存的写入频率与 TTL 共振——每轮写入都在同一批过期时刻附近。

本项目有两类缓存暴露在这个风险下：

| 缓存 | 写入模式 | 雪崩风险形态 |
|---|---|---|
| 工单统计 | 每次重建写一次固定 TTL | 所有实例共享同一日期 key；并发写入覆盖同一个值，不会生成实例专属副本 |
| GEO 池 / 工单池索引 | 发布时 `PEXPIRE` 一次 | 索引是整 key（不是批量 key），风险形态是「失效失败后漂移窗口无上界」 |

---

## 二、Redis 结构选型

两类缓存采用了**不同的防雪崩策略**，都值得对照理解：

| 缓存 | 策略 | 效果 |
|---|---|---|
| 统计缓存 | **随机 TTL**：`20 + ThreadLocalRandom.nextInt(11)` 秒（20~30s） | 每次重建随机选择存活时间；当前是共享热点 key，不能描述成多副本错峰 |
| GEO 池 / 工单池索引 | **固定 TTL 2 小时，写入不续期**（发布时 `PEXPIRE` 一次） | TTL 是漂移的硬上界：即使失效操作失败，旧索引最多存活 2 小时 |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `TicketServiceImpl.cacheTicketStatistics` | 写缓存时叠加随机 TTL（`TICKET_STATS_CACHE_BASE_SECONDS = 20` + `TICKET_STATS_CACHE_JITTER_SECONDS = 10`） |
| `OrderGeoPool` | `INDEX_TTL = 2h`；`PUBLISH_ORDER_GEO_POOL` Lua 发布时 `PEXPIRE` 一次 |
| `TicketPoolIndex` | `INDEX_TTL = 2h`；`PUBLISH_TICKET_POOL` Lua 发布时 `PEXPIRE` 一次 |

全项目 `ThreadLocalRandom` 共 4 处，与本考点相关的只有统计 TTL 这一处：`TicketServiceImpl`（TTL 抖动）、`RideOrderServiceImpl` / `ChatRequestRunner`（随机 owner 令牌）、`IdGeneratorConfig`（雪花 owner）。

---

## 四、实现拆解

### 4.1 随机 TTL：抖动幅度怎么取

```
TTL = 20 + nextInt(11)    → 均匀分布于 [20, 30] 秒
```

抖动幅度（10 秒）相对基础值（20 秒）达到 50%，是显著打散。选择依据：

- **下界 20 秒**：统计面向仪表盘，太短则重建频率过高（回源压力反而成为新雪崩源）；
- **上界 30 秒**：B 端对数据新鲜度的容忍上限；
- **均匀分布**：简单且足够——过期时刻自然铺满 [20, 30] 秒窗口，无需更精细的分布。

多实例共享 `ticket:statistics:{yyyyMMdd}`，最后一次成功写入决定该 key 的值与 TTL。随机抖动不会为同一个 key 产生多个不同到期的副本；未抢到锁的降级查询只读数据库，不回填缓存。

### 4.2 索引类缓存的「固定 TTL 不续期」

GEO 池与工单池的索引采用相反的策略：**发布时 `PEXPIRE` 2 小时，之后任何读写都不续期**。这解决的是雪崩的另一个形态——**失效失败后的无限漂移**：

- 索引失效（换版本号）失败时（Redis 抖动），旧索引理论上会存活到自然过期；
- 若每次读取都续期，一次失效失败 + 持续读取 = 旧索引**永不过期**，脏数据无上界；
- 固定 TTL 把漂移窗口封顶在 2 小时，失效失败的影响是有限的。

两类策略的分工：**数据缓存（可重算、可陈旧）用随机 TTL 打散重建压力；索引导出（结构整体、失效即重建）用固定 TTL 封顶漂移**。

### 4.3 冷启动：为什么不用「预热」

缓存冷启动（应用重启 / 首次部署）时统计缓存为空，第一批请求会全部 miss——这本质上是击穿（单 key），由 [04 篇](04-cache-breakdown-mutex.md) 的互斥锁处理，不需要雪崩层面的额外机制。

另一个容易混淆的概念是**连接池预热**：`util/HikariPoolLoader` 在 `ApplicationReadyEvent` 时提前建立数据库连接，这与缓存预热无关——它解决的是「第一批请求建连接慢」的问题，不是「缓存 miss」的问题。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 随机 TTL（统计） | 逻辑过期 / 永不过期 + 异步刷新 | 统计允许 miss 与短暂陈旧；随机 TTL 是零结构改动的最小解 |
| 固定 TTL 不续期（索引） | 读取时续期（活跃保活） | 续期会让「失效失败 + 持续读取」变成永不过期的脏索引；不续期牺牲一点命中率换漂移有界 |
| 抖动 10s / 基础 20s | 更大抖动 | 已足够打散；抖动过大只是把 miss 铺得更宽，无额外收益 |

---

## 六、边界与已知问题

1. **随机 TTL 防不了 Redis 整体故障**：实例宕机时所有缓存一起消失，恢复后的回源洪峰仍需容量控制；统计读缓存/加锁异常会直接抛出，不能承诺 Redis 故障期间始终降级查库。「雪崩」的故障子类只能靠 Redis 高可用部署解决，不是应用层 TTL 策略的职责。
2. **GEO 池 / 工单池在订单高频变更时重建开销大**：失效-重建模型（整池失效后全量回表）优先完整性，订单频繁变更时 2 小时内的多次重建是已知代价，见 [15 篇](15-nearby-order-geo.md)、[23 篇](23-ticket-pool-zset.md)。
3. **统计 TTL 的随机数种子每实例独立**（`ThreadLocalRandom`）：多实例的抖动天然不同步，这是想要的性质。

---

## 七、如何验证

```bash
# 1. 统计缓存的随机 TTL：多次触发重建（DEL 后查询）观察
redis-cli TTL ticket:statistics:<yyyyMMdd>
# 期望：刚写入时 TTL 在 [20, 30] 秒范围，可重复；读取时还需扣除已流逝时间

# 2. 索引的固定 TTL：触发重建后观察，后续读取不改变 TTL
redis-cli TTL <order:geo:pool 相关 key>    # 期望：≤ 7200 且只减不增
# （读取 100 次后再看，TTL 仍在单调下降，没有跳回 7200）

# 3. 多实例验证：各实例读取的是同一个日期 key，观察最后一次写入决定的 TTL
```

---

## 八、延伸阅读

- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 单 key 击穿的互斥锁（冷启动 miss 由它吸收）
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md)、[`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— 索引类缓存的失效-重建模型与固定 TTL
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 连接池预热与缓存预热的区分
