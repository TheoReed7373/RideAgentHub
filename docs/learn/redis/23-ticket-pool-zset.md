# 工单池 ZSet 排序：score 位权拼接 + 版本令牌发布

> **Redis 考点**：多个排序维度（优先级、更新时间、主键）拼进一个 ZSet score；「版本 + 锁令牌」原子发布；写入提交后整池失效、读取时全量重建。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/TicketPoolIndex.java`、`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（`INVALIDATE_TICKET_POOLS` / `PUBLISH_TICKET_POOL` / `READ_TICKET_POOL`）

---

## 一、业务场景

B 端工单池按状态分池、按「优先级降序 → 更新时间降序 → 主键降序」分页展示。这是个**复合排序 + 分页总数**的查询——直接走数据库要 `ORDER BY priority, updated_at, id` + `COUNT`，每次翻页两查；ZSet 把排序结果物化，翻页变成一次 `ZREVRANGE`。

实现采用与 GEO 池（[15 篇](15-nearby-order-geo.md)）同构的**失效-重建模型**：工单写入提交后整池失效，下次读取抢锁全量回表重建。工单管理写入频率低（客服操作量级），重建代价可接受——这与订单 GEO 池（订单高频变更）的取舍考量相同但量级判断不同。

---

## 二、Redis 结构选型

按 `TicketStatus` 分池（5 个状态），每池三件套 + 全局版本：

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:pool:version` | String（UUID） | **无 TTL** | 全局版本：任何工单变更即更换 |
| `ticket:pool:ready:{status}` | String（`{version}:{count}`） | 2 小时 | 各池就绪标记 |
| `ticket:pool:{status}` | ZSet | 2 小时（发布时 `PEXPIRE` 一次） | 排序索引：member = 工单主键 |

**score 的位权拼接**：

```
score = priority × 1e13 + updatedAtEpochMillis（UTC）
member = String.format("%019d", id)        ← 19 位补零
```

- `PRIORITY_WEIGHT = 1e13`：毫秒时间戳最大 ~1.7e12 < 1e13，优先级占高位、时间占低位——**优先级严格主导，时间只在同级内决胜**；
- 时间用 `toInstant(ZoneOffset.UTC)`：**数据库 DATETIME 不带时区**，固定 UTC 映射避免实例时区 / 夏令时改变排序；`updatedAt` 为空按 0 处理（新工单排同级最后）；
- member 补零：ZSet 等分（score 相同）时按**成员字典序**排序，`%019d` 使字典序 == 主键数值序。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `TicketPoolIndex.syncByTicketId` | 工单变更后（`afterCommit`）整池失效：`INVALIDATE_TICKET_POOLS`（换版本 + 删全部状态池） |
| `TicketPoolIndex.slice(status, offset, size)` | 读 → miss 抢锁 → 锁内双检 → 全量回表 → `PUBLISH_TICKET_POOL` 发布 → 再读 |
| `TicketPoolIndex.readSlice` | `READ_TICKET_POOL` Lua：校验标记 + 读总数与当前页 |
| `TicketPoolIndex.isCurrent` | 回表后版本二次校验（失败整页退回 DB） |
| `TicketServiceImpl` 的工单分页查询 | `slice` + 回表补全 → `isCurrent` 校验 → 降级 DB 查询 |

---

## 四、实现拆解

### 4.1 失效：跨状态变更统一整池

`syncByTicketId` 用 `INVALIDATE_TICKET_POOLS` 一次换版本 + 删**全部**状态池（不是单个池）：工单经常跨状态流转（待分配 → 处理中 → 已关闭），「只失效源池 + 目标池」要维护状态迁移矩阵，漏一条就是幽灵工单。全删的代价是翻倍重建（两个池各重建一次），一致性模型简单是主要收益。

空池**也发布**（`count = 0` 的标记）：空是合法状态（该状态无工单），不能与「未就绪」混淆——`READ_TICKET_POOL` 对「标记缺失」返回空数组走降级，对「标记 = version:0」返回空页。

### 4.2 发布与读取：同 GEO 池的双校验

`PUBLISH_TICKET_POOL` 校验「版本未变 + 锁令牌未易主」才发布；就绪标记 `{version}:{count}` 原子写入。`READ_TICKET_POOL` 一次执行内：

```lua
local version = redis.call('GET', KEYS[1])
local count   = redis.call('ZCARD', KEYS[3])
if not version or redis.call('GET', KEYS[2]) ~= version .. ':' .. count then
    return {}                     -- 标记缺失/不一致 → 空返回（调用方降级）
end
local result = {version, tostring(count)}
local members = redis.call('ZREVRANGE', KEYS[3], ARGV[1], ARGV[2])   -- 倒序分页
-- 追加成员
```

**「索引缺失不能伪装成空池」**：`ZCARD` 与标记比对在同一次执行内——若 ZSet 被误删而标记还在（`count` 不匹配），返回空数组触发重建，**不会**把「无索引」当成「该状态没有工单」返回给用户。

### 4.3 回表校验：整页退回

`TicketServiceImpl` 拿到 `PoolSlice(version, total, ids)` 后回表 `selectByIds`：

- 数量不符（行被删除）；
- 状态不符（工单已流转到别的池）；
- `isCurrent` 版本校验失败（回表期间又发生了失效）——

任一命中则**整页退回数据库查询**（返回真实的总数与当前页），**不返回过滤后的残页**（残页的 total 是错的：显示「共 42 条」实际给 39 条）。

`readSlice` 自身还有形态校验：`total < 0`、`ids.size() != min(size, total - offset)`、有重复 id / 非正 id → 判为不可信，返回空走降级。offset 用 **long** 且 `slice` 入口检查 `offset > Long.MAX_VALUE - size`（加法溢出防护）。

### 4.4 发布数据的质量闸

`slice` 重建回表时对每条工单校验：`id > 0` 且 `ticketStatus` 等于当前池的状态——**任何一条非法（脏数据 / 回表期间被改状态）放弃整次发布**（返回空走降级），不发布「部分正确」的索引。

### 4.5 锁与事务边界

与 GEO 池同构：重建锁 `RedisLock`（10 秒 TTL）；`slice` 在事务活跃时直接返回空（事务中的旧快照不能发布）；调用方 TicketServiceImpl.afterTicketMutation 将 syncByTicketId 挂 afterCommit；syncByTicketId 本身立即执行失效（回滚时调用方不触发）。固定 2 小时 TTL 不续期——失效失败的漂移窗口有上界。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| score 位权拼接 | RedisSearch / 多次查询归并 | 一维 score 承载多维排序，`ZREVRANGE` 一次拿到全序；位权保证字典序语义正确 |
| 固定 UTC 映射时间 | 实例默认时区 | 多实例时区 / 夏令时会改变排序 |
| member 补零 | 直接 `String.valueOf(id)` | 等分时字典序 ≠ 数值序（"9" > "10"）；补零对齐 |
| 跨状态全删 | 只删源池 + 目标池 | 状态迁移矩阵易漏；全删的一致性模型简单 |
| 回表校验失败整页退回 | 过滤残缺继续返回 | 残页的 total 是错的（误导分页）；退回 DB 慢而正确 |
| 版本 key 无 TTL | 与池同 TTL | 与 [09 篇](09-lease-token.md) / [15 篇](15-nearby-order-geo.md) 同一模式：版本号是失效时钟，不能倒转 |

---

## 六、边界与已知问题

1. **工单高频变更时重建开销大**：每次变更全池失效；客服场景写入低频，可接受（订单 GEO 池同模型但量级判断不同）。
2. **翻页期间数据变化表现为「跳页」**：重建后版本变化 → 当前页整页退回 DB → DB 是最新序（可能跳过 / 重复若干条）。分页浏览对最终一致性的容忍是前提。
3. **offset 深翻页成本**：按排名区间执行 `ZREVRANGE` 的复杂度为 O(log N + M)，N 为成员总数、M 为返回条数；不是按分数查询带 LIMIT offset 的线性跳过模型；B 端工单池翻页深度有限，可接受。
4. **`updatedAt` 为空的工单**（新插入未更新过）：时间位按 0，同级排最后——与「按创建时间兜底」的备选相比少一次字段选择，行为一致可预期。

---

## 七、如何验证

```bash
# 1. 就绪标记与池
redis-cli GET ticket:pool:ready:<status>          # 期望：{version}:{count}
redis-cli ZCARD ticket:pool:<status>              # 期望：与 count 一致
redis-cli TTL ticket:pool:<status>                # 期望 ≤ 7200，读取不续期
redis-cli TTL ticket:pool:version                 # 期望 -1

# 2. score 语义：观察成员的 score
redis-cli ZRANGE ticket:pool:<status> 0 -1 WITHSCORES
# 期望：score 大 → 优先级高或更新时间新；同级时按时间近者在前
# 等分成员按补零后的字典序（ZRANGE 正序看 member 格式，19 位数字）

# 3. 失效：变更一个工单（任何状态流转）后
redis-cli EXISTS ticket:pool:ready:<旧status>     # 期望 0（全部池失效）
redis-cli GET ticket:pool:version                 # 版本已更换

# 4. 重建后翻页：标记重新出现，count 更新
# 5. 空池：某状态无工单 → ready = {version}:0，返回空页（非降级）
```

---

## 八、延伸阅读

- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 同一套失效-重建模型（GEO 池）的完整推导
- [`09-lease-token.md`](09-lease-token.md) —— 版本 key 无 TTL 的通用模式
- [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md) —— 索引固定 TTL 不续期的理由
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 重建用的手写令牌锁
