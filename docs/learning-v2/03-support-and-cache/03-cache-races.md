# 第 14 章 缓存问题与并发窗口：穿透、击穿、雪崩在项目中长什么样

> 第 13 章把工单池与统计缓存的读、重建、失效走了一遍。本章换一个问题：**当缓存没有挡住请求、或者挡住之后又发生了并发写，会怎样？** 术语只有三个——穿透、击穿、雪崩——但本项目里哪些是真的接了线、哪些只是“理论上可以这样做”，必须顺着调用方查，不能凭类名下结论。
>
> 沿用虚构人物与数据：客服苏晴（`SUPPORT`，`6003003`）、管理员老周（`ADMIN`，`5004004`）；地理编码工具由 OrderAgent / DailyAgent 在模型工具循环中调用。城市名“燕 city”与统计数字都是示意。**本章没有启动服务、运行测试、连接 MySQL/Redis/Redisson 或发送 HTTP/模型请求**；除明确标注“测试源码阅读”的地方，结论都是仓库静态推导，未实测。

## 学习目标

读完本章，你应该能：

1. 用具体请求数量与时间区分穿透、击穿、雪崩，并说出本项目对应的真实调用链与纯假设场景。
2. 追踪 `GeoRegeoTool#geo → CityCodeUtil#getCityCode → Redis Lua → bus_citycode` 的完整读路径，解释空值哨兵、墓碑与版本号各自解决什么。
3. 判断城市码失效/墓碑写入口是否接入真实业务，而不是从 Javadoc 推断；说明“读路径已接线、写路径仅测试接线”的证据。
4. 逐入口确认用户名布隆过滤器是否被 `mightContain` 保护，说明已写入、已重建与已读取是三件不同的事。
5. 复用 S2 的统计缓存，用可达时间线说明删除后旧读回填、晚于 500ms 回填、未执行任务与响应丢失的窗口；区分单次 TTL 与系统整体收敛。
6. 阅读城市码/缓存测试与布隆测试的覆盖缺口，区分源码事实、测试源码阅读、实际运行与未知。

## 前置知识与阅读时间

- 必读：[第 13 章 工单池索引与统计缓存](02-ticket-index-and-cache.md)——统计缓存、双删、版本/锁/TTL 的基础与证据口径。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——`@Transactional` 代理、提交后回调、跨库没有共同事务。
- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——ZSet/TTL/Lua 的 `KEYS`/`ARGV` 与原子边界。
- 相关：[第 6 章 权限与防护](../01-auth/02-authorization-and-defense.md)——布隆过滤器在 A2 中的“现状”结论。
- 阅读时间：约 90～110 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置与测试写完整仓库相对路径。
- **证据约定**：区分“仓库源码事实”“测试源码阅读”“实际运行”。本章未实际运行。

---

## 零、先记住十个事实

1. **穿透**是“查一个根本不存在的数据”：每次请求都穿过缓存打到 DB；**击穿**是“一个热点 key 失效瞬间，大量请求同时打 DB”；**雪崩**是“大批 key 在同一时段失效或 Redis 整体不可用”。判据是数据是否存在、是否单 key 热点、是否批量同时到期，不是“有没有用 Redis”。
2. 城市码读路径只有 **Redis + MySQL 两级**：`CityCodeUtil#getCityCode` 先读版本、再读数据；没有本地 Map 缓存。
3. 城市码数据 key 是 `amap:city_code:{规范化城市名}`，TTL **7 天**；版本 key 是 `amap:city_code:version:{规范化城市名}`，只 `INCR`、**无 TTL**；墓碑值 `__TOMBSTONE__` 存活 **5 秒**；空值哨兵 `__NULL__` 与正常值共用 7 天 TTL。
4. `CityCodeUtil#invalidate` 会 `INCR` 版本并写入 5 秒墓碑，但 `src/main/java` **没有任何业务调用方**，只有 `RedisCacheRegressionTest` 调用。Javadoc 说“事务提交后调用”不等于已经接线。
5. 城市码读路径是接线的：`OrderAgent` / `DailyAgent` 注册 `GeoRegeoTool`，其 `geo` 工具调用 `getCityCode(city)`；`RegeoTool` 虽然构造器接收 `CityCodeUtil`，却既不保存也不使用。
6. 用户名布隆过滤器 key 是 `auth:user:username:bloom`，Redisson `RBloomFilter<String>`，期望插入 100 万、误判率 0.01，参数**硬编码**；DB 真值来自 `sys_user` 中 `role='USER' AND status=1 AND is_deleted=0` 的用户名。
7. `UserUsernameBloomFilterService#mightContain` 在 `src/main/java` **零调用**：注册/创建账号只 `addUsername`，用户名可用性检查和登录直接查 DB；ADMIN 的 `POST /user/rebuild` 会全量重建。
8. 统计缓存的互斥锁只覆盖单 key 重建：锁 5 秒、双检、拿不到锁等 3×60ms 再直查 DB 且不写缓存；锁过期后的第二个写者来自**新调用**，没有版本号。
9. 统计缓存写后是**提交后首次删除 + 500ms 延迟删除**；旧读回填晚于 500ms 会留下旧值，响应丢失、任务未执行、并发重写、淘汰与 TTL 改动都让“何时消失”成为条件结果。
10. Lua 脚本的原子隔离只保证脚本执行期间不被插入命令；**出错不会自动回滚**已经执行的 `INCR` / `SET` / `DEL`。响应丢失时服务端是否已执行未知；本章所有时间线都是源码推导，未实测。

---

## 一、先用数字分清三种问题

### 1.1 穿透：查询根本不存在的数据

假设 Redis 与 MySQL 里都**没有**“燕 city”这条城市码，且 100 次请求**串行**到达、Redis 写入与回填都成功、期间没有失效/并发删除/淘汰，DB 数据也不变化（这些是下面数字成立的前提，不是默认保证）：

- 第 1 次：缓存 miss → 查 `bus_citycode` → 没有 → 把 `__NULL__` 写回缓存（7 天）。
- 第 2～100 次：命中 `__NULL__` → 直接返回 null，不再查 DB。

这是**已有空值哨兵时**的穿透防护。若没有哨兵，100 次都会打到 DB——这才是穿透的典型危害。另一个候选防护是布隆过滤器“不在集合里直接拒绝”，但本项目 `mightContain` 没有业务调用方（第三节）。所以：

- **真实调用链**：城市码的 `__NULL__` 空值哨兵（第二节）。
- **未接线能力**：用户名布隆的读侧拦截（第三节）。
- **教学假设**：把 100 次请求换成 10 万个随机不存在的城市名，每个都生成不同 key——空值哨兵会占满 Redis，布隆也无法区分“合法但不存在的城市名”。这不是当前代码要解决的问题。

### 1.2 击穿：单个热点 key 失效的瞬间

假设统计 key `ticket:statistics:20261116` 正好到期，同一秒来 50 个仪表盘请求：

- 没有保护：50 个请求全部 miss，各自执行 4 条 `selectCount`，数据库瞬间承受 200 次计数。
- 当前实现：一个请求用 `ticket:statistics:lock:20261116` 加锁（5 秒）后查 DB 并写回；没拿到锁的最多等 3×60ms 读缓存，仍 miss 就**自己直查 DB 且不写缓存**。能直接说的是：**在锁未过期期间，多个调用对同一锁键的 `SET NX` 只有一个能成功拿到令牌**。锁 TTL 5 秒且不续期，旧持有者超过租期后仍可能停留在重建代码，新调用也可拿到新锁进入，两者可重叠；单条 Redis 写命令有先后，不等于整个重建过程互斥。它不保证“只有一个请求查 DB”。

这是**单 key 热点**问题。锁与双检的细节在 S2 第 5/6 节，本章第四节复用其结论。

### 1.3 雪崩：大批 key 同时失效或 Redis 整体不可用

假设 10 万个缓存 key 都被设成“创建后 3600 秒过期”，并且在同一分钟写入——一小时后它们同时消失，请求一起涌向 DB。或者 Redis 实例整体不可用，所有 key 一起不可读。

本项目的实际做法是：

| 缓存 | TTL 策略 | 是不是批量同时过期的雪崩防护 |
|---|---|---|
| 统计 `ticket:statistics:{yyyyMMdd}` | 20～30 秒（`20 + nextInt(11)`） | 抖动错开同一 key 的重建时刻；一天只有一个 key，**不是**打散 10 万个 key |
| 工单池 `ticket:pool:*` / `ready:*` | 发布时固定 2 小时，读取不续期 | 整 key 重建 + 写后失效；不是给一批 key 随机 TTL |
| 城市码 `amap:city_code:*` | 数据 7 天、墓碑 5 秒、版本无 TTL | 每个城市一个 key，按写入时刻自然分散；没有专门的随机化 |
| 聊天历史、会话等 | 有条件续期 / 固定 TTL（F4） | 见 F4，不在此重复 |

**结论**：仓库里能看到“短 TTL + 抖动”和“固定 TTL + 写失效”两种策略，但没有一个“给大批量 key 加随机偏移防雪崩”的显式实现；把 10 万 key 同时到期当成项目现状是教学假设，不是源码事实。Redis 整体不可用时，统计的 `get` 异常直接上抛，工单池 `slice` 会降级 DB（S2 5.6），两者行为不同。

### 1.4 三者对照

| 问题 | 判据 | 本项目的真实接线 | 仅假设/未接线 |
|---|---|---|---|
| 穿透 | 数据不存在，每个请求都穿过缓存 | 城市码 `__NULL__` 哨兵 | 布隆 `mightContain` 挡读；随机不存在城市名 flooding |
| 击穿 | 单 key 热点、刚失效 | 统计缓存互斥锁 + 双检 + 等待降级（**仅锁未过期时，同一锁键的 `SET NX` 只让一个调用拿到令牌**） | “锁保证只有一个请求查 DB / 整个重建过程互斥”（不成立：等待超时直查，租期过后新旧写者可重叠；单条写命令有先后不等于过程互斥） |
| 雪崩 | 大批 key 同时失效 / Redis 整体挂 | 统计 TTL 抖动、池固定 TTL + 写失效；池 Redis 读异常回 DB | 10 万 key 随机 TTL 打散；统计 Redis 故障自动回 DB |

---

## 二、城市码读路径：从工具调用到 Lua

### 2.1 真实调用方

`GeoRegeoTool#geo(address, city, toolContext)` 先调 `cityCodeUtil.getCityCode(city)`，再把结果传给高德地理编码接口。`OrderAgent` 和 `DailyAgent` 都在 `ToolCallbacks.from(...)` 中注册 `geoRegeoTool`，并在手写分派的 `case "geo"` 中调用它；`city` 参数缺失时分派代码传空字符串 `""`，`getCityCode` 对 blank 输入直接返回 null，表示全国检索。

`RegeoTool` 的构造器虽然接收 `CityCodeUtil cityCodeUtil`，但方法体没有把这个参数赋给字段，`regeo` 方法也没有城市码逻辑；它既没有在自己的类里使用城市码，也没有出现在上述两个 Agent 的 `ToolCallbacks.from` 中。**不要因为它构造器的签名就说“逆地理编码用了城市码缓存”。**

### 2.2 读路径源码节选

```java
// agentbase/amap/util/citycode/CityCodeUtil.java#getCityCode（真实节选，省略 Javadoc）
String normalized = cityName.trim().toLowerCase(Locale.ROOT);
String key = RedisKeyConstants.amapCityCodeKey(normalized);
String versionKey = RedisKeyConstants.amapCityCodeVersionKey(normalized);
String version = redisTemplate.opsForValue().get(versionKey);
String cached = redisTemplate.opsForValue().get(key);

if (TOMBSTONE.equals(cached)) {
    return queryCityCode(normalized);          // 命中墓碑：查 DB，但不回填
}
if (cached != null) {
    return NULL_SENTINEL.equals(cached) ? null : cached;
}
String code = queryCityCode(normalized);
redisTemplate.execute(RedisScripts.CACHE_CITY_CODE_IF_VERSION_MATCH,
        List.of(key, versionKey),
        version == null ? "0" : version,
        code == null ? NULL_SENTINEL : code,
        String.valueOf(TimeUnit.DAYS.toSeconds(CACHE_TTL_DAYS)));
return code;
```

逐段解释：

1. `cityName` 为 null/blank 直接返回 null；否则 `trim().toLowerCase(Locale.ROOT)` 规范化。`RedisKeyConstants#amapCityCodeKey` / `#amapCityCodeVersionKey` 内部也做同样的规范化，所以 `getCityCode` 与 `invalidate` 拼出的 key 一致。源码注释记录了曾经“key 做 trim、DB 查询用原始入参”造成污染的问题，现在两处使用同一个 `normalized`。
2. **先读 version，再读 data**，两条都是普通 `GET`。version 读到的值在回填时作为 `ARGV[1]`；data 读到的值决定命中/墓碑/miss。
3. 墓碑 `__TOMBSTONE__`：查 DB 直接返回，**不回填**。源码注释给出的理由是：回填会把墓碑覆盖成 7 天正常条目，等于提前关闭竞态窗口。5 秒后墓碑自然过期。
4. 命中 `__NULL__`：返回 null；命中其他值：返回城市码。两者都不查 DB。
5. miss：查 `bus_citycode`（`name` 或 `simple_name` 等于规范化名），然后执行 Lua 尝试回填，**无论脚本返回 1 还是 0，Java 都直接返回刚才查到的 `code`**。返回值被忽略。
6. 全类**没有 try/catch**：Redis 读异常或 DB 查询异常都会从工具调用链向上抛；工具链如何转成 SSE/文本错误属于 C1/C2，本章不推断。

### 2.3 key、TTL 与哨兵

| 键 | 类型 | 值 | TTL | 写入方 |
|---|---|---|---|---|
| `amap:city_code:{name}` | String | 城市码 / `__NULL__` / `__TOMBSTONE__` | 回填 604800 秒（7 天）；墓碑 5 秒 | `CACHE_CITY_CODE_IF_VERSION_MATCH` / `INVALIDATE_CITY_CODE` |
| `amap:city_code:version:{name}` | String | 整数版本字符串 | 无 TTL（只 `INCR`） | `INVALIDATE_CITY_CODE` |

`__NULL__` 只是**某次 `queryCityCode` 返回 null 的缓存结果**：DB 可能没找到匹配行，也可能找到行但 `cityCode` 字段本身为 null；数据库后来完全可能变化，它不是“永久不存在”的证明。`__TOMBSTONE__` 只是调用 `invalidate` 时写进 data key 的短命标记，既不证明当前有写事务，也不检查事务是否已提交。两者放在同一个 data key 上，靠值本身区分，不新增第三个 key。

### 2.4 两个 Lua 脚本

```lua
-- util/RedisScripts.java#INVALIDATE_CITY_CODE（真实节选）
local version = redis.call('INCR', KEYS[2])
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return version
```

| 参数 | 含义 |
|---|---|
| `KEYS[1]` | data key |
| `KEYS[2]` | version key |
| `ARGV[1]` | 写入的值，调用方传 `__TOMBSTONE__` |
| `ARGV[2]` | 墓碑 TTL 秒数，调用方传 `5` |
| 返回 | `INCR` 后的版本号（`Long`）；`CityCodeUtil#invalidate` 不使用返回值 |

```lua
-- util/RedisScripts.java#CACHE_CITY_CODE_IF_VERSION_MATCH（真实节选）
local current = redis.call('GET', KEYS[2]) or '0'
if current ~= ARGV[1] then
    return 0
end
if redis.call('SET', KEYS[1], ARGV[2], 'NX', 'EX', ARGV[3]) then
    return 1
end
return 0
```

| 参数 | 含义 |
|---|---|
| `KEYS[1]` / `KEYS[2]` | data key / version key |
| `ARGV[1]` | `getCityCode` 读到的版本；没读到传 `"0"` |
| `ARGV[2]` | 城市码或 `__NULL__` |
| `ARGV[3]` | 数据 TTL 秒数 `604800` |
| 返回 `1` | 版本一致且 data key 原本不存在，`SET NX` 成功写入 |
| 返回 `0`（第一种） | **版本比较阶段**：`GET version ≠ ARGV[1]`，脚本直接 `return 0`，**尚未执行 `SET NX`**；不能把这种拒绝归因于 NX |
| 返回 `0`（第二种） | 版本相同，但 data key 已有值：`SET ... NX` 因 key 存在返回 nil，脚本 `return 0`，不覆盖（例如墓碑仍在或另一读线程已回填） |

两个脚本都是多 key Lua：在 Redis Cluster 上要求同槽；仓库 key 没有 hash tag，部署拓扑未知。脚本内命令不被其他客户端插入，但中途错误不会回滚已执行的 `INCR` 或 `SET`（见第六节）。

### 2.5 四条路径汇总

| 缓存状态 | 实际动作 | 是否查 DB | 是否回填 |
|---|---|---|---|
| data = 城市码 | 直接返回 | 否 | 否 |
| data = `__NULL__` | 返回 null | 否 | 否 |
| data = `__TOMBSTONE__` | `queryCityCode` 后返回 | 是 | **否**（避免覆盖墓碑） |
| data 不存在 | `queryCityCode` → `CACHE_CITY_CODE_IF_VERSION_MATCH` → 返回查到的值 | 是 | 仅当版本一致且 key 仍为空 |

注意“热点空值”与“不存在数据”在这个实现里共用同一个 data key：`__NULL__` 是**某次查询返回 null** 的结果（未找到行或 cityCode 为 null），带 7 天 TTL；墓碑是失效脚本写入的临时标记，带 5 秒 TTL，不表示当前真有写事务。若 DB 后来新增了城市码而没有任何失效动作，旧 `__NULL__` 可能继续返回 null 直到 7 天 TTL、被淘汰或外部删除——这取决于是否有后续干预，不是无条件保证。

### 2.6 失效写入口：已实现，但业务未接线

```java
// agentbase/amap/util/citycode/CityCodeUtil.java#invalidate（真实节选）
public void invalidate(String cityName) {
    if (cityName == null || cityName.isBlank()) return;
    String key = RedisKeyConstants.amapCityCodeKey(cityName.trim());
    redisTemplate.execute(RedisScripts.INVALIDATE_CITY_CODE,
            List.of(key, RedisKeyConstants.amapCityCodeVersionKey(cityName)),
            TOMBSTONE, String.valueOf(TOMBSTONE_TTL.toSeconds()));
}
```

Javadoc 写的是“城市编码变更的事务提交后调用”。但逐个核验调用方：

- `src/main/java` 中**没有任何** Controller/Service 调用 `CityCodeUtil#invalidate`；
- `src/test/java/.../RedisCacheRegressionTest.java` 的两个城市码用例直接调用它（第 208、215 行）；
- `bus_citycode.sql` 是一份初始化数据，当前仓库没有“运维改城市码”的业务端点。

所以准确说法是：**墓碑脚本与失效方法已实现，读路径也能识别墓碑；但写入口仅在测试中接线，业务侧未接线。** 不能从 Javadoc 推出“城市数据变更会自动失效缓存”，更不能由墓碑推断当前有写事务或它已提交——`invalidate` 本身不检查事务状态。也不能说“墓碑读路径没接线”——读路径是接线的，只是平时不会被业务触发。

---

## 三、用户名布隆过滤器：只写不读的现状

### 3.1 类与参数

```java
// service/base/UserUsernameBloomFilterService.java（真实节选）
private static final long EXPECTED_INSERTIONS = 1000000L;
private static final double FALSE_PROBABILITY = 0.01;

public boolean mightContain(String username) {
    if (!StringUtils.hasText(username)) return false;
    return getBloomFilter().contains(username);
}
public void addUsername(String username) {
    if (!StringUtils.hasText(username)) return;
    getBloomFilter().add(username);
}
public long rebuild() {
    RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.userUsernameBloomKey());
    bloomFilter.delete();
    bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
    List<String> usernames = userAuthMapper.selectUsernamesForBloom();
    if (usernames == null) return 0;
    List<String> normalized = usernames.stream()
            .map(value -> value == null ? null : value.trim())
            .filter(StringUtils::hasText).distinct().toList();
    for (String username : normalized) bloomFilter.add(username);
    return normalized.size();
}
```

- key：`auth:user:username:bloom`（`RedisKeyConstants#USER_USERNAME_BLOOM_KEY`），Redisson `RBloomFilter<String>`。
- 期望插入量与误判率硬编码为 `1_000_000` / `0.01`，不是配置键；`getBloomFilter` 每次调用都 `tryInit`，Redisson 对已初始化的过滤器重复调用通常是幂等的，但本章没有运行验证。
- DB 真值：`UserAuthMapper#selectUsernamesForBloom` 查 `sys_user` 中 `role='USER' AND status=1 AND is_deleted=0 AND username IS NOT NULL` 的用户名，再 trim/去空/去重。
- 没有 try/catch：Redisson 或 DB 异常直接向调用方抛出。

### 3.2 创建、写入、重建分别在哪里

| 动作 | 真实调用方 | 说明 |
|---|---|---|
| 初始化（隐式） | `getBloomFilter` 中 `tryInit` | `addUsername` / `mightContain` 都会触发 |
| 单条写入 | `AuthServiceImpl#registerByEmailCode`（约 104 行，带 `@Transactional`）、`#createAccount`（约 251 行，仅 `role=USER`，无本地事务注解） | 在 `createUser` 之后调用 `addUsername`；结果取决于失败发生在 add 之前、add 执行/响应丢失、还是 add 成功后 DB 回滚，见 3.4；不能概括为“失败路径不写” |
| 全量重建 | `UserController#rebuildUsernameBloom`（`POST /user/rebuild`，`@RequirePermission({"ADMIN"})`） | `delete → tryInit → DB 查询 → 逐条 add`，返回写入条数 |
| 读取判断 | **无业务调用方** | `mightContain` 在 `src/main/java` 只有定义，没有 `contains` 的业务调用 |

逐个入口核对：

- `registerByEmailCode` / `createAccount`：**只写**，不读；
- `isUsernameAvailable`：直接 `userAuthService.findByUsernameAndRole(...)`，不调布隆；
- `loginByPassword → findUserByPrincipal`、邮箱登录、验证码登录、改密：都直接查 DB；
- `UserController` 除 ADMIN 重建端点外，没有把布隆接进任何登录/注册入口。

结论：**这是一个“已实现写入与重建、没有实现读侧拦截”的布隆过滤器。** 把它写成“注册/登录入口已用布隆挡穿透”与源码冲突。

### 3.3 误判、故障与重建边界

- 误报（false positive）：`FALSE_PROBABILITY = 0.01` 是 `tryInit` 时传入的**目标误判概率参数**，不是实测值，也不是“最坏情况下必有约 1% 用户名误判”的保证；实际行为受已插入数量、容量、负载与 Redisson 实现影响，本章未实测。若未来把它接进 `isUsernameAvailable`，直接 `return !mightContain(username)` 可能把可用用户名误判为占用——正确用法应是“判否才可作为肯定不存在的依据，判是仍需查 DB 确认”，当前代码没有这个读调用。
- 判否的可靠性有前提：过滤器必须完整覆盖相关用户名、相关写入都成功、Redis 数据未丢失，且**写入与查询的规范化/数据库匹配语义一致**（`rebuild` 只 trim、去空、去重；DB 查询按 `role='USER' AND status=1 AND is_deleted=0`，不是全量账号）。任一前提不成立，理论上“已加入元素不漏报”的结论对该元素就不适用。`rebuild` 先 `delete` 再逐条 `add`，这段时间窗口里过滤器可能缺少刚注册或尚未加入的用户；若此时真有读侧调用（目前没有），结果不可靠。Redis 数据丢失、手动清空、重建失败同理。
- 故障：Redisson 异常没有兜底；`addUsername` 抛异常与数据库事务的相互影响见 3.4。不要把“异步/不影响主链”当成已实现。
- 数据库仍是注册冲突与登录身份的**真值**：过滤器既不存储用户名明文，也不提供权威答案；当前它连读侧拦截都没有接线。

### 3.4 注册事务与 Redis 写入的边界

`AuthServiceImpl#registerByEmailCode` 带 `@Transactional`；源码顺序是 `userAuthService.createUser(userAuth)`（DB）→ `usernameBloomFilterService.addUsername(username)`（Redis），`addUsername` 的 Redisson 异常没有捕获。按失败位置和事务结果分三种情况：

1. **失败发生在 `add` 之前**（参数空白、校验失败、验证码无效、DB 写入异常等）：`addUsername` 根本没被调用，通常事务代理生效时 DB 回滚，本次不会因这条路径新增过滤器元素。
2. **`add` 本身抛异常或响应丢失**：Redis 端可能已写入（`add` 在服务端执行完但响应丢失），也可能未写入；客户端只看到异常。通常运行时异常从该方法传播出去时 DB 回滚，但 Redis 已成功的写入不随 MySQL 回滚撤销。**不能用 `mightContain` / `contains` 重查来证明这次 `add` 是否成功**：判“可能存在”可能来自误报或此前已存在的元素，重建、数据丢失与规范化差异同样影响结果；它至多是概率性线索，当前代码也没有读侧调用。
3. **`add` 成功之后 DB 回滚**：当前源码在方法体内 `addUsername` 之后只有日志和 `return`；事务代理还需在方法返回后提交，提交失败并回滚时，已成功的 Redis 写入也不会自动撤销。若未来增加操作导致回滚，同样如此；是否提交成功或回滚以实际事务结果为准，未实测。

这是跨库没有共同事务的具体例子，与 F3 一致；以上区分的是源码可达分支，不是运行验证。

`AuthServiceImpl#createAccount` 与 `UserAuthServiceImpl#createUser` 都**没有本地 `@Transactional` 注解**：前者由实际调用链决定是否有外层事务，后者只是 Mapper 调用；若调用方包了事务，DB 写入可能共享该事务，否则各自独立提交。因此不能把注册方法的“DB 回滚 + Redis 可能残留”结论套到 `createAccount`，也不能无条件断言哪一方一定成功。两条路径的共同点是：只有 `role=USER` 才写布隆、异常不捕获、`mightContain` 没有读侧接线。

---

## 四、击穿：复用统计缓存的互斥重建

S2 已经给出统计缓存的完整路径，这里只抽取与“击穿”相关的结论，不重复推导。

1. 热点 key：`ticket:statistics:{yyyyMMdd}`，TTL `20 + nextInt(11)` 秒（20～30 秒）。一天只有一个统计 key，抖动错开的是不同实例/不同重建的写回时刻，不是“打散一批 key”。
2. 命中判断：`opsForValue().get` → JSON 反序列化；空白视为 miss，坏 JSON 会尝试删除后返回 null；`get` 异常没有兜底，直接上抛。
3. 互斥重建：`RedisLock#tryLock(lockKey, 5s)` 用 UUID 令牌 + `SET NX PX`；拿到锁后双检、执行 4 条 `selectCount`、写回缓存、`finally` 用 `RELEASE_LOCK_IF_MATCH` 比对令牌释放；拿不到锁的调用最多等 3 次 × 60ms 读缓存，仍 miss 就直查 DB 且**不写缓存**，本次调用不再抢锁。
4. 锁过期后的第二个写者必须是**新的独立调用**；两个写者各写一次。Redis 对单条写命令有先后顺序，但两个重建过程可以重叠（旧持有者可能还在查 DB），最终值取决于写回等操作的真实先后，不是锁保证的“顺序互斥”；缓存没有版本号（S2 5.4、6.4）。
5. 当前 HTTP 调用链下四条 count 是独立语句、不是同一快照；被有外层事务的调用方调用时是否共享快照取决于隔离级别与 DB（S2 7.3）。
6. 同一锁键在有效期内限制其他调用获得令牌；过期后旧持有者与新持有者可能同时执行重建，也不保证等待者拿到新值。等待超时直查 DB 的分支仍会访问数据库，所以“击穿保护 = DB 只被查一次”是错的。

---

## 五、双删：删除之后仍有旧值回填

### 5.1 从提交到两次删除

写工单的 Service 方法在成功后调用 `afterTicketMutation`，它做两件事：

1. 若 `ticketId` 非空白，注册 `afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId))`（索引整池失效，见 S2）；
2. 调用 `evictTicketStatistics()`，内部再注册一个 `afterCommit`：先 `delete(cacheKey)`，再 `delayedCacheEvictor.evictAfter(cacheKey, 500ms)`。

`afterCommit` 判断的是 `TransactionSynchronizationManager.isSynchronizationActive()`：有同步时只注册、提交后执行；无同步时立即执行。回滚不执行。第一次删除只捕获 `RuntimeException`，`Error` 可能中断后续登记；`DelayedCacheEvictor` 是单 daemon 线程，任务内 `delete` 捕获 `RuntimeException`，调度被拒绝或进程退出时任务可能丢失。

### 5.2 时间线 A：旧读在 500ms 内回填

前提：T1 是统计读请求，T2 提交了一笔工单；T1 的 DB 查询在 T2 删除之后、500ms 之前完成写回。

| 时刻 | T1（读） | T2（写） | 缓存 |
|---|---|---|---|
| t0 | 读 miss，开始 4 条 `selectCount`，读到旧四项 | — | 空 |
| t1 | — | 提交；提交后第一次删除，登记 500ms 延迟删除 | 空 |
| t2 | 写回旧四项，TTL 20～30 秒 | — | 旧值 |
| t3 | 其他读者命中旧值 | — | 旧值 |
| t4 | — | 已登记的延迟删除执行 | 空 |
| t5 | 新的 miss 才可能重建出提交后的值 | — | 视重建结果 |

延迟删除能清掉 t2 写回的旧值，是因为它在 t2 **之后**执行；如果写回晚于延迟删除，就进入下一条时间线。

### 5.3 时间线 B：旧读晚于 500ms 回填

| 时刻 | T1（读） | T2（写） | 缓存 |
|---|---|---|---|
| u0 | 读 miss，DB 查询较慢 | — | 空 |
| u1 | — | 提交；第一次删除；登记 500ms 延迟删除 | 空 |
| u2 | — | 延迟删除执行，此时 T1 尚未写回 | 空 |
| u3 | T1 在 700ms 写回旧值 | — | 旧值，TTL 从 u3 起 20～30 秒 |
| u4 | 其他读者仍命中旧值 | — | 旧值 |

u3 之后旧值能留多久是**条件结果**：只有该次 `SET` 确实成功、此后没有新的工单写入触发删除、没有已登记未执行的延迟删除任务、没有并发读者写入新值、key 未被淘汰且 TTL 未被外部改动时，才可以说它留到 u3 起算的 20～30 秒。删除响应丢失时服务端可能已经删了；后续若有新写入，会再登记新的删除任务。单次写入的 TTL 不是系统整体收敛上界，反复慢写回可以一轮轮重新计时。

### 5.4 事务边界、四条查询与响应丢失

- 事务同步活跃时，删除动作只注册为提交后回调、提交后才执行，回滚不执行；事务同步未激活时，`afterCommit` 立即执行，没有“提交后”的等待。回滚不发生在这条立即执行路径上。
- 重建的四条 `selectCount` 与双删是两个方向相反的动作：一个写回，一个删除；谁先谁后决定旧值是否残留，没有版本号仲裁。
- 第一次删除/延迟删除的 Redis `delete` 响应丢失时，服务端可能已删也可能未删，客户端只看到异常或超时；不能凭异常断言缓存仍有旧值。
- 已登记但尚未执行的延迟删除任务可能稍后执行，即使此后没有新的工单写入；这与“没有新写入就没有下一次删除”是两回事。

---

## 六、城市码回填与失效的两条竞争时间线

先划清**实际实现**与**假设场景**：

- 实际实现：`getCityCode` 读版本、读数据、查 DB、用 Lua 回填；`invalidate` 能写墓碑并 `INCR` 版本。
- 假设场景：某段业务代码在城市编码变更时调用 `invalidate`。**当前仓库没有这样的业务调用方**，只有测试调用。下列时间线因此用来解释机制，不表示生产环境正在发生。

### 6.1 时间线 C：DB 读取期间发生失效（假设业务调用 invalidate）

前提（**miss 路径，与命中路径互斥**）：初始 data key 不存在、version=`v1`；T1 读 `GET version=v1` 后查 DB，当时 DB 返回旧码 `A`；T2 在 T1 查完 DB、执行回填脚本之前成功调用 `invalidate`（仅测试可构造，业务侧未接线），把 DB 更新为 `B` 并失效缓存。

| 时刻 | T1（读，miss 分支） | T2（失效） | Redis / DB |
|---|---|---|---|
| c0 | `GET version` → `v1`；`GET data` → nil | — | 无 data，version=`v1` |
| c1 | `queryCityCode` 读到旧值 `A` | — | DB 此刻仍是 `A` |
| c2 | — | DB 先更新为 `B`；随后 `INCR version` → `v2`，`SET data=__TOMBSTONE__ EX 5` | 版本已变，墓碑写入 |
| c3 | 执行 `CACHE_CITY_CODE_IF_VERSION_MATCH(ARGV[1]=v1, ARGV[2]=A)` | — | 脚本先比较 `current=v2 ≠ v1`，返回 0；**未执行 `SET NX`** |
| c4 | Java 忽略正常返回值，走 `return code`，本次返回 `A` | — | 缓存仍是墓碑；本次响应可能旧于 DB 当前值 `B` |
| c5 | 墓碑 5 秒后过期 | — | 下一次读 miss，按当时 DB 重新查/回填 |

命中路径单独说明：若 T1 在 c0 读到 `data=A`（命中），`getCityCode` 立即返回 `A`，**不会**查 DB 或执行回填脚本；上表 c1 之后对命中 T1 不成立，不能把两条互斥路径拼成一条时间线。若失效发生在 T1 读版本**之前**，T1 读到新版本且 data 是墓碑，走“墓碑分支查 DB 不回填”，同样不会进入 c3。缓存是否被旧值污染、旧值残留多久，取决于初始 data 状态与后续删除/覆盖/TTL，不能由这条时间线保证。

### 6.2 时间线 D：失效写墓碑后，慢读回填被版本拒绝（假设业务调用 invalidate）

前提：data key 为空，version=`v1`；T1/T3 都查“燕 city”；T2 在 T1 的 DB 查询期间调用 `invalidate`。

| 时刻 | T1（慢读） | T2（失效） | T3（快读） | 缓存 |
|---|---|---|---|---|
| d0 | `GET version=v1`，`GET data` → nil | — | — | 空 |
| d1 | 开始查 DB | — | — | 空 |
| d2 | — | `INCR` → `v2`；`SET data=__TOMBSTONE__ EX 5` | — | 墓碑 |
| d3 | 查到 `B`，执行回填脚本（ARGV=v1）→ 返回 0 | — | — | 墓碑未被覆盖 |
| d4 | — | — | `GET version=v2`，`GET data=__TOMBSTONE__` | 墓碑 |
| d5 | — | — | 按墓碑分支查 DB，返回，**不回填** | 墓碑 |
| d6 | — | — | 仅在墓碑 5 秒到期前未被提前删除/覆盖、随后确有请求且该次 DB 查询与回填脚本都成功的前提下，下一次读才可能写入新值 | 可能仍为空或写入新值（不保证） |

d3 的返回 0 发生在脚本的**版本比较阶段**：`current=v2` 不等于 `ARGV[1]=v1`，脚本直接返回，**没有执行 `SET NX`**；不能把这次拒绝说成 NX 冲突。T3 在 d4 看到墓碑后查 DB 且不回填，才避免旧值被写成 7 天。**单独讨论 NX 竞争**：若两个读线程读到的版本都等于当前版本（例如都读到 `v1`，期间没有失效），data key 仍为空，两者执行脚本时版本比较都通过；先到的 `SET NX` 写入并返回 1，后到的因 key 已存在返回 0——这才是 NX 拒绝。Java 对正常返回的 1/0 都不使用。墓碑 5 秒后过期，下一次读回到 miss 路径；能否重建成功取决于当时 DB/Redis 是否正常（城市码回填没有重建锁）。

### 6.3 时间线 E：响应丢失与脚本中途错误（结果未知）

要分清三种完全不同的情况：

1. **脚本执行中途 `SET` 报错**：`INVALIDATE_CITY_CODE` 先 `INCR` 再 `SET`。如果 `SET` 这条命令本身报错，版本可能已经增加而墓碑没写；Java 看到异常，但不能断言版本没变。这是“命令执行失败”，不是“响应丢失”。
2. **客户端收到响应丢失**：整条 Lua 可能已经在服务端执行完（`INCR` 与 `SET` 都成功），也可能执行到一半或完全没执行，客户端只看到超时/异常。**不能只凭响应丢失推断“只执行了 INCR”**，下一次读到的版本/数据取决于 Redis 里的真实状态，须重查 key 才能判断。
3. **脚本正常返回 0/1**：Java 调用方忽略返回值并继续 `return code`；返回 0 表示这次未写入缓存，1 表示写入成功。只有脚本本身**抛出异常**时，Java 才不会继续执行后面的 `return code`，异常会沿 `GeoRegeoTool → Agent` 工具链上抛（如何呈现给客户端属于 C1/C2，本章不推断）。

`CACHE_CITY_CODE_IF_VERSION_MATCH` 的 `SET NX EX` 若已成功但响应丢失，缓存已写入，下一次读可能命中刚写的值；不能把异常等同于“没写”。旧值是否仍存在取决于初始状态、失效是否真的执行以及后续删除/覆盖/TTL，不能保证残留到某个固定时刻。Lua 原子隔离只说明脚本执行期间别的客户端命令不能插入；**出错不会回滚**已经执行的 Redis 命令。多 key 脚本在 Cluster 下需同槽，key 没有 hash tag，部署拓扑未知。

这些都不是“重试保证成功”：墓碑会过期，失效方法没有业务调用方，回填脚本的正常返回值被忽略，Redis/DB 异常没有重试。

---

## 七、随机 TTL、固定 TTL 与适用边界

| 机制 | 参数 | 保护什么 | 不保护什么 |
|---|---|---|---|
| 统计缓存抖动 | `20 + nextInt(11)` 秒 | 错开同一 key 的重建时刻，降低尖峰 | 不是批量 key 打散；不保证到点即正确 |
| 统计重建锁 | 5 秒 UUID 令牌 | 仅在锁未过期期间，对同一锁键的 `SET NX` 只让一个调用拿到令牌 | 租期过后旧持有者仍可运行、新持有者可进入，重建过程可重叠；单条写命令有先后不等于过程互斥；等待者一定拿到新值 |
| 城市码墓碑 | 5 秒 | 失效脚本写入的短命标记，使读线程在该窗口不回填 | 不证明存在写事务或已提交；业务侧无写入口；墓碑过期后的旧值残留 |
| 城市码版本 | `INCR`，无 TTL | **在失效成功、版本未被重置/外部改动的前提下**，让回填脚本拒绝“读版本之后、回填之前失效”的旧快照 | 不保证本次 Java 返回值最新；不覆盖命中路径；不依赖业务自动失效；响应丢失时阶段未知 |
| 工单池固定 TTL | 发布时 2 小时 | 写失效未执行时的兜底过期 | 到点前正确；到点后一定重建成功（S2 7.2） |
| 空值哨兵 | 7 天 | 防止不存在数据反复穿透 | 数据后来新增但没有失效入口时的及时可见性 |

“为什么统计用短 TTL、池用固定 TTL？”源码能支持的是效果层面的解释：统计是单 key 仪表盘，写后由双删主动失效，短 TTL 兜底；池是整 key 重建的排序索引，写后由版本推进失效，固定 TTL 兜底。仓库没有设计决策记录，不编造作者曾比较过哪些方案。

---

## 八、测试阅读：覆盖什么、没覆盖什么

本章只读了测试源码，**没有运行任何测试**；没有连接业务 Redis/MySQL，也没有发 HTTP/模型请求。

### 8.1 城市码测试

| 测试 | 门控、真实/替身 | 断言 | 没覆盖 |
|---|---|---|---|
| `RedisCacheRegressionTest#cityMissCannotOverwriteTombstoneOrRefillAfterTombstoneExpires` | `@EnabledIfSystemProperty(named="test.redis.port", matches="\\d+")`；**真实** `StringRedisTemplate`，`CityCodeMapper` 为 Mockito mock；在 mapper 回调里直接调 `invalidate` | 墓碑写入后读路径不回填；模拟查询耗时超过墓碑 TTL 后，data key 仍为空 | 真实业务写路径、真实 MySQL `bus_citycode`、Redis 网络故障 |
| `RedisCacheRegressionTest#cityCachePreservesNullSentinelAndIsIndependentOfDefaultLocale` | 同上门控；真实 Redis + mock Mapper | 不存在的城市写入 `__NULL__`，两次读取只查一次 DB；默认 Locale 改成 `tr-TR` 不影响 key 生成 | 哨兵 7 天到期后的行为；城市码新增后的失效 |

整类共有 **13 个 `@Test`**：9 个聊天/消息缓存用例、2 个城市码用例、`existingHashAndLockScriptsStillProtectData` 与 `redisLockHelperRejectsContentionAndProtectsNewOwnerAfterExpiry`。`@BeforeEach` 只生成随机 `chatId`，把 `chat:history:{chatId}` 与 `chat:history:version:{chatId}` 两个**键名**加入 `keys`，**不创建**这两个 key；城市码用例另加 `amap:city_code:{city}` / `amap:city_code:version:{city}` 的键名；哈希/锁用例加 `hash-{chatId}` / `lock-{chatId}`；辅助锁用例加 `helper-lock-{chatId}`。`@AfterEach` 对这些键名执行 `redis.delete(...)`（键不存在也无副作用），不用 `FLUSHALL`。城市码 Locale 用例临时把默认 Locale 改成 `tr-TR` 并在 `finally` 恢复。端口由 `test.redis.port` 系统属性注入，连接主机和参数是源码字面量，本章不复制连接值。

### 8.2 布隆与统计

- `src/test/java` 全量检索 `mightContain` **零命中**：没有任何测试覆盖布隆读路径；注册/重建入口也没有对应测试断言。
- 统计缓存读/重建/计数同样没有行为测试；**就统计缓存而言**，`CacheConsistencyRegressionTest` 只验证提交后回调注册、首次删除失败仍登记延迟任务、回滚不失效（S2 8.1）；该类其余用例覆盖 Heap/Redis 租约与延迟删除执行器，不验证统计缓存本身。
- 集成测试清理范围见 S2 8.2：`TicketPoolRedisIntegrationTest` 每轮删 `ticket:pool:*`，`TicketPoolDatabaseIntegrationTest` 创建随机 schema、清表、删库；都与本章城市码 key 不重叠，但同样只能对一次性专用实例运行。

### 8.3 可选隔离实验（本章未运行）

只能在一次性专用 Redis 上做，不要指向共享实例：

```powershell
# 整类运行（未执行）：创建/修改/删除下方清单中的全部 key
mvn -Dtest=RedisCacheRegressionTest -Dtest.redis.port=<ISOLATED_REDIS_PORT> test

# 只跑两个城市码方法（Surefire 方法选择语法，本章未实测）：
mvn '-Dtest=RedisCacheRegressionTest#cityMissCannotOverwriteTombstoneOrRefillAfterTombstoneExpires+cityCachePreservesNullSentinelAndIsIndependentOfDefaultLocale' -Dtest.redis.port=<ISOLATED_REDIS_PORT> test
```

整类运行实际影响（按测试源码逐项核对，全部只针对 `test.redis.port` 指定的实例）：

| 范围 | 创建/修改 | 清理 |
|---|---|---|
| `@BeforeAll` / `@AfterAll` | 建立/销毁 `LettuceConnectionFactory`，连接 `127.0.0.1:<test.redis.port>` | 不删 key |
| `@BeforeEach` / `@AfterEach` | `@BeforeEach` 只生成随机 `chatId`，把 `chat:history:{chatId}`、`chat:history:version:{chatId}` 键名加入 `keys`，**不创建**聊天 key；9 个聊天用例中的 `RedisMemory#append` / `overwriteIfLeaseValid` / `clear` 等才实际创建/修改聊天 List 与版本值 | `@AfterEach` 对 `keys` 中的键名执行删除（不存在的键无副作用） |
| 聊天回填暂存键（整类运行可达） | `overwriteIfLeaseValid` 以随机 token 生成 `chat:history:staging:{chatId}:{token}`，用 MULTI 写 List 并 `EXPIRE 60s`；`OVERWRITE_IF_VERSION_MATCH` 版本匹配时 `RENAME` 到 data key（正常消费），版本不符时 `DEL` | **不在测试 `keys` 清单**：正常由 Lua 消费/删除；Java catch 中尽力 `delete`（失败只忽略）；若暂存键的过期时间已成功设置，则可由 1 分钟 TTL 兜底——不由 `@AfterEach` 保证清除 |
| 两个城市码用例 | `amap:city_code:{city}`（data，可能被写为 `__NULL__` / `__TOMBSTONE__`）、`amap:city_code:version:{city}`（`INCR`）；`CityCodeMapper` 为 mock；第二个用例临时把 JVM 默认 Locale 设为 `tr-TR`，`finally` 恢复 | 两 key 加入 `keys` 后由 `@AfterEach` 删除；Locale 已恢复 |
| `existingHashAndLockScriptsStillProtectData` | `hash-{chatId}`（Hash：`classification=ORDER`、`HASH_SET_WITH_EXPIRE` 写 `locked` 并设 TTL）、`lock-{chatId}`（String：`new-owner`，`RELEASE_LOCK_IF_MATCH`） | 两 key 加入 `keys` 后删除 |
| `redisLockHelperRejectsContentionAndProtectsNewOwnerAfterExpiry` | `helper-lock-{chatId}`（`RedisLock#tryLock` 两次、令牌不同，临时 `EXPIRE 20ms` 观察过期，`unlock` 比对令牌删除） | key 加入 `keys` 后删除 |

不创建数据库对象，不使用 `FLUSHALL`。只跑两个城市码方法时，`@BeforeEach` 仍生成 `chatId` 并把两个聊天键名加入 `keys`、`@AfterEach` 仍对它们执行删除；但这两个用例自身不写聊天 key，因此**不会因初始化创建聊天 key**。城市码方法把自己用到的 data/version 键名加入清理列表。暂存键只出现在聊天回填用例中：整类运行时它可能由 Lua 正常消费/删除、异常时由 Java 尽力删除，或在 TTL 成功设置时由 1 分钟 TTL 兜底，不由测试 `keys` 清单清理。如果实例上还有别的应用 key，应换实例而不是扩大删除范围。不要在业务 Redis 上手工写 `__NULL__` / `__TOMBSTONE__`。

---

## 九、动手验证

### 9.1 只读追踪

**练习 A：一次城市码 miss**

从 `OrderAgent#executeTool` 的 `case "geo"` 追到 `GeoRegeoTool#geo`、`CityCodeUtil#getCityCode`、两个 `GET`、DB 查询和 `CACHE_CITY_CODE_IF_VERSION_MATCH`。回答：若 `city` 参数缺失，会查 Redis 吗？若查到 `__NULL__`，会发生什么？

<details>
<summary>练习 A 参考答案</summary>

分派代码在 `city` 缺失时传空字符串；`getCityCode` 对 blank 输入直接返回 null，不会读 Redis/DB，高德查询按全国检索。city 非空时先规范化，依次 `GET version`、`GET data`；data = `__NULL__` 时直接返回 null，不查 DB，也不重写缓存。miss 时查 `bus_citycode`（name 或 simple_name），再用 Lua 按版本条件回填；脚本返回值 Java 不使用。
</details>

**练习 B：三种问题定性**

1. 100 次查同一个不存在的城市名；2. 统计 key 到期瞬间 50 个仪表盘请求；3. 假设 10 万个 key 设成同一 TTL 同时写入。分别属于哪类问题？项目里有哪些真实保护？

<details>
<summary>练习 B 参考答案</summary>

1 是穿透：真实保护是 `__NULL__` 空值哨兵（7 天）；布隆读路径未接线，不能算保护。2 是击穿：真实保护是统计锁 + 双检 + 3×60ms 等待降级；等待超时仍会直查 DB，所以不是“只查一次”。3 是雪崩：是教学假设，不是当前批量 key 的现状；项目有统计 TTL 抖动与池固定 TTL，但没有“10 万 key 随机偏移”的显式实现。
</details>

**练习 C：接线判断**

只看类名和 Javadoc，`CityCodeUtil#invalidate` 像是“城市变更提交后自动失效”。请给出判断它是否接线的证据与结论。

<details>
<summary>练习 C 参考答案</summary>

搜 `invalidate(` 的调用方：`src/main/java` 零调用，`RedisCacheRegressionTest` 两处（208、215 行）直接调用。读路径确实识别墓碑，但业务侧没有触发墓碑的写路径。结论：脚本与方法已实现、读路径已接线、写入口仅测试接线；不能按 Javadoc 断言业务会自动失效。
</details>

**练习 D：双删时间线**

T1 在 T2 提交后 700ms 才把旧四项写回；T2 的 500ms 延迟删除已执行。旧值会留到什么时候？

<details>
<summary>练习 D 参考答案</summary>

不能无条件说“留到 20～30 秒”。只有该次 `SET` 成功、之后没有新工单写入触发删除、没有已登记未执行的任务、没有并发写回新值、key 未被淘汰且 TTL 未被外部改动时，才可以说它留到该次写入起算的 20～30 秒；若删除响应丢失，服务端可能已删；若有新写入，会有新的双删任务。
</details>

### 9.2 隔离实验见 8.3

只列步骤与影响，不提供业务实例操作。不要运行会写业务数据的命令。

---

## 十、一页复习

**一句话**：穿透看“数据存不存在”，击穿看“是不是单 key 热点刚失效”，雪崩看“是不是大批 key 同时失效或 Redis 整体不可用”；本项目真正接线的是城市码空值哨兵、统计互斥锁/双检/双删、池版本+固定 TTL，布隆读侧与城市码失效写侧都未接线。

**城市码**：

- 读顺序：规范化 → `GET version` → `GET data` → 墓碑/空值/正常值/miss → 查 DB → Lua 版本条件回填（返回值被忽略）。
- key：data `amap:city_code:{name}`（7 天）、version `amap:city_code:version:{name}`（无 TTL）、墓碑 5 秒。
- 失效：`invalidate` 只被测试调用；业务写路径未接线。

**布隆**：

- `auth:user:username:bloom`；`1e6` / `0.01` 是 `tryInit` 的目标参数，非实测误判率或上界；注册/创建账号只 `add`（事务边界见 3.4）；ADMIN `POST /user/rebuild` 全量重建；`mightContain` 零调用。
- 判否可靠性需完整覆盖、写入成功、数据未丢失、规范化/查询语义一致；重建 `delete → add` 有缺漏窗口，读路径未接线；DB 仍是真值。

**统计与双删**：

- 单 key 20～30 秒、锁 5 秒（仅在有效期内同一锁键的 `SET NX` 只让一个调用拿到令牌）、3×60ms 等待后直查不写缓存；租期过后重建过程可重叠，单条写命令先后不等于过程互斥，无版本号。
- 提交后首次删除 + 500ms 延迟删除；回填晚于 500ms 会残留；单次 TTL 不是整体收敛上界。

**边界**：

- Lua 原子隔离不是错误回滚；响应丢失结果未知；多 key Lua 在 Cluster 要同槽，key 无 hash tag。
- 测试只读未运行；整类 13 个用例，`@BeforeEach` 只收集键名不创建聊天 key、`@AfterEach` 删除键名；暂存键 `chat:history:staging:{chatId}:{token}` 不在 `keys` 清单，由 Lua 消费或异常时尽力删除，TTL 成功设置时以 1 分钟过期兜底；只跑两个城市码方法不会因初始化创建聊天 key（见 8.3）。

---

## 十一、自测与答案

**1. 100 次查同一个不存在的城市名，当前代码会打多少次 DB？如果换成 100 个不同的不存在城市名呢？**

<details>
<summary>参考答案</summary>

前提是 100 次请求串行、首次回填成功、期间无失效/并发删除/淘汰、DB 与 key 状态不变。同一个名字：第一次 miss 查一次 DB，写入 `__NULL__`；之后 99 次命中哨兵返回 null，不查 DB。不同名字：每个 key 第一次都 miss，各自查一次 DB 并写 `__NULL__`，最多 100 次；哨兵不解决“大量不同不存在 key”的 flooding，且写入的 key 会占 Redis 空间直到其 7 天 TTL、被删除或淘汰。并发请求、回填失败或响应丢失都会让实际次数偏离这些数字。这是源码静态推导，未实测。
</details>

**2. 城市码读路径里，为什么先读 version 再读 data？版本变化后旧快照会怎样？**

<details>
<summary>参考答案</summary>

回填脚本要把读开始时看到的版本作为 `ARGV[1]`；先读 version 才能在查完 DB 后校验“期间是否失效”。在失效确实成功（`INCR` 已执行、版本未被外部重置）、且 T1 走的是 miss 路径的前提下：若失效发生在读版本之后，当前版本已变，脚本在**版本比较阶段**返回 0（尚未执行 NX），旧快照不会写入缓存；但 Java 忽略正常返回值，T1 仍把自己查到的值返回给调用方，所以“不污染缓存”不等于“本次响应一定最新”。若 T1 是命中路径，它根本不查 DB、不执行回填，不能把两条互斥路径混为一谈。
</details>

**3. `__NULL__` 与 `__TOMBSTONE__` 的寿命和语义有什么不同？命中墓碑时为什么不能回填？**

<details>
<summary>参考答案</summary>

`__NULL__` 只是某次 `queryCityCode` 返回 null（未找到行，或找到行但 `cityCode` 为 null）的缓存结果，带 7 天 TTL；DB 后来可能变化，它不是“永久不存在”的证明。`__TOMBSTONE__` 只是调用 `invalidate` 时写入的短命标记，只活 5 秒，不证明当前有写事务或已提交。命中墓碑时若回填，会把 5 秒标记覆盖成 7 天条目，延长旧快照的可服务时间；所以读路径选择查 DB 直接返回，不回填。墓碑 5 秒后自然过期。
</details>

**4. `CityCodeUtil#invalidate` 是否已经接入业务写路径？给出证据。**

<details>
<summary>参考答案</summary>

没有。Javadoc 声称“事务提交后调用”，但全量检索调用方：`src/main/java` 无调用，只有 `RedisCacheRegressionTest` 两个用例直接调用。读路径能识别墓碑，但生产业务没有触发写入口；不能从注释推断已接线，也不能说“墓碑机制没实现”——是写入口未接线。
</details>

**5. 用户名布隆过滤器保护了哪些入口？`isUsernameAvailable` 是否使用 `mightContain`？**

<details>
<summary>参考答案</summary>

没有保护任何读入口。`mightContain` 在 `src/main/java` 零调用；注册与创建账号只调用 `addUsername`，`isUsernameAvailable` 与登录各入口直接查 DB。ADMIN 的 `POST /user/rebuild` 会全量重建。不要因为存在服务类或 Javadoc 就说“所有入口已保护”。
</details>

**6. 统计缓存拿不到锁的调用会等多久？它会再次抢锁吗？最后查 DB 会写缓存吗？**

<details>
<summary>参考答案</summary>

最多 3 次 `sleep(60ms)` + 读缓存，命中即返回；3 次都 miss 就记 warn 并直查 DB 返回，**不写缓存**；本次调用不会再抢锁。锁过期后要形成两个写者，必须是另一个新发起的独立调用。详见 S2 5.4/6.4。
</details>

**7. 统计缓存的 500ms 延迟删除能否保证旧值 500ms 内消失？给出反例。** 

<details>
<summary>参考答案</summary>

不能。读线程若在第一次删除前查到旧四项，且 DB 查询/写回在 500ms 之后才完成，延迟删除已经执行过，旧值会重新写回；只有后续新写入的删除、并发新值覆盖、TTL 到期、外部删除或淘汰才可能清掉它。删除响应丢失时服务端可能已删，结果未知；已登记未执行的任务仍可能稍后执行。
</details>

**8. `RedisCacheRegressionTest` 使用真实组件还是 mock？它会清理什么？**

<details>
<summary>参考答案</summary>

由 `test.redis.port` 门控，用真实 `LettuceConnectionFactory`/`StringRedisTemplate`，`CityCodeMapper` 是 Mockito mock；整类共 **13 个 `@Test`**（9 个聊天/消息缓存、2 个城市码、1 个哈希锁脚本、1 个 RedisLock 辅助锁）。`@BeforeEach` 只生成 `chatId`，把 `chat:history:{chatId}`、`chat:history:version:{chatId}` 键名加入 `keys`，**不创建**聊天 key；`@AfterEach` 对这些键名执行删除。城市码/哈希/辅助锁用例另加自己的键名。聊天回填用例还会生成 `chat:history:staging:{chatId}:{token}`，它**不在 `keys` 清单**：正常由 Lua 消费/删除，异常时 Java 尽力删除，若暂存键的过期时间已成功设置，则可由 1 分钟 TTL 兜底。Locale 用例临时改 `tr-TR` 并在 `finally` 恢复。整类与方法限定的影响见 8.3；本章只读测试，未运行。
</details>

**9. 回填脚本返回 0 有哪两种原因？Java 会怎样？脚本抛异常时还一样吗？**

<details>
<summary>参考答案</summary>

两种返回 0：① 版本比较阶段 `GET version ≠ ARGV[1]`，脚本直接返回，**根本没执行 `SET NX`**（失效发生在读版本之后）；② 版本相同但 data key 已有值，`SET NX` 因 key 存在返回 nil 后脚本返回 0（例如墓碑仍在或另一个读线程已回填）。只有②才是 NX 拒绝。Java 对**正常返回**的 0/1 都不使用，仍走 `return code`，本次查询不算失败，只是缓存未写入；下一次读按真实状态判断。若脚本**抛出异常**，Java 不会执行后面的 `return code`，异常沿工具链上抛；这与返回 0 是两条不同路径。测试未覆盖这些分支，以上为源码阅读结论。
</details>

**10. 下面哪种说法最准确？（A）项目用布隆防住了用户名穿透；（B）城市码失效已接入业务写路径；（C）统计锁保证只有一次 DB 查询；（D）Lua 原子隔离不等于错误回滚。**

<details>
<summary>参考答案</summary>

D。A 错：`mightContain` 零业务调用；B 错：`invalidate` 仅测试调用；C 错：等待超时后请求仍直查 DB，锁过期后还可能有新调用的第二个写者；D 对：脚本执行期间命令不穿插，但中途错误不回滚已执行的 `INCR`/`SET`，响应丢失时结果未知。
</details>

---

## 十二、常见误区与导航

### 12.1 常见误区

- 把“有 `UserUsernameBloomFilterService` 类”说成“注册/登录已用布隆挡穿透”：`mightContain` 零调用。
- 把 `CityCodeUtil#invalidate` 的 Javadoc 当成接线证据：业务侧无调用方，只有测试调用。
- 把空值哨兵与墓碑混为一谈：`__NULL__` 是某次查询返回 null 的结果（DB 后来可变），`__TOMBSTONE__` 是失效脚本写入的短命标记（不代表一定有写事务或已提交）。
- 把统计互斥锁说成“DB 只被查一次”：等待超时会直查，锁过期后的新调用也会写。
- 把“双删”说成“500ms 内旧值必消失”：回填晚于延迟删除、响应丢失、任务未执行都会留下旧值。
- 把 20～30 秒抖动说成“10 万 key 的雪崩防护”：当前只有一个统计 key，抖动只错开重建时刻。
- 把 Lua 原子性说成跨 Redis/MySQL 事务或错误回滚：都不是；多 key 在 Cluster 还要同槽。
- 把只读测试断言说成“测试通过”：本章没有运行任何测试或中间件。

### 12.2 导航与源码入口

- **上一章**：[第 13 章 工单池索引与统计缓存](02-ticket-index-and-cache.md)——统计缓存、版本/锁/TTL 与双删的来源。
- **下一章**：[第 15 章 锁、版本租约与唯一 ID](04-locks-leases-and-ids.md)——把本章的版本与锁抽象出来，与订单锁、ID 对比。
- **相关专题**：
  - 权限与归属分层 → [第 6 章 权限与防护](../01-auth/02-authorization-and-defense.md)
  - 事务、提交后回调与跨库边界 → [第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)
  - Redis ZSet/TTL/Lua 基础 → [第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)
  - 工单池版本/ready/重建锁 → [第 13 章](02-ticket-index-and-cache.md)

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置和测试写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 城市码读路径 | `agentbase/amap/util/citycode/CityCodeUtil.java#getCityCode/#queryCityCode/#invalidate` |
| 城市码真实调用方 | `agentbase/tool/GeoRegeoTool.java#geo`；`agents/OrderAgent.java#executeTool/#callbacks`；`agents/DailyAgent.java#executeTool/#callbacks`；对照 `agentbase/tool/RegeoTool.java`（注入未使用） |
| 城市码脚本与键 | `util/RedisScripts.java#INVALIDATE_CITY_CODE/#CACHE_CITY_CODE_IF_VERSION_MATCH`；`constant/RedisKeyConstants.java#AMAP_CITY_CODE_PREFIX/#amapCityCodeKey/#amapCityCodeVersionKey` |
| DB 真值 | `domain/entity/CityCode.java`（`@TableName("bus_citycode")`）；`mapper/CityCodeMapper.java`；`src/main/resources/sql/bus_citycode.sql` |
| 布隆过滤器 | `service/base/UserUsernameBloomFilterService.java#mightContain/#addUsername/#rebuild`；`constant/RedisKeyConstants.java#USER_USERNAME_BLOOM_KEY` |
| 布隆写入/重建入口 | `service/impl/AuthServiceImpl.java#registerByEmailCode/#createAccount`；`controller/UserController.java#rebuildUsernameBloom`；`mapper/UserAuthMapper.java#selectUsernamesForBloom` |
| 统计缓存与双删 | `service/impl/TicketServiceImpl.java#getTicketStatistics/#getCachedTicketStatistics/#cacheTicketStatistics/#queryTicketStatisticsFromDb/#evictTicketStatistics/#afterTicketMutation/#afterCommit`；`service/base/RedisLock.java`；`service/base/DelayedCacheEvictor.java` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/RedisCacheRegressionTest.java`（城市码两例）；`CacheConsistencyRegressionTest.java`；S2 的池测试见上一章 |
