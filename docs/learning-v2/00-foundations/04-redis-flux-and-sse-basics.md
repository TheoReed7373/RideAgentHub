# 第 4 章 Redis 与流式响应基础：两次“不是一次返回”的过程

> 本章把两个容易一起出现、但不是同一条业务因果链的主题放在同一篇：**第一部分讲 Redis 最小基础**，主线是“登录态、司机心跳、订单超时待办怎么存在 Redis 里”；**第二部分讲对话流最小基础**，主线是“一条用户消息怎么变成一段逐步输出的响应”。两部分各有场景与调用链，不硬说成“Redis 导致 SSE”。
>
> 本批不拆成两篇：两部分连续写在本文件里，后续引用仍指向 F4。本章只建立基础；完整工具循环、RAG、确认状态机、各专题中的锁/版本推导由后续各章主讲（见文末相关专题链接）。没有启动项目、没有连接 Redis/模型、没有发 HTTP 请求；所有运行时行为均标为静态推导或未实测。

## 学习目标

读完本章，你应该能：

1. 分别说出 `String`、`Hash`、`Set`、`List`、`ZSet`、`GEO` 在本项目的一处真实使用，包含 key、值/字段/score/member、调用者以及数据能否恢复。
2. 区分 **key 的 TTL**、**业务结束**、**成员超时**和**有条件续期**；不把所有 Redis 数据都叫“缓存”，也不承诺 Redis 丢失后无条件恢复。
3. 追踪一个 Redis Lua 脚本的调用方、`KEYS` / `ARGV`、返回值和 Java 分支；解释脚本执行期间的原子隔离，以及脚本运行错误不具备关系型事务自动回滚。
4. 说清普通 HTTP 响应与流式响应的区别；解释 `Flux`、`Publisher`/`Subscriber`、订阅、取消、终止信号和 `AgentEvent` 类型；不把“方法返回 `Flux`”等同于“整个应用使用 WebFlux”。
5. 从静态源码说明客户端连接、后台订阅、单轮执行锁与终态释放的联系；区分单次模型流超时和整轮时长，并列出源码不能证明的部分。

## 前置知识与阅读时间

- 需要：读完[第 1 章 业务地图](01-project-map.md)、[第 2 章 请求路径与配置](02-request-path-and-config.md)、[第 3 章 数据存在哪，事务管到哪](03-data-and-transactions.md)。会读 Java 方法与注解即可。
- 不需要：Redis、Lua、Reactor、SSE、WebFlux 的预备知识。首次出现的术语就地解释。
- 阅读时间：约 100～120 分钟（含两部分练习与自测）。可以分两次读：第一～七节、第八～十三节。
- **路径约定**：Java 类使用简写，基准是 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、Python 脚本写完整仓库相对路径。代码块注释给完整路径；定位优先用“类 + 方法”，不依赖行号。
- **证据约定**：“仓库实现”来自当前源码；“框架规则”会注明 Spring Framework / Spring Boot / Reactor 的精确版本或手册；“测试阅读”表示读过断言但未运行；“静态推导，未实测”表示没有启动服务或发请求；不能从仓库证明的客户端条件和线格式会明确限定。

---

## 零、先固定四条来自 F3 的边界

F3 已通过复审，并校正了四处事实。本章会把它们当作已知，不重写：

1. **事务代理新建与加入外层的返回时点不同**：如果代理新建并拥有物理事务，提交/回滚、回调与清理完成后调用者才拿到结果；如果只是加入已有外层事务，本次参与调用可以先返回外层方法，物理事务最终由最外层边界提交/回滚。Redis 写入不在 MySQL 事务里。
2. **接单成功路径的顺序**：`driverAcceptOrder` 先条件更新，再注册 `syncOrderStatus`、`orderGeoPool.remove`，**随后**在有坐标时直接执行 `driverGeoIndex.refreshIfExist`，**最后**才注册 `orderDelayQueue.schedule`。位置刷新发生在延迟队列动作注册之前，且是提交前的 Redis 直接写入；条件更新自身抛异常时还没有走到位置刷新。
3. **超时处理不是通用 Redis 恢复器**：`OrderTimeoutJob` 扫描 MySQL 中满足条件的订单并调用 `handleTimeoutOrder`；状态变更时可能间接同步/删除状态缓存、失效 GEO 池、释放司机占位或重新排队，但这不等于“扫描恢复所有 Redis 数据”。
4. **数据库读的可见性**：RC 普通一致性读只看其他事务已提交数据，但能看到本事务自己的修改；`FOR UPDATE` 锁定读也基于当前可用记录和本事务自己的修改。那是 MySQL 的读语义，不能套用到 Redis：Redis 没有事务快照读这一层。

---

# 第一部分：Redis 最小基础

## 一、场景 A：登录后，司机上线，订单开始等超时

> 人物与 ID 均为虚构。

林小雨刚登录，服务端签发一个令牌；司机老陈打开接单页并上报位置；系统给一单新订单登记“15 分钟没人接就取消”。这三件事都涉及 Redis，但性质不同：

- 登录令牌是**只有 Redis 一份的登录态**；丢了要重新登录。
- 司机在线位置是**只有 Redis 一份的实时状态**，还带成员级心跳；丢了司机就离线。
- 订单状态缓存、附近订单池、工单池等是**可由 MySQL 重建的索引/缓存**。
- 延迟队列里的待办没有完整持久副本，数据库扫描只能按状态和时间**部分兜底**。

先问一个问题：为什么这些不都放进 MySQL？因为它们的用途不同：加速查询、表达短时在线状态、做协调与排队。Redis 是内存键值库，读写延迟低，提供几类数据结构和一次执行多个命令的 Lua；但它不是 MySQL 的“自动同步副本”。

## 二、最小概念：key、值、TTL、序列化、原子性

### 2.1 key 与六种数据结构

Redis 的基本单位是 **key → value**。key 是字符串名字，例如 `auth:token:abc`；value 的形态由数据结构决定：

| 结构 | 最小解释 | 本项目常见字段 |
|---|---|---|
| `String` | 一个 key 存一个字符串/数字值 | `auth:token:{token}` 存 JSON；`order:status:{orderId}` 存状态数字 |
| `Hash` | 一个 key 内再放多个 `field → value` | `user:loc:{userId}` 的 `latitude`/`longitude`/`address` |
| `Set` | 无序、去重的 member 集合 | `auth:user_tokens:{userId}` 存该用户所有令牌字符串 |
| `List` | 按插入顺序排列的字符串序列 | `chat:history:{chatId}` 按时间追加消息 JSON |
| `ZSet` | 每个 member 带一个浮点 `score`，按 score 排序 | `delay:order:timeout` 的 score 是到期毫秒时间戳 |
| `GEO` | 基于 ZSet 的经纬度集合，可按半径查询 | `driver:geo:online` 的 member 是 driverId，`GEOADD` 存坐标 |

`StringRedisTemplate` 只处理字符串；对象要先**序列化**。例如 `TokenServiceImpl` 用 `ObjectMapper.writeValueAsString(token)` 把 `UserToken` 变成 JSON 字符串，`RedisMemory` 用 `MessageParser.parse(message)` 把消息变成 JSON 字符串，`DriverGeoIndex` 用 `BigDecimal.toPlainString()` 传坐标。读回来时再按对应格式解析；Redis 不知道 Java 类型。

### 2.2 TTL 不是业务寿命

**TTL（Time To Live）**是 Redis key 的剩余存活时间，到期后 Redis 自动删除整个 key。它和下面三件事不同：

- **业务结束**：订单取消、用户确认消费、司机主动下线，代码主动 `DEL` 队列成员或 `HDEL`/`ZREM` 某个成员；key 本身可能还在。
- **成员超时**：共享 key 无法给每个 member 单独设 TTL，于是把“最后心跳时间”放进 Hash，读取时按时间差清理某个 member。
- **有条件续期**：某些写入会刷新 key 的 TTL；某些索引刻意只在发布时设一次，后续读取不续期。

Redis 的 `EX` / `EXPIRE` 单位是**秒**，`PX` / `PEXPIRE` / `PTTL` 单位是**毫秒**。ZSet 的 `score` 是业务时间戳，不是 TTL；例如延迟队列的 member 不会因为 `score` 到点自动消失，要由 `claimDue` 主动领取并移除。

### 2.3 原子性要限定范围

单条 Redis 命令（如 `SET key value EX 60`）是原子执行的：执行期间不会插入另一条命令。但“先 `GET`，再在 Java 里判断，再 `SET`”是三次往返，中间可能有别的客户端插入。**Lua 脚本**把多次 `redis.call` 放进服务端一次执行，期间不会被其他 Redis 命令穿插。

这只保证 **Redis 侧的原子隔离**：

- 不保证 Redis 与 MySQL 一起提交或回滚；
- 脚本中途报错不会像关系型事务那样撤销已执行的命令；
- 脚本返回值只是给 Java 调用方一个分支依据；
- 业务幂等性要另外设计，例如用唯一 member、令牌或状态条件。

---

## 三、六种结构在本项目的真实落点

下面每个例子都要同时看 key、值、使用者、寿命与恢复来源。`constant/RedisKeyConstants.java` 集中定义大部分 key；也有少数 key 由业务类自行拼接，例如 `TokenServiceImpl.versionKey()` 的 `auth:session-version:{userId}`、`ToolResponseMemory.key()` 的 `chat:tool:v2:{chatId}:{callId}`。

| 结构 | 真实 key 与值 | 使用者 | 寿命 / 是否刷新 | 恢复来源 |
|---|---|---|---|---|
| `String` | `auth:token:{token}` → `UserToken` JSON | `TokenServiceImpl.saveToken/getToken`；`AuthTokenInterceptor` 读取 | `SET ... 'EX', ARGV[2]`，`auth.token-ttl-seconds=604800` 秒；每次签发设置，不滑动续期 | **不能从 MySQL 恢复**。`getToken` 回查 MySQL 只校验账号/角色/密码戳，令牌本体只在 Redis |
| `String` | `order:status:{orderId}` → `"10"` / `"20"` 等状态 | `OrderGrabService.tryGrab/syncOrderStatus`；`RideOrderServiceImpl` | `CACHE_TTL=5min`；`SYNC_ORDER_STATUS` 单调推进时 `EX` 300 秒；`refreshOrderStatus` 删除 | 可从 `sys_ride_order` 回表；旧值未过期时不会立即回源 |
| `Hash` | `user:loc:{userId}` 的 `latitude`/`longitude`/`address` | `UserServiceImpl.saveUserLocation/getUserLocation`；多个 Agent 工具读取 | **无 TTL**，也没有 MySQL 写入 | **只有 Redis 一份**，丢失后需用户重新上报 |
| `Hash` | `chat:info:{chatId}` 的 `classification`、`locked`、`quoteId`、`break` 等 | `ChatServiceImpl`、`OrderAgent`、`OrderConfirmation`、`ChatManager` | 混合：普通 `HSET` 不设过期；`lockChat` 用 Lua 设 3600 秒；`quoted` 设 24h；`restoreChat` 可 `persist` | `locked` 有 MySQL 镜像；报价/确认/粘滞等槽位没有完整 DB 镜像，丢失后要重新走流程 |
| `Hash` | `driver:online:beat` 的 `field=driverId`，`value=最后心跳毫秒` | `DriverGeoIndex.update/location` | **key 无 TTL**；`ONLINE_TTL=60s` 是读取/刷新时逐成员判断 | 只有 Redis 一份，丢失即司机离线 |
| `Set` | `auth:user_tokens:{userId}` 的 member = token | `TokenServiceImpl.SAVE/REVOKE/REMOVE` | 每次 `SAVE` 用 `EXPIRE` 设置为 `tokenTTL*2` 秒（默认 1209600） | 只有 Redis 一份；撤销时整体删除 |
| `List` | `chat:history:{chatId}` 的值是 `MessageParser.parse(message)` JSON | 主链：`MessageMemory#save` → `redisMemory.appendIfVersionMatch`，`MessageMemory#get` → `redisMemory.getAll` + Java 消息窗口；L2 缺失时 `MessageMemory#get` 调 `overwriteIfLeaseValid` 回填，`RedisMemory#getLastN/#append` 只是辅助/测试路径 | 当前会话 24h、非当前 30min；回填 24h；暂存 key 1min；版本 key `PERSIST` | **可**从 MySQL `sys_messages` 通过 `MessageMemory` 回填，且回填带版本号租约 |
| `ZSet` | `delay:order:timeout`，score = 到期毫秒时间戳，member = orderId | `OrderDelayQueue.schedule/cancel/claimDue`；`OrderTimeoutJob` | **key 无 TTL**，score 不是 TTL；同 orderId `ZADD` 覆盖旧 score 即“改期” | 条目只在 Redis；`OrderTimeoutJob` 按 MySQL 状态/时间/批次做有限补偿，不恢复完整 ZSet |
| `ZSet` | `chat:ratelimit:{userId}`，score = Redis `TIME` 毫秒，member = UUID | `ChatRateLimiter.tryAcquire` → `SLIDING_WINDOW_RATE_LIMIT` | 所有调用先 `ZREMRANGEBYSCORE` 清理并 `ZCARD` 计数；**仅放行分支**才 `ZADD` 并 `PEXPIRE` 窗口毫秒，超限 `return -1` 前不登记、不刷新 TTL | 只有 Redis 一份；Redis 异常或返回 `null` 只让限流器本次放行，不保证整个聊天可用 |
| `ZSet` | `ticket:pool:{statusCode}`，score = 优先级权重 + 更新时间毫秒，member = 19 位补零 ID | `TicketPoolIndex.slice/readSlice` | 发布时固定 `PEXPIRE` 2h；后续读取不续期 | 可从工单表全量重建 |
| `GEO` | `driver:geo:online`，member = driverId，值为坐标 | `DriverGeoIndex.goOnline/refreshIfExist/location` | **key 无 TTL**；成员级 60s 心跳与 `driver:online:beat` 联动清理 | 只有 Redis 一份 |
| `GEO` | `order:geo:pool`，member = orderId，值为订单起点 | `OrderGeoPool.search/read`；提交后由 `add/remove` 失效 | 发布时固定 2h；不因单笔写入续期 | 可从 MySQL `sys_ride_order` 中 `CREATED` 订单整池重建 |

### 3.1 两个 Hash：一个没有 TTL，一个有成员级心跳

`user:loc` 是最容易误判成“用户资料缓存”的例子。下面是真实节选：

```java
// service/impl/UserServiceImpl.java#saveUserLocation / #getUserLocation（真实节选，省略类声明与周围方法）
public void saveUserLocation(String userId, UserLocation location) {
    String key = RedisKeyConstants.userLocKey(userId);
    stringRedisTemplate.opsForHash().put(key, "latitude", location.getLatitude());
    stringRedisTemplate.opsForHash().put(key, "longitude", location.getLongitude());
    stringRedisTemplate.opsForHash().put(key, "address", location.getAddress());
}

public UserLocation getUserLocation(String userId) {
    String key = RedisKeyConstants.userLocKey(userId);
    Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
    if (entries.isEmpty()) {
        return null;
    }
    return UserLocation.builder()
            .latitude(entries.get("latitude") != null ? entries.get("latitude").toString() : null)
            .longitude(entries.get("longitude") != null ? entries.get("longitude").toString() : null)
            .address(entries.get("address") != null ? entries.get("address").toString() : null)
            .build();
}
```

这里没有 `expire`，没有 Mapper 写库，也没有回表重建。三次 `HSET` 是三条独立命令，但即使全部成功，数据也只存在 Redis。Redis 丢失后 `getUserLocation` 返回 `null`，不是“下次查询变慢”。

司机心跳则刻意不能给整个共享 GEO key 设 TTL。`constant/RedisKeyConstants.java` 的注释已经把原因写清楚：只要还有一个司机在心跳，整体 key 的 TTL 就会被刷新，离线司机成员永远不会被清除。于是实现把“最后心跳时间”放到 `driver:online:beat` Hash，用 `UPDATE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION` 逐成员判断并清理 `driver:geo:online` 中的成员。

```java
// service/base/DriverGeoIndex.java#update（真实节选，省略 imports、枚举、其它方法）
private static final Duration ONLINE_TTL = Duration.ofSeconds(60);

private boolean update(String driverId, BigDecimal lng, BigDecimal lat, String mode) {
    if (driverId == null || driverId.isBlank() || !validCoordinates(lng, lat)) {
        return false;
    }
    try {
        return Long.valueOf(1).equals(redisTemplate.execute(RedisScripts.UPDATE_DRIVER_LOCATION,
                keys(), driverId, lng.toPlainString(), lat.toPlainString(),
                String.valueOf(ONLINE_TTL.toMillis()), mode));
    } catch (RuntimeException e) {
        log.warn("司机位置上报失败: driverId={}", driverId, e);
        return false;
    }
}
```

`ONLINE_TTL` 不是 `EXPIRE`，而是脚本参数：刷新模式若发现心跳缺失、时间倒退、差值 `>= 60000ms` 或 GEO 中没有成员，就 `HDEL` + `ZREM` 并返回 0；否则 `GEOADD` + `HSET` 新心跳并返回 1。这是**成员超时**，不是 key TTL。

### 3.2 String、Set 与一次签发令牌

`TokenServiceImpl` 用一个内联 Lua 同时写令牌 String 和用户令牌 Set，避免“令牌写好了、索引没写”的窗口：

```lua
-- service/impl/TokenServiceImpl.java#SAVE（真实脚本节选）
if (redis.call('GET', KEYS[3]) or '0') ~= ARGV[4] then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('SADD', KEYS[2], ARGV[3])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]) * 2)
return 1
```

调用方传的 key/参数是：

```java
// service/impl/TokenServiceImpl.java#saveToken（真实节选；省略前置校验与异常处理）
Long saved = redisTemplate.execute(SAVE, List.of(RedisKeyConstants.tokenKey(token.getToken()),
        RedisKeyConstants.userTokensKey(token.getUserId()), versionKey(token.getUserId())),
        objectMapper.writeValueAsString(token), String.valueOf(authProperties.getTokenTtlSeconds()),
        token.getToken(), String.valueOf(token.getSessionVersion()));
if (!Long.valueOf(1).equals(saved)) throw new UnauthorizedException("登录状态已变化，请重新登录");
```

- `KEYS[1] = auth:token:{token}`：String，值是 `UserToken` JSON，TTL 604800 秒。
- `KEYS[2] = auth:user_tokens:{userId}`：Set，member 是 token，TTL 是令牌 TTL 的两倍。
- `KEYS[3] = auth:session-version:{userId}`：版本 String，脚本先校验会话版本；撤销时 `INCR`，旧令牌不能重新发布。
- `ARGV[1]` 是 JSON，`ARGV[2]` 是 TTL 秒数，`ARGV[3]` 是 token，`ARGV[4]` 是 sessionVersion。

`src/main/resources/application.yaml` 的 `auth.token-ttl-seconds: 604800` 与 `config/AuthProperties.java` 的字段默认值一致；其它属性源可以覆盖它，静态阅读不能确认生产实际值。这个脚本的原子性只覆盖 Redis 三个 key；MySQL 账号查询在 `getToken` 里另做，不参与这个脚本。

### 3.3 List：聊天历史的真实主链

`chat:history:{chatId}` 是 List，元素是 `MessageParser.parse(message)` 的 JSON 字符串；MySQL `sys_messages` 才是持久副本。当前业务协调入口是 `MessageMemory#save` / `#get`，不是 `RedisMemory#append`。

**保存时先写 MySQL，再按版本与缓存完整状态处理 Redis**。`MessageMemory#save` 的真实顺序是：归属校验、焦点会话 TTL 切换、读取 L1/L2 版本号、`mysqlMemory.append(...)`，然后才尝试同步缓存。

```java
// agentbase/memory/MessageMemory.java#save（真实节选；省略参数校验、assertOwned、switchFocusChatIfNeeded、updateChatTime）
long heapVersion = heapMemory.currentVersion(chatId);
long redisVersion = redisMemory.currentVersion(chatId);
// 先完成持久化，避免数据库写入失败后缓存仍返回未保存的消息。
mysqlMemory.append(chatId, newMessages);
try {
    redisMemory.appendIfVersionMatch(chatId, newMessages, FOCUS_CHAT_TTL, redisVersion);
    heapMemory.appendIfVersionMatch(chatId, newMessages, heapVersion);
} catch (RuntimeException e) {
    heapMemory.clear(chatId);
    throw e;
}
```

`RedisMemory#appendIfVersionMatch` 调用的是 `RedisScripts.APPEND_HISTORY_IF_VERSION_MATCH`：

```lua
-- util/RedisScripts.java#APPEND_HISTORY_IF_VERSION_MATCH（真实脚本）
local current = redis.call('GET', KEYS[1]) or '0'
local version = redis.call('INCR', KEYS[1])
redis.call('PERSIST', KEYS[1])
if current ~= ARGV[1] then
    redis.call('DEL', KEYS[2])
elseif redis.call('EXISTS', KEYS[2]) == 1 then
    for i = 3, #ARGV do
        redis.call('RPUSH', KEYS[2], ARGV[i])
    end
    redis.call('PEXPIRE', KEYS[2], ARGV[2])
end
return version
```

最低限度语义：版本 key 总是递增并 `PERSIST`；若写库期间版本已变，删除可能过时的 Redis 数据 key；版本一致**且缓存本来就存在**时，才 `RPUSH` 新消息并刷新 TTL；缓存缺失时脚本什么都不追加，**不会创建只有几条新消息的残缺列表**，后续读取会按租约从 MySQL 整体回填。复杂并发推导留 C4，本章只记住“MySQL 先持久化，Redis 只在缓存完整且版本匹配时追加”。

**读取时用 `getAll`，不是 `getLastN`**。`MessageMemory#get` 按 L1 堆 → L2 Redis → L3 MySQL 查；L2 路径调用 `redisMemory.getAll(chatId)`（`LRANGE 0 -1`），拿到完整列表后再在 Java 里 `tail(redisAll, lastN)` 做消息窗口裁剪。L2 缺失时读 MySQL，并用 `RedisMemory.overwriteIfLeaseValid` 持版本租约整体回填。当前会话/非当前会话的 24h/30min TTL 由 `MessageMemory#switchFocusChatIfNeeded` 设置，回填固定 24h。

`RedisMemory#append`、`#getLastN` 和 `APPEND_WITH_VERSION_BUMP` 确实存在，`RedisCacheRegressionTest` 直接调用它们；`RepairDatabaseIntegrationTest` 只调用 `MysqlMemory#append`，不涉及 `RedisMemory`。在当前 `src/main/java` 主链里检索 `redisMemory.append(`、`redisMemory.getLastN(` 没有找到调用方。它们是辅助方法/测试路径，不能写成本章 `MessageMemory` 的业务主链；而 `overwriteIfLeaseValid` 是 L2 缺失回填的主链，不属于辅助路径。

### 3.4 ZSet：延迟队列与滑动窗口

`delay:order:timeout` 的 score 是 `System.currentTimeMillis() + delay.toMillis()`，member 是 orderId。`schedule` 通过 `ZADD` 同 member 覆盖旧 score 实现“改期”，`claimDue` 用 Lua 一次完成“查到期 + 移除”，`cancel` 用 `ZREM`。

```java
// service/base/OrderDelayQueue.java#schedule / #claimDue（真实节选；省略类注释、cancel、afterCommit）
public void schedule(String orderId, Duration delay) {
    if (orderId == null || orderId.isBlank() || delay == null) {
        return;
    }
    long deadline = Math.addExact(System.currentTimeMillis(), Math.max(0L, delay.toMillis()));
    afterCommit(() -> redisTemplate.opsForZSet().add(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId, deadline));
}

public List<String> claimDue(int batchSize) {
    if (batchSize <= 0) {
        return Collections.emptyList();
    }
    List<?> claimed = redisTemplate.execute(RedisScripts.CLAIM_DUE_ORDERS,
            List.of(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY),
            String.valueOf(System.currentTimeMillis()), String.valueOf(batchSize));
    return claimed == null ? Collections.emptyList() : claimed.stream().map(String.class::cast).toList();
}
```

`ride-order.timeout.*-minutes` 是**业务超时分钟数**，用于算 score，不是 Redis key 的 TTL。`OrderDelayQueueIntegrationTest` 验证了并发领取不重复、改期不会被提前领取、批次上限生效；这些测试由 `test.redis.port` 门控，本轮只读未运行。

### 3.5 GEO：整池索引与固定 TTL

`order:geo:pool` 是可重建的完整索引：`OrderGeoPool.search` 先尝试读取，失效/未就绪时用 `rideOrderMapper.selectList(status=CREATED)` 查库，再由 `PUBLISH_ORDER_GEO_POOL` 一次性 `GEOADD` 全部候选并 `PEXPIRE` 2 小时。后续读取**不续期**，避免旧索引寿命被无限延长。提交后若订单状态变化，`add/remove` 通过 `afterCommit` 失效整池，而不是把事务中的单笔坐标直接发布。

`driver:geo:online` 相反：它是实时状态索引，没有 MySQL 重建路径；失效粒度下沉到成员，见 3.1。

---

## 四、TTL、业务结束、成员超时与恢复边界

### 4.1 四类寿命

| 类别 | 真实例子 | 单位 / 来源 | 是否刷新 | 业务含义 |
|---|---|---|---|---|
| 无过期 | `user:loc:{userId}`、`auth:session-version:{userId}`、`delay:order:timeout`、`driver:geo:online`、`driver:online:beat`、`chat:history:version:{chatId}`、`ticket:pool:version` | 代码没有调用 `EXPIRE`；版本 key 还常显式 `PERSIST` | 不适用 | Redis 故障/清空后不会“按 TTL 自动回来”；有些是唯一状态，有些由业务清理 |
| 固定过期 | `auth:token:{token}` 604800s；`chat:classify:*` 21600s；`order:geo:pool` 2h；`ticket:pool:*` 2h；`chat:tool:v2:*` 24h；`chat:info:{chatId}` 锁 60min、报价 24h | `application.yaml` 的 `auth.*` / `chat.guard.*`，或代码常量 `Duration` | 分类缓存每次 `put` 重设；令牌索引每次签发重设；订单/工单池只在发布时设一次，后续读取不续期 | 过期后 key 整体消失；不等于业务对象被数据库删除 |
| 有条件续期 | `chat:history:{chatId}` 当前会话 24h / 非当前 30min，且只在 `APPEND_HISTORY_IF_VERSION_MATCH` 的“版本匹配 + 缓存存在”分支追加并 `PEXPIRE`；`chat:ratelimit:{userId}` 只在 `SLIDING_WINDOW_RATE_LIMIT` 的放行分支 `ZADD` 后 `PEXPIRE` 窗口毫秒；`HASH_SET_WITH_EXPIRE` 每次 `lockChat` 重设 3600s；工单序号仅 `TTL<0` 时设 48h | `MessageMemory`/`RedisMemory`、`ChatRateLimiter`、`ChatManager`、`TicketServiceImpl` | 视脚本/写入分支而定；限流拒绝分支与聊天历史缓存缺失分支都不刷新数据 key TTL | 活跃数据延长寿命，但最终仍可能过期；未登记/未追加的调用不算“刷新” |
| 成员级心跳 | `driver:geo:online` + `driver:online:beat` | `DriverGeoIndex.ONLINE_TTL=60s`；脚本用 Redis `TIME` 毫秒 | `goOnline`/`refreshIfExist` 更新坐标与心跳；`location` 读取时清理过期成员 | key 不整体过期；单个司机超过 60s 无心跳会被 `HDEL` + `ZREM` |

注意 `score` 到期和 key 到期不同：延迟队列 member 到点不会自己消失，`CLAIM_DUE_ORDERS` 用 `ZRANGEBYSCORE` 找到后逐条 `ZREM`。业务结束则可能是显式删除，例如 `OrderDelayQueue.cancel`、`DriverGeoIndex.goOffline`、`TokenServiceImpl.REVOKE`。

### 4.2 消失后谁能恢复

| 恢复方式 | 例子 | 触发条件与限制 |
|---|---|---|
| 回 MySQL 重建 | `chat:history` 经 `MessageMemory`；`chat:tool:v2` 经 `ChatToolResponseMapper`；`ticket:pool`、`order:geo:pool`、统计/城市编码缓存 | 需要 MySQL 可用、回表逻辑被调用；订单状态缓存旧值未过期时不会立即回表；索引重建还受版本/锁限制 |
| 数据库查询修复 | `order:status`、`driver:active` | `OrderGrabService.tryGrab` 状态缓存缺失时按 `order_id` 回表；`driver:active` 每次预检前用 `sys_ride_order` 查进行中订单重写，但占位本身仍只在 Redis |
| 重新调用模型 | `chat:classify:*` | 分类结果没有 DB 副本；缓存失效后 `resolveClassification` 重新调用分类模型，且空结果不缓存 |
| 用户/司机重新操作 | 登录令牌、验证码、用户当前位置、报价确认槽位、司机在线位置 | 没有完整持久副本；不是“变慢”，而是流程中断或状态丢失 |
| 有范围数据库兜底 | `delay:order:timeout` 的漏入队/领取后丢失 | `OrderTimeoutJob` 只在 `enabled`、订单仍在 MySQL、状态/时间满足、`batch-size` 轮到、本轮执行成功时按订单重新触发处理；不恢复 ZSet 全貌 |
| 不由扫描恢复 | Mongo 孤儿轨迹、未过期旧状态缓存、GEO 池成员、登录态、会话槽位、限流窗口等 | `OrderTimeoutJob` 不是通用 Redis 重建器；`handleTimeoutOrder` 可能间接改状态缓存/池/占位，但不能据此概括成全量恢复 |

所以 Redis 故障、key 消失后的影响要逐个 key 判断：轻则重新查库或重调模型，重则重新登录、重新上报位置或重新走一遍报价确认。F1 已把“回 MySQL / 重调模型 / 用户重新操作 / 无法恢复”分成四类，本章补上 TTL 与触发前提。

---

## 五、追踪一个真实 Lua：`SLIDING_WINDOW_RATE_LIMIT`

选这个脚本，是因为它同时展示 ZSet、Lua、TTL、返回值和业务降级；完整抢单脚本在 F3 已按 `GRAB_ORDER_ATOMIC` 讲过。

### 5.1 调用方与脚本

`ChatRateLimiter.tryAcquire` 每次对话前申请一次额度。配置来自 `ChatGuardProperties`：`chat.guard.rate-limit-window-seconds` 默认 60 秒，`rate-limit-max` 默认 30 次；`application.yaml` 写的是同值。窗口换算成毫秒后传给脚本。

```java
// service/base/ChatRateLimiter.java#tryAcquire（真实节选；省略 imports、类注释、字段与构造）
long windowMillis = TimeUnit.SECONDS.toMillis(properties.getRateLimitWindowSeconds());
if (windowMillis <= 0 || properties.getRateLimitMax() <= 0) {
    return true;
}
try {
    Long remaining = redisTemplate.execute(
            RedisScripts.SLIDING_WINDOW_RATE_LIMIT,
            List.of(RedisKeyConstants.chatRateLimitKey(userId)),
            String.valueOf(windowMillis),
            String.valueOf(properties.getRateLimitMax()),
            UUID.randomUUID().toString());
    if (remaining == null) {
        return true;
    }
    return remaining >= 0;
} catch (Exception e) {
    log.warn("对话限流判定失败，本次放行: userId={}", userId, e);
    return true;
}
```

```lua
-- util/RedisScripts.java#SLIDING_WINDOW_RATE_LIMIT（真实脚本）
redis.replicate_commands()
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local used = redis.call('ZCARD', KEYS[1])
if used >= limit then
    return -1
end
redis.call('ZADD', KEYS[1], now, ARGV[3])
redis.call('PEXPIRE', KEYS[1], window)
return limit - used - 1
```

| 项 | 内容 |
|---|---|
| `KEYS[1]` | `chat:ratelimit:{userId}`，一个 ZSet |
| `ARGV[1]` | 窗口毫秒数，默认 `60 * 1000` |
| `ARGV[2]` | 窗口内最大次数，默认 `30` |
| `ARGV[3]` | 本次调用的 UUID member；同毫秒两次调用用同一 member 会被 `ZADD` 去重，所以必须唯一 |
| 所有调用先执行 | `TIME` 取 Redis 服务器时间 → `ZREMRANGEBYSCORE` 清窗口外 member → `ZCARD` 计数。拒绝调用也可能清掉过期 member，但不会登记本次调用 |
| 返回 `-1` | `used >= limit` 时**直接返回**：不执行 `ZADD`，也不执行 `PEXPIRE`，所以不会刷新 key TTL；Java 收到 -1 返回 false |
| 返回 `>= 0` | 未超限时才 `ZADD` 登记本次 UUID，`PEXPIRE` 用毫秒单位刷新 key，并返回剩余额度；Java 返回 true |
| Java 得到 `null` | `ChatRateLimiter#tryAcquire` 本次返回 true |
| Java 捕获异常 | 记日志并让 `tryAcquire` 本次返回 true；这只是**限流判定放行**，不是整个聊天可用性的保证 |

因此“每次调用都 `PEXPIRE`”是错的：清理与计数每次都有，登记与 TTL 刷新只在放行分支。`ChatRequestRunner` 还依赖 Redisson 单轮锁，`ChatServiceImpl#chat` 依赖 `chat:info` Hash / `MessageMemory`，`MessageMemory` 的 L2 又依赖 Redis；Redis 故障或脚本返回 `null` 只越过限流这一步，后续聊天仍可能因锁、会话状态或记忆依赖失败，不能保证整体可用。脚本执行期间其它 Redis 命令仍不能穿插；若脚本在部分 `redis.call` 之后报错，也不会像关系型事务那样自动回滚已写操作。测试 `ChatGuardRedisIntegrationTest` 用真实 Redis 验证了返回 `1/0/-1`、过期 member 被清除、并发 40 次在限额 7 时只放行 7 次且 `zCard==7`；该测试由 `test.redis.port` 门控，本轮未运行。

### 5.2 短时间线：多条命令 vs 一个脚本

设窗口内已有 1 次调用、限额为 2，T1、T2 同时申请。

```text
多条独立命令（教学假设，用来说明竞态，不是本仓库实现）：
t0  T1 ZREMRANGEBYSCORE / ZCARD -> used=1
t1  T2 ZREMRANGEBYSCORE / ZCARD -> used=1
t2  T1 ZADD 自己 -> 放行
t3  T2 ZADD 自己 -> 也放行
结果：used=3，超过限额 2。每条命令原子，但三步之间可以插入另一个客户端。

一个 Lua 脚本（当前实现）：
t0  T1 脚本开始：先 ZREMRANGEBYSCORE 清理、ZCARD 计数=1；
    used < limit，才 ZADD 本次 UUID、PEXPIRE 刷新 TTL、返回 0 -> 放行。
t1  T2 脚本开始：同样先 ZREMRANGEBYSCORE 清理、ZCARD 计数=2；
    used >= limit，直接 return -1 -> Java 拒绝。
    该分支没有 ZADD，也没有 PEXPIRE，因此不登记本次调用、不刷新 key TTL。
结果：used=2，没有超限。另一条 Redis 命令不能插在 T1 脚本中间。
```

Lua 解决的是“检查 + 登记”不能被别的 Redis 命令穿插。它**没有**解决：

- Redis 与 MySQL 的跨库原子性；
- 脚本运行到一半报错后的自动回滚；前面 `redis.call` 已产生的修改不会因关系型事务语义自动撤销；
- 业务幂等性；脚本每成功一次就消耗一次额度，重复请求不是天然无害；
- 脚本返回值不会自动影响其它调用方；它只影响 `ChatRateLimiter.tryAcquire` 自己按 `remaining >= 0` 判断，且脚本错误前的写入不会自动回滚。

如果部署为 Redis Cluster，多 key 脚本的所有 `KEYS` 必须落在同一槽，通常靠 `{hash-tag}` 或改造 key/脚本。`src/main/resources/application.yaml` 只配置单机 `spring.data.redis.host/port/database`，`RedisKeyConstants` 没有 hash tag，测试也用单机直连；仓库里没有启用 Cluster 或跨槽执行的证据。单 key 的限流脚本不受这个约束，但 `TokenServiceImpl.SAVE`、`GRAB_ORDER_ATOMIC`、`PUBLISH_ORDER_GEO_POOL` 等多 key 脚本不能假设在 Cluster 上可直接运行。

---

## 六、与 F3 的事务/回调边界如何接上

F4 不把 Redis 写说成 MySQL 事务的一部分。当前实现里有三种情况：

1. **提交后动作**：`OrderGrabService#syncOrderStatus`、`OrderDelayQueue#schedule`、`OrderGeoPool#add/remove` 都调用各自的 `afterCommit` 辅助方法。若事务同步激活，注册 `afterCommit`；若没有事务同步，立即执行并吞掉运行时异常。它们只决定 Redis 写入时机，不把 Redis 纳入 MySQL 事务。
2. **事务内直接 Redis 写入**：`OrderGrabService#tryGrab` 在 MySQL 事务中直接写 `driver:active`、可能预写 `order:status`，并执行 Lua 占位；`driverAcceptOrder` 在有坐标时于提交前调用 `driverGeoIndex.refreshIfExist`。这些写入可能先于提交发生，回滚不会自动撤销，必须靠 `afterCompletion` 补偿、TTL 或后续按数据库修复。
3. **超时任务间接改变 Redis**：`OrderTimeoutJob` 按 MySQL 订单调用 `handleTimeoutOrder`；`handleArriveTimeout` 可能 `releaseDriver`、`refreshOrderStatus`、失效 GEO 池并重新排队，`systemCancel` 可能同步状态缓存、取消待办、失效池、释放司机。这说明超时处理会**间接**改 Redis，但它不是 Redis 通用恢复器，也不承诺恢复所有 key。

再强调 F3 的两条读语义：事务代理新建物理事务时，控制器通常要等提交与回调完成才拿到结果；加入外层事务时，本次调用可先返回外层方法。RC 一致性读既只看其他事务已提交数据，也看得到本事务自己的修改；这些是 MySQL 的规则，Redis 没有同样的隔离级别/快照读概念。

---

## 七、第一部分简短复习

- Redis 的 `String/Hash/Set/List/ZSet/GEO` 都是 key 下的不同 value 形态；对象进 Redis 前要序列化成本项目约定的字符串/JSON。
- key TTL、业务结束、ZSet score、成员级心跳是四件事。`EX` 是秒，`PX/PEXPIRE/PTTL` 是毫秒；`ride-order.timeout.*-minutes` 是业务时间，不是 key 过期。
- 聊天历史主链是 `MessageMemory#save` 先写 MySQL，再由 `appendIfVersionMatch` 按版本/缓存完整性追加或失效；`#get` 用 `RedisMemory#getAll` 后在 Java 里截窗口。`append` / `getLastN` / `APPEND_WITH_VERSION_BUMP` 是辅助或测试路径，不是这条主链。
- `SLIDING_WINDOW_RATE_LIMIT` 对所有调用先清理并计数；超限 `return -1` 前不 `ZADD`、不 `PEXPIRE`，只有放行分支登记并刷新 TTL。Redis 异常或返回 `null` 只让 `ChatRateLimiter` 本次放行；后续锁、会话、记忆仍可能失败。
- 有些 Redis 数据可从 MySQL 重建，有些要重调模型，有些要用户/司机重新操作，有些只能靠有范围的数据库扫描部分兜底。不能把所有 Redis 数据叫缓存。
- Lua 把多次 Redis 命令合成一次服务端原子执行，脚本执行期间其它 Redis 命令不能穿插；但没有跨 Redis/MySQL 事务，也没有报错自动回滚，返回值只影响对应 Java 分支。
- 多 key 脚本若在 Redis Cluster 上执行须同槽；仓库只有单机配置与无 hash tag 的 key，不能假设跨槽可用。

---

# 第二部分：对话流最小基础

## 八、场景 B：林小雨问“这单怎么这么贵”

> 下面是**示意请求**，没有实际发送；token、chatId 都是占位。

```http
POST /chat/v2/c/3f8c9d2e-1111-4222-8333-444455556666 HTTP/1.1
Host: localhost:8080
Authorization: Bearer <token>
Content-Type: application/json
Accept: text/event-stream

{"prompt":"这单怎么这么贵，能解释一下吗？"}
```

仓库里的评测客户端 `src/test/llm_judge_eval.py` 确实这样设置了 `Accept: text/event-stream` 与 `Content-Type: application/json`，用 `stream=True` 读取，并只解析 `data:` 开头的行。它是客户端脚本，不是服务端接口声明；服务端 `ChatController` 没有写 `produces`。

这一问不是“等所有字生成完再一次性返回”：服务端会先发分类通知，再逐步发模型文字片段，必要时发工具开始事件，最后结束流。我们要解释的是这条链以及它的终止/取消边界。

## 九、最小概念：普通响应、流、Flux、SSE、Servlet 栈

### 9.1 普通响应与流式响应

- **普通响应**：控制器返回一个对象，框架选一个 `HttpMessageConverter` 把它序列化，写完整个 body 后结束响应。成功/失败的 HTTP 状态在响应头里。
- **流式响应**：响应头先发出，body 由多个事件逐步写出。一旦响应已提交，后续业务错误通常不能再“改成 500”，只能在流里发一个错误事件、以错误信号终止，或由框架/容器处理连接。
- **SSE（Server-Sent Events）**：一种文本行协议，服务端按 `data: ...` 等行向浏览器持续推送。它是响应体格式，和 HTTP 状态码不是同一层。

`src/main/resources/api/chat-security.md` 明确写了：同步控制器异常由全局处理器映射 HTTP 状态，流式端点在流开始发送后的错误通过 error 事件与流终态表达。不能把业务 `code`、HTTP 状态、流内 error 事件混为一谈。

### 9.2 Flux、Publisher / Subscriber、订阅与终止

Reactor 的 `Flux<T>` 是一个 **Publisher**：它描述“将来可能产生 0～N 个元素，最后以完成或错误结束”。它本身不是已经算好的 `List<T>`；只有 **Subscriber** 调用 `subscribe` 后，数据才开始流动。Subscriber 通过 `Subscription.request(n)` 表达需求量，这就是**背压**的最小含义。

- `onNext(element)`：来一个元素，例如一个 `AgentEvent`。
- `onComplete()`：正常终止。
- `onError(throwable)`：错误终止。
- `cancel()`：订阅者表示不再需要数据；是否能立刻停止底层任务，取决于发布者与框架实现，不能一概而论。
- `Sinks.Many<T>`：本项目用它手动 `tryEmitNext` / `tryEmitComplete` / `tryEmitError`，把后台业务产生的事件推进 Flux。
- `doFinally(signal)`：其所在链收到完成、错误或取消信号时执行回调；本项目用它发起单轮锁的异步释放。

### 9.3 返回 `Flux` 不等于使用 WebFlux

仓库事实：

- `pom.xml` 的 parent 是 Spring Boot `3.5.9`，Java 21；Web 依赖是 `spring-boot-starter-web`，没有 `spring-boot-starter-webflux`，没有显式 `reactor-core` 版本。
- 本机 `~/.m2/repository/org/springframework/boot/spring-boot-dependencies/3.5.9/spring-boot-dependencies-3.5.9.pom` 管理 `spring-framework.version=6.2.15`、`reactor-bom.version=2024.0.13`。Reactor 类型通过依赖传递出现在 classpath。
- `RideAgentHubApplication` 只调用 `SpringApplication.run`，没有设置 `WebApplicationType`；`config/WebMvcConfig.java` 实现 Servlet 栈的 `WebMvcConfigurer`；`AuthTokenInterceptor` 用 `jakarta.servlet.http.HttpServletRequest`。

框架规则（Spring Framework 6.2.x）：Spring MVC 在 Servlet 异步请求上支持返回 Reactor/RxJava 等响应式类型。对多值 `Flux`：

- 若协商出的媒体类型是 `text/event-stream` 或元素是 `ServerSentEvent`，适配成 `SseEmitter` 流式写出；
- 若是其它流式媒体类型如 `application/x-ndjson`，适配成 `ResponseBodyEmitter`；
- 若只是 `application/json` 等普通媒体类型，则收集成 `List` 后按单值响应写出。

依据是 Spring Framework 6.2.19 官方文档《Asynchronous Requests》Reactive Types 小节，以及精确版本 v6.2.15 源码 `spring-webmvc/.../ReactiveTypeHandler.java#handleValue`。因此本项目是 **Servlet 栈 + Spring MVC 的响应式返回值适配**，不是 WebFlux 应用。

---

## 十、从控制器到流：一条正常调用链

### 10.1 控制器：把 userId 取成参数，返回 `Flux<AgentEvent>`

```java
// controller/ChatController.java#chat / #resume（真实节选；省略 import、其它端点）
@RestController
@RequestMapping("/chat/v2")
public class ChatController {
    @Resource
    private com.fancy.rideagenthub.service.base.ChatRequestRunner requestRunner;

    @RequirePermission
    @PostMapping("/c/{id}")
    public Flux<AgentEvent> chat(@PathVariable String id, @RequestBody ChatParamDTO chatParam) {
        String userId = UserTokenContext.getUserIdInString();
        return requestRunner.run(userId, id, chatParam, false);
    }

    @RequirePermission
    @PostMapping("/r/{id}")
    public Flux<AgentEvent> resume(@PathVariable String id, @RequestBody ChatParamDTO chatParam) {
        String userId = UserTokenContext.getUserIdInString();
        return requestRunner.run(userId, id, chatParam, true);
    }
}
```

逐段读：

- 类级 `@RequestMapping("/chat/v2")` 加方法级 `@PostMapping("/c/{id}")` / `"/r/{id}"`；没有 `produces`、没有 `consumes`。
- `@RequirePermission` 当前只要求登录；`UserTokenContext.getUserIdInString()` 在 Tomcat 线程里从 token 上下文取身份，并作为 `String` 参数传给 Runner。F1 已说明后台身份靠显式传参，不依赖 ThreadLocal 自动跨线程。
- `@RequestBody ChatParamDTO` 由 Spring MVC 按请求 `Content-Type` 选转换器；返回类型是 `Flux<AgentEvent>`，不是 `Result`，也不是 `SseEmitter`。
- 控制器没有直接构造 SSE 字符串；它把 Runner 返回的 Publisher 交给 Spring MVC。

全 `src/main/java` 检索 `produces`、`text/event-stream`、`ServerSentEvent`、`SseEmitter`、`ResponseBodyEmitter` 均无命中；服务端代码没有显式写 `data:` 行。

### 10.2 `AgentEvent`：事件对象，不是 SSE 帧

```java
// domain/dto/AgentEvent.java（真实源码；仅省略 package/import 之外无删改）
public record AgentEvent(String type, // 事件类型: "message", "tool_start", "error", "confirm"，"notify"
        Object payload // 数据载荷: 字符串 或 JSON对象
) {
    public static AgentEvent message(String content) { return new AgentEvent("message", content); }
    public static AgentEvent tool(String toolName) { return new AgentEvent("tool_start", toolName); }
    public static AgentEvent error(String error) { return new AgentEvent("error", error); }
    public static AgentEvent comfirm(Object json) { return new AgentEvent("confirm", json); }
    public static AgentEvent notify(String note){ return new AgentEvent("notify", note); }
}
```

要点：

- 真实类型是 Java `record`，字段只有 `type` 与 `payload`；事件类型是字符串字面量，没有枚举。
- 工厂方法名 **`comfirm`** 拼写如此，但产生的 `type` 是 `"confirm"`。搜索 `confirm` 时不要漏掉这个方法。
- `message` / `tool_start` / `error` / `notify` 的 payload 通常是字符串；`confirm` 的 payload 在 `OrderAgent` 里是 `objectMapper.writeValueAsString(orderParams)` 生成的 **JSON 字符串**，`src/main/resources/api/chat-security.md` 也提醒客户端要解析外层事件后再解析 payload。
- `AgentEvent` 不是 `ServerSentEvent`，没有 `id` / `event` / `retry` 字段；项目没有构造 SSE 帧。

### 10.3 Runner：单轮锁、后台订阅与 `autoConnect(2)`

下面是 `ChatRequestRunner#run` 的方法体完整节选，省略了 imports、类字段和构造器；方法体没有删改。

```java
// service/base/ChatRequestRunner.java#run（真实节选；方法体完整，省略类声明/import/构造器）
public Flux<AgentEvent> run(String userId, String chatId, ChatParamDTO request, boolean resume) {
    java.util.UUID.fromString(chatId);
    return Flux.defer(() -> {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        var lock = redis.getLock("chat:execution:" + chatId);
        long owner = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        try {
            var acquisition = lock.tryLockAsync(0, -1, TimeUnit.SECONDS, owner).toCompletableFuture();
            boolean acquired;
            try { acquired = acquisition.get(3, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException | InterruptedException timeout) {
                acquisition.thenAccept(late -> { if (late) lock.unlockAsync(owner); });
                if (timeout instanceof InterruptedException) Thread.currentThread().interrupt();
                throw timeout;
            }
            if (!acquired) {
                return Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"));
            }
            chats.assertOwned(userId, chatId, !resume);
        } catch (Exception e) {
            if (lock.isHeldByThread(owner)) lock.unlockAsync(owner);
            return Flux.error(e);
        }
        // 保持后台订阅至终态，客户端断开不能提前释放正在执行工具的锁。
        Flux<AgentEvent> events = sink.asFlux().doOnSubscribe(subscription -> {
            try {
                executor.execute(() -> AgentStreams.guard(sink, () -> {
                    if (resume) service.resume(chatId, request, sink, userId);
                    else service.chat(chatId, request, sink, userId);
                }));
            } catch (RuntimeException failure) { sink.tryEmitError(failure); }
        }).doFinally(signal -> lock.unlockAsync(owner)).publish().autoConnect(2);
        events.subscribe(ignored -> {}, ignored -> {});
        return events;
    });
}
```

按执行顺序拆开，关键是区分**外层 `Flux.defer` 的订阅**与**内部 `events` 的两个订阅者**：

1. `UUID.fromString(chatId)` 在 `Flux.defer` **之外**：Controller 调用 `Runner.run` 时同步执行，非法 UUID 在返回 Flux 前就抛异常。它与后面的抢锁/归属检查不是同一时机。
2. `Runner.run` 返回的是外层 `Flux.defer(...)`。此时 supplier 还没执行，**没有 sink、没有锁、没有内部 events，也没有哨兵订阅**；HTTP 层只是拿到一个 Publisher 描述。
3. HTTP 层订阅外层 Flux，`defer` 的 supplier 才开始执行：创建 `Sinks.Many`、锁 key `chat:execution:{chatId}`、随机 long owner（`ThreadLocalRandom`，不是线程 ID），然后调用 `tryLockAsync(0, -1, TimeUnit.SECONDS, owner)`。`waitTime=0` 不排队；`leaseTime=-1` 是 Redisson 租约参数，库语义不在本仓库源码中证明。`acquisition.get(3, TimeUnit.SECONDS)` 只等加锁 Future 完成，**不是抢锁超时或整轮时限**；晚到且为 true 会补偿 `unlockAsync`。
4. **失败分支到此结束，不创建内部 events 和哨兵**：`Future` 超时/中断、`!acquired` 或 `chats.assertOwned` 异常时，supplier 直接返回 `Flux.just(AgentEvent.error(...))` 或 `Flux.error(e)`。竞争失败流是正常完成；try/catch 异常路径在 `isHeldByThread(owner)` 时先异步解锁。
5. 只有抢锁与归属检查都通过，supplier 才构造内部 `events = sink.asFlux().doOnSubscribe(...).doFinally(...).publish().autoConnect(2)`，并立即执行 `events.subscribe(ignored -> {}, ignored -> {})`。这是**第 1 个内部订阅者**（后台哨兵），仍然不是第二个 HTTP 请求。
6. supplier 返回 `events`；外层 `Flux.defer` 把**原来的 HTTP 订阅者**接到这个内部 `events` 上，成为**第 2 个内部订阅者**。外层订阅与内部第二订阅是同一次请求的订阅传播，不是两个独立 HTTP 请求。Reactor Core 3.7.14 `FluxAutoConnect#subscribe` 显示，达到订阅数时调用 `source.connect(...)`。
7. `autoConnect(2)` 连接上游后，位于 `publish()` **之前**的 `doOnSubscribe(...)` 被触发，把 `AgentStreams.guard(sink, () -> service.chat/resume(...))` 提交给 `chatExecutor`；`executor.execute` 自己抛 `RuntimeException` 时 `sink.tryEmitError(failure)`。Runner 内没有 `doOnCancel` / `doOnError` / `doOnComplete`。
8. 同在上游、`publish()` 之前的 `doFinally(signal -> lock.unlockAsync(owner))` 观察的是 `sink.asFlux()` 链的终止。Reactor Core 3.7.14 `FluxDoFinally` 源码表明：上游订阅收到 `onComplete` / `onError` / `cancel` 时，分别以 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 调用一次回调；本项目的 lambda 忽略 `signal` 类型，只发起异步 `unlockAsync(owner)`，**不等待结果**。因此“上游若收到 cancel 会触发回调”是精确版本框架规则；但“HTTP 断连是否会向上游传播 cancel”还取决于 Spring MVC 与 `publish()` 的取消传播，仓库没有实测，不能当成本项目已观测事实。
9. 注释和后台哨兵表达的设计意图是“客户端断开不能提前释放正在执行工具的锁”；`FluxAutoConnect#subscribe` 源码没有在单个下游取消时调用断开，但完整取消传播仍涉及 `FluxPublish` 与 Spring MVC，本轮不据此承诺断连后的释放时点。

`config/AsyncWorkConfig.java` 的 `chatExecutor` 是 `ThreadPoolTaskExecutor`：core 8、max 32、queue 64、线程名前缀 `chat-request-`；Runner 用 `@Qualifier("chatExecutor")` 注入后直接 `executor.execute`，没有 `@Async`。线程池生命周期与默认拒绝策略属于 Spring/线程池框架行为，本轮没有运行观测。

### 10.4 后台业务与事件

`ChatServiceImpl#chat` 在后台线程中先做守卫，再读历史、分类、发通知、路由到 Agent。下面两段是**真实源码节选，但合起来不是可直接编译的完整方法**：省略了 `chatManager.isLocked`、`canStartChat`、`assertOwned`、`break=yes` 等早退分支、字段与日志；这些省略会影响完整流程判断，需按源码 `chat` 方法 84～146 行核对。

```java
// service/impl/ChatServiceImpl.java#chat（真实节选 1；读取历史并分类）
String classification;
List<Message> messages = memory.get(userId, id, 100);
Object classRedis = redisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(id),
        "classification");
if (messages.isEmpty()) {
    classification = resolveClassification(
            SINGLE_TURN_CONTEXT, null, param.getPrompt());
} else {
    // 对话不为空：分类依赖"上一轮路由结果 + 助理最后的回复"，两者都必须进缓存 key
    classification = resolveClassification(
            String.valueOf(classRedis), lastAssistantText(messages), param.getPrompt());
}
if (classification == null) {
    String failFtr = "路由失败，请换种方式问问题。";
    sink.tryEmitNext(AgentEvent.message(failFtr));
    sink.tryEmitComplete();
    return;
}
sink.tryEmitNext(AgentEvent.notify(classification));
if ("DANGER".equals(classification)) {
    sink.tryEmitNext(AgentEvent.message("您的命令不被支持，请换个内容继续吧。"));
    sink.tryEmitComplete();
    return;
}
```

```java
// service/impl/ChatServiceImpl.java#chat（真实节选 2；按分类路由；与上段之间还有若干分支）
if (messages.isEmpty()) {
    chatManager.initChat(userId, id, param.getPrompt());
}
if (classRedis != null && classRedis.equals("ORDER")) {
    if(!classification.equals("ORDER")){
        sink.tryEmitNext(AgentEvent.notify("当前处于下单流程，如果您想换个话题，请新建对话。"));
    }
    orderAgent.invoke(sink, id, userId, param.getPrompt());
    return;
}
redisTemplate.opsForHash().put(RedisKeyConstants.chatInfoKey(id), "classification", classification);
switch (classification) {
    case "ORDER" -> orderAgent.invoke(sink, id, userId, param.getPrompt());
    case "DAILY" -> dailyAgent.invoke(sink, id, userId, param.getPrompt());
    case "SUPPORT" -> supportAgent.invoke(sink, id, userId, param.getPrompt());
    case "OTHER" -> fallbackAgent.invoke(sink, id, userId, param.getPrompt());
    default -> {
        log.warn("未知分类: {}", classification);
        sink.tryEmitNext(AgentEvent.message("未知分类，请换种方式问问题。"));
        sink.tryEmitComplete();
    }
}
```

`canStartChat` 说明了“流内错误不是 HTTP 状态”：

```java
// service/impl/ChatServiceImpl.java#canStartChat（真实节选）
if (param == null || !StringUtils.hasText(param.getPrompt())) {
    sink.tryEmitNext(AgentEvent.error("消息内容不能为空"));
    sink.tryEmitComplete();
    return false;
}
if (!chatRateLimiter.tryAcquire(userId)) {
    sink.tryEmitNext(AgentEvent.notify("消息发送过于频繁，请稍后重试。"));
    sink.tryEmitComplete();
    return false;
}
return true;
```

空 prompt 发 `error` 事件后**正常完成**；限流失败发 `notify` 后正常完成。这与 `GlobalExceptionHandler` 里同步异常的 401/403/400 不是一回事。

`agents/AgentStreams.java` 是所有模型流订阅共享的出口，方法体完整且很短：

```java
// agents/AgentStreams.java#subscribe / #guard（真实源码；省略 package/import）
public static void subscribe(Flux<ChatResponse> source, Sinks.Many<AgentEvent> sink,
        Consumer<ChatResponse> next, Consumer<Throwable> error, Runnable complete) {
    source.takeUntilOther(reactor.core.publisher.Mono.delay(java.time.Duration.ofMinutes(3))
            .flatMap(ignored -> reactor.core.publisher.Mono.error(new java.util.concurrent.TimeoutException("模型响应超时"))))
        .doOnNext(next)
        .doOnComplete(complete)
        .subscribe(ignored -> {}, failure -> {
            guard(sink, () -> error.accept(failure));
            sink.tryEmitError(failure);
        });
}

public static void guard(Sinks.Many<AgentEvent> sink, Runnable action) {
    try {
        action.run();
    } catch (Exception failure) {
        sink.tryEmitNext(AgentEvent.error("处理失败，请稍后重试"));
        sink.tryEmitError(failure);
    }
}
```

- `doOnNext(next)`：各 Agent 把模型增量文本 `sink.tryEmitNext(AgentEvent.message(content))`。
- `doOnComplete(complete)`：以 `SupportAgent` 为例，纯文本回复时先 `saveNewMessages(...)` 再 `sink.tryEmitComplete()`；有工具调用时进入 `handleToolCalls` 并递归 `runLoopStep`。
- 错误回调：先发 `AgentEvent.error(error.getMessage())` 再 `sink.tryEmitError(error)`；`guard` 保证完成回调抛异常时也发通用 error 并错误终止。
- `Duration.ofMinutes(3)` 是**硬编码的单次模型订阅超时**，在 `application.yaml` 与 `ChatProperties` 中没有找到对应的模型流超时配置键（文件里另有 Redis/高德/订单超时等无关键）；每次 `openAiChatModel.stream(prompt)` 重新订阅就重新计时。Daily/Order/Support 的工具循环会递归调用 `runLoopStep`，所以这不是整轮 SSE 总时限；`DailyAgent` 另有 `history.size() - originalHistorySize > 200` 的工具调用增长保护。仓库没有找到整轮总超时配置。

`OrderAgent` 的确认事件也体现 payload 形态：

```java
// agents/OrderAgent.java（真实节选；省略周边字段与构造）
String orderJson = objectMapper.writeValueAsString(orderParams);
saveNewMessages(userId, chatId, history, originalHistorySize);
stringRedisTemplate.opsForHash().put(chatInfoKey, "break", "yes");
sink.tryEmitNext(AgentEvent.comfirm(orderJson));
sink.tryEmitComplete();
```

它发的是 `type="confirm"`、`payload` 为 JSON 字符串的事件，同时把 `chat:info` 的 `break` 置为 `yes`。确认状态机的完整推导留给后续章节，本章只定位事件来源。

---

## 十一、`Accept`、Content-Type 与 SSE 线格式：哪些能确定，哪些不能

### 11.1 仓库里能确定的事实

| 事实 | 来源 |
|---|---|
| `ChatController#chat/resume` 返回 `Flux<AgentEvent>`，没有 `produces` / `consumes`，也没有 `ResponseEntity` | `controller/ChatController.java` |
| 全 `src/main/java` 没有 `text/event-stream`、`ServerSentEvent`、`SseEmitter`、`ResponseBodyEmitter`、`"data:` 或 `produces` | 全库检索 |
| `AgentEvent` 是 `record(type, payload)`，不是 `ServerSentEvent`，事件类型是字符串字面量 | `domain/dto/AgentEvent.java` |
| 客户端评测脚本发送 `Accept: text/event-stream`、`Content-Type: application/json`，`stream=True`，只解析 `data:` 行，跳过空串与 `[DONE]` | `src/test/llm_judge_eval.py#build_sut_headers`、`#sse_events`、`#call_chat` |
| API 文档写了事件 JSON 形状，以及“流开始发送后的错误通过 error 事件与流终态表达”；没有写 SSE 帧格式或 `Accept` 约束 | `src/main/resources/api/chat-security.md` |
| 服务端代码没有发 `[DONE]`，也没有显式构造 `event:` / `id:` / `retry:` 行 | 全库检索 + `AgentEvent` |

### 11.2 框架层能确定、项目未实测的部分

Spring Framework 6.2.15 源码 `ReactiveTypeHandler#handleValue` 的关键判断是：

- 返回值能适配成 `Publisher` 且是多值类型；
- 调用 `getMediaTypes(request, presetContentType)`：若没有映射产生的 producible 类型，就用 `ContentNegotiationManager.resolveMediaTypes(request)` 解析请求（包括 `Accept`）；
- 若媒体类型集合里有 `text/event-stream`，或元素类型是 `ServerSentEvent`，创建 `SseEmitter` 并订阅；
- 否则若是 `CharSequence` 元素走文本流，若是 concrete JSON stream 走 NDJSON 流；再否则用 `DeferredResultSubscriber` 把 `Flux` 收集为 `List` 后按普通响应写出。

Spring Framework 6.2.19 官方文档《Asynchronous Requests》Reactive Types 小节给出同一规则：多值 `Flux` 只有流式媒体类型（如 `text/event-stream`、`application/x-ndjson`）才逐步写出；其它媒体类型（如 `application/json`）按 `DeferredResult<List<?>>` 处理。文档与 v6.2.15 源码同属 6.2 线；项目受管版本是 6.2.15。

所以：

- **不能**只因方法返回 `Flux` 就断言任何 `Accept` 条件下都是 `text/event-stream` 或 SSE 帧。
- 当客户端按评测脚本发送 `Accept: text/event-stream` 且 Jackson 能写 `AgentEvent` 时，按框架源码会走 `SseEmitter`；`SseEmitter#extendResponse` 在响应头未设置时写 `Content-Type: text/event-stream`。
- v6.2.15 `SseEmitter#send(Object, MediaType)` 对非 `ServerSentEvent` 元素执行 `event().data(object, APPLICATION_JSON)`，builder 生成 `data:` 前缀、由 `HttpMessageConverter` 序列化对象、`\n` 与结尾空行；不会自动写 `event:` / `id:` / `retry:`。因此从框架源码可推导每条 `AgentEvent` 大致是 `data: {"type":"...","payload":"..."}\n\n` 形状。
- **未经实际 HTTP 请求验证**的还有：最终响应头里的精确 `Content-Type` 参数、Jackson 对 `Object payload` 的具体字节形态、`Accept` 为 `*/*` 或 `application/json` 时最终选择哪种转换器、是否发生 406、实际网络分块。本轮没有抓包、没有 MockMvc/WebTestClient 测试，不能写成项目实测结果。
- `src/test/llm_judge_eval.py` 能证明客户端**期望** `data:` 行并跳过 `[DONE]`，不能证明服务端实际每次都按这个字节格式输出。

### 11.3 事件、错误信号和 HTTP 状态

复审后要把“构造了什么信号”和“客户端观察到什么”分开。下面按发生层与响应是否已提交区分：

| 层 / 时机 | 本项目例子 | 能确定什么 | 不能保证什么 |
|---|---|---|---|
| Controller 返回 Flux 前的同步异常 | `@RequestBody` 绑定失败、`UserTokenContext.getUserIdInString()` 抛 `UnauthorizedException`、`UUID.fromString(chatId)` 抛 `IllegalArgumentException` | 异常发生在 Publisher 返回/订阅前；`GlobalExceptionHandler` 按类型映射 `UnauthorizedException→401`、`IllegalArgumentException→400`、参数异常→400、`ForbiddenException→403`、兜底 `Exception→500` | 不能无条件说“归属一定 403”，归属检查实际在 `defer` supplier 里；若异常发生在异步订阅阶段或响应已提交，最终 HTTP 状态要看框架处理路径，本轮未实测 |
| `Flux.defer` supplier 内返回 `Flux.error` | 加锁 Future 异常、`chats.assertOwned` 抛 `ForbiddenException` | 它是外层订阅时由 supplier 返回的 Publisher 错误；**没有内部 events、没有哨兵、没有后台业务**；外层订阅者会在订阅阶段收到 `onError` | 不能仅凭返回 `Flux.error(ForbiddenException)` 保证 HTTP 403；是否由全局处理器映射、响应头是否已提交、SSE emitter 还是 DeferredResult 路径处理，取决于协商与提交状态，仓库未实测 |
| 流内 `error` 业务事件 | `sink.tryEmitNext(AgentEvent.error(...))`、竞争失败 `Flux.just(AgentEvent.error(...))` | 这是响应体里的一个普通数据元素；`Flux.just(...)` 还是**正常完成**的流，且没有拿锁、没有后台哨兵 | 不保证事件已送达：客户端可能已断开、序列化/写入可能失败；它也不是 HTTP 状态 |
| `sink.tryEmitError` | `AgentStreams` 错误回调、`guard` 兜底、模型流超时 | 若 sink 成功发出错误信号，上游以 `ON_ERROR` 触发 `doFinally` 并异步调用 `unlockAsync(owner)`；这属于错误终止，不是 error 事件 | 不保证客户端一定观察到某个错误对象/HTTP 错误码；`unlockAsync` 不等待结果，释放成功时点不确定 |
| HTTP 响应是否已提交 | 响应头/首个事件已写出 vs 尚未写出 | 未提交时，同步异常处理可能设置状态码；已提交后，状态码和响应头不能改成 403/500，只能靠流内事件、错误终态或连接关闭表达 | 仓库没有 HTTP 抓包、MockMvc/WebTestClient 测试，不能给出本项目每种分支的实测状态或线格式 |

`Flux.just(AgentEvent.error("会话正在处理中"))` 与 `sink.tryEmitError` 语义不同：前者是一条 error 事件后正常完成；后者是 Publisher 错误终止。前者没有拿锁，也没有内部 events/哨兵；后者若已拿到锁，且错误信号到达上游，会由 `doFinally` 触发 `unlockAsync`。两者都**不能**推成“客户端一定看到 403/500 或一定收到某个事件”。本项目未发送任何请求，以上为源码与框架规则的静态推导。

---

## 十二、单轮执行锁与三条终态时间线

### 12.1 锁的五个事实点

| 点 | 源码位置与行为 |
|---|---|
| owner / key | `ChatRequestRunner#run`：`"chat:execution:" + chatId`；`ThreadLocalRandom.nextLong(1, Long.MAX_VALUE)` 作为 owner 实参 |
| 获取 | 外层 `Flux.defer` 被 HTTP 层订阅后，supplier 才调用 `lock.tryLockAsync(0, -1, TimeUnit.SECONDS, owner)`；`Future.get(3, TimeUnit.SECONDS)` 只限制等待加锁结果 |
| 竞争失败 | supplier 直接返回 `Flux.just(AgentEvent.error("会话正在处理中..."))`，正常完成；没有内部 `events`、没有哨兵、没有后台业务，也不释放他人锁 |
| 获取/归属异常 | supplier 的 `catch` 中若 `isHeldByThread(owner)` 则 `unlockAsync(owner)`，返回 `Flux.error(e)`；同样没有内部 `events`、哨兵或 `doFinally` 路径 |
| 终态释放 | 只有成功分支才构造内部 `events` 链；`doFinally` 挂在 `sink.asFlux()` 上、`publish()` 之前。Reactor Core 3.7.14 `FluxDoFinally` 在该上游收到 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 时都会调用一次回调；本项目 lambda 忽略信号类型，只发起异步 `unlockAsync(owner)`，**不等待结果**，源码不能保证释放成功时点 |

### 12.2 时间线 1：正常完成（外层订阅 → 内部第二订阅 → 后台业务）

先固定起点：`Controller` 调用 `Runner.run` 得到的是**外层 `Flux.defer`**；此时 supplier 尚未执行，`sink`、锁、内部 `events` 和哨兵都不存在。`UUID.fromString` 已在 `defer` 外同步完成。

```text
t0  客户端带 token 与 Accept 发起 POST；鉴权、@RequestBody 绑定、Controller 取 userId 完成。
t1  Controller 调用 Runner.run：UUID 同步校验通过；方法返回外层 Flux.defer。
    此刻 supplier 未执行，HTTP 层只拿到一个 Publisher 描述。
t2  Spring MVC/HTTP 层订阅外层 Flux；defer 的 supplier 才开始执行：
    创建 sink、Redisson lock key 与随机 owner，等待 tryLockAsync Future，
    检查 acquired 与 chats.assertOwned。
t2a 失败分支：Future 超时/中断、!acquired、assertOwned 异常等在这里直接返回
    Flux.just(error) 或 Flux.error(e)，没有内部 events、没有哨兵、没有后台业务。
t3  只有抢锁与归属都通过，supplier 才创建内部 events 链
    sink.asFlux().doOnSubscribe(...).doFinally(...).publish().autoConnect(2)，
    并立即 events.subscribe(...) 登记第 1 个内部订阅者（后台哨兵）。
t4  supplier 返回 events；外层 Flux 的订阅者（同一个 HTTP 订阅，不是第二个请求）
    被 defer 接到内部 events 上，成为第 2 个内部订阅者。
t5  autoConnect(2) 达到 2，source.connect(...) 连接上游；doOnSubscribe 触发，
    把 AgentStreams.guard(sink, () -> service.chat/resume(...)) 提交给 chatExecutor。
t6  后台发 notify(分类)、若干 message(文字片段)，必要时发 tool_start 并进工具循环。
t7  Agent 完成回调保存消息，sink.tryEmitComplete()（或 OrderAgent 发 confirm 后 complete）。
t8  上游 doFinally 收到 ON_COMPLETE，调用 lock.unlockAsync(owner)；
    HTTP 层收到 onComplete，结束响应。unlockAsync 不等待结果，释放成功时点不确定。
```

这里 `unlockAsync` 与 HTTP 收尾的相对先后没有序列化保证；能确定的是上游终态触发了异步解锁调用。外层订阅与内部第二订阅是同一次 HTTP 请求的订阅传播，**不是两个独立请求**。

### 12.3 时间线 2：客户端断开

```text
t0～t6 复用 12.2 的修正起点：外层 Flux 已被订阅，supplier 抢锁/归属成功，
    内部 events 与哨兵已创建，外层订阅者成为第二订阅者，autoConnect 已连接上游，
    后台业务已提交；响应头已发出，可能已写出若干 data: 行。
t7  客户端关闭连接；HTTP 层对该下游订阅可能取消，下一次写入也可能失败。
t8  是否把这个 cancel 传播到内部 events、再传播到 publish() 前的上游 sink.asFlux，
    取决于 Spring MVC 的订阅者与 Reactor publish/autoConnect 实现；仓库没有实测，
    不能断言上游一定收到 CANCEL。内部哨兵与设计注释表达的是“不提前停止后台业务”的意图。
t9  只有当上游实际收到终态（ON_COMPLETE / ON_ERROR / CANCEL）时，doFinally 才按
    Reactor 3.7.14 规则调用一次 lock.unlockAsync(owner)；异步、不等待结果。
    若 cancel 没传播到上游且后台也没有终态，源码不能给出锁释放时点。
```

必须限定的部分：

- Spring Framework 6.2 文档《Disconnects》明确说 Servlet API 不提供客户端离开的即时通知；通常要等下一次写失败等事件。
- Reactor Core 3.7.14 `FluxDoFinally` 证明的是“**上游若收到 cancel**，`doFinally` 会用 `CANCEL` 触发”；这不是“本项目 HTTP 断连一定让上游 cancel”的实测结论。
- `FluxAutoConnect#subscribe` 在达到订阅数时 `source.connect(...)`，源码没有在该方法里按单个下游取消调用断开；完整传播还涉及 `FluxPublish` 与 Spring MVC，本轮不推断。
- `unlockAsync` 不等待结果；不能承诺“客户端一断开，锁立刻释放”，也不能推断后台永不终止时锁何时释放。`leaseTime=-1` 的 Redisson 库语义本轮未实测。

### 12.4 时间线 3：单次模型流超时

```text
t0～t6 复用 12.2 的修正起点，内部 events 已连接，后台业务已在 chatExecutor 上执行。
t7  某一轮 runLoopStep 调用 openAiChatModel.stream(prompt)，
    AgentStreams.subscribe 开始 3 分钟 Mono.delay 计时。
t8  3 分钟内没有完成该次订阅；takeUntilOther 收到 TimeoutException。
t9  subscribe 的错误回调通常先 sink.tryEmitNext(AgentEvent.error(...))；
    该事件是否送达客户端取决于连接和写入状态，不能无条件保证。
t10 sink.tryEmitError(failure) 使上游以 ON_ERROR 终止；
    doFinally 调用 lock.unlockAsync(owner)（异步、不等待结果）。
t11 HTTP 层按错误终态收尾；若响应头已提交，不能再改成 500/403。
    客户端最终观察到什么，本轮没有 HTTP 实测。
```

要点：

- 这是**单次模型订阅**超时，不是整轮请求总时限。工具循环每递归一次 `runLoopStep` 就新建一次 `stream(prompt)` 与新的 3 分钟计时；仓库没有整轮 SSE 总超时配置。
- 超时后底层模型 HTTP 请求是否被物理取消，源码无法证明。
- 错误分支不是“成功完成”，也不能保证所有异常都返回对应 HTTP 状态；`src/main/resources/api/chat-security.md` 已区分同步异常与流内错误，11.3 又按响应是否提交收窄了观察边界。
- `SecurityRepairRegressionTest#completionCallbackFailureTerminatesSink` 用 `Flux.empty()` + 完成回调抛异常验证了 `guard` 会终止 sink；没有测试真正等待 3 分钟超时、客户端断连或异步解锁结果。

---

## 十三、第二部分简短复习

- 普通响应一次写完；流式响应先发响应头、再逐步写事件。HTTP 状态、业务事件、流终态、HTTP 连接终态是四层，不能混成一句“客户端看到错误”。
- `Flux` 是 Publisher，`Sinks.Many` 是本项目手动产生事件的入口；订阅才开始流动。`doFinally` 在 `publish()` 前的上游链上：按 Reactor Core 3.7.14，上游收到 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 都会触发回调；但 HTTP 断连是否传播到该上游 cancel 未实测。
- Controller 返回外层 `Flux.defer` 时 supplier 尚未运行；HTTP 层订阅外层后才创建 sink/锁并检查归属。只有成功分支才构造内部 `events`、登记哨兵第一订阅，再由同一个外层 HTTP 订阅者成为第二订阅，`autoConnect(2)` 才连接上游并提交后台业务。竞争失败/归属或获取异常没有内部 events 和哨兵；UUID 校验在 defer 外。
- 本项目是 Boot 3.5.9 / Spring MVC 6.2.15 的 Servlet 栈返回 `Flux`，不是 WebFlux 应用。`produces` 未声明，`Accept` 与其它协商条件决定框架是走 SSE、NDJSON 还是收集为 List。
- `AgentEvent` 是 `record(type, payload)`，工厂 `comfirm` 拼写特殊；`confirm` 的 payload 是 JSON 字符串。流内 error 事件、错误终止、同步 HTTP 状态和响应是否已提交要分别判断，不能保证 403 状态、事件送达或客户端一定观察到错误。
- `chat:execution:{chatId}` 锁用随机 owner、后台哨兵订阅和 `doFinally` 释放；`unlockAsync` 异步、不等待结果，成功时点不确定。3 分钟是单次模型订阅超时，工具循环每轮重新计时；客户端断开、底层模型请求取消和整轮总超时都不能从仓库单独证明。

---

## 十四、动手验证（只读）

### 练习 A：按 key 找结构、有效期和恢复来源

目标：不运行 Redis，只用源码回答“这个 key 是什么结构、谁能重建它”。

| 步骤 | 搜索位置 | 预期发现 | 判断依据 |
|---|---|---|---|
| 1 | `RedisKeyConstants` 搜 `USER_LOC_PREFIX`；`UserServiceImpl#saveUserLocation` | `user:loc:{userId}` 是 Hash，三个字段 | `opsForHash().put` 三次 |
| 2 | 同两个方法继续看 `expire` / Mapper | 没有 TTL、没有写库 | 只有 Redis 一份 |
| 3 | `RedisKeyConstants` 搜 `DELAY_ORDER_TIMEOUT_KEY`；`OrderDelayQueue#schedule` | 一个 ZSet，score 是毫秒时间戳 | `opsForZSet().add` + `Duration.toMillis()` |
| 4 | `OrderTimeoutJob#recoverOverdueOrders` | 扫 MySQL 已有订单，不扫 ZSet | 部分兜底，不是通用恢复 |
| 5 | `MessageMemory#save/#get`、`RedisMemory#appendIfVersionMatch/#getAll`、脚本 `APPEND_HISTORY_IF_VERSION_MATCH` | 主链先 MySQL 持久化，再按版本/缓存完整性追加或失效；读取用 `getAll` 后 Java 截窗口 | 源码调用顺序与脚本分支 |
| 6 | 检索 `src/main/java` 中 `redisMemory.append(` / `redisMemory.getLastN(`；看 `RedisCacheRegressionTest` | 主源码未找到这两个调用；测试直接使用辅助方法 | 调用方检索，不把测试路径当主链 |
| 7 | `RedisKeyConstants` 搜 `DRIVER_GEO_ONLINE_KEY` / `DRIVER_ONLINE_BEAT_KEY`；`DriverGeoIndex#update` | GEO + Hash，key 无 TTL，成员 60s 心跳 | 脚本按 `ONLINE_TTL` 清理成员 |
| 8 | `TokenServiceImpl#SAVE`；`ChatGuardProperties` / `ClassificationCache#put` | String 令牌 + Set 索引；分类缓存固定 6h | `SET` / `SADD` / `opsForValue().set(..., TimeUnit.SECONDS)` |
| 9 | `RedisScripts.SLIDING_WINDOW_RATE_LIMIT` 与 `ChatRateLimiter#tryAcquire` | 所有调用先清理计数；拒绝分支无 `ZADD` / `PEXPIRE`；`null`/异常只让限流器本次放行 | 脚本分支与 Java 返回路径 |

<details>
<summary>练习 A 参考答案</summary>

1. `user:loc:{userId}` 是 Hash，字段 `latitude`/`longitude`/`address`，值都是字符串；`UserServiceImpl` 是写入者和读取者。
2. 它没有 `expire` 调用，也没有 Mapper 写入；`getUserLocation` 在 `entries().isEmpty()` 时直接返回 `null`。Redis 丢失后只能由用户重新上报，不是回 MySQL 重读。
3. `delay:order:timeout` 是 ZSet，member 是 orderId，score 是 `System.currentTimeMillis() + delay.toMillis()`；同 member 再 `ZADD` 即改期。key 本身不设 TTL，score 到点也不会自动删除成员。
4. `OrderTimeoutJob` 按 `enabled`、状态、时间阈值与 `batch-size` 查 MySQL，再调用 `handleTimeoutOrder`；它能让部分漏掉的超时处理重新触发，可能间接改状态缓存/池/占位，但不恢复完整队列或其它 Redis 状态。
5. `chat:history:{chatId}` 是 List。`MessageMemory#save` 先 `mysqlMemory.append(...)`，再调用 `redisMemory.appendIfVersionMatch(...)`；`APPEND_HISTORY_IF_VERSION_MATCH` 在版本不匹配时删除缓存，在版本匹配且缓存存在时才 `RPUSH` 并 `PEXPIRE`，缓存缺失时不创建残缺列表。`MessageMemory#get` 的 L2 路径是 `redisMemory.getAll(chatId)`（`LRANGE 0 -1`）后用 `tail(redisAll, lastN)` 截取；L2 缺失才查 MySQL 并用 `overwriteIfLeaseValid` 持租约回填。
6. `RedisMemory#append`、`#getLastN` 与 `APPEND_WITH_VERSION_BUMP` 确实存在，`RedisCacheRegressionTest` 直接调用；`RepairDatabaseIntegrationTest` 只调 `MysqlMemory#append`。当前 `src/main/java` 的 `MessageMemory` 主链没有调用 `redisMemory.append(` 或 `redisMemory.getLastN(`。它们是辅助/测试路径，不能替代第 5 步的真实主链。
7. `driver:geo:online` 是 GEO，`driver:online:beat` 是 Hash；两者整体都不设 TTL。`DriverGeoIndex` 传 60 秒给脚本，刷新/读取时按 Redis `TIME` 判断并 `HDEL` + `ZREM` 单个司机。没有 MySQL 重建路径。
8. `TokenServiceImpl.SAVE` 用三个 KEYS：`auth:token:{token}` 写 JSON String，`auth:user_tokens:{userId}` 写 token 到 Set，第三个是 session-version；令牌 TTL 来自 `auth.token-ttl-seconds`，Set TTL 是其两倍。`chat:classify:*` 用 `classify-cache-ttl-seconds`（默认 21600 秒），每次 `put` 重设；它不是数据库缓存，失效后由 `resolveClassification` 重新调用分类模型，空结果不写。
9. 限流脚本每次都 `ZREMRANGEBYSCORE` + `ZCARD`；`used >= limit` 直接 `return -1`，没有 `ZADD` / `PEXPIRE`，因此不登记、不刷新 TTL。只有放行分支才 `ZADD` + `PEXPIRE`。Java 收到 `null` 或捕获异常时 `tryAcquire` 返回 true；这只是限流判定放行，后续 Redisson 锁、`chat:info`、记忆等 Redis 依赖仍可能让聊天失败。
</details>

### 练习 B：追踪 chat 入口到锁、订阅、事件和终态

目标：用源码定位“谁创建事件、外层与内层谁先订阅、谁释放锁，以及哪些信号不能保证客户端观察到”。

| 步骤 | 搜索位置 | 预期发现 | 判断依据 |
|---|---|---|---|
| 1 | `ChatController#chat` | `POST /chat/v2/c/{id}` 返回 `Flux<AgentEvent>`；无 `produces` | 方法签名与注解 |
| 2 | `ChatRequestRunner#run` 的 `return Flux.defer(...)` | UUID 在 defer 外同步校验；Controller 返回外层 Flux 时 supplier 尚未执行，没有 sink/锁/内部 events/哨兵 | 代码层次 |
| 3 | 同方法 `tryLockAsync` 与 `chats.assertOwned` | HTTP 层订阅外层 Flux 后，supplier 才创建 sink/锁/owner、抢锁并检查归属 | `Flux.defer` 的订阅时机 |
| 4 | 同方法搜 `!acquired`、`catch` 与 `Flux.error` | 竞争失败或归属/获取异常直接返回流；没有内部 events、没有哨兵、没有后台业务 | 提前 return 的词法位置 |
| 5 | 同方法搜 `doOnSubscribe` / `events.subscribe(...)` / `autoConnect(2)` | 成功分支才创建内部 events 并登记第 1 个哨兵；supplier 返回 events 后，原 HTTP 订阅者成为第 2 个内部订阅，`autoConnect(2)` 连接上游并提交后台业务 | `publish().autoConnect(2)` 与 Reactor 3.7.14 `FluxAutoConnect#subscribe` |
| 6 | `AgentEvent` / `ChatServiceImpl#canStartChat` / `SupportAgent` | `notify`、`message`、`error`、`tool_start`、`confirm` 的产生位置 | 工厂方法调用 |
| 7 | `AgentStreams#subscribe` | 单次 3 分钟超时，`doOnNext` / `doOnComplete` / 错误终止 | `takeUntilOther(Mono.delay(...))` |
| 8 | `ChatRequestRunner` 的 `doFinally`、Reactor 3.7.14 `FluxDoFinally` | 上游 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 都可触发回调；lambda 忽略信号类型，仅异步 `unlockAsync` | 版本源码与 `publish()` 前的位置 |
| 9 | `GlobalExceptionHandler`、11.3 与 12.3/12.4 | 同步异常、defer 内 `Flux.error`、流内 error 事件、`sink.tryEmitError`、响应是否已提交需分开 | 异常层与观察边界 |

<details>
<summary>练习 B 参考答案</summary>

1. `ChatController#chat` 从 `UserTokenContext` 取 userId 作为参数，返回 `requestRunner.run(..., false)` 的 `Flux<AgentEvent>`；类和这个方法都没有 `produces`，也没有显式 SSE 类型。
2. `UUID.fromString(chatId)` 在 `Flux.defer` 外，非法值会同步抛异常；Controller 只拿到外层 `Flux.defer` 时，supplier 尚未执行，sink、锁、内部 `events` 和哨兵都还不存在。
3. HTTP 层订阅外层 Flux 后，`defer` 的 supplier 才开始执行：创建 `Sinks.Many`、`chat:execution:{chatId}` 锁、随机 long owner；`tryLockAsync(0, -1, TimeUnit.SECONDS, owner)` 的 0 是不排队，`-1` 是 Redisson 租约参数，`acquisition.get(3, TimeUnit.SECONDS)` 只等加锁 Future，不是整轮超时。Future 晚到且成功时有补偿解锁；随后 `chats.assertOwned` 检查归属。
4. `!acquired` 返回 `Flux.just(AgentEvent.error(...))`：流里有一条 error 事件但 Publisher 正常完成；加锁/归属异常则 `Flux.error(e)`。这些分支都在创建内部 `events` 之前提前返回，因此没有哨兵、没有 `autoConnect` 连接，也没有后台业务。
5. 只有成功分支才创建 `events = sink.asFlux().doOnSubscribe(...).doFinally(...).publish().autoConnect(2)`，并 `events.subscribe(...)` 登记第 1 个内部订阅者（后台哨兵）。supplier 返回 `events` 后，外层 `Flux.defer` 把**同一个 HTTP 订阅者**接到内部 `events` 上，成为第 2 个内部订阅者；这不是第二个 HTTP 请求。`autoConnect(2)` 连接上游后 `doOnSubscribe` 才把 `service.chat/resume` 提交给 `chatExecutor`。
6. `AgentEvent.notify` 来自分类结果/限流提示/锁定提示；`message` 来自模型增量文本或提示文字；`tool_start` 来自工具监听器；`confirm` 来自 `AgentEvent.comfirm`；`error` 来自空 prompt、模型错误、`guard` 兜底等。`confirm` payload 是 JSON 字符串。
7. `AgentStreams.subscribe` 给每次 `Flux<ChatResponse>` 配一个 3 分钟 `Mono.delay`；成功片段走 `doOnNext`，模型流完成走 `doOnComplete`，错误/超时走错误回调并 `sink.tryEmitError`。工具循环会重新订阅、重新计时，因此没有“整轮 3 分钟”保证。
8. `doFinally` 挂在 `sink.asFlux()` 上、`publish()` 之前。Reactor Core 3.7.14 `FluxDoFinally` 表明上游收到 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 都会触发一次回调；本项目忽略信号类型，只异步调用 `lock.unlockAsync(owner)`，不等待结果。HTTP 断连是否让该上游收到 `CANCEL` 尚未实测，不能把一般算子语义当成本项目观察事实。
9. 方法返回 Flux 前的同步异常可能由 `GlobalExceptionHandler` 按类型映射；`defer` supplier 内 `Flux.error` 是外层订阅阶段的 Publisher 错误，不能无条件保证 403；流内 `AgentEvent.error` 只是普通数据元素，不保证送达；`sink.tryEmitError` 是错误终止，是否转成 HTTP 状态还取决于响应是否已提交。`unlockAsync` 成功时点和客户端最终观察到什么都没有本轮实测。
</details>

### 可选隔离观察（本轮未运行）

若读者以后准备了一次性 Redis 测试实例，并且已有测试写入了 key，可以只用只读命令观察结构与 TTL：

```powershell
redis-cli -p <isolated-test-port> TYPE "driver:online:beat"
redis-cli -p <isolated-test-port> HGETALL "driver:online:beat"
redis-cli -p <isolated-test-port> TTL "driver:geo:online"
redis-cli -p <isolated-test-port> ZRANGE "delay:order:timeout" 0 -1 WITHSCORES
redis-cli -p <isolated-test-port> TTL "order:geo:pool"
```

预期（**未实测**）：心跳是 `hash`、司机 GEO key 的 TTL 为 `-1`、延迟队列是 `zset` 且 score 为毫秒时间戳、订单 GEO 池若有数据会有剩余毫秒 TTL。不要对业务实例执行；不要使用 `FLUSHALL` 或清空库；测试自身的 `@AfterEach` 会删除它创建的 key，本轮没有运行这些命令，也没有连接任何 Redis。

### 测试阅读（只读，未运行）

本章只把测试当作“有人写过哪些断言”的证据，**没有运行任何测试**。相关文件都在 `src/test/java/com/fancy/rideagenthub/`。

| 测试 | 门控 / mock | 与本章相关的断言 | 没覆盖 |
|---|---|---|---|
| `ChatGuardRegressionTest.java` | 无门控；mock `StringRedisTemplate`，用 HashMap 模拟 `ValueOperations` | 分类缓存 key 的上下文隔离、TTL=0 禁用、空结果不写、Redis 故障降级 | 不执行真实 Lua，不验证网络 |
| `ChatGuardRedisIntegrationTest.java` | `@EnabledIfSystemProperty(named="test.redis.port")`；真实测试 Redis | 滑动窗口 Lua 返回 `1/0/-1`、并发 40 次只放行 7 次、分类缓存 TTL 在 `(21500,21600]` | 未测 Spring MVC/SSE，未测业务实例 |
| `RedisCacheRegressionTest.java` | 同 Redis 端口门控；真实 Redis，部分用例 mock `MysqlMemory` 等 | 数据 List 有 TTL、版本 key 无 TTL、旧租约回填被拒、残缺历史重建、锁令牌不误删新持有者 | 不证明生产 Redis 拓扑，不测断连 |
| `GeoPoolRedisIntegrationTest.java` | 同 Redis 端口门控；mock `RideOrderMapper` | 订单 GEO 池 2h 固定 TTL 不续期；司机 GEO key `TTL=-1`；成员心跳过期只清单个司机 | 未测 Cluster/跨槽，未测真实司机客户端 |
| `OrderDelayQueueIntegrationTest.java` | 同 Redis 端口门控；真实 Redis | 并发领取 20 条不重复、改期不被提前领取、`LIMIT` 批次上限 | 不测 `OrderTimeoutJob` 的 MySQL 扫描全部条件 |
| `RedisLockRegressionTest.java` | 无门控；mock `StringRedisTemplate` | 手写 `RedisLock` 令牌唯一、竞争失败返回 null、按令牌释放、释放异常不抛出 | 不执行真实 Redis，不等同于 Redisson `chat:execution` 锁 |
| `ChatServiceRegressionTest.java` | 无门控；mock `ChatModel`、Agent、缓存、限流、Redis Hash | 限流/缓存命中/空 prompt/分类标签/DANGER/ORDER 粘滞下应发哪些 `AgentEvent` | 没有 HTTP 层、没有真实模型、没有 SSE 线格式断言 |
| `SecurityRepairRegressionTest.java` | 无门控；mock Redisson `RLock`、`ChatService` | `AgentStreams` 完成回调抛异常必须终止 sink；Runner 同步首事件不丢且 `unlockAsync` 被调用 | 只订阅一次配合哨兵；没测真实 3 分钟超时、客户端断开或异步解锁结果 |
| `SecurityRedisIntegrationTest.java` | 同 Redis 端口门控；mock `JavaMailSender`/`UserAuthMapper` | 验证码并发消费、撤销后 Set 索引与令牌删除、密码变更拒绝旧令牌 | 与 SSE 无直接关系，不测登录 HTTP 全链路 |

共同边界：mock 回归测试不执行真实 Lua/网络；端口门控的 Redis 测试需要隔离的本地 Redis，并用 `@AfterEach` 删除自己生成的 key；仓库没有 Redis Cluster、hash tag、跨槽脚本、真实 SSE 字节、3 分钟超时或客户端断连的测试证据。

---

## 十五、一页复习

**Redis 部分**

- 六种结构：String（令牌/状态）、Hash（用户位置/心跳/会话字段）、Set（用户令牌索引）、List（聊天历史）、ZSet（延迟队列/限流/工单池）、GEO（司机位置/待接单订单池）。
- key TTL `EX` 是秒、`PX/PEXPIRE/PTTL` 是毫秒；ZSet score 只是业务排序值；成员超时靠 Hash 心跳逐成员判断。
- 聊天历史主链：`MessageMemory#save` 先写 MySQL，再由 `APPEND_HISTORY_IF_VERSION_MATCH` 按版本与缓存存在状态追加/失效；`#get` 用 `getAll` 后 Java 截窗口。`append` / `getLastN` 是辅助或测试路径。
- 限流脚本所有调用先清理、计数；超限 `return -1` 前不 `ZADD` / `PEXPIRE`，只有放行分支登记并刷新 TTL。`ChatRateLimiter` 异常/`null` 放行只越过限流，不保证后续锁、会话、记忆可用。
- 固定过期、按分支刷新、仅首次设置、整体无过期、业务主动删除要分开。Redis 丢失后：可回 MySQL / 重调模型 / 用户重新操作 / 有范围 DB 兜底 / 无法恢复，不能统一说“变慢”。
- Lua 脚本在 Redis 服务端一次执行多个命令，期间不插入其它 Redis 命令；不跨 MySQL、不自动回滚、不保证幂等。多 key 在 Redis Cluster 上须同槽，仓库没有 Cluster 或 hash tag 配置。

**对话流部分**

- 普通响应一次写完；`Flux<AgentEvent>` 是响应式多值返回。Spring MVC 6.2.15 在 Servlet 异步支持上按 Accept/媒体类型适配 `Flux`；返回 `Flux` 不等于 WebFlux。
- `ChatController` 没有 `produces`；若协商为 `text/event-stream`，框架用 `SseEmitter` 写 `data:` 行；其它媒体类型可能收集为 List。项目没有显式构造 SSE 帧，实际线格式与响应头未实测。
- `AgentEvent` 是 `record(type, payload)`，工厂 `comfirm` 拼写特殊；流内 error 事件、错误终态、同步 HTTP 状态、响应是否已提交是不同概念，不能无条件保证 403、事件送达或客户端观察到错误。
- `ChatRequestRunner` 返回外层 `Flux.defer`；HTTP 订阅后才创建 sink/锁并检查归属，成功分支才构造内部 `events`、登记哨兵第一订阅，同一个 HTTP 订阅者成为第二订阅，`autoConnect(2)` 才连接上游。失败分支没有内部 events/哨兵。UUID 校验在 defer 外。
- `doFinally` 在 `publish()` 前的上游链：Reactor Core 3.7.14 对 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 都触发；本项目忽略信号类型，只异步 `unlockAsync`，不等待结果。HTTP 断连是否传播到该上游 cancel 未实测。
- `AgentStreams` 的 3 分钟是单次模型订阅超时；工具循环每轮重新计时，源码没有整轮总超时、对应模型流超时配置键或底层模型请求物理取消的保证。

**与 F3 的连接**

- 新建事务与加入外层事务的返回时点不同；接单成功路径先注册状态/GEO 动作，再可选刷新位置，最后注册延迟队列；超时处理可间接改 Redis 但不是通用恢复器；数据库一致性读能看到本事务自己的修改。这些 F3 结论在 F4 不重写，只作为边界。

---

## 十六、自测与答案

**1. 说出六种 Redis 数据结构在本项目各一处真实使用，并指出哪一种完全没有 MySQL 重建路径。**

<details>
<summary>参考答案</summary>

String：`auth:token:{token}` 存 `UserToken` JSON；Hash：`user:loc:{userId}` 存纬度/经度/地址；Set：`auth:user_tokens:{userId}` 存该用户 token；List：`chat:history:{chatId}` 存消息 JSON；ZSet：`delay:order:timeout` 以到期毫秒时间戳为 score；GEO：`driver:geo:online` 存司机坐标。完全没有 MySQL 重建路径的例子是 `user:loc:{userId}` 或司机在线位置：`UserServiceImpl` 和 `DriverGeoIndex` 都没有 Mapper 写入，丢失后需要用户/司机重新上报。令牌、验证码、确认槽位等也不是 MySQL 缓存。
</details>

**2. `order:geo:pool` 固定 2 小时 TTL，而 `driver:geo:online` 没有整体 TTL。为什么不能给后者也简单设 2 小时？**

<details>
<summary>参考答案</summary>

因为 `order:geo:pool` 是从 MySQL `CREATED` 订单整池重建的索引，固定 TTL 能让旧索引自然校准；不续期可以避免旧快照无限存活。`driver:geo:online` 是所有司机共享的实时位置池，Redis 的 `EXPIRE` 只能作用于整个 key：任何一个司机心跳都可能刷新整个 key 的 TTL，导致不再心跳的司机成员永远留着。实现改为把最后心跳放进 `driver:online:beat` Hash，用 60 秒阈值在读取/刷新时逐成员清理。这是成员级失效，不是 key 级 TTL。
</details>

**3. 追踪 `SLIDING_WINDOW_RATE_LIMIT`：写出 KEYS、三个 ARGV、返回值与 Java 分支。拒绝调用是否刷新 TTL？脚本报错会不会回滚已执行命令？**

<details>
<summary>参考答案</summary>

`KEYS[1]` 是 `chat:ratelimit:{userId}`；`ARGV[1]` 是窗口毫秒数，`ARGV[2]` 是限额，`ARGV[3]` 是 UUID member。**所有调用**先 `ZREMRANGEBYSCORE` 清理、`ZCARD` 计数；若 `used >= limit`，脚本直接 `return -1`，没有 `ZADD`、没有 `PEXPIRE`，所以拒绝调用不登记、不刷新 key TTL。未超限才 `ZADD` 本次 UUID、`PEXPIRE` 刷新 TTL，并返回剩余额度 `limit-used-1`。Java 中 `remaining >= 0` 放行；`-1` 拒绝；`null` 或捕获异常时 `tryAcquire` 本次也返回 true，但这只越过限流判定，后续 Redisson 锁、`chat:info`、记忆等 Redis 依赖仍可能让聊天失败。脚本报错不会像关系型事务那样回滚已执行的 `redis.call`；Redis 只保证脚本执行期间其它命令不穿插，已发生的修改可能保留。
</details>

**4. 用短时间线说明“多条独立命令”与“一个 Lua 脚本”在并发限流中的差别。**

<details>
<summary>参考答案</summary>

假设限额 2、已有 1 次调用。独立命令下，T1/T2 可能都在 `ZCARD` 看到 1，随后各自 `ZADD`，结果两人都放行、used=3。每条命令原子，但检查与登记之间能插入另一客户端。Lua 下 T1 脚本先清理、计数=1，再 `ZADD` + `PEXPIRE` 放行；T2 脚本同样先清理、计数=2，随后 `used >= limit` 直接返回 -1，被拒分支**不执行 `ZADD` / `PEXPIRE`**，所以不刷新 TTL。Lua 解决的是 Redis 侧“检查 + 登记”的原子隔离；不解决跨库事务、脚本错误回滚或业务幂等。
</details>

**5. 为什么不能说“Redis 清空只会让系统变慢”？举三个不同恢复类别。**

<details>
<summary>参考答案</summary>

`chat:history` 可从 MySQL 回填，通常只是多一次查询；`chat:classify:*` 没有 DB 副本，要重新调用分类模型；`auth:token:*` / `user:loc:*` / 报价确认槽位 / 司机在线位置只有 Redis 一份，分别需要重新登录、重新上报位置、重新走确认/上线流程；`delay:order:timeout` 只能由 `OrderTimeoutJob` 在状态/时间/开关/批次满足时部分兜底。故“只是变慢”只适用于少数可回源缓存，不能概括全部 Redis 数据。
</details>

**6. 本项目方法返回 `Flux<AgentEvent>`，为什么说它不是 WebFlux 应用？`Accept` 会影响什么？**

<details>
<summary>参考答案</summary>

`pom.xml` 只有 `spring-boot-starter-web`，没有 `spring-boot-starter-webflux`；启动类未设置 `WebApplicationType`，`WebMvcConfig` 实现 Servlet 的 `WebMvcConfigurer`。Spring Framework 6.2.15 的 `ReactiveTypeHandler` 让 Spring MVC 在 Servlet 异步请求中适配 `Publisher`：多值 `Flux` 在媒体类型含 `text/event-stream` 时用 `SseEmitter`，其它普通媒体类型可能收集为 List。`ChatController` 没有 `produces`，所以 `Accept` 与内容协商决定是否走 SSE；不能断言任何 `Accept` 下都是 `text/event-stream`。这些都是框架规则，实际响应头与线格式本轮未实测。
</details>

**7. `AgentEvent` 有哪些字段和事件类型？为什么 `confirm` 事件容易搜不到？**

<details>
<summary>参考答案</summary>

`AgentEvent` 是 `record(String type, Object payload)`；工厂方法产生 `"message"`、`"tool_start"`、`"error"`、`"confirm"`、`"notify"`。产生 confirm 的方法名实际拼写为 `comfirm`（少一个 n），直接搜 `AgentEvent.confirm(` 会漏掉；要搜 `AgentEvent.comfirm(` 或 `"confirm"`。confirm 的 payload 在 `OrderAgent` 里是 `objectMapper.writeValueAsString(orderParams)` 生成的 JSON 字符串，不是已展开的对象。
</details>

**8. 单轮执行锁在哪些分支释放，哪些分支不释放？`doFinally` 对哪些信号生效？客户端断开能立刻释放吗？**

<details>
<summary>参考答案</summary>

先按订阅顺序：Controller 返回外层 `Flux.defer` 时 supplier 尚未运行；HTTP 层订阅外层后 supplier 才创建 sink/锁并检查归属。竞争失败返回 `Flux.just(AgentEvent.error(...))`，获取/归属异常返回 `Flux.error(e)`，都在创建内部 `events` 之前结束：**没有内部 events、没有哨兵、没有后台业务，也没有上游 `doFinally` 路径**。竞争失败没有拿锁，不释放他人的锁；晚到 Future 若 `late=true` 有补偿 `unlockAsync`；异常分支在 `isHeldByThread(owner)` 时异步解锁。只有成功分支才构造内部 `events`、登记哨兵第一订阅，再由同一个 HTTP 订阅者成为第二订阅并连接上游。

`doFinally` 挂在 `publish()` 前的 `sink.asFlux()` 上。Reactor Core 3.7.14 `FluxDoFinally` 对该上游的 `ON_COMPLETE` / `ON_ERROR` / `CANCEL` 都会调用一次回调；本项目 lambda 忽略信号类型，只异步 `unlockAsync(owner)`，不等待结果，释放成功时点不确定。注意这只能说明“**上游若收到 cancel** 会触发”，不能说明 HTTP 断连一定传播到该上游：Spring MVC 断连通知、`publish()/autoConnect` 的取消传播和内部哨兵共同决定，仓库没有实测。因此不能承诺客户端一断开锁立刻释放；Servlet API 也不提供即时断连通知。
</details>

**9. `AgentStreams` 的 3 分钟超时是整轮 SSE 总时限吗？超时后一定返回 HTTP 500 吗？**

<details>
<summary>参考答案</summary>

不是。`Duration.ofMinutes(3)` 包在 `AgentStreams.subscribe` 的 `takeUntilOther(Mono.delay(...))` 上，作用于一次 `openAiChatModel.stream(prompt)` 订阅；工具循环递归 `runLoopStep` 会重新 `stream` 并重新计时。仓库没有整轮总超时配置。起点应按 12.2：外层 Flux 已被订阅、抢锁/归属成功、内部 events 与哨兵已建、`autoConnect(2)` 已连接上游、后台业务已提交。超时走 Publisher 错误回调：通常先尝试 `AgentEvent.error`，该事件是否送达取决于连接与写入状态；随后 `sink.tryEmitError` 使上游以 `ON_ERROR` 终止，`doFinally` 异步 `unlockAsync(owner)`。若响应头已提交，不能把它写成一定返回 HTTP 500；流内 error 事件、错误终态、HTTP 状态与客户端实际观察要分开。底层模型 HTTP 请求是否被物理取消，源码没有证明。
</details>

**10. F3 的哪些结论在本章需要保留？列出至少三条。**

<details>
<summary>参考答案</summary>

① 代理新建物理事务时，调用者拿到结果前完成提交/回调；加入外层事务时，本次调用可先返回外层方法，物理事务由最外层决定。② `driverAcceptOrder` 成功路径先注册状态缓存与 GEO 失效，再可选刷新司机位置，最后注册延迟队列动作；位置刷新是先于提交的 Redis 直接写入，条件更新自身异常时不会走到它。③ `OrderTimeoutJob` 扫描 MySQL 后调用 `handleTimeoutOrder`，可能间接同步/删除状态缓存、失效 GEO 池、释放占位或重排队列，但不是通用 Redis 恢复器。④ RC 一致性读只看其他事务已提交数据，同时能看到本事务自己的修改；锁定读也基于当前记录与本事务修改。
</details>

**11. 聊天历史的当前主链是 `RedisMemory#append` / `#getLastN` 吗？一次保存与一次读取分别走哪些方法？**

<details>
<summary>参考答案</summary>

不是。`MessageMemory#save` 先 `mysqlMemory.append(...)` 持久化，再调用 `redisMemory.appendIfVersionMatch(...)`；对应 Lua 是 `APPEND_HISTORY_IF_VERSION_MATCH`。脚本总递增并 `PERSIST` 版本 key：版本不符时删除旧缓存，版本匹配且数据 key 存在时才 `RPUSH` + `PEXPIRE`，缓存缺失时不创建残缺列表。`MessageMemory#get` 的 Redis 路径调用 `redisMemory.getAll(chatId)`（`LRANGE 0 -1`），随后在 Java 里 `tail(redisAll, lastN)` 截消息窗口；L2 缺失才查 MySQL 并 `overwriteIfLeaseValid` 持租约回填。`RedisMemory#append`、`#getLastN` 和 `APPEND_WITH_VERSION_BUMP` 存在且被 `RedisCacheRegressionTest` 等测试直接调用，但当前 `src/main/java` 的 `MessageMemory` 主链没有调用它们，不能写成本章主链。
</details>

**12. Controller 同步抛错、`Flux.defer` 内 `Flux.error`、流内 error 事件、`sink.tryEmitError` 与 HTTP 响应是否已提交分别是什么关系？**

<details>
<summary>参考答案</summary>

Controller 返回 Flux 前的同步异常（参数绑定、`UserTokenContext`、`UUID.fromString`）发生在 Publisher 返回/订阅前，若响应未提交可能由 `GlobalExceptionHandler` 按类型映射，但不能据此无条件说“归属一定 403”：`chats.assertOwned` 在 `defer` supplier 内，异常会变成 `Flux.error`。`Flux.error` 是外层订阅阶段的 Publisher 错误，没有内部 events/哨兵；是否映射成 HTTP 状态要看框架路径与响应是否已提交。流内 `AgentEvent.error` 只是普通数据元素，不保证送达；`sink.tryEmitError` 是错误终止，若到达上游会触发 `doFinally` 异步 `unlockAsync`，但不保证客户端观察到具体错误对象或 HTTP 错误码。响应头已提交后状态码不能再改成 403/500，只能靠流内事件、错误终态或连接关闭表达。本轮没有发送 HTTP 请求，不能补造实测行为。
</details>

---

## 十七、导航

- **上一章**：[第 3 章 数据存在哪，事务管到哪](03-data-and-transactions.md)。
- **下一章**：[第 5 章 登录与会话](../01-auth/01-login-and-session.md)——本章的令牌三 key、验证码族与 Lua 原子性在登录链里系统展开。
- **相关专题**：
  - 抢单完整 Lua 与条件更新 → [第 8 章 抢单与并发](../02-orders/02-grab-and-concurrency.md)
  - 超时、延迟队列与补偿 → [第 10 章 超时与恢复](../02-orders/04-timeouts-and-recovery.md)
  - 缓存竞态、版本租约与双删 → [第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)
  - 一轮对话状态机、工具循环与 SSE 细节 → [第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)、[第 17 章 分类路由与工具循环](../04-chat-and-agent/02-routing-and-tool-loop.md)、[第 19 章 三层记忆与工具结果反查](../04-chat-and-agent/04-memory-and-tool-results.md)
  - Redis 类型/TTL/Lua 的更多横向比较 → [第 15 章 锁、版本租约与唯一 ID](../03-support-and-cache/04-locks-leases-and-ids.md)

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；资源、配置与测试脚本写完整仓库相对路径。

| 结论 | 源码 / 资料位置 |
|---|---|
| Web/Reactor 依赖与受管版本 | `pom.xml`；本机 `~/.m2/repository/org/springframework/boot/spring-boot-dependencies/3.5.9/spring-boot-dependencies-3.5.9.pom`（Spring Framework 6.2.15、Reactor BOM 2024.0.13）；本机 `~/.m2/repository/io/projectreactor/reactor-bom/2024.0.13/reactor-bom-2024.0.13.pom`（Reactor Core 3.7.14） |
| Redis key 前缀与设计注释 | `constant/RedisKeyConstants.java` |
| 代表性 Lua 与调用方 | `util/RedisScripts.java`（`SLIDING_WINDOW_RATE_LIMIT`、`APPEND_HISTORY_IF_VERSION_MATCH`、`APPEND_WITH_VERSION_BUMP`）；`service/base/ChatRateLimiter.java`、`OrderDelayQueue.java`、`OrderGrabService.java`；`service/impl/TokenServiceImpl.java`；`service/base/EmailCodeService.java` |
| 六种结构的使用者 | `service/impl/TokenServiceImpl.java`、`UserServiceImpl.java`；`service/base/DriverGeoIndex.java`、`OrderGeoPool.java`、`TicketPoolIndex.java`；`agentbase/memory/MessageMemory.java#save/#get`、`RedisMemory.java#appendIfVersionMatch/#getAll/#getLastN/#append`；`ToolResponseMemory.java`；`service/base/ClassificationCache.java`、`ChatRateLimiter.java`；`agentbase/chatinfo/ChatManager.java`、`service/base/OrderConfirmation.java` |
| 聊天历史真实主链 | `agentbase/memory/MessageMemory.java#save/#get/#switchFocusChatIfNeeded/#tail`；`RedisMemory.java#appendIfVersionMatch/#getAll/#overwriteIfLeaseValid`；`util/RedisScripts.java#APPEND_HISTORY_IF_VERSION_MATCH`；测试辅助路径 `RedisCacheRegressionTest`（直接调 `RedisMemory`），`RepairDatabaseIntegrationTest` 只用 `MysqlMemory` |
| TTL 配置来源 | `src/main/resources/application.yaml`；`config/AuthProperties.java`、`ChatGuardProperties.java`、`RideOrderTimeoutProperties.java` |
| 事务/回调边界 | `service/impl/RideOrderServiceImpl.java#driverAcceptOrder`、`#handleTimeoutOrder`、`#handleArriveTimeout`、`#systemCancel`；`OrderGrabService#afterCommit`；`OrderDelayQueue#afterCommit`；`OrderGeoPool#remove` |
| 对话入口与事件 | `controller/ChatController.java`；`domain/dto/AgentEvent.java`；`service/impl/ChatServiceImpl.java`；`exception/GlobalExceptionHandler.java`；`agents/AgentStreams.java`、`SupportAgent.java`、`OrderAgent.java`、`DailyAgent.java` |
| 单轮锁与后台执行 | `service/base/ChatRequestRunner.java`；`config/AsyncWorkConfig.java`；`src/main/resources/api/chat-security.md`；Reactor Core v3.7.14 `FluxAutoConnect.java`、`FluxDoFinally.java` |
| 客户端请求条件 | `src/test/llm_judge_eval.py#build_sut_headers`、`#sse_events`、`#call_chat` |
| 框架规则（精确版本） | Spring Framework v6.2.15 `ReactiveTypeHandler.java`、`SseEmitter.java`；6.2.x 官方《Asynchronous Requests》Reactive Types / Disconnects / Configuration 小节；6.2.x《Return Values》；Reactor Core v3.7.14 `FluxDoFinally.java`（`ON_COMPLETE`/`ON_ERROR`/`CANCEL`）与 `FluxAutoConnect.java`（达到订阅数时 `source.connect`） |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/ChatGuardRedisIntegrationTest.java`、`RedisCacheRegressionTest.java`、`CacheConsistencyRegressionTest.java`、`RepairDatabaseIntegrationTest.java`、`GeoPoolRedisIntegrationTest.java`、`OrderDelayQueueIntegrationTest.java`、`ChatGuardRegressionTest.java`、`ChatServiceRegressionTest.java`、`SecurityRepairRegressionTest.java`、`RedisLockRegressionTest.java`、`SecurityRedisIntegrationTest.java` |
