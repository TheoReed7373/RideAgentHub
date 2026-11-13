# 滑动窗口限流

> **Redis 考点**：ZSet 的 score 存时间戳，「剔除过期 + 计数 + 登记」在同一段 Lua 内原子完成；时间源是 Redis `TIME`——多实例时钟偏斜与固定窗口双倍放行都从这里解决。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/ChatRateLimiter.java`、`src/main/java/com/fancy/rideagenthub/interceptor/RequestRateLimitInterceptor.java`、`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`（`SLIDING_WINDOW_RATE_LIMIT`）

---

## 一、业务场景

项目里有两套限流，共用同一段 Lua、作用于不同维度：

| 限流 | 维度 | 限额 | 挂载点 | 拒绝行为 |
|---|---|---|---|---|
| 对话限流 | userId | 30 次 / 60 秒 | `ChatServiceImpl.canStartChat` | `notify` 事件「消息发送过于频繁，请稍后重试。」结束流 |
| 入口限流 | 来源地址摘要 | 120 次 / 60 秒 | `RequestRateLimitInterceptor`（只挂 `/auth/**` 与 `/order/estimate`） | HTTP 429 |

**双维度的原因**：userId 限流挡「登录用户的滥用」（对话烧的是模型配额）；来源地址限流挡「未登录接口的匿名刷量」（注册 / 登录 / 估价烧的是验证码邮件与 DB）——匿名请求没有 userId 可归集，只能按网络来源。

**为什么是滑动窗口而不是固定窗口**：固定窗口（`INCR` + `EXPIRE`）的两端是硬边界——第 59 秒发 30 次、第 61 秒再发 30 次，1 分钟内通过了 60 次（2 倍阈值）。滑动窗口按「最近 N 秒」精确计数，不存在这个尖峰。

---

## 二、Redis 结构选型

| 项 | 对话限流 | 入口限流 |
|---|---|---|
| Key | `chat:ratelimit:{userId}`（`chatRateLimitKey`） | `request:rate:{SHA-256 摘要(remoteAddr)}`（就地拼接） |
| 结构 | ZSet：member = UUID，score = 当前毫秒 | 同左 |
| 限额 | 30 / 60000ms（`ChatGuardProperties`，可配置） | 120 / 60000ms（硬编码） |

member 用 **UUID** 而不是 userId / 固定值：同一毫秒内的两次调用若用同一 member，`ZADD` 会去重成一条——限流形同虚设。

入口限流的 key 是**地址摘要而非明文 IP**：`CredentialStamp.of(remoteAddr)`（SHA-256）——Redis 数据 / 备份 / 慢日志泄露时不直接暴露访问者 IP 明文。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `SLIDING_WINDOW_RATE_LIMIT` Lua | `replicate_commands` + `TIME` → 剔除过期 → 计数 → 限额判断 → `ZADD` + `PEXPIRE` |
| `ChatRateLimiter.tryAcquire(userId)` | 参数空 / 配置 ≤ 0 时放行；执行 Lua；**异常放行** |
| `RequestRateLimitInterceptor.preHandle` | `OPTIONS` 放行；执行 Lua；超限抛 `BusinessException(429)` |
| `ChatServiceImpl.canStartChat` | 对话链路前置：空 prompt 不消耗配额（以 error 事件结束）；限流拒绝发 notify 结束流 |
| `config/WebMvcConfig` | 拦截器注册路径（`/auth/**`、`/order/estimate`） |

---

## 四、实现拆解

### 4.1 `SLIDING_WINDOW_RATE_LIMIT`：三步原子

```lua
redis.replicate_commands()
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local window = tonumber(ARGV[1])          -- 窗口毫秒
local limit  = tonumber(ARGV[2])          -- 限额
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)   -- ① 剔除窗口外
local used = redis.call('ZCARD', KEYS[1])                   -- ② 计数
if used >= limit then return -1 end
redis.call('ZADD', KEYS[1], now, ARGV[3])                   -- ③ 登记
redis.call('PEXPIRE', KEYS[1], window)                      -- key 寿命对齐窗口
return limit - used - 1                                     -- 剩余额度
```

ARGV 只有 **3 个**（窗口 / 限额 / member）——**时间不在参数里**：`redis.call('TIME')` 取 Redis 服务器时间。为什么不用应用侧传 `now`：多实例部署下应用时钟偏斜会让「实例 A 登记的请求」在实例 B 的判定里落到窗口外（或未来）——**限流窗口对多实例失去统一意义**。`replicate_commands()` 让读时钟这类非确定性操作在主从复制时确定化。

「剔除 → 计数 → 登记」必须原子：拆开的话两个并发请求都通过计数（`ZCARD` 都是 29），都登记——**限额被突破**。返回值语义：`-1` 拒绝、`≥ 0` 剩余额度（调用方 `remaining >= 0` 判定）。

`PEXPIRE window` 是 key 的兜底寿命：不活跃用户 / 地址的 ZSet 自动消失，不累积冷数据。

### 4.2 对话限流的拒绝语义：流内事件而非异常

`ChatRateLimiter.tryAcquire` 返回 false → `canStartChat` 发 `notify`（「消息发送过于频繁，请稍后重试。」）并结束 SSE 流——**限流拒绝在流式端点里也是事件**（前端统一按事件渲染），不是 HTTP 429。注意细节：

- **空 prompt / null 请求不消耗配额**（参数校验在限流之前，以 error 事件结束）——格式错误不该烧用户的额度；
- **`resume` 同样受限流**（恢复链路走同一个 `canStartChat`）——不能靠恢复接口绕限流。

### 4.3 对话限流的故障取舍：异常放行

```java
catch (Exception e) {
    log.warn("对话限流判定失败，本次放行: userId={}", userId, e);
    return true;
}
```

限流器选择在自身判定异常时放行，以免单独的限流故障直接拒绝请求。此处放行只表示限流器不拒绝；聊天仍依赖 Redisson 单轮锁、会话元数据等 Redis 路径，Redis 整体不可用时不能保证继续对话。上游模型异常由聊天层转为错误事件/终态，不承诺 HTTP 429 原样透传。

入口限流则相反：没有捕获 Redis 异常，执行失败会继续抛出并由全局异常处理器返回 HTTP 500；Lua 返回 null 或负数时才抛 HTTP 429。不能将异常路径写成 null 返回路径。

### 4.4 `OPTIONS` 放行

CORS 预检请求（`OPTIONS`）不消耗限额——预检是浏览器自动行为，用户没有发起实际请求；是否预检取决于请求是否满足简单请求条件，以及浏览器的预检缓存；并非每个真实请求都会重新预检。预检若进入限流拦截器，OPTIONS 会直接放行，避免把它计入真实业务请求额度。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 滑动窗口 | 固定窗口 | 固定窗口边界双倍放行；滑动窗口精确「最近 N 秒」 |
| member = UUID | member = userId | 同毫秒两次调用 ZADD 去重 → 限流失效 |
| Redis `TIME` | 应用传时间 | 多实例时钟偏斜；窗口失去统一意义 |
| key 用地址摘要 | 明文 IP | 泄露面控制（备份 / 慢日志） |
| 两套并存共用 Lua | 一套通用 | 维度不同（userId / 地址）、故障方向相反（放行 / 拒绝）、挂载层不同（服务 / 拦截器）；共用的只有窗口算法 |
| 对话拒绝走事件 | HTTP 429 | 流式端点的拒绝也应是流的一部分 |
| 空请求不耗配额 | 先限流后校验 | 格式错误不该烧额度 |

---

## 六、边界与已知问题

1. **入口限流按直接连接来源地址聚合**：`getRemoteAddr()` 是 TCP 对端地址，未信任 `X-Forwarded-For` 等头（反向代理部署需确保代理层剥离这些头）。共享出口 IP（公司网络 / 运营商 NAT）的合法用户可能互相挤占 120 次 / 分钟的额度。
2. **对话限流在 Redis 故障期间失效**（4.3 的取舍）：模型配额暴露给上游 429。
3. **ZSet 内存与窗口内请求数成正比**：60 秒内每个请求一个 member。限额本身就是上界（30 / 120），内存可控。
4. **限额参数位置不对称**：对话限流可配置（`chat.guard.*`）；入口限流的 120/60 硬编码在拦截器——调整需要改代码。

---

## 七、如何验证

```bash
# 1. 对话限流：连续对话后
redis-cli ZCARD chat:ratelimit:<userId>          # 窗口内已计数（≤ 30）
redis-cli TTL chat:ratelimit:<userId>            # 期望 ≤ 60（TTL 单位秒；PTTL 才是 ≤ 60000 毫秒）
redis-cli ZRANGE chat:ratelimit:<userId> 0 -1 WITHSCORES   # member 是 UUID，score 是毫秒时间戳

# 2. 滑动语义：第 0 秒发满 30 次，第 59 秒仍被拒，第 61 秒（最早记录滑出窗口）恢复
# 3. 入口限流：连续调 /auth/email-code 121 次 → 第 121 次收到 429
redis-cli ZCARD request:rate:<摘要>               # 期望 120

# 4. OPTIONS 放行：浏览器跨域场景下预检请求不产生计数
# 5. 故障方向：停掉 Redis
#    → 对话限流放行（日志「对话限流判定失败，本次放行」）
#    → 认证入口抛 Redis 异常并返回 HTTP 500；配额耗尽才是 429
```

---

## 八、延伸阅读

- [`21-llm-classification-cache.md`](21-llm-classification-cache.md) —— 限流与缓存共同保护分类调用（缓存挡重复、限流挡突发）
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `SLIDING_WINDOW_RATE_LIMIT` 与 Redis `TIME` 的三处应用
- [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 时间源统一到 Redis 的另一处实践
- [`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md) —— 两套限流的安全测试覆盖
