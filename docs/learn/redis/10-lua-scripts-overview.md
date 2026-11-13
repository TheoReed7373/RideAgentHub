# Lua 脚本总览：22 个主脚本 + 11 个内联脚本

> **Redis 考点**：Lua 把「多条命令 + 中间判断」收成一次原子执行——判断与动作之间不存在被并发插队的窗口。
> **来源**：`docs/01-redis-application-points.md` §2.4（原子操作与分布式锁）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（22 个主脚本）+ 5 个业务类的 11 个内联脚本

---

## 一、为什么需要 Lua

Redis 单线程执行命令，但**两条命令之间存在间隙**：`GET` 判断锁归属之后、`DEL` 之前，锁可能已经易主。项目里所有「读 → 判断 → 写」的模式最终都收敛到 Lua：

| 场景 | 若不用 Lua 的窗口 |
|---|---|
| 令牌锁释放 | `GET` 校验与 `DEL` 之间锁易主 → 误删他人的锁 |
| 抢单 | 「订单可抢」判断与「占位」之间订单被抢走 |
| 限流 | 「剔除过期 + 计数 + 登记」三步之间被并发穿插 → 计数失真 |
| 版本校验回填 | 「校验版本」与「替换数据」之间并发写入 → 数据丢失 |
| 心跳判定 | 「读心跳」与「读位置」之间司机恰好过期 → 读到已离线司机的坐标 |

Lua 脚本执行期间不会插入其他客户端命令；但运行时错误不会回滚此前已完成的写入，原子隔离不等于事务回滚。

---

## 二、脚本分布

| 位置 | 数量 | 说明 |
|---|---|---|
| `util/RedisScripts.java` | **22** 个 `DefaultRedisScript` | 集中维护，`public static final` 直接引用 |
| `service/impl/TokenServiceImpl` | 3 个内联 | `SAVE` / `REVOKE` / `REMOVE`（token 发布与撤销） |
| `service/base/EmailCodeService` | 4 个内联 | `ISSUE` / `VERIFY` / `CLEANUP` / `COMPLETE`（验证码全生命周期） |
| `service/impl/TicketServiceImpl` | 1 个内联 | `TICKET_SEQUENCE`（工单号 `INCR` + `EXPIRE`） |
| `service/base/OrderConfirmation` | 2 个内联 | `CONFIRM` / `CONSUME`（订单确认状态机） |
| `agentbase/tool/OrderSearchTool` | 1 个内联 | `CANCEL`（取消令牌原子消费） |

统计脚本时的一个排障经验：**用简单 grep `new DefaultRedisScript` 会漏计**——内联脚本有的用完全限定名 `new org.springframework.data.redis.core.script.DefaultRedisScript<>` 声明（`TokenServiceImpl`、`TicketServiceImpl`），必须按类逐个核对。

---

## 三、代码落点速查

### 3.1 主脚本总表（`RedisScripts.java`，22 个）

| # | 脚本 | 返回类型 | 用途 | 调用方 |
|---|---|---|---|---|
| 1 | `UPDATE_DRIVER_LOCATION` | Long | 原子更新位置与心跳；`refresh` 模式只更新未过期司机；时间源 Redis `TIME` | `DriverGeoIndex.update` |
| 2 | `REMOVE_DRIVER_LOCATION` | Long | 原子摘除心跳 + GEO 成员 | `DriverGeoIndex.goOffline` |
| 3 | `READ_DRIVER_LOCATION` | **List** | 原子检查心跳并返回同一时刻的位置；过期时顺手清理 | `DriverGeoIndex.location` |
| 4 | `INVALIDATE_ORDER_GEO_POOL` | Long | 换版本号 + 删就绪标记与 GEO 池 | `OrderGeoPool.invalidate` |
| 5 | `PUBLISH_ORDER_GEO_POOL` | Long | 校验版本与锁令牌后原子发布整池（`GEOADD` 批量 + `PEXPIRE` + 就绪标记） | `OrderGeoPool.search` |
| 6 | `SEARCH_ORDER_GEO_POOL` | **List** | 原子读就绪标记、成员数与 `GEORADIUS` 结果 | `OrderGeoPool.read` |
| 7 | `INVALIDATE_TICKET_POOLS` | Long | 换版本号 + 删全部状态池 | `TicketPoolIndex.syncByTicketId` |
| 8 | `PUBLISH_TICKET_POOL` | Long | 校验版本与锁令牌后原子发布 ZSet 索引与就绪标记 | `TicketPoolIndex.slice` |
| 9 | `READ_TICKET_POOL` | **List** | 原子校验版本、成员数并读总数与当前页（`ZREVRANGE`） | `TicketPoolIndex.readSlice` |
| 10 | `CLAIM_DUE_ORDERS` | **List** | 一次操作内领取并移除到期任务（`ZRANGEBYSCORE` + 逐条 `ZREM`） | `OrderDelayQueue.claimDue` |
| 11 | `HASH_SET_WITH_EXPIRE` | Long | `HSET` + `EXPIRE` 原子执行（返回 `EXPIRE` 结果） | `ChatManager.lockChat` |
| 12 | `RELEASE_LOCK_IF_MATCH` | Long | 仅锁令牌匹配时删除锁 | `RedisLock.unlock`、`OrderGrabService.rollbackGrab` |
| 13 | `INVALIDATE_CITY_CODE` | Long | 城市编码失效：`INCR` 版本 + 写墓碑 | `CityCodeUtil.invalidate` |
| 14 | `CACHE_CITY_CODE_IF_VERSION_MATCH` | Long | 版本匹配才 `SET NX EX` 回填城市编码 | `CityCodeUtil.getCityCode` |
| 15 | `CURRENT_HISTORY_VERSION` | Long | 读聊天历史版本号；处理归零/带 TTL 分支并 `PERSIST` | `RedisMemory.currentVersion` |
| 16 | `APPEND_WITH_VERSION_BUMP` | Long | `INCR` 版本 + `PERSIST` + `RPUSH`（通用追加） | `RedisMemory.append` |
| 17 | `APPEND_HISTORY_IF_VERSION_MATCH` | Long | 写路径：总会递增版本；版本不符 DEL 数据，匹配且数据 key 存在才追加并 PEXPIRE，缺失保持 miss | `RedisMemory.appendIfVersionMatch` |
| 18 | `OVERWRITE_IF_VERSION_MATCH` | Long | 回填：校验版本 + `INCR` + `RENAME` 暂存 | `RedisMemory.overwriteIfLeaseValid` |
| 19 | `CLEAR_HISTORY_WITH_VERSION_BUMP` | Long | 清空：`INCR` 版本 + `PERSIST` + `DEL` 数据 | `RedisMemory.clear` |
| 20 | `GRAB_ORDER_ATOMIC` | Long | 检查订单状态与司机占位并原子占位（返回 1 / -1 / -2） | `OrderGrabService.tryGrab` |
| 21 | `SYNC_ORDER_STATUS` | Long | 订单状态单调推进（迟到的旧状态不能覆盖新状态） | `OrderGrabService.syncOrderStatus` |
| 22 | `SLIDING_WINDOW_RATE_LIMIT` | Long | 原子剔除过期记录、检查限额并登记本次调用 | `ChatRateLimiter`、`RequestRateLimitInterceptor` |

返回 `List` 的 4 个（#3 / #6 / #9 / #10）：Lua 的多值返回（位置对、订单+距离、版本+总数+成员、到期订单列表）。

**`false` 与 `nil` 的陷阱**：Lua 里 `redis.call('GET', key)` 对不存在的 key 返回 **false**（不是 nil）。脚本里比较前要显式处理：`SYNC_ORDER_STATUS` 用 `current == false or tonumber(current) == nil` 双重判断，`CACHE_CITY_CODE_IF_VERSION_MATCH` 用 `or '0'` 把 false 归一成可比较的字符串。

### 3.2 内联脚本总表（11 个）

| 脚本 | 所在类 | 用途 |
|---|---|---|
| `SAVE` | `TokenServiceImpl` | 校验会话版本后原子 `SET` + `SADD` + `EXPIRE` 索引 |
| `REVOKE` | `TokenServiceImpl` | `INCR` 版本 + 遍历删全部令牌 + 删索引 |
| `REMOVE` | `TokenServiceImpl` | `DEL` 主体 + `SREM` 索引 |
| `ISSUE` | `EmailCodeService` | 冷却判定 + 三组 key 一次写入 |
| `VERIFY` | `EmailCodeService` | 校验 + 计数 + 消费/预留 |
| `CLEANUP` | `EmailCodeService` | 按归属清理发送失败状态 |
| `COMPLETE` | `EmailCodeService` | 事务提交/回滚后消费或释放预留 |
| `TICKET_SEQUENCE` | `TicketServiceImpl` | `INCR` + `TTL < 0` 时 `EXPIRE 48h` |
| `CONFIRM` | `OrderConfirmation` | 校验 15 分钟时效 + `break` 状态机推进 + 签发/清除创建权限 |
| `CONSUME` | `OrderConfirmation` | 一次性消费创建权限（`HDEL`） |
| `CANCEL` | `OrderSearchTool` | 校验取消令牌 + 绑定订单 + 5 分钟时效 + `HDEL` |

内联的理由：脚本与该类独有的 key 组强绑定（如验证码的五组派生 key 只在 `EmailCodeService` 使用），就近声明可读性更好；代价是统计与检索需要逐类核对。

---

## 四、实现要点

### 4.1 声明形态：直接 `new DefaultRedisScript<>(文本块, 类型)`

主脚本都是 `public static final DefaultRedisScript<Long> X = new DefaultRedisScript<>("""...""", Long.class)`，没有工厂方法、没有 `setScriptText` 二次调用。Spring Data Redis 自动做 EVALSHA → EVAL 的降级（按脚本内容 SHA1 缓存），无需手工管理脚本加载。

### 4.2 时间源：三处使用 Redis `TIME`

`UPDATE_DRIVER_LOCATION` / `READ_DRIVER_LOCATION` / `SLIDING_WINDOW_RATE_LIMIT` 在脚本内 `redis.call('TIME')` 取当前时间。**多实例部署下应用服务器时钟可能偏斜**，以应用时间做心跳判定 / 限流窗口会出现「实例 A 认为在线、实例 B 认为离线」的分裂；Redis 单点时间是唯一可信的公共时钟。

`SLIDING_WINDOW_RATE_LIMIT` 还先调 `redis.replicate_commands()`——让脚本的非确定性（读时钟）在复制时确定化。

### 4.3 版本 key 的 `PERSIST`

历史脚本家族（#15~#19）都会 `PERSIST` 版本 key（`APPEND_WITH_VERSION_BUMP` 里另有作用于数据 key 的「无 TTL」分支）。这是「版本号单调、租约时钟不倒转」的落地，推导见 [09 篇](09-lease-token.md) 4.7。

### 4.4 参数设计惯例

- **KEYS 全部显式传入**（Redis Cluster 按 key 分片路由）；相同前缀不保证同槽；当前 key 未统一使用 `{hash-tag}`，多 key Lua 与历史暂存区 RENAME 尚不能直接用于 Redis Cluster；
- **ARGV 传值**：时间统一毫秒、TTL 按脚本注释（秒或毫秒）；
- **返回码语义化**：`1` 成功、`0` 校验失败、`-1` / `-2` 区分失败原因（`GRAB_ORDER_ATOMIC`：-1 订单不可抢、-2 司机已占用）。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Lua 收口原子性 | `MULTI`/`EXEC` 事务 | `MULTI` 不支持「读 → 判断 → 分支写」（`WATCH` 乐观重试在竞争激烈时退化成自旋）；Lua 一次执行、有分支 |
| Lua + Redisson 混用 | 全部自研 Lua / 全部 Redisson | Redisson 的锁 / 布隆等成熟组件直接用（见 [13 篇](13-distributed-lock.md)）；业务语义的原子判断（抢单、确认状态机）没有现成组件，Lua 是唯一选择 |
| 22 主 + 11 内联 | 全部集中 | 集中便于速查；强绑定单一类的脚本就近内联。两份清单都要同步维护（见「六」） |
| 脚本内 `redis.call('TIME')` | 应用侧传时间 | 多实例时钟偏斜；时间源统一到 Redis |

---

## 六、边界与已知问题

1. **脚本执行期间阻塞整个 Redis**：单实例 Redis 串行执行，慢脚本 = 全局停顿。GEO 与工单池发布遍历全量回表结果，未设置发布条数上限；`REVOKE` 也遍历用户全部 token，数据增长会延长阻塞时间；**不要往脚本里放无界循环或大列表遍历**——历史回填绕暂存 key 而非 `ARGV` 正是这个原因（[09 篇](09-lease-token.md) 4.4）。
2. **动态 key 拼接绕过 Cluster 路由检查**：`REVOKE` 里 `ARGV[1] .. token` 拼出 token key。单实例部署无影响；上 Cluster 前需要改造为显式 KEYS。
3. **两份清单（集中 + 内联）的同步成本**：新增脚本容易只改一处。本篇 3.1 / 3.2 是当前口径（22 + 11 = 33 段）。
4. **`replicate_commands()` 在 Redis 7+ 是冗余**：新版本脚本默认支持非确定性命令调用；保留无害，且兼容旧版本。

---

## 七、如何验证

```bash
# 1. 脚本缓存：应用首次执行后 EVALSHA 命中（按内容 SHA1 缓存）
redis-cli SCRIPT EXISTS <sha1>

# 2. 手工验证单脚本语义（以 RELEASE_LOCK_IF_MATCH 为例）
redis-cli SET mylock token-a
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1]) end
                return 0" 1 mylock token-b    # 期望 0（令牌不匹配，不删）
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1]) end
                return 0" 1 mylock token-a    # 期望 1（匹配，删除）

# 3. 验证 false vs nil 陷阱
redis-cli EVAL "local v = redis.call('GET', KEYS[1]);
                return v == false and 'missing' or tostring(v)" 1 nonexistent-key
# 期望 'missing'（不存在的 key 在 Lua 里返回 false）

# 4. 统计口径核对（注意完全限定名会漏检）：
# util/RedisScripts.java 声明数 = 22；5 个业务类内联声明合计 = 11
```

---

## 八、延伸阅读

- 各脚本的业务语境：[12 篇](12-chat-lock-atomic.md)（#11）、[13 篇](13-distributed-lock.md)（#12）、[14 篇](14-grab-order-lua.md)（#20 / #21）、[15 篇](15-nearby-order-geo.md)（#4~#6）、[16 篇](16-driver-online-heartbeat.md)（#1~#3）、[17 篇](17-order-timeout-delay-queue.md)（#10）、[09 篇](09-lease-token.md)（#15~#19）、[22 篇](22-sliding-window-rate-limit.md)（#22）
- [01 篇](01-shared-session-token.md)、[02 篇](02-email-code-cooldown.md)、[11 篇](11-two-phase-confirm-token.md)、[25 篇](25-agent-hitl-state.md) —— 内联脚本的业务语境
- Redis 官方文档：[EVAL script](https://redis.io/commands/eval/)、[EVALSHA](https://redis.io/commands/evalsha/)
