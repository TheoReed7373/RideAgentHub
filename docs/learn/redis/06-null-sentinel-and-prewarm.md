# 空值哨兵与状态预热：把「key 不存在」收敛成单一含义

> **Redis 考点**：用哨兵值（sentinel）把「key 不存在」这一种物理状态，收敛成逻辑上唯一的一种含义 —— *本进程此前没关心过它*。既防缓存穿透，又让缓存状态可被判定。
> **来源**：`docs/02-cache-consistency-race.md` 第二节的前置知识；`docs/01-redis-application-points.md` P0-1
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/amap/util/citycode/CityCodeUtil.java`、`src/main/java/com/fancy/rideagenthub/service/base/OrderGrabService.java`
> **代码版本**：当前工作区为 `RideAgentHub` / `com.fancy.rideagenthub`（基准路径 `src/main/java/com/fancy/rideagenthub/`）。本系列撰写于历史快照 `bdc40d7`（旧名 `TaxiAgent`、旧包 `com.fancy.taxiagent`，属已被重写合并的旧分支），文中的 `文件:行号` 与部分代码块是**改造前快照**，整体已漂移；学习时以「类名 + 方法名 + 章节名」定位当前源码，具体行为一律以当前代码为准。

---

## 【当前实现对照 / 本篇勘误】（2026-11-13 代码反向复核）

> 本篇二～八章正文与 `redis-cli` 命令写于改造前快照，**作为缺陷推导教材保留**；凡与本节冲突，一律以本节与当前源码为准。

| # | 正文中的旧说法 | 当前代码事实 |
|---|---|---|
| 1 | 四处称「订单不存在时由 Lua 返回 `-1`」 | `order == null` 时 `tryGrab` **直接返回 `ORDER_NOT_FOUND`，根本不执行 Lua**（`OrderGrabService.java:67-75,97`）；Lua 的 `-1` 只表示 `statusKey != '10'`，`-2` 表示司机非空闲，`1` 才是成功 |
| 2 | 三章把「DB 条件更新兜底」列在 `OrderGrabService.tryGrab` 职责行内 | 该类全文无 `UPDATE`；条件更新在 `RideOrderServiceImpl.java:275-284`，`OrderGrabService` 只做 Lua 预检、占位、回填与回滚 |
| 3 | `__MISSING__` 哨兵、状态缓存 2 小时、占位 6 小时、`warmUpDriverActive` | 当前只有 `IDLE="__IDLE__"` 与统一 `CACHE_TTL=5min`（`OrderGrabService.java:29-30`），预热已收敛进 `tryGrab` 事务 + `afterCompletion`；旧 TTL 期望全部失效 |

### 【原文恢复】六章「边界与已知问题」被删的推导（改造前快照）

> 按 `git show HEAD:` 原样恢复本次修改删掉的 5 条原理性推导。**结论已随代码改变，推导过程仍有学习价值**。

1. **哨兵 TTL 本质上仍是「猜一个有效期」**。`__MISSING__` 的 60 秒是拍出来的 —— 如果真实业务里「订单在 30 秒后被重放创建」，这 30 秒内预检会一直返回 `ORDER_NOT_FOUND`。这是已知的、由 TTL 长度换取的取舍，不是 bug。
2. **哨兵与真实值有理论撞车风险**。如果某个城市编码真的是字符串 `"__NULL__"`，或者某条真实数据恰好等于 `"__MISSING__"`，语义就被破坏了。当前三类哨兵的形态在真实数据里不可能出现，但这属于**约定而非约束** —— 没有代码层的校验去阻止它。
3. **`MISSING_SENTINEL` 只覆盖 `tryGrab` 与 `refreshOrderStatus` 两条路径**。`syncOrderStatus` 写入的永远是真实状态码，不会写哨兵 —— 这是对的，因为它的调用方已经拿到了 DB 权威值。
4. **`warmUpDriverActive` 与 Lua 之间存在窗口**。key 缺失时才回源，回源结果写入后 Lua 才执行。若两次抢单同时命中「key 缺失」，会各自回源一次 —— 多一次 DB 查询，不影响正确性。
5. **哨兵不解决「结构不存在」**。`driver:active` 的每个 key 都是成员级独立设 TTL，不需要 `ready` 标记；但 GEO 池、ZSet 池这种「共享一个 key」的结构必须另加 `ready` 标记 —— 因为 Redis 会在成员清空时**自动删除**这个 key，哨兵模式在这里失效。

**当前对应结论**：第 1/3/4 条随哨兵删除与预热收敛不再适用于抢单路径；第 2 条在 `CityCodeUtil` 的 `__NULL__`/`__TOMBSTONE__` 上仍成立；第 5 条正是 `OrderGeoPool` 用 `:ready` + `:version` 的原因。**验证命令修订**：第 1 组仍可用 `SET amap:city_code:<normalized> "__NULL__" EX 604800` + `GET/TTL`；抢单改为 `SET order:status:123 10 EX 300`、`SET driver:active:7 __IDLE__ EX 300`，再用 2 KEYS / 4 ARGV 的 `GRAB_ORDER_ATOMIC` 观察 `1 / -1 / -2`。

---

## 一、业务场景

项目里有三类「查了才知道」的读请求，它们的共同点是**入参由外部决定，且入参可能指向一条不存在的数据**：

| 场景 | 入参 | 「不存在」的概率 | 不处理的后果 |
|---|---|---|---|
| 高德逆地理编码取城市 adcode | 用户/LLM 给出的城市名（可能写错、可能是"XX市"、可能是空字符串） | 高：LLM 生成的城市名不可控 | 每次请求都穿透到 DB |
| 司机抢单预检订单状态 | `orderId` | 中：伪造/已删除的订单号 | 用不存在的 orderId 可反复刷同一张表 |
| 抢单预检司机占用位 | `driverId` | 低：司机确实可能空闲 | 无法区分「空闲」与「状态丢失」 |

前两类是**缓存穿透**（Cache Penetration）问题：查询一个必然不存在的数据，缓存永远不命中，请求全部落到 DB。第三类更隐蔽 —— 它不是性能问题，而是**语义问题**：`GET driver:active:{driverId}` 返回 nil 时，到底是「这个司机没有进行中的订单」，还是「Redis 被重启/淘汰了，这个 key 从来没被写过」？两者在协议层面完全一样，但处理方式相反：

- 前者应当放行抢单；
- 后者如果放行，一个正在跑单的司机就会被判定为空闲，**一人两单**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 值语义 |
|---|---|---|---|
| `amap:city_code:{cityName}` | String | 7 天 | 城市 adcode |
| 同上 | String | 7 天 | `__NULL__` —— DB 里确实没有这条记录（稳定的查询结果） |
| `order:status:{orderId}` | String | 2 小时 | 订单当前状态码（如 `"10"`） |
| 同上 | String | **60 秒** | `__MISSING__` —— 该订单不存在 |
| `driver:active:{driverId}` | String | 6 小时 | 司机进行中的 orderId |
| 同上 | String | 6 小时 | `__IDLE__` —— 司机确实空闲 |

TTL 常量定义在 `OrderGrabService.java:63`（`ORDER_STATUS_TTL_SECONDS = 2h`）、`:71`（`MISSING_SENTINEL_TTL_SECONDS = 60s`）、`:80`（`DRIVER_ACTIVE_TTL_SECONDS = 6h`）；城市编码的 `CACHE_TTL_DAYS = 7` 在 `CityCodeUtil.java:31`。

两个哨兵常量定义在 `OrderGrabService.java:48` 与 `:55`：

```java
static final String IDLE_SENTINEL = "__IDLE__";       // 司机空闲
static final String MISSING_SENTINEL = "__MISSING__"; // 订单不存在
```

空值哨兵定义在 `CityCodeUtil.java:21`：

```java
private static final String NULL_SENTINEL = "__NULL__";
```

> 注意：`__NULL__` 与 `__MISSING__` 是**同一类东西的两个实现**，都是「查过，但没有」这个事实的缓存。`__IDLE__` 则不同 —— 它缓存的不是「没有」，而是「有，且值为空」。

> 【当前实现】上表是**改造前快照**：`__MISSING__` 常量、60 秒 / 2 小时 / 6 小时三档 TTL 都已从 `OrderGrabService` 删除。当前形态是：
>
> | Key | 结构 | TTL | 值语义 |
> |---|---|---|---|
> | `amap:city_code:{normalized}` | String | 7 天 | 城市 adcode / `__NULL__`（保留） |
> | `amap:city_code:version:{normalized}` | String | **无 TTL** | 版本租约（新增）；`INVALIDATE_CITY_CODE` 递增，`CACHE_CITY_CODE_IF_VERSION_MATCH` 校验后才回填 |
> | 同上（墓碑） | String | **5 秒** | `INVALIDATE_CITY_CODE` 写的墓碑，命中后不回填 |
> | `order:status:{orderId}` | String | **5 分钟（300 秒）** | 订单当前状态码；不存在时**不写哨兵**，由 Lua 返回 `-1` |
> | `driver:active:{driverId}` | String | **5 分钟（300 秒）** | 司机进行中的 orderId / `__IDLE__`（**只剩这一个**哨兵） |
>
> TTL 统一为 `OrderGrabService.CACHE_TTL = Duration.ofMinutes(5)`，写入不续期；`IDLE_SENTINEL = "__IDLE__"` 保留，`MISSING_SENTINEL`、`ORDER_STATUS_TTL_SECONDS`、`MISSING_SENTINEL_TTL_SECONDS`、`DRIVER_ACTIVE_TTL_SECONDS` 均已删除。城市编码侧的 `NULL_SENTINEL = "__NULL__"` 与 7 天 TTL 不变，但读路径多了「墓碑 + 版本租约」两个分支（见 4.1 与 [08 篇](08-city-code-tombstone.md)）。
>
> **为什么会收敛成一档 TTL**：抢单相关缓存只是**快速预检**，正确性由 DB 行锁 + 条件更新兜底。既然不是权威数据，就不需要用不同 TTL 精细表达「不存在结论的有效期」；统一 5 分钟既限制了陈旧状态的最大存活时间，也避免了长 TTL 占位把司机钉死在「忙」。原来 4.3 节「7 天 vs 60 秒」的推导作为**设计取舍教材**仍然成立，保留在下方，但不再是当前代码。

---

## 三、代码落点

> 原表行号与部分方法名（`warmUpDriverActive`、`releaseDriver(String)`）已失效，以下按当前方法名定位。

| 类 / 方法 | 职责 |
|---|---|
| `agentbase/amap/util/citycode/CityCodeUtil.getCityCode` | **读缓存 → 命中 `__NULL__` 返回 null → 命中墓碑则走 DB 且不回填 → 未命中回源 DB → 用 `CACHE_CITY_CODE_IF_VERSION_MATCH` Lua 校验版本后 `SET NX EX`**（不再直接 `set`） |
| `CityCodeUtil.invalidate(String)` | 走 `INVALIDATE_CITY_CODE` Lua：`INCR` 版本 + `SET` 5 秒墓碑。**生产代码 0 调用方**（机制已就位·写路径未接入），仅回归测试调用 |
| `CityCodeUtil.NULL_SENTINEL` | `__NULL__`，保留 |
| `service/base/OrderGrabService.tryGrab` | `@Transactional(MANDATORY)` 事务内预热：两处 `FOR UPDATE`（司机账号行、在途订单行）→ 缓存写 5 分钟状态 / `__IDLE__` → `GRAB_ORDER_ATOMIC` Lua 预检占位 → DB 条件更新兜底；**不再有 `warmUpDriverActive` 独立方法、不再落 `__MISSING__`** |
| `OrderGrabService.releaseDriver(driverId, orderId)` | 签名多了 `orderId`；释放走 `RELEASE_LOCK_IF_MATCH` 校验锁令牌，不再简单 `SET __IDLE__` |
| `OrderGrabService.refreshOrderStatus` / `syncOrderStatus` | 状态与缓存不一致时**删缓存**（不是写 60 秒哨兵）；`syncOrderStatus` 走 `SYNC_ORDER_STATUS` Lua 保证单调推进，数值回退必须先删 key |
| `util/RedisScripts` 的 `INVALIDATE_CITY_CODE` / `CACHE_CITY_CODE_IF_VERSION_MATCH` | 城市编码的墓碑与版本租约（当前两段 Lua） |
| `constant/RedisKeyConstants` 的 `ORDER_GEO_POOL_READY_KEY`、`TICKET_POOL_READY_PREFIX` | 索引「已预热」标记：当前值形如 `{version}:{count}`，不再只是 `"1"`（见 15 / 23 篇） |
| `util/HikariPoolLoader.warmUpHikariPool()` | `@EventListener(ApplicationReadyEvent)` 预热**数据库连接池**，与 Redis 缓存预热无关（见 6.6） |

---

## 四、实现拆解

### 4.1 哨兵解决的其实是「状态不可判定」

> 【当前实现】「把解释 A 显式写进 value」的原理不变，但 `CityCodeUtil.getCityCode` 现在有**四个分支**（改造前只有两个）：
>
> 1. 命中正常值 → 返回 adcode；
> 2. 命中 `__NULL__` → 返回 null（下方代码块讲的正是这条）；
> 3. **命中墓碑**（`INVALIDATE_CITY_CODE` 写的 5 秒标记）→ 视为「有写入正在进行，缓存不可信」，走 DB 且**不回填**；
> 4. 未命中 → 回源 DB 后先取 `amap:city_code:version:{normalized}` 版本租约，再用 `CACHE_CITY_CODE_IF_VERSION_MATCH` Lua「版本匹配才 `SET … NX EX`」；版本不符返回 0、放弃回填，防止墓碑过期后旧快照污染缓存。
>
> 此外 key 与 DB 查询统一使用 `cityName.trim().toLowerCase(Locale.ROOT)` 的 `normalized`，旧版 key 分裂 / 缓存污染 bug 已修复；`.or()` 也已用 `.and(w -> ...)` 包裹。墓碑机制本身**写路径未接入生产代码**（见 [08 篇](08-city-code-tombstone.md) 6.1）。下方代码块是改造前读路径，保留作对照。

如果不写哨兵，一个 key 的**缺失**同时承担了两种互斥的语义：

```
GET amap:city_code:不存在的城市 → nil
    ├─ 解释 A：DB 里确实没有这个城市
    └─ 解释 B：这个 key 从没被查过（或刚被淘汰）
```

哨兵的做法是：把解释 A **显式写进 value**，于是 nil 就只剩解释 B 一种含义。这就是「状态预热」—— 读到 nil 时，处理逻辑是确定的：**回源 DB**。

`CityCodeUtil.java:75-77` 就是这条规则在读路径上的落地：

```java
if (cached != null) {
    return NULL_SENTINEL.equals(cached) ? null : cached;   // 明确"查过且没有" → 直接返回 null，不查 DB
}
// 走到这里说明 key 真的不存在 → 下面回源 DB
```

### 4.2 不缓存空值就一定会有穿透

这一点值得说透：**空值哨兵和防穿透是同一件事**。

假设不做哨兵，查询一个 DB 里不存在的城市名：

```
请求 1 → 缓存 miss → 查 DB → 没有 → 写缓存？如果写 null 就是没写 → 返回 null
请求 2 → 缓存 miss → 查 DB → 没有 → 同样没写 → ...
请求 N → ...
```

每一次请求都命中不了缓存，**每次都要打 DB**。这个 key 的 QPS 等于「错误城市名的请求 QPS」—— 而错误城市名恰恰来自 LLM 生成，完全不可控。这就是缓存穿透。

如果反过来写入 `__NULL__`：

```
请求 1 → 缓存 miss → 查 DB → 没有 → SET key "__NULL__" EX 7d
请求 2..N → 缓存命中 "__NULL__" → 直接返回 null，零 DB 查询
```

穿透被一次性堵住。代价是 Redis 里多了一条「什么都没存」的记录 —— 这笔账几乎总是划算的。

### 4.3 两种 TTL：7 天 vs 60 秒（改造前的推导，保留作教材）

> 【当前状态】下表「`__MISSING__` 60 秒」在代码中**已不存在**：抢单缓存统一 `CACHE_TTL = 5 分钟`，订单不存在时由 Lua 返回 `-1`，不写哨兵；`CityCodeUtil.__NULL__` 的 7 天 TTL 仍是当前实现，但瞬时「不可信」交给 5 秒墓碑 + 版本租约，不再靠猜 TTL。
>
> 之所以保留整节：它讲的「**不存在这个结论的有效期有多长**」是缓存设计里最容易拍错的一个判断，与当前是否写哨兵无关。面试里被问「为什么空值缓存的 TTL 要短」，这仍然是最好的回答框架。

同样是「不存在」的哨兵，两处的 TTL 差了四个数量级，这不是随手写的：

| | `CityCodeUtil.__NULL__` | `OrderGrabService.__MISSING__` |
|---|---|---|
| TTL | **7 天**（与正常值相同，`CityCodeUtil.java:80-85`） | **60 秒**（`OrderGrabService.java:71`） |
| 理由 | 城市表是 SQL 初始化的静态数据，「这个城市名不在表里」是一个**长期稳定的事实** | `orderId` 是**未来可能被真正创建出来**的 —— 重放/补偿/分库分表路由错误等场景下，同一个 ID 稍后可能真的有订单 |

关键在于**「不存在」这个结论的有效期有多长**：

- 城市表的结论有效期 = 下一次运维改数据（可能是几个月后）→ 缓存 7 天不过分；
- 订单的结论有效期 = 下一次有人创建这个订单（可能就在下一秒）→ 缓存 60 秒都算长。

写反了的后果是不对称的：

- 哨兵 TTL **太长** → 数据后来真的出现了，但缓存一直说「不存在」，业务被长期钉死；
- 哨兵 TTL **太短** → 多几次 DB 查询，仅此而已。

所以**哨兵 TTL 宁短勿长**。`OrderGrabService` 的注释把这条写得很直白（`:65-70`）：

> 比正常状态短得多：orderId 在未来可能被真正创建出来（例如重放/补偿场景），不该用一个长 TTL 的哨兵把它长期钉死为"不存在"。

### 4.4 用 `SET __IDLE__` 代替 `DEL`（原理保留，当前改为条件删除）

> 【当前状态】下方「空闲是信息，不是缺席」的原理讨论仍然值得读，但当前释放路径已改为**条件删除**：`releaseDriver(driverId, orderId)` 走 `RELEASE_LOCK_IF_MATCH` Lua 校验锁令牌后才释放，`refreshOrderStatus` 直接删缓存而不是写哨兵，`afterCompletion` 负责对齐 DB 回滚。
>
> 为什么不再无条件写 `__IDLE__`：抢单缓存已降级为 5 分钟的**快速预检**，正确性由司机账号行锁 + 活跃订单 `FOR UPDATE` + 条件更新兜底；此时让一个陈旧值继续存活 5 分钟，不如直接删掉交给下一次事务内预热重建。`IDLE_SENTINEL` 常量仍在（表示「已知空闲」），只是不再由释放路径每次写入。这个变化也说明：**哨兵的价值取决于缓存是不是权威数据** —— 一旦退化成预检，语义就要让位给一致性。

`OrderGrabService.java:191-200`：

```java
public void releaseDriver(String driverId) {
    ...
    redisTemplate.opsForValue().set(
            RedisKeyConstants.driverActiveKey(driverId),
            IDLE_SENTINEL,
            DRIVER_ACTIVE_TTL_SECONDS,
            TimeUnit.SECONDS);
}
```

这里最容易写错的就是顺手写成 `DEL`。区别是：

| 操作 | 释放后 key 的状态 | 下一次抢单 |
|---|---|---|
| `DEL` | 不存在 → 「未知」 | `tryGrab:137-139` 判定为缺失，**多一次 DB 回源**才能确认司机空闲 |
| `SET __IDLE__` | 存在且已知 → 「已知的空」 | 正常路径**零 DB 查询**，直接进 Lua |

`DEL` 在这里不是错，只是把「已知的空」降级成了「未知」。**空闲是信息，不是缺席** —— 这是哨兵模式最核心的一句话。同理，`warmUpDriverActive`（`:270-274`）回源后无论结果是忙是闲都写回一个具体值，而不是「没有就不写」。

### 4.5 同一思路的第三处：索引的「已预热」标记

`RedisKeyConstants.java:127-133` 与 `:186-192` 是哨兵模式在**结构层面**的翻版：

> 之所以需要单独的标记：ZSet 在成员清空后会被 Redis 自动删除，于是"这个状态确实没有工单"与"索引还没建起来"在 `EXISTS` 上无法区分。

GEO 池（`ORDER_GEO_POOL_KEY`）同理：`ZCard == 0` 既可能是「真的没有待接单订单」，也可能是「池子还没建」。加一个 `...:ready` 标记 key，就把这两种情况分开了 —— 与 `__NULL__` 区分「查过了没有」和「没查过」是同一个套路，只是对象从「一条数据」变成了「一个结构」。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 用**哨兵字符串**编码「空」 | 缓存里存 JSON `{"exists": false}` | 项目统一走 `StringRedisTemplate`，裸字符串最省事；哨兵值本身不需要结构 |
| 哨兵用 `__XXX__` 全大写双下划线 | 用 `null` / 空串 | 空串是合法业务值；`null` 无法在 Redis 中表达。带下划线的哨兵**在肉眼和日志里一眼可辨** |
| ~~`__MISSING__` TTL 取 60 秒~~（改造前） | 与状态同取 2 小时 | 推导见 4.3，仍是最好的「空值 TTL 宁短勿长」教材；**当前不写哨兵**，订单不存在由 Lua 返回 `-1`，状态统一 5 分钟 |
| `CityCodeUtil` 的 `__NULL__` 与正常值共用 7 天 | 单独给哨兵一个短 TTL | 城市表的「不存在」是稳定事实，没有短 TTL 的必要；真正的瞬时状态交给 5 秒墓碑 + 版本租约（见 [`08-city-code-tombstone.md`](08-city-code-tombstone.md)） |
| **当前释放路径：条件删除**（`RELEASE_LOCK_IF_MATCH` + `afterCompletion`） | `SET __IDLE__`（改造前）/ 无条件 `DEL` | 缓存已降级为 5 分钟预检、DB 行锁是权威，让陈旧值多活 5 分钟不如删掉重建；原理讨论保留在 4.4 |

### 哨兵 vs 逻辑过期 vs 布隆过滤器

三种防穿透手段常被一起提，但**解决的问题不在同一层**：

| 方案 | 拦截的是什么 | 代价 | 本项目用法 |
|---|---|---|---|
| **空值哨兵** | 已经被查过一次的**具体 key** | 每个不存在的 key 都要在 Redis 里占一份空间 | 当前只剩 `__NULL__`（城市编码）与 `__IDLE__`（司机空闲）；`__MISSING__` 已删除 |
| **布隆过滤器** | 未查询的判断，在**入口**就挡掉 | 有假阳性（需回源兜底）；删除困难，只能重建 | `UserUsernameBloomFilterService`（`auth:user:username:bloom`），见 [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) |
| **逻辑过期** | 不拦截，而是允许返回旧值 + 异步重建 | 接受一段时间的不一致；需要额外的重建锁 | 本项目未使用；缓存 TTL 都走物理过期 |

三者的关系是**互补而非替代**：

- 哨兵的盲区是「**大量不同的**不存在 key」。攻击者用 100 万个不同的随机 orderId 刷接口，每个都会落一条 60 秒的哨兵 —— 穿透是挡住了，但 Redis 里多了 100 万个 key，把内存打爆。**这才是布隆过滤器的主场**：在进入缓存层之前就判断「这个 key 一定不存在」。
- 布隆过滤器的盲区是「删除」，而哨兵天然处理删除后重查。
- 逻辑过期解决的是「**热点 key 同时失效**导致的击穿」，与前两者正交。

本项目在用户名注册场景用了布隆过滤器（判重），在城市/订单场景用了哨兵（判不存在），各自对应自己的盲区。

---

## 六、边界与已知问题

1. **TTL 本质上仍是「猜一个有效期」**。改造前 `__MISSING__` 的 60 秒是拍出来的；当前所有抢单相关状态统一 `CACHE_TTL = 5 分钟`，同样是一个「预检状态最长陈旧多久」的拍板值。区别在于当前**不写哨兵**，且 DB 行锁 + 条件更新才是权威，所以 TTL 猜错的后果只是「多一次回表」，不会把订单长期钉死为不存在。

2. **哨兵与真实值有理论撞车风险**。如果某个城市编码真的是字符串 `"__NULL__"`，或者某条真实数据恰好等于 `"__IDLE__"`，语义就被破坏了。当前两类哨兵的形态在真实数据里不可能出现，但这属于**约定而非约束** —— 没有代码层的校验去阻止它。（旧文档列的第三类 `__MISSING__` 已删除。）

3. ✅ **状态缓存的写入路径已统一**。`syncOrderStatus` 走 `SYNC_ORDER_STATUS` Lua 保证**单调推进**（只允许新状态 ≥ 当前状态，相等时刷新 TTL）；若发生回池到 `CREATED` 这类数值回退，必须先由 `refreshOrderStatus` **删缓存 key**，否则脚本会拒绝覆盖。旧版「`MISSING_SENTINEL` 只覆盖两条路径」的说法已不适用。

4. ✅ **预热窗口已收敛进事务**。`warmUpDriverActive` 独立方法已删除，预热合并进 `OrderGrabService.tryGrab`：`@Transactional(propagation = MANDATORY)` 内先对司机账号行、在途订单行加 `FOR UPDATE`，再写 5 分钟缓存并执行 `GRAB_ORDER_ATOMIC` Lua；回滚经 `afterCompletion` 用 `RELEASE_LOCK_IF_MATCH` 释放占位。两次抢单不会再各自在事务外回源一次。详见 [`14-grab-order-lua.md`](14-grab-order-lua.md)。

5. **哨兵不解决「结构不存在」**。`driver:active` 本身不需要 `ready` 标记（它的每个 key 都是成员级的、独立设 TTL 的），但 GEO 池、ZSet 池这种「共享一个 key」的结构必须另加 `ready` 标记 —— 因为 Redis 会在成员清空时**自动删除**这个 key，哨兵模式在这里失效。当前 `ready` 值升级为 `{version}:{count}`，连「空池」与「数据丢失」也能区分，见 [15 篇](15-nearby-order-geo.md)、[23 篇](23-ticket-pool-zset.md)。

### 6.6 连接池预热 ≠ 缓存预热

项目里确实有一个叫「预热」的组件，但它和本章的「状态预热」**不是一回事**，这是读代码时最容易混淆的一处：

- `util/HikariPoolLoader.warmUpHikariPool()` 监听 `ApplicationReadyEvent`，执行一次 `cityCodeMapper.selectCount(null)`，目的是**把数据库连接池的第一个连接真正建起来**，避免首个业务请求承担 TCP + 认证 + 建连延迟；异常只 `log.warn`，不影响启动。
- 本章的「预热」指的是**把「已知的空 / 已知的状态」写进 Redis**，让后续读不必回源。GEO 池、工单池索引的 `ready` 标记也是这一类。

所以五章表格里「本项目没有缓存预热」说的是「没有开机就把业务数据灌进 Redis 的定时任务」，与 `HikariPoolLoader` 并不矛盾。缓存索引仍然采用**按需重建**：第一次查询抢锁 → 全量回表 → Lua 校验版本后原子发布。详见 [15 篇](15-nearby-order-geo.md)、[23 篇](23-ticket-pool-zset.md)。

---

## 七、如何验证

```bash
# 1. 空值哨兵：查一个不存在的城市（key 用 trim + 小写后的 normalized）
redis-cli GET amap:city_code:<normalized>
# 第一次：nil（未预热）；第二次起：__NULL__
redis-cli TTL amap:city_code:<normalized>              # 期望接近 604800（7 天，未变）
redis-cli GET amap:city_code:version:<normalized>      # 版本租约
redis-cli TTL amap:city_code:version:<normalized>      # 期望 -1（无 TTL）
# 墓碑：写路径未接入生产代码，可用 RedisCacheRegressionTest 触发 CityCodeUtil.invalidate()
# 期望：INCR 版本 + SET 5 秒墓碑；墓碑期内读走 DB 且不回填，过期后由
#       CACHE_CITY_CODE_IF_VERSION_MATCH 校验版本才允许回填

# 2. 订单状态：不存在的 orderId 不再写 __MISSING__ 哨兵
redis-cli GET order:status:<不存在的orderId>           # 期望 nil（Lua 返回 -1）
redis-cli GET order:status:<真实orderId>               # 期望 "10"（待接单）
redis-cli TTL order:status:<真实orderId>               # 期望 ≈300（统一 5 分钟，不是 7200）

# 3. 司机空闲哨兵（常量仍在，但释放路径改为条件删除）
redis-cli GET driver:active:{driverId}                 # "__IDLE__" 或进行中的 orderId
redis-cli TTL driver:active:{driverId}                 # 期望 ≈300（不是 21600）

# 4. 条件删除：用 RELEASE_LOCK_IF_MATCH 校验令牌，而不是无条件 DEL / SET __IDLE__
redis-cli SET driver:active:7 <my-token> EX 300
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 driver:active:7 wrong-token
# 期望 (integer) 0 —— 令牌不匹配，什么都不删
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 driver:active:7 <my-token>
# 期望 (integer) 1 —— 本人释放；下一次抢单由 tryGrab 事务内两处 FOR UPDATE 重新预热
```

> 上面的 TTL 期望已按当前 `CACHE_TTL = 5 分钟` 重算；旧命令里的 `__MISSING__`、7200、21600、`warmUpDriverActive` 全部失效。命令未在有 Redis 的环境实机跑过，落地时以脚本参数为准。

---

## 八、延伸阅读

- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 缓存穿透的另一种解法：布隆过滤器挡在缓存之前
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— `__IDLE__` 在抢单 Lua 里的用法：`active ~= ARGV[2]` 而非 `active == false`
- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 同一个类里的第三种取值语义：5 秒墓碑 + 版本租约（写路径未接入）
- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 哨兵解决了「读不到」，没解决「读到旧的」
- [`09-lease-token.md`](09-lease-token.md) —— 版本号是另一种「状态编码」；当前版本 key 用 `PERSIST` 永久保留
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 22 个主脚本 + 11 个内联脚本的清单
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— `order:geo:pool:ready` 预热标记（当前值为 `{version}:{count}`）
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— 工单池的版本令牌与空池语义
- [`README.md`](README.md) —— 术语与状态标注规范
- [`../../01-redis-application-points.md`](../../01-redis-application-points.md) —— P0-1 抢单模型的原始盘点（已附落地状态）
