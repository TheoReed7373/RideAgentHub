# 司机在线心跳：共享 GEO + 心跳 Hash

> **Redis 考点**：GEO key 的成员无法单独设置过期——用「共享 GEO 存位置 + Hash 存心跳时间」的组合实现成员级 TTL；时间源统一为 Redis `TIME`，过期判定与清理原子完成。
> **来源**：`docs/01-redis-application-points.md` §2.5（订单与司机）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/DriverGeoIndex.java`、`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（`UPDATE_DRIVER_LOCATION` / `REMOVE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION`）

---

## 一、业务场景

司机在线状态的两个需求：

1. **位置**：附近订单查询要以司机坐标为圆心（[15 篇](15-nearby-order-geo.md)）；接单请求携带的坐标要能刷新在线司机位置；
2. **在线判定**：60 秒无心跳即离线——司机直接杀掉 App 不会发「下线」请求，必须靠时间判定。

难点：**GEO key 的成员没有独立 TTL**（过期只能作用于整个 key）。把「成员级过期」拆成两个原语：

- 共享 GEO key 存坐标（`GEOADD` / `GEOPOS`）；
- 心跳 Hash 存每个司机最后活跃的毫秒时间戳（`HSET driverId → now`）。

过期判定 = `now - 心跳 < 60s`，由 Lua 在读取时原子执行；清理 = 把两个结构里的同名成员一起摘掉。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `DRIVER_ONLINE_BEAT_KEY`（`RedisKeyConstants`） | Hash：`driverId → 毫秒时间戳` | 无（成员级语义靠判定） | 心跳 |
| `DRIVER_GEO_ONLINE_KEY`（`RedisKeyConstants`） | GEO：`driverId → lng lat` | 无（成员级语义靠判定） | 在线司机坐标 |

常量：`ONLINE_TTL = 60 秒`。坐标合法性用 Web Mercator 纬度范围（±85.05112878，`validCoordinates`）——GEO 底层 geohash 的定义域，而非通常的 ±90。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `DriverGeoIndex.goOnline` | 上线：`UPDATE_DRIVER_LOCATION`（`online` 模式） |
| `DriverGeoIndex.refreshIfExist` | 接单时刷新：同一脚本 `refresh` 模式——**只更新尚未过期的司机** |
| `DriverGeoIndex.goOffline` | 主动下线：`REMOVE_DRIVER_LOCATION` 摘除两个成员 |
| `DriverGeoIndex.location(driverId)` | 一次读取在线状态 + 坐标：`READ_DRIVER_LOCATION`，过期顺手清理 |
| `DriverGeoIndex.status` / `position` | `location` 的语义化封装 |
| `DriverLocationServiceImpl` | HTTP 层：上报（写入失败返回 false 提示重试）、查询（不可用抛 503，不误报离线） |

---

## 四、实现拆解

### 4.1 `UPDATE_DRIVER_LOCATION`：两种模式

```lua
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if ARGV[5] == 'refresh' then
    local beat = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
    if not beat or now < beat or now - beat >= tonumber(ARGV[4])
            or not redis.call('ZSCORE', KEYS[2], ARGV[1]) then
        redis.call('HDEL', KEYS[1], ARGV[1])
        redis.call('ZREM', KEYS[2], ARGV[1])
        return 0                                   -- 已离线/脏心跳 → 清理，不刷新
    end
end
redis.call('GEOADD', KEYS[2], ARGV[2], ARGV[3], ARGV[1])
redis.call('HSET', KEYS[1], ARGV[1], string.format('%.0f', now))
return 1
```

- **时间源是 Redis `TIME`**：多实例部署下应用时钟偏斜会造成「实例 A 判在线、实例 B 判离线」的分裂，Redis 单点时间是唯一可信公共时钟；
- `refresh` 模式的四重校验：心跳存在、不是「未来时间」（`now < beat` 的脏数据防御）、未超时、GEO 成员仍在——**任何一个不满足就把两个成员都清掉**：接单请求不能把已下线（或心跳脏）的司机「复活」；
- `online` 模式无前置校验：上线即写入。

### 4.2 `READ_DRIVER_LOCATION`：检查 + 读取 + 清理一体

```lua
local beat = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
local positions = redis.call('GEOPOS', KEYS[2], ARGV[1])
if beat and now >= beat and now - beat < tonumber(ARGV[2]) and positions[1] then
    return positions[1]                -- 在线：返回同一时刻的坐标
end
redis.call('HDEL', KEYS[1], ARGV[1])   -- 过期 / 坐标缺失：顺手清理两个成员
redis.call('ZREM', KEYS[2], ARGV[1])
return {}
```

「**检查心跳**」与「**读取位置**」在同一次执行内完成——拆开的话，「检查通过」与「读到坐标」之间司机恰好过期，会拿到已离线司机的坐标。孤儿成员（有心跳无坐标 / 有坐标无心跳）同样被顺手清理，两个结构不会各自留下脏数据。

Java 侧 `location()` 的三态返回：`ONLINE`（带坐标）/ `OFFLINE`（空）/ **`UNAVAILABLE`**（Redis 异常）——位置服务不可用时**不误报离线**，HTTP 层据此返回 503。

### 4.3 上报的「写失败要重试」语义

`DriverLocationServiceImpl` 上报接口返回 `Result<Boolean>`：HTTP 上报调用 online 模式；Redis 异常被 DriverGeoIndex 捕获后返回 `data=false`，refresh 的离线判定只用于接单刷新，客户端应重试。**写入失败≠成功上报**——司机以为自己在上线，实际已经掉线，附近订单查询会以旧坐标或离线处理。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 共享 GEO + 心跳 Hash 两个结构 | 单 GEO（成员无法单独过期） / 每司机独立 key（无法 `GEORADIUS`） | 成员级 TTL + 范围查询两个需求各取一个结构 |
| Redis `TIME` 作时间源 | 应用时钟 | 多实例时钟偏斜分裂判定 |
| 读取时惰性清理 | 定时扫描（ZSet / 后台任务） | 惰性清理零额外组件；被查询到的过期成员当场清掉，查不到的留着无害 |
| 判定与读取原子（Lua） | 先 HGET 再 GEOPOS | 两命令之间状态可能变化 |
| 三态（ONLINE / OFFLINE / UNAVAILABLE） | 两态（把故障当离线） | 误报离线会触发错误的业务分支（接单刷新失败 vs 司机真下线） |

---

## 六、边界与已知问题

1. **孤儿成员理论存在、读取即清**：应用进程在 Lua 调用途中退出不会中断 Redis 端脚本；手工改数、数据损坏或脚本运行时错误等仍可能造成单边成员——下一次该司机被读取时被顺手清理；未被读取的成员只是少量空间冗余。
2. **心跳写入无 TTL 兜底**：两个结构都是无 TTL 的共享 key，成员清理完全靠 Lua 惰性判定。没有定时全量清理，长期不再被查询、刷新或主动下线的成员会一直保留，空间占用可随历史司机数增长。
3. **60 秒窗口内「心跳后立刻杀进程」**：最长 60 秒内司机仍被判在线（附近订单圆心用旧坐标）——业务容忍（订单匹配粒度远大于此）。
4. **`refreshIfExist` 不上线**：接单刷新只针对已在线司机（4.1 的 `refresh` 校验）；未上线司机接单不会出现在在线池（与 [14 篇](14-grab-order-lua.md) 的 `refreshIfExist` 调用对应）。

---

## 七、如何验证

```bash
# 1. 心跳与坐标
redis-cli HGET <driver:online:beat key> <driverId>      # 期望：毫秒时间戳
redis-cli GEOPOS <driver:geo:online key> <driverId>     # 期望：[lng, lat]

# 2. 时间源：心跳值应与 Redis 服务器时间一致（与应用时钟无关）
redis-cli TIME                    # 秒.微秒；换算毫秒与心跳值比对

# 3. 过期清理：等待 60 秒不心跳后查询该司机状态
redis-cli HGET <beat key> <driverId>                    # 期望：(nil)（读取时被清理）
redis-cli GEOPOS <geo key> <driverId>                   # 期望：(nil)

# 4. 主动下线（POST /order/driver/offline）：两个成员立即消失
# 5. 接单刷新：离线司机的接单请求携带坐标 → 不会重新上线（Lua 返回 0 并清理）
```

---

## 八、延伸阅读

- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 订单侧 GEO 池（版本化整池）与本篇共享成员池的对照
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 本篇三段脚本速查（含 `TIME` 时间源清单）
- [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) —— 另一处 Redis `TIME` 的应用
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 接单请求的坐标刷新链路
