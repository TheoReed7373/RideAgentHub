# 附近订单：GEO

> **Redis 考点**：GEO 按经纬度查「三公里内最近的 N 个」；配合「整池失效 + 版本校验重建」保证索引与数据库一致，故障时整页降级为数据库查询。
> **来源**：`docs/01-redis-application-points.md` §2.5（订单与司机）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/OrderGeoPool.java`、`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（`INVALIDATE_ORDER_GEO_POOL` / `PUBLISH_ORDER_GEO_POOL` / `SEARCH_ORDER_GEO_POOL`）

---

## 一、业务场景

在线司机端的「附近订单」列表：以司机当前位置为圆心，查**三公里内**待接单订单、按距离升序、最多 200 个候选。这是**高频 + 按地理位置过滤**的查询，正是 GEO 结构的适用面。实现要同时满足：

1. **完整性**：新订单刚落库不能被存量索引遮蔽——单笔增量写入没有全量上下文；
2. **一致性**：订单接走 / 取消后，索引里的旧候选不能继续被查到；
3. **可用性**：Redis 故障或索引竞争时，宁可回数据库慢查，不能报错。

---

## 二、Redis 结构选型

GEO 底层是 ZSet（member + geohash 编码 score），本项目的四件套：

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `order:geo:pool:version` | String（UUID） | **无 TTL** | 版本号：失效即更换 |
| `order:geo:pool:ready` | String（`{version}:{count}`） | 2 小时（与池同寿） | 就绪标记：版本 + 成员数 |
| GEO 池 key | GEO | 2 小时（发布时 `PEXPIRE` 一次，不续期） | 完整待接单池 |
| 重建锁 key | String（令牌） | 10 秒（`REBUILD_LOCK_TTL`） | 重建互斥 |

常量：`SEARCH_RADIUS_KM = 3.0`、`SCAN_LIMIT = 200`、`INDEX_TTL = 2h`。查询命令用 `GEORADIUS`（Lua 内执行，兼容 Redis 5+——`GEOSEARCH` 是 6.2+ 命令）。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `OrderGeoPool.add` / `remove` | 订单变更入口：**只注册失效**（`add` 委托 `remove`），挂 `afterCommit` |
| `OrderGeoPool.invalidate` | `INVALIDATE_ORDER_GEO_POOL` Lua：换版本 + 删就绪标记与池 |
| `OrderGeoPool.search(lng, lat)` | 读 → miss 则抢锁 → 锁内双检 → 全量回表 → `PUBLISH_ORDER_GEO_POOL` 发布 → 再读 |
| `OrderGeoPool.read` | `SEARCH_ORDER_GEO_POOL` Lua：原子校验就绪标记 + `GEORADIUS` |
| `OrderGeoPool.isCurrent` | 回表后二次校验版本（调用方据此决定降级） |
| 调用方（`RideOrderServiceImpl`） | `isCurrent` 不通过 → 整页降级 DB 查询（时间 / ID 倒序，不限距离与条数） |

---

## 四、实现拆解

### 4.1 失效：整池而不是单成员 `ZREM`

订单被接走时把它从池里 `ZREM` 掉看似更「增量」，但增量维护有三个问题：状态机复杂化（每条状态变更都要走到删除）、漏删会持续返回幽灵订单、失效失败后一致性无上界。当前方案：**任何订单变更（新增 / 接单 / 取消 / 超时）都使整池失效**（换版本号 + 删标记与池），下次查询全量回表重建——「完整重建的池」不存在部分状态漂移。代价是订单高频变更时重建开销大（全量回表 + 批量 `GEOADD`），对当前订单密度可接受。

### 4.2 发布：版本 + 锁令牌双重校验

`PUBLISH_ORDER_GEO_POOL` 的开头：

```lua
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[2] then
    return 0        -- 版本已换（有更新的失效）或锁已易主 → 放弃发布
end
redis.call('DEL', KEYS[3], KEYS[4])
-- 批量 GEOADD ... PEXPIRE 池 2h；SET 就绪标记 {version}:{count}
```

两个校验对应两种竞态：

- **版本校验**：重建期间（全量回表耗时）又有订单变更换了版本——本次快照已过时，**放弃发布**；
- **锁令牌校验**：重建超过锁 TTL（10 秒）后锁过期被别的实例抢到——**过期锁持有者不能发布**（其数据可能比新持有者旧）。

就绪标记 `{version}:{count}` 让读取方能校验「标记 + 成员数」双一致。

### 4.3 读取：原子三查

`SEARCH_ORDER_GEO_POOL` 在一次执行里：读版本、读就绪标记、`ZCARD` 成员数，三者不一致（版本缺失 / 标记不匹配）返回空（走降级）；一致则 `GEORADIUS … ASC COUNT 200 WITHDIST`，返回 `{version, orderId, distance, ...}`。**标记与池内容同瞬一致**，不存在「标记还在、池已被删」的中间态。

### 4.4 回表后二次校验与整页降级

调用方拿到 `GeoSlice(version, orders)` 后回表补订单详情，再 `isCurrent(slice)` 比对当前版本——**回表期间池可能已被失效重建**，版本不一致则**整页退回数据库**，不返回「部分新部分旧」的混合结果。降级查询不限三公里与 200 条（可用性优先，时间序代替距离序）。

### 4.5 事务中不发布、回滚不发布

`search` 开头检查事务活跃状态——事务中直接返回空（走降级）：**事务中的旧快照或未提交修改不能发布为公共索引**。`add` / `remove` 的失效挂 `afterCommit`：回滚时不触发，索引与回滚后的 DB 一致。

### 4.6 非法成员整页作废

`read` 解析结果时校验每个成员：`id > 0`、`distance` 有限且非负——**任何一个成员非法则整个返回判为不可信**（返回空走降级），不做「过滤掉坏成员继续用」的容忍（容忍等于接受残缺索引）。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 整池失效 + 全量重建 | 单成员增量 `GEOADD` / `ZREM` | 完整性优先：增量易漏状态、失效失败无上界；重建模型简单（版本即真相） |
| `GEORADIUS`（Lua 内） | `GEOSEARCH` | 兼容 Redis 5；语义等价（按半径 + COUNT + 排序） |
| 版本 key 无 TTL | 与池同 TTL | 版本归零重建会与在途校验撞车（[09 篇](09-lease-token.md) 同一模式） |
| 固定 2h 不续期 | 读取续期 | 续期会让「失效失败 + 持续读取」变成永不过期的脏索引（[05 篇](05-cache-avalanche-random-ttl.md)） |
| 降级整页回 DB | 部分降级 / 报错 | 混合新旧结果比慢更糟；报错不可接受 |

---

## 六、边界与已知问题

1. **订单高频变更时重建开销大**：每次变更都使整池失效，短时间多次重建 = 多次全量回表。设计取舍已声明（完整性优先）；订单量上来后可考虑「失效合并窗口」。
2. **降级查询失去距离序**：降级时返回时间序、不限三公里——前端展示语义会变（`distanceKm` 为空），调用方要感知降级状态。
3. **重建锁 10 秒是上界**：超时的重建者不会发布（校验失败），但全量回表超过 10 秒意味着数据量或 DB 已异常，此时每次查询都在降级边缘。
4. **空池也发布**：池为空时只保留 count=0 的就绪标记（Redis 不保留零成员 GEO/ZSet key）——空是合法状态（无待接单订单），不能与「未就绪」混淆。

---

## 七、如何验证

```bash
# 1. 就绪标记与池的关系
redis-cli GET order:geo:pool:ready            # 期望：{version}:{count}（如 uuid:42）
redis-cli ZCARD <order:geo:pool key>           # 期望：与 count 一致
redis-cli TTL <order:geo:pool key>             # 期望：≤ 7200，且读取不续期（只减不增）
redis-cli TTL order:geo:pool:version           # 期望 -1（无 TTL）

# 2. 失效：创建 / 接走一个订单后
redis-cli GET order:geo:pool:ready             # 期望：(nil)（整池已失效）
redis-cli EXISTS <order:geo:pool key>          # 期望 0

# 3. 重建：下次司机查询附近订单后，ready 重新出现且 count 更新
# 4. 距离语义：GEORADIUS 结果按距离升序，最大 200 条
# 5. 降级：重建竞争期间（手工制造锁占用）查询 → 返回 DB 时间序结果
```

---

## 八、延伸阅读

- [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 司机端共享 GEO key 与成员级心跳
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— 同一套「版本 + 锁令牌发布」模型在工单池的落地
- [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md) —— 索引固定 TTL 不续期的理由
- [`09-lease-token.md`](09-lease-token.md) —— 版本 key 无 TTL 的通用模式
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 本篇三段脚本速查
