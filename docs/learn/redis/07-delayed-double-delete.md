# 延迟双删

> **Redis 考点**：写操作更新 DB 后删缓存，仍有一个「读线程在删除前查走旧快照、在删除后写回」的窗口 —— 用第二次延迟删除兜住它。
> **来源**：`docs/01-redis-application-points.md` §2.3（缓存一致性与竞态）；`docs/02-cache-consistency-race.md` §2.1 的竞态推导
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/TicketServiceImpl.java`（`afterTicketMutation` / `evictTicketStatistics`）、`src/main/java/com/fancy/rideagenthub/service/base/DelayedCacheEvictor.java`

---

## 一、业务场景

工单统计缓存（20~30 秒随机 TTL）背后的数据是工单表——**写操作高频**：`TicketServiceImpl` 里 10 个写工单的方法（提交、状态流转、分配、回复、关闭等）都调用 `afterTicketMutation` 统一收尾。

先删缓存再更新 DB 或先更新 DB 再删缓存（cache-aside 的两种顺序），都无法回避这个竞态：

```
T0 读线程：miss → 查 DB 拿到旧快照 S（工单数 = 99）
T1 写线程：DB 提交（工单数 = 100）→ 删除缓存
T2 读线程：此刻才把 S 回填进缓存（工单数 = 99，带 20~30s TTL）
↑ T1 的删除发生在 T2 的回填之前 —— 脏快照将存活一个 TTL
```

单次删除兜不住的原因：**回填的时机不可预测**——读线程在删除前查走快照，在删除后才写回，删除动作落在了中间。

---

## 二、Redis 结构选型

| 组件 | 形态 | 说明 |
|---|---|---|
| 被保护缓存 | `ticket:statistics:{yyyyMMdd}`（String，随机 TTL 20~30s） | 值为 `TicketDataVO` JSON |
| 第一次删除 | 提交后立即 `DEL` | 挂在 `afterCommit` |
| 第二次删除 | 500ms 后 `DEL` | `DelayedCacheEvictor` 自建单线程调度器 |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `TicketServiceImpl.afterTicketMutation` | 写工单的统一收尾：**先**注册索引同步（`ticketPoolIndex.syncByTicketId` 挂 `afterCommit`），**再**调 `evictTicketStatistics` |
| `TicketServiceImpl.evictTicketStatistics` | 挂 `afterCommit`：第一次删除（try/catch 包裹）+ 调度 500ms 第二次删除 |
| `service/base/DelayedCacheEvictor` | 单线程守护调度器：`evictAfter(key, delay)`；`@PreDestroy` 优雅关闭（`awaitTermination(5s)`） |

`afterCommit` 辅助方法（`TicketServiceImpl` 私有）在无事务时立即执行——同一个失效逻辑在事务内外行为一致。

---

## 四、实现拆解

### 4.1 为什么必须提交后删除

若在事务内删除缓存：并发读线程 miss 后回源 DB，读到的是**尚未提交**的数据（或读到旧值），并把它回填——制造出一个比目标竞态更早、更容易触发的脏窗口。挂在 `afterCommit` 上，删除时 DB 已可见，读线程回源拿到的至少是提交后的数据；竞态只剩「删除前查走旧快照」这一种，由第二次删除兜。

回滚场景自动正确：`afterCommit` 根本不触发，索引与缓存停留在写入前的状态，与回滚后的 DB 一致。

### 4.2 顺序：先注册索引同步，再失效统计缓存

`afterTicketMutation` 里两个动作的顺序是刻意的：

```
afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId))   // ① 先注册
evictTicketStatistics()                                        // ② 再失效
```

若 `evictTicketStatistics` 在注册前抛异常，索引同步已经挂上——**统计缓存的独立故障不能阻止索引版本推进**。反过来（先失效再注册）则统计缓存故障会让索引也停在旧版本。

### 4.3 第一次删除的容错语义

```java
try { stringRedisTemplate.delete(cacheKey); }
catch (RuntimeException e) { log.warn(...); }   // 删除失败不阻断
```

数据库已经提交，删除失败时**必须继续调度第二次删除**（不能把异常上抛把业务请求打失败）。删除成功只能清除当时已存在的缓存；若旧快照在第二次删除之后才回填，仍会残留到 TTL 到期。

### 4.4 第二次删除的延迟取值

`TICKET_STATS_DELAYED_EVICT = 500ms`。取值依据（源码注释原话）：

> 需覆盖「读线程查 DB + 回填缓存」的耗时，否则第二次删除仍早于回填，竞态依旧存在。统计查询是 4 次 selectCount，实测在几十毫秒量级，取 500ms 留足余量。延迟过长只会多几次缓存未命中，不会造成脏数据，故宁可取大。

**双删的容错方向是不对称的**：延迟偏大 → 多几次 miss（无害）；延迟偏小 → 脏窗口漏网（有害）。所以取值策略是「宁可偏大」。

### 4.5 自建调度器与 Spring 调度器并存

`DelayedCacheEvictor` 自建单线程 `ScheduledExecutorService`（守护线程 `delayed-cache-evictor`），而非复用 Spring 的 `@Scheduled` 基础设施。理由：双删任务要求**提交即入队、精确毫秒级延迟**，与定时轮询语义不同；且自建线程池的排队 / 拒绝行为独立于业务调度器（`RejectedExecutionException` 有专门处理）。启动类已开 `@EnableScheduling`（`OrderTimeoutJob` 等使用），两者互不干扰。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 延迟双删 | 延迟单删 / 不删（靠短 TTL 自愈） | 短 TTL（20~30s）本身已限制脏数据寿命，双删把「TTL 内的脏读概率」再压一个量级；代价极小（500ms 后一次 DEL） |
| 500ms 延迟 | 更短（100ms）/ 更长（2s） | 容错不对称：偏大无害、偏小有害；实测回填耗时几十毫秒，500ms 是 10 倍余量 |
| 自建单线程调度器 | Spring `@Scheduled` / 时间轮 | 毫秒级精确延迟 + 提交即入队；单线程足够（双删任务轻量、无并发语义） |
| 事务回调统一模式 | 写路径各自为政 | 10 个写方法统一走 `afterTicketMutation`，漏掉一个方法就是漏掉一条失效路径（见 4.2 的顺序设计） |

> 一个诚实的判断：统计 TTL 只有 20~30 秒、数据面向仪表盘，**单纯从数据价值看双删是「过度设计」**——TTL 到期脏数据自然消失。这里保留双删是因为它同时是「事务回调统一模式」的教学载体：同样的 `afterCommit` + 延迟兜底结构，出现在 GEO 池、延迟队列、抢单占位等所有「提交后生效」的场景里（见 [README](README.md) §3.1 引用枢纽）。

---

## 六、边界与已知问题

1. **延迟双删降低概率，不消除竞态**：读线程卡顿超过 500ms 后才回填，脏快照仍会写入并存活到 TTL 到期。它适合「短 TTL + 低价值数据」；7 天 TTL 的数据请改用墓碑（[08 篇](08-city-code-tombstone.md)）或版本租约（[09 篇](09-lease-token.md)）。
2. **进程崩溃会丢掉第二次删除**：调度任务在内存里，重启即失。脏数据存活到 TTL 到期（20~30 秒），可接受。
3. **双删不覆盖「读路径的破坏性回填」**：若回填语义是「整体替换」而非「写入」，需要 Lease（[09 篇](09-lease-token.md)）。
4. **`updateTicketTime` 不单独失效**：它是辅助方法（只更新时间戳），由调用方统一走 `afterTicketMutation` 覆盖，不算遗漏。

---

## 七、如何验证

```bash
# 1. 写工单后观察双删
#    （提交工单的瞬间到 500ms 之间，缓存 key 应经历 存在→DEL→(重建) 的过程）
redis-cli EXISTS ticket:statistics:<yyyyMMdd>   # 提交后立即查：多数时候已为 0

# 2. 观察应用日志：
#    "工单统计缓存已失效(双删): key=..."（debug 级）
#    第一次删除失败时："工单统计缓存失效失败"（warn），随后第二次删除仍会执行

# 3. 构造竞态复现（需配合断点）：
#    在 DB 查询后挂起，写事务提交后 500ms 内放行读线程并完成回填
#    → 第二次删除清掉旧快照；若在第二次删除之后才回填，则旧值仍可能残留到 TTL 到期

# 4. 回滚验证：触发工单写异常回滚，缓存与索引应保持不变
```

---

## 八、延伸阅读

- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 长 TTL 场景：双删救不了时的「看状态」方案
- [`09-lease-token.md`](09-lease-token.md) —— 回填破坏性场景：版本校验方案
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 同一套 `afterCommit` 模式在延迟队列上的应用
- [`docs/02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 三解法适用边界的完整推导
