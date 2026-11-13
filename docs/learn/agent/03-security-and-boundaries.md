# 安全边界：从 Token 到 `@RequirePermission`、从计价到支付门禁

> **学习目标**：把散落在 `src/main/resources/api/*.md` 里的接口契约串成一条可讲清楚的链路，明确「当前已实现」与「契约/部分实现」的边界，并能解释改密后旧令牌为何失效、支付为何默认 503。
> **代码基准**：`src/main/java/com/fancy/rideagenthub/` 下的 `service/impl/TokenServiceImpl.java`、`interceptor/`、`security/`、`aspect/`、`config/PaymentConfig.java`、`service/PaymentVerifier.java`、`service/base/RidePricing.java`、`config/IdGeneratorConfig.java`、`config/Config.java`、`exception/GlobalExceptionHandler.java`，以及 `src/main/resources/sql/`。
> **状态约定**：`当前实现` / `部分实现` / `设计中` / `已废弃`，与 [`../redis/README.md`](../redis/README.md) 的术语规范一致。

---

## 一、业务背景与问题定义

AI Agent 系统里「用户输入」和「模型输出」都不可信：客户端可以伪造 `userId`、价格、里程、Mongo 轨迹标识；模型可能被提示词注入诱导自报价格或自授下单权限；旧令牌可能在改密后被继续使用。因此需要一条**服务端定权威**的边界：

1. 身份与角色只从登录态取，不信任请求体；
2. 价格、轨迹、订单状态由服务端重算/重查；
3. 高风险动作（下单、支付、取消）必须由服务端签发的一次性令牌授权；
4. 并发注册、跨用户访问、异常到 HTTP 状态码要有统一裁决。

---

## 二、架构设计与鉴权链

```mermaid
flowchart LR
    REQ[HTTP 请求] --> RLI[RequestRateLimitInterceptor<br/>来源地址摘要 120/60]
    REQ --> ATI[AuthTokenInterceptor.preHandle]
    ATI --> TS[TokenServiceImpl.getToken<br/>三段校验]
    TS --> CTX[UserTokenContext<br/>ThreadLocal]
    CTX --> PA[PermissionAspect<br/>@RequirePermission]
    PA --> CTRL[Controller]
    CTRL --> GEH[GlobalExceptionHandler<br/>异常 → HTTP]
    ATI -.afterCompletion.-> CLR[清理 ThreadLocal]
```

| 组件 | 位置 | 职责 |
|---|---|---|
| `AuthTokenInterceptor` | `interceptor/` | 解析 `Authorization: Bearer` / `X-Auth-Token`，写 `UserTokenContext`，`afterCompletion` 清理 |
| `UserTokenContext` | `security/` | ThreadLocal 保存 userId / role，`getNullable()` 供 AOP 使用 |
| `PermissionAspect` | `aspect/` | 读上下文校验 `@RequirePermission`，**不查 DB、不查 Redis** |
| `TokenServiceImpl` | `service/impl/`（注意不在 `service/base/`） | 会话发布、撤销与二次校验 |
| `CredentialStamp` | `security/` | 由密码摘要生成凭据指纹 |
| `GlobalExceptionHandler` | `exception/` | 统一异常 → HTTP 状态 |

---

## 三、完整执行链：一次带权限的请求

1. **入口限流**：`RequestRateLimitInterceptor` 挂 `/auth/**` 与 `/order/estimate`，按 `CredentialStamp.of(remoteAddr)` **摘要**生成 `request:rate:{digest}`，120 次/60 秒，`OPTIONS` 放行、`OPTIONS` 不消耗配额。它复用 `SLIDING_WINDOW_RATE_LIMIT` Lua（Redis `TIME`，3 个 ARGV）。**key 存摘要不是明文 IP**。
2. **令牌解析**：`AuthTokenInterceptor.preHandle` 取 token → `TokenServiceImpl.getToken`：
   - 反序列化 `UserToken`；
   - **会话版本围栏**：`sessionVersion != currentVersion()` → 返回 null（全量撤销 / 改密后旧令牌失效）；
   - **DB 状态/角色**：查 `user_id`、`is_deleted=0`、`status=1`，比对 role；
   - **凭据摘要**：比对 `CredentialStamp.of(user.getPassword())`，改密后旧令牌立即失效。
3. **写入上下文**：把 userId / role 放进 `UserTokenContext`（ThreadLocal）。
4. **AOP 校验**：`PermissionAspect` 读上下文，与 `@RequirePermission({"ADMIN"})` 比对；`RagController` 7 个端点、`/user/admin/**` 7 个端点、`/user/rebuild` 都走这里。
5. **业务执行**：Controller → Service → Mapper；`OrderGrabService`、`RidePricing`、`OrderConfirmation` 等各自做权威校验。
6. **收尾**：`afterCompletion` 清理 ThreadLocal（异步任务必须在提交前取出身份，不能依赖 ThreadLocal 跨线程传递）；异常由 `GlobalExceptionHandler` 映射。

---

## 四、核心源码解析

**`service/impl/TokenServiceImpl.java` 的 `SAVE` Lua（3 KEYS / 4 ARGV）**

```lua
if (redis.call('GET', KEYS[3]) or '0') ~= ARGV[4] then return 0 end  -- 会话版本围栏
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])                     -- token JSON
redis.call('SADD', KEYS[2], ARGV[3])                                    -- 反向索引
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]) * 2)                    -- 索引 TTL ×2
return 1
```

KEYS 依次是 `auth:token:{token}`、`auth:user_tokens:{userId}`、`auth:session-version:{userId}`；ARGV 是 JSON、ttl(604800)、token 串、`sessionVersion`。`REVOKE` 一段 Lua 完成 `INCR` 版本 + 遍历 `SMEMBERS` 删全部令牌 + 删索引；`REMOVE` 是 `DEL` + `SREM`。`deleteAllTokensByUserId` 在事务活跃时注册 `TransactionSynchronization.afterCommit()` 再执行 revoke，避免回滚后把会话撤销了。`addTokenToUserIndex` 直接抛 `UnsupportedOperationException("令牌与索引必须由saveToken原子写入")` —— **不允许绕过 Lua 单独写索引**。

**`config/PaymentConfig.java`（默认拒绝）**

```java
@Bean @ConditionalOnMissingBean
PaymentVerifier unavailablePaymentVerifier() {
    return (orderId, userId, channel, tradeNo, amount) -> {
        throw new BusinessException(503, "支付渠道尚未接入，无法核验支付结果");
    };
}
```

`service/PaymentVerifier.java` 的签名只有 `(orderId, userId, channel, tradeNo, amount)`；源码注释里的「商户 / 币种 / 支付终态」是**接入渠道时需实现的未来契约**，不是现有能力。把它写成「已核验商户和币种」就是典型的「把设计写成实现」。

**`service/base/RidePricing.java`（服务端重算价格）**：起步 `8.00` 含 `2.0km`，超出 `2.20/km`，`>20km` 部分再加 `1.10/km`；时长按 `CEILING` 取整 × `0.50/min`；加急固定 `+5.00` 且**在同一类内**给 `finalMultiplier` 再 `+0.20`（工具层无独立加价），车型倍率 `1.0/1.6/2.5`；`estPrice = (subTotal + 5.00) × finalMultiplier`，`BigDecimal` + `setScale(2, HALF_UP)`。调用点：`RideOrderServiceImpl.calculatePrice`（估价/创建/结束行程复用）、`OrderTool.getEstPrice`、`OrderSearchTool.calcEstPriceByOrderToolRule`。**客户端与模型传的价格都只是展示值**。

**`exception/GlobalExceptionHandler.java`**：`UnauthorizedException` → 401、`ForbiddenException` → 403、`BusinessException.code` 在 400–599 直接透传（越界钳制为 400）、参数类异常 → 400、`DuplicateKeyException` → 409、其它 → 500。`ChatInfoService.assertOwned` 三分支：会话不存在 `BusinessException(404)` → 404；**不属于当前用户 `ForbiddenException` → 403**；已停用 `BusinessException(410)` → 410。

| 异常 | 位置 | HTTP |
|---|---|---|
| `UnauthorizedException` / `ForbiddenException` | `GlobalExceptionHandler.java:15-18` | 401 / 403 |
| `BusinessException` | `:19-20,35-37` | `code`（400–599 透传，越界钳 400） |
| `IllegalArgumentException` / 参数绑定异常 | `:21-25` | 400 |
| `DuplicateKeyException` | `:26-29` | 409 |
| 其它 `Exception` | `:30-34` | 500 |

---

## 五、底层原理

- **会话版本围栏**：`auth:session-version:{userId}` 无 TTL。全量撤销时 `INCR`，所有旧令牌里的 `sessionVersion` 立刻与当前值不符；`SAVE` 也会先校验版本再写，防止「撤销与并发登录」交错。比「遍历删除所有 token」更可靠：即使某个 token key 已过期或漏删，旧令牌也过不了校验。
- **凭据摘要**：把密码摘要的一个指纹塞进 token JSON，读令牌时用 DB 当前密码重算比对。改密后旧令牌自动失效，无需维护黑名单。
- **反向索引 TTL ×2**：`auth:user_tokens:{userId}` 比单个 token 活得更久，保证遍历踢下线时不会先于 token 过期；这是「Set 索引 + 多个 String」结构的常见陷阱。
- **两套限流并存**：按 `userId` 30/60（对话，前置在 `ChatServiceImpl.canStartChat()`）与按来源摘要 120/60（入口层），维度不同、共用 `SLIDING_WINDOW_RATE_LIMIT` Lua。**故障行为相反**：`ChatRateLimiter` 对 Redis 异常 `try/catch` 后放行（fail-open）；`RequestRateLimitInterceptor` **无 try/catch**，Redis 异常会继续抛出并由 `GlobalExceptionHandler` 兜底为 500，只有「配额耗尽」才主动抛 `BusinessException(429)`。
- **生成列唯一索引**：`sql/init.sql` 用生成列 + `uk_active_username_role` 保证「同角色、未删除账号」的用户名/邮箱唯一；逻辑删除后名称可复用。`sql/migrations/20261113_security_consistency.sql` 是旧库升级路径，迁移前需人工确认历史重复数据，不自动删账号。
- **雪花 ID 与节点租约**：`config/IdGeneratorConfig` 用 Redisson `id:node:{dc}:{worker}` 的**永久租约**（`leaseTime = -1` + 看门狗）；重复节点配置启动即抛异常，`SnowflakeIdWorker.nextId()` 发现租约丢失就拒绝发号，避免多实例 workerId 相同产生重复 ID。`config/Config.jacksonCustomizer()` 把 `BigDecimal` 与超过 `2^53-1` 的 `Long` 序列化成字符串，防止雪花 ID 在前端丢精度。

---

## 六、设计决策与方案对比

| 决策 | 当前方案 | 备选与取舍 |
|---|---|---|
| 会话存储 | Redis 有状态会话 + 版本围栏 | 无状态 JWT 更省 Redis，但撤销/改密即时失效需要黑名单或短 TTL |
| 权限判定 | AOP 读 `UserTokenContext` | 每次查 DB 更实时但多一次往返；缓存角色又要处理失效。当前**没有独立权限缓存** |
| 支付 | 默认拒绝 503 + 接口扩展点 | 直接返回成功会构成伪造支付；接入真实渠道前**必须**默认拒绝 |
| 价格 | 服务端 `RidePricing` 重算 | 信任客户端/模型价格等于把资金字段交给提示词注入 |
| 唯一性 | DB 生成列唯一索引裁决 | 只靠应用层校验挡不住并发注册；布隆过滤器只作加速且可误判 |
| 幂等 | 三处服务端一次性令牌（`CONFIRM`/`CONSUME`、`CANCEL`、验证码） | 通用幂等注解/切面**仍未实现**，不要说成已有能力 |

**部分实现清单**：支付渠道核验（仅接口 + 默认 503）、RAG 物理取消（逻辑 `cancel(true)`）、墓碑写路径（机制就位但生产 0 调用方）、`ESMemory`/`MemoryAdvisor`（空骨架）。**已废弃**：`Chat2Controller` / `TestController` 与 `/order/chat/*`、`/test/rag`。

---

## 七、完整示例与异常

| 场景 | 输入 | 中间状态 | 输出 |
|---|---|---|---|
| 改密后旧令牌 | 旧 `X-Auth-Token` | `credentialStamp` 与 DB 新密码摘要不符 | `getToken` 返回 null → 401 |
| 全量撤销 | `deleteAllTokensByUserId` | `INCR` 会话版本 + 批量 `DEL` + `SREM` | 所有旧令牌 401；事务未提交时不执行 |
| 重复节点配置 | 两个实例同 `dc:worker` | 抢永久租约失败 | 启动即抛 `IllegalStateException` |
| 伪造价格下单 | 请求体带 `estPrice=1` | 服务端 `RidePricing.calculate` 重算 | 价格以服务端为准 |
| 支付请求 | `payOrder` | 参数非空 400 → `withOrderLock` → **非本人** 403 → 状态非 `FINISHED_WAIT_PAY` 409 → `paymentVerifier == null` 503「支付核验尚未配置」/ 默认 Bean 503 | `503 支付渠道尚未接入` |
| 跨用户访问会话 | 别人的 `chatId` | `assertOwned` 抛 `ForbiddenException` | **HTTP 403**（会话不存在 404、停用 410） |
| 并发同名注册 | 两个请求 | 唯一索引裁决 | `DuplicateKeyException` → 409 |

---

## 八、常见问题与面试延伸

1. **为什么不用 JWT？** 需要即时撤销、改密失效与会话版本围栏；Redis 有状态会话可直接 `INCR` 版本让全部旧令牌失效。
2. **「权限缓存」实现了吗？** 没有独立权限缓存，但**「鉴权链不查库」是错的**：`PermissionAspect` 只读 ThreadLocal，上游 `TokenServiceImpl.getToken()` 每次请求仍要查 Redis（`auth:token:*`）+ DB（`sys_user` 的 `is_deleted/status/role` 与密码摘要，`TokenServiceImpl.java:66-78`）。准确说法是「**切面不查库，但每请求仍有 Redis + DB 往返；缺的是独立角色缓存**」。
3. **两套限流的 key 有什么区别？** 对话按 `userId`（`chat:ratelimit:`），入口按来源地址**摘要**（`request:rate:`），后者不存明文 IP；`OPTIONS` 放行。
4. **支付为什么不能先放行再补核验？** 订单状态会变成已支付，资金与业务状态不一致；默认拒绝是唯一安全的未接入态。
5. **为什么 `BusinessException.code` 要钳制 400–599？** 防止业务异常把非法状态码传给 HTTP 层；超出范围统一归一。
6. **`chatId` 必须来自 `/newuuid` 吗？** 不必。`ChatRequestRunner` 只做 `UUID.fromString` 格式校验，会话须属于当前用户即可（[`src/main/resources/api/chat-security.md`](../../../src/main/resources/api/chat-security.md) 已回写）。
7. **还能怎么加强？** CORS 白名单外置、锁与令牌的可观测性、支付渠道适配器、RAG 线程池参数外置 —— 都是**可选优化**。**但「限流阈值配置化」不是未实现**：`ChatGuardProperties`（prefix `chat.guard`）已在 `application.yaml:76-81` 外置 `rate-limit-max=30`、`rate-limit-window-seconds=60`、`classify-cache-ttl-seconds=21600`、`fingerprint-length=32`，`ChatRateLimiter` 与 `ClassificationCache` 均注入该 Bean；仍硬编码的是入口层 120 次/60 秒（`RequestRateLimitInterceptor.java:22`）、3 分钟超时与线程池参数。

> 关联：[01 编排与 SSE](01-orchestration-and-sse.md)、[02 RAG 一致性与预算](02-rag-consistency-and-budget.md)、[`../redis/01-shared-session-token.md`](../redis/01-shared-session-token.md)、[`../redis/22-sliding-window-rate-limit.md`](../redis/22-sliding-window-rate-limit.md)。
