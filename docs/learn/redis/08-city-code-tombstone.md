# 墓碑机制：用状态标记替代猜时间

> **Redis 考点**：失效一个长 TTL 缓存时，直接 `DEL` 会给并发读线程留下回填旧快照的窗口，且窗口多长无法预测 —— 改为写入一个短 TTL 的「墓碑」标记，读线程看到墓碑就知道此刻不可信。
> **来源**：`docs/01-redis-application-points.md` §2.3（缓存一致性与竞态）；`docs/02-cache-consistency-race.md` §2.2 的竞态推导
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/amap/util/citycode/CityCodeUtil.java`、`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（`INVALIDATE_CITY_CODE` / `CACHE_CITY_CODE_IF_VERSION_MATCH`）

---

## 一、业务场景

城市编码缓存 TTL **7 天**（高德行政区划数据，低频变更、高频读取）。失效它的竞态：

```
写线程：城市数据变更，DEL key
读线程：key 不存在 → 查 DB（此刻写尚未可见，拿到旧值）→ set 旧值 + 7 天 TTL
```

**延迟双删在这里失效**（见 [07 篇](07-delayed-double-delete.md)）：双删的第二次删除靠「sleep 一段再删」，但这里无法预测读线程多久后回填——查 DB 的耗时可以估，但「读线程恰好在 DEL 前查走、在 DEL 与第二次 DEL 之间回填」的窗口没有上界。**把『猜时间』换成『看状态』**：写线程不删 key，而是写入一个短 TTL 的墓碑 `__TOMBSTONE__`（5 秒）：

- 读线程看到墓碑 → 知道「此刻有写入正在进行，缓存不可信」→ 查 DB 但**不回填**；
- 墓碑 5 秒后自动过期 → 缓存回到正常 miss 状态。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `amap:city_code:{name}` | String | 7 天 | 正常值 / `__NULL__` 空值哨兵 / `__TOMBSTONE__` 墓碑（5 秒） |
| `amap:city_code:version:{name}` | String（整数） | **无 TTL（永久）** | 版本号：失效时 `INCR`，兜住墓碑过期后的迟到回填 |

单 key 承载三种值语义（正常 / 空 / 墓碑）靠值区分，而非三个 key——墓碑与哨兵都是「瞬时可判定的短状态」，不值得独立的 key。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `CityCodeUtil.getCityCode` | 规范化（`trim().toLowerCase`）→ 读版本与数据 → 命中墓碑：查 DB 不回填；miss：查 DB 后版本校验回填 |
| `CityCodeUtil.invalidate` | `INVALIDATE_CITY_CODE` Lua：`INCR` 版本 + 写 5 秒墓碑 |
| `RedisScripts.INVALIDATE_CITY_CODE` | 原子「版本递增 + 墓碑写入」 |
| `RedisScripts.CACHE_CITY_CODE_IF_VERSION_MATCH` | 原子「版本校验 + `SET NX EX` 回填」 |
| `CityCodeUtil.queryCityCode` | DB 查询：`.and(w -> w.eq(name).or().eq(simpleName))`，`and()` 包裹 OR 组 |

---

## 四、实现拆解

### 4.1 读路径：三种命中 + 一次回填

```java
String normalized = cityName.trim().toLowerCase(Locale.ROOT);   // key 与 DB 同值
String version = redis.get(versionKey);
String cached  = redis.get(key);

if ("__TOMBSTONE__".equals(cached)) {
    return queryCityCode(normalized);        // 墓碑：查 DB，绝不回填
}
if (cached != null) {
    return "__NULL__".equals(cached) ? null : cached;
}
String code = queryCityCode(normalized);
redis.execute(CACHE_CITY_CODE_IF_VERSION_MATCH,
        List.of(key, versionKey),
        version == null ? "0" : version,
        code == null ? "__NULL__" : code,
        "604800");
return code;
```

**命中墓碑为什么不回填**：回填不仅会写入「未经确认的值」，更严重的是把墓碑覆盖成一个 7 天 TTL 的正常条目——**墓碑提前失效，竞态窗口重新打开**。

### 4.2 版本号：墓碑过期后的第二道防线

墓碑只有 5 秒。极端时序：读线程在墓碑写入前查走旧快照、卡顿超过 5 秒后才回填——墓碑已过期，回填看到的可能是正常缓存或 miss。**版本号兜住这一支**：

`INVALIDATE_CITY_CODE` 在写墓碑的同时 `INCR` 版本 key；读线程回填前捕获的版本号已经过期，`CACHE_CITY_CODE_IF_VERSION_MATCH` 比对当前版本不符，直接拒绝。版本 key 无 TTL，**不会随数据一起过期归零**——版本号是「失效时钟」，时钟可以停，不能倒转（同 [09 篇](09-lease-token.md) 的推导）。

### 4.3 key 规范化：为什么 trim 和大小写都要管

`getCityCode` 对城市名统一 `trim().toLowerCase(Locale.ROOT)`，**cache key 与 DB 查询用同一个规范化值**。若 key 做了规范化而 DB 查询用原始入参：MySQL 8 默认的 NO PAD 排序规则（`utf8mb4_0900_ai_ci`）下 `"北京 "` 与 `"北京"` 的查询结果不同，却共用同一个 key——缓存污染。

`invalidate` 内部同样做 `trim().toLowerCase`，失效的 key 与读取的 key 必然一致。

### 4.4 写路径未接线（部分实现，如实标注）

`invalidate()` **当前没有生产调用方**——机制、两段 Lua、回归测试（`RedisCacheRegressionTest` 的墓碑用例）齐备，但城市表的写路径（`bus_citycode.sql` 导入、运维改数）没有接入失效调用。当前生效的防护是读路径的版本校验与 `__NULL__` 哨兵；「城市数据变更后主动失效」需要在引入城市表写入的地方补 `invalidate` 调用。**不要把墓碑描述为已闭环的一致性方案。**

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 墓碑（短 TTL 标记） | 延迟双删 | 回填窗口不可预测时「猜时间」失效；墓碑用状态替代时间（见一） |
| 墓碑 5 秒 TTL | 更长 / 更短 | 需覆盖「并发读线程查完 DB、正在回填」的窗口；城市编码是低频运维数据，写入期间让读请求短暂穿透 DB 完全可接受 |
| 版本 key 无 TTL | 与数据同 TTL | 版本归零后重建会与在途租约撞车（完整推导见 [09 篇](09-lease-token.md) 4.7） |
| 墓碑命中不回填 | 命中墓碑也回填 | 回填会覆盖墓碑，使失效标记提前消失 |
| 原子「INCR 版本 + 写墓碑」 | 两条独立命令 | 两条命令间崩溃会留下「版本未动、墓碑已写」或反过来的不一致中间态 |
| `.and()` 包裹 OR 组 | 直接 `.eq().or().eq()` | 裸 OR 会把后续追加的外层条件卷进 OR 分支，改写 SQL 语义 |

---

## 六、边界与已知问题

1. **写路径未接线**（4.4）：`invalidate` 无生产调用方，是「机制已就位、待接线」状态。
2. **墓碑期内读请求全部穿透 DB**：这是墓碑的固有代价——用短窗口的穿透换「不回填旧快照」。低频写入场景代价可忽略；高频写入场景应换失效-重建模型（[15 篇](15-nearby-order-geo.md)）。
3. **三种值共享 String 空间**：`__NULL__` / `__TOMBSTONE__` / 城市编码值靠命名约定区分，无运行时校验。
4. **版本 key 不回收**：普通查询不会创建版本 key，只有 invalidate 会 INCR 创建；城市名参数未限定为已有城市，因此不能把版本 key 空间视为固定万级；但把这套模式搬到无界 key 空间（如 chatId）时要重新评估（见 [19 篇](19-multi-level-cache.md)）。

---

## 七、如何验证

```bash
# 1. 正常查询后
redis-cli GET amap:city_code:hangzhou              # 期望：城市编码
redis-cli TTL amap:city_code:hangzhou              # 期望：≤ 604800
redis-cli TTL amap:city_code:version:hangzhou      # 期望：从未 invalidate 时 -2；执行过 invalidate 后 -1（无 TTL）

# 2. 手工触发失效（当前只有测试调用 invalidate；可手工模拟 Lua 效果）
redis-cli EVAL "local v = redis.call('INCR', KEYS[2]);
                redis.call('SET', KEYS[1], '__TOMBSTONE__', 'EX', 5);
                return v" 2 amap:city_code:hangzhou amap:city_code:version:hangzhou
redis-cli GET amap:city_code:hangzhou              # 期望：__TOMBSTONE__，TTL ≤ 5
redis-cli GET amap:city_code:version:hangzhou      # 版本已 +1

# 3. 墓碑 5 秒后自动过期
sleep 6
redis-cli EXISTS amap:city_code:hangzhou           # 期望 0（回到 miss 状态）

# 4. 大小写 / 首尾空格归一
redis-cli GET amap:city_code:hangzhou              # 用 "HangZhou "、HANGZHOU 查询后 key 相同
# KEYS 断言时注意排除 :version: key
redis-cli KEYS "amap:city_code:*" | findstr /V ":version:"
```

---

## 八、延伸阅读

- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 「猜时间」方案及其失效条件
- [`09-lease-token.md`](09-lease-token.md) —— 版本号语义的完整展开（为什么时钟不能倒转）
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 同一个 key 上的空值哨兵（「已知的空」vs「此刻不可信」）
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 本篇两段脚本的速查表
