# 空值哨兵与状态预热

> **Redis 考点**：把「key 不存在」（未知）与「已知的空」（查过 DB，确实没有）区分开——前者继续回源，后者用哨兵值缓存下来防穿透。
> **来源**：`docs/01-redis-application-points.md` §2.2（缓存三大经典问题）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/amap/util/citycode/CityCodeUtil.java`、`src/main/java/com/fancy/rideagenthub/service/base/OrderGrabService.java`、`src/main/java/com/fancy/rideagenthub/util/HikariPoolLoader.java`

---

## 一、业务场景

缓存读取的返回只有两种原始形态：命中（有值）或 miss（key 不存在）。但 miss 是**语义不明**的——可能是「还没查过」（应该回源），也可能是「查过了、DB 里没有」（不该再查）。把这两种情况混为一谈，同一个「不存在」的城市名会被反复穿透到 DB。

本项目的两种哨兵值：

| 哨兵 | 场景 | 值 | 语义 |
|---|---|---|---|
| `__NULL__` | 城市编码查询 | 空值哨兵 | DB 里确实没有这个城市名——**稳定的查询结果**，缓存 7 天 |
| `__IDLE__` | 司机抢单占位 | 状态哨兵 | 司机当前没有进行中订单——**可变的瞬时状态**，缓存 5 分钟 |

两者的寿命策略截然不同：`__NULL__` 表达的是「事实」（不会变，可长期缓存），`__IDLE__` 表达的是「状态」（会变，短 TTL + 主动维护）。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 哨兵语义 |
|---|---|---|---|
| `amap:city_code:{name}` | String | 7 天（`CACHE_TTL_DAYS`） | `__NULL__` = DB 无此城市；正常值 = 城市编码 |
| `amap:city_code:version:{name}` | String | **无 TTL** | 版本租约（配合墓碑，见 [08 篇](08-city-code-tombstone.md)） |
| `driver:active:{driverId}` | String | 5 分钟（`CACHE_TTL`） | `__IDLE__` = 司机空闲；正常值 = 进行中订单号 |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `CityCodeUtil.getCityCode` | 读缓存 → 命中哨兵返回 null；miss → 查 DB → `CACHE_CITY_CODE_IF_VERSION_MATCH` 版本校验回填（DB 无值时回填 `__NULL__`） |
| `OrderGrabService.tryGrab` | 事务内先依据 DB 查询结果**修复**占位（`set` `__IDLE__` 或订单号），再走 `GRAB_ORDER_ATOMIC` 抢占 |
| `util/HikariPoolLoader` | `ApplicationReadyEvent` 时预热数据库连接池（与缓存预热无关，见 4.4） |

---

## 四、实现拆解

### 4.1 `__NULL__`：把「查询结果」与「未查询」分开

`getCityCode` 命中缓存后：

```
cached == "__NULL__"  →  返回 null（DB 无此城市，不再回源）
cached != null        →  返回编码
cached == null        →  回源 DB，回填时写入真实值或 __NULL__
```

回填走 `CACHE_CITY_CODE_IF_VERSION_MATCH`（版本匹配才 `SET NX EX`）：`NX` 保证不覆盖任何已有值（包括墓碑与并发写入），版本校验保证墓碑过期后迟到的旧快照回不来。**「查过且为空」从此成为一个可缓存 7 天的稳定结果**。

### 4.2 `__IDLE__`：状态编码而不是 DEL

`driver:active:{driverId}` 表达的是司机当前占用状态。用 `__IDLE__` 字符串而不是「key 不存在」表示空闲，理由：

- **`GRAB_ORDER_ATOMIC` 需要可判定的初值**：抢占 Lua 检查「当前值 == `__IDLE__`」才允许写入订单号——key 不存在时 Lua 需要额外分支处理，且无法区分「从未初始化」与「空闲」；
- **占位状态可主动维护**：`tryGrab` 在事务内依据 DB 的在途订单查询**主动修复**这个 key（`set` 为 `__IDLE__` 或真实订单号）——缓存过期或丢失时，事务内的行锁查询把它重建为正确值，而不是等下一次抢单失败才发现。

注意这里**不再有独立的「状态预热」方法**：预热被合并进 `tryGrab` 的 `@Transactional(MANDATORY)` 事务——司机账号行 `FOR UPDATE` 查询本身就要读在途订单，顺带把占位 key 修正是零成本的。这是「预热与业务读合并」的形态：**回源时顺手重建缓存，比独立的预热入口更不容易漏**。

### 4.3 状态变更时删除缓存（而非写哨兵）

司机接单失败回滚时，`rollbackGrab` 走 `RELEASE_LOCK_IF_MATCH`（值等于本订单号才删）释放占位——状态回退用**条件删除**表达，而不是把值改回 `__IDLE__`。理由：删除是「下次查询回源重建」的信号；而写回 `__IDLE__` 会与「另一个并发事务刚写入的新订单」竞争，可能把新状态覆盖回旧状态。

### 4.4 连接池预热 ≠ 缓存预热

`HikariPoolLoader` 在 `ApplicationReadyEvent` 中执行一次 `cityCodeMapper.selectCount(null)`，触发数据库访问与连接池初始化；没有循环获取连接或填满最小空闲数的逻辑。解决的问题是**冷启动时第一批请求要同步建连接**（TCP + 认证握手，每个几十毫秒）。它与缓存预热无关：缓存 miss 由击穿锁与降级策略吸收（[04 篇](04-cache-breakdown-mutex.md)）。两个「预热」经常被混为一谈，但它们预热的是完全不同的资源。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| `__NULL__` 缓存空值 | 布隆过滤器 | 城市编码集合规模有限且查询高频；空值哨兵直接、无假阳性，且能区分「不存在」的具体含义 |
| `__IDLE__` 状态哨兵 | key 不存在 = 空闲 | Lua 抢占需要一个可判定的初值；且「不存在」语义不明（未初始化 / 空闲 / 过期无法区分） |
| 预热合并进 `tryGrab` 事务 | 独立的状态预热入口 | 独立入口容易漏调用（谁负责调？什么时候调？）；事务内顺带修复是零成本且必然执行的 |
| 状态回退用条件删除 | 写回 `__IDLE__` | 避免旧事务回滚覆盖新事务的写入（见 4.3） |

---

## 六、边界与已知问题

1. **哨兵值是「魔法字符串」**：`__NULL__` / `__IDLE__` / `__TOMBSTONE__`（[08 篇](08-city-code-tombstone.md)）与业务值共享同一个 String 空间。业务值若恰好等于哨兵字符串会语义错乱——靠命名约定（双下划线包裹）防御，没有运行时校验。
2. **`__IDLE__` 的 5 分钟 TTL 是失效兜底**：占位 key 的真实值由事务内修复 + 抢单 Lua 维护；TTL 保证极端时序（写入失败后无人修复）下旧值不会无限存活。
3. **空值哨兵缓存 7 天**：城市表若新增了原本「不存在」的城市，旧哨兵要等 7 天过期（或走墓碑失效，见 [08 篇](08-city-code-tombstone.md)——写路径当前未接线）。

---

## 七、如何验证

```bash
# 1. 查询一个 DB 里没有的城市名
redis-cli GET amap:city_code:nonexistentcity
# 期望：__NULL__（查询一次后写入），TTL ≈ 604800

# 2. 正常城市名
redis-cli GET amap:city_code:hangzhou          # 期望：城市编码
redis-cli TTL amap:city_code:hangzhou          # 期望：≤ 604800

# 3. 司机占位状态：司机接单后
redis-cli GET driver:active:<driverId>         # 期望：订单号（不再是 __IDLE__）
redis-cli TTL driver:active:<driverId>         # 期望：≤ 300（5 分钟）
# 订单完成 / 取消后：key 被条件删除（EXISTS 期望 0）或下次接单时被修复

# 4. 版本 key
redis-cli TTL amap:city_code:version:hangzhou   # 从未 invalidate 为 -2；执行过失效后为 -1
```

---

## 八、延伸阅读

- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 同一个 key 上的墓碑机制：哨兵表达「已知的空」，墓碑表达「此刻不可信」
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— `__IDLE__` 在抢单 Lua 中的角色
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 穿透的另一解法（布隆）与哨兵的对比
- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 缓存 miss 的击穿防护
