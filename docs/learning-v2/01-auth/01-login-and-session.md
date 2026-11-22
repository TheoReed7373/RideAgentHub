# 第 5 章 登录与会话：从一封验证码邮件到带身份的请求

> 前四章回答了"请求怎么进来、数据存在哪、事务管到哪、Redis 和流式响应是什么"。本章回答一个更贴近用户的问题：**林小雨第一次打开这个系统时，她是怎么变成"一个有身份的请求"的？** 服务端又凭什么相信"这个请求就是林小雨"？
>
> 本章只讲身份链：验证码、注册、登录、令牌、拦截器、注销与改密后的旧会话。角色权限、资源归属、异常映射的完整规则留给下一章 A2。所有示例请求均使用虚构占位数据，**未实测**；运行时行为以静态源码推导为准。

## 学习目标

读完本章，你应该能：

1. 按真实端点、DTO 字段、请求头和响应结构，完整复述"发验证码 → 注册 → 密码登录 / 验证码登录 → 拿到 token → 带 token 请求 → 身份进入业务"的调用链，并指出每一步谁调用谁、读写什么状态。
2. 说出验证码的**冷却、有效期、错误次数、一次性消费**分别由哪个 key、哪段 Lua 保证；解释注册/重置密码（有事务，"预留"）与验证码登录（无事务，"直接消费"）两种模式的差异。
3. 解释 token 的签发、Redis 存储（String + Set 索引）、固定 TTL（非滑动续期）、拦截器提取与双层校验（Redis 会话版本 + MySQL 凭据指纹）、ThreadLocal 的生命周期与清理。
4. 推演修改密码 / 注销 / 多设备登录后，旧令牌为什么失效：会话版本号挡住"撤销后晚到的签发"，凭据指纹挡住"Redis 删除失败时残留的旧令牌"——并指出各自的失败窗口。
5. 区分 401、业务响应（`Result.code`）、异常映射三层，并说明哪些结论是源码可证、哪些未实测。

## 前置知识与阅读时间

- 硬前置：[第 2 章 请求路径与配置](../00-foundations/02-request-path-and-config.md)（拦截器、DTO、`Result`）、[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)（key/TTL/Lua、令牌三 key）。推荐先读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)（`afterCommit`/`afterCompletion` 回调、事务边界）。
- 不需要：Spring Security 全家桶知识——本项目只用 `spring-security-crypto` 里的 `BCryptPasswordEncoder`，没有引入 Security 框架本身。
- 阅读时间：约 80～100 分钟（含两组练习与自测）。
- **路径约定**：Java 类使用简写，基准是 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**："仓库实现"来自当前源码；涉及 Lua/事务回调语义时引用 F3/F4 已核验的精确版本规则；"测试阅读"表示读过断言但未运行；"未实测"表示没有启动服务或发请求。

---

## 零、场景：林小雨的第一天（按当前实现核对）

> 人物、邮箱、验证码、token 全部虚构。括号里是"这一步在当前代码里真的存在吗"的核对结论。

1. 林小雨打开 App，输入邮箱 `xiaoyu@example.invalid`，点"发送验证码"。**存在**：`POST /auth/email-code`，场景 `REGISTER`。
2. 她收到 6 位数字验证码，填好密码提交注册。**存在**：`POST /auth/register`；注册后拿到的是 `userId`，**不会**自动登录——她还要再走一次登录。
3. 她用"邮箱 + 密码"登录。**存在**：`POST /auth/login/password`。她也本可以用验证码登录：**存在**：`POST /auth/login/email-code`（仅 USER 角色）。
4. 响应里带一个 token，之后每个请求放在 `Authorization: Bearer <token>` 或 `X-Auth-Token` 头里。**存在**：拦截器从这两个头提取。
5. 晚上她在平板上又登录了一次。**存在**：两次登录产生两个 token，同一用户的两个 token 同时有效，互不影响。
6. 她忘了密码，用验证码重置。**存在**：`POST /auth/password/reset`。按设计**意图**，重置后两台设备的旧 token 应全部失效——但前提是事务提交成功、afterCommit 的 REVOKE 确实执行且 Redis 操作成功（细节见 6.2，不是无条件保证）。
7. 她想注销账号。**存在**：`DELETE /auth/account`，但它需要**有效登录态**；第 6 步重置后旧 token 已失效（若 REVOKE 成功），所以她**必须先重新登录**，再调用注销。注销的意图是逻辑删除 + 全部 token 失效；同样受提交/REVOKE/数据库状态条件约束。

故事之外，当前实现还有两个前置"关卡"值得先知道（细节在后面展开）：

- `/auth/**` 路径先过一个**按客户端 IP 的滑动窗口限流**（每 60 秒 120 次，超限 429），见 `interceptor/RequestRateLimitInterceptor.java` 与 `config/WebMvcConfig.java#addInterceptors`。
- 发验证码前，`RESET_PASSWORD` 场景要求该邮箱**已存在 USER 账号**，否则 404（避免向未注册邮箱发重置码，见 `AuthServiceImpl#sendEmailCode`）。

---

## 一、最少概念：三方状态与五组 key

### 1.1 一次登录涉及的三方状态

| 状态 | 存在哪 | 寿命 | 谁写 | 谁读 |
|---|---|---|---|---|
| 验证码族（码、冷却、尝试、归属、认领） | Redis | 秒级（60s～300s） | `EmailCodeService` | `EmailCodeService` |
| 令牌族（token 正文、用户 token 索引、会话版本） | Redis | 默认 7 天 / 14 天（前两项可由 `auth.token-ttl-seconds` 覆盖）/ 版本 key 无 TTL | `TokenServiceImpl` | `TokenServiceImpl`、`AuthTokenInterceptor` |
| 账号真值（密码 BCrypt 哈希、角色、状态、逻辑删除） | MySQL `sys_user` | 持久 | `UserAuthServiceImpl` | `TokenServiceImpl#getToken`（仅当 Redis token 存在且版本匹配时才回查） |

关键认知（F4 已建立，这里落到登录场景）：**令牌本体只存在 Redis，没有 MySQL 副本**。Redis 清空 = 所有登录态失效，所有人重新登录。但"密码变了没有"这件事以 MySQL 为准——**只有先通过 Redis 两步检查的请求，才会回查数据库**（短路逻辑见 5.2）。

### 1.2 验证码族的五组 key

前缀来自 `constant/RedisKeyConstants.java`，email 一律 `trim().toLowerCase()`（`EmailCodeService#sendCode/#verifyAndConsume`、`UserAuthServiceImpl` 查询时同样小写化）：

| key | 类型/值 | TTL | 作用 |
|---|---|---|---|
| `auth:email_code:{scene}:{email}` | String，6 位数字码 | 默认 300 秒（`auth.email-code-ttl-seconds`，可配置） | 验证码本体 |
| `auth:email_code:cd:{scene}:{email}` | String，issueId（UUID） | 默认 60 秒（`auth.email-code-cooldown-seconds`，可配置） | 发送冷却 |
| `auth:email_code:{scene}:{email}:attempts` | String，数字计数 | 对齐码的剩余 TTL | 错误尝试次数 |
| `auth:email_code:{scene}:{email}:owner` | String，issueId | 默认 300 秒（与码同 TTL，可配置） | 本轮发码的"归属者"，SMTP 失败清理时防止误删新一轮码 |
| `auth:email_code:{scene}:{email}:claim` | String，claimId | 对齐码的剩余 TTL | 事务内"预留"锁：注册/重置在事务提交前先占住码 |

`scene` 是 `REGISTER` / `LOGIN` / `RESET_PASSWORD` 三选一（`domain/enums/EmailScene.java`，大小写不敏感解析，非法值抛 `IllegalArgumentException` → 400）。**同一邮箱三个场景各自独立**：注册码不能拿来登录。

注意 key 里的 `{scene}` 实际是**小写**：`RedisKeyConstants#emailCodeKey/#emailCodeCooldownKey` 对 scene 调用 `toLowerCase()`，而调用方传的是 `scene.name()`（大写）。所以 `REGISTER` 对应的真实 key 是 `auth:email_code:register:{email}`，冷却 key 是 `auth:email_code:cd:register:{email}`；另外三组 key 同样小写。

### 1.3 令牌族的三组 key 与两个"指纹"

| key | 类型/值 | TTL | 作用 |
|---|---|---|---|
| `auth:token:{token}` | String，`UserToken` JSON | 默认 604800 秒（7 天），`AuthProperties.tokenTtlSeconds` 提供默认、`auth.token-ttl-seconds` 可覆盖 | 单个令牌正文 |
| `auth:user_tokens:{userId}` | Set，member = token | 默认 1209600 秒（14 天，令牌 TTL × 2），每次签发重设 | 该用户全部令牌的索引，撤销时按它批量删 |
| `auth:session-version:{userId}` | String，递增整数 | **无 TTL**（`REVOKE` 只 `INCR`，不设过期） | 会话版本号，撤销时 +1 |

`UserToken`（`security/UserToken.java`）里有两个容易混淆的字段：

- `sessionVersion`：签发时从 `auth:session-version:{userId}` 读出的版本号。校验时必须等于当前版本号——**它挡住的是"撤销之后才完成的旧登录"**。
- `credentialStamp`：`CredentialStamp.of(BCrypt 哈希)`，即对**密码哈希再做一次 SHA-256**（`security/CredentialStamp.java`）。校验时与数据库当前密码哈希的指纹比对——**它挡住的是"改密后仍残留在 Redis 里的旧令牌"**（注释原话：撤销期间旧密码验证生成的令牌也不能复活）。

一个是在 Redis 里比（版本号），一个是在 MySQL 上比（密码哈希指纹）——两道栅栏互补，后面第八节推演它们的窗口。

### 1.4 为什么每个 `/auth/**` 请求还先过一层 IP 限流

`config/WebMvcConfig.java#addInterceptors` 把 `RequestRateLimitInterceptor` 挂在 `/auth/**` 与 `/order/estimate` 上：key 是 `request:rate:` + 客户端 IP 的 SHA-256，复用 F4 讲过的 `SLIDING_WINDOW_RATE_LIMIT`（窗口 60000 毫秒、上限 120、UUID member），超限抛 `BusinessException(429)`。`OPTIONS` 预检直接放行。它是登录接口的"防爆破外圈"，与验证码自身的尝试次数限制是两回事。

---

## 二、发送验证码：冷却与一次性签发

### 2.1 调用链

```text
POST /auth/email-code {"email":"xiaoyu@example.invalid","scene":"REGISTER"}
  → RequestRateLimitInterceptor.preHandle（IP 限流，超限 429）
  → AuthController#sendEmailCode
  → AuthServiceImpl#sendEmailCode
      ① request null → 400；validateEmail：必须含 @ 和 .，否则 400
      ② EmailScene.fromString：非法场景 → IllegalArgumentException → 400
      ③ RESET_PASSWORD 场景：userAuthService.findUserRoleByEmail 为 null → 404 "账号不存在"
      ④ emailCodeService.sendCode(email, scene)
  → 响应 Result.ok()（示意，未实测：{"code":200,"message":"success","data":null,"total":0}）
```

### 2.2 `sendCode` 与 ISSUE 脚本

```java
// service/base/EmailCodeService.java#sendCode（真实节选；省略 imports、字段、日志）
public void sendCode(String email, EmailScene scene) {
    email = email.trim().toLowerCase(Locale.ROOT);
    String code = String.format("%06d", RANDOM.nextInt(1000000));   // SecureRandom 6 位数字
    String issueId = java.util.UUID.randomUUID().toString();
    String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
    String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
    Long issued = redisTemplate.execute(ISSUE, List.of(codeKey, cooldownKey,
            codeKey + ":attempts", codeKey + ":owner", codeKey + ":claim"), code,
            String.valueOf(authProperties.getEmailCodeTtlSeconds()),
            String.valueOf(authProperties.getEmailCodeCooldownSeconds()), issueId);
    if (!Long.valueOf(1).equals(issued)) throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
    sendEmail(email, scene, code, issueId);
}
```

```lua
-- service/base/EmailCodeService.java#ISSUE（真实脚本）
if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[5]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])   -- 码，300s
redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[3])   -- 冷却，60s，值为 issueId
redis.call('SET', KEYS[4], ARGV[4], 'EX', ARGV[2])   -- owner=issueId，300s
redis.call('DEL', KEYS[3])                           -- 清掉上一轮的 attempts
return 1
```

先把 `sendCode` 的实参顺序与脚本 KEYS 一一对上——这是上一版读错的地方：

| 调用实参（顺序） | 脚本内名字 | 作用 |
|---|---|---|
| `codeKey` | `KEYS[1]` | 验证码本体（String） |
| `cooldownKey` | `KEYS[2]` | 冷却 key（值 = issueId，60 秒） |
| `codeKey + ":attempts"` | `KEYS[3]` | 错误尝试计数 |
| `codeKey + ":owner"` | `KEYS[4]` | 本轮发码的 issueId，供 SMTP 失败清理归属 |
| `codeKey + ":claim"` | `KEYS[5]` | 事务预留锁（由 VERIFY 的 reserve 分支写入） |

所以 ISSUE 的第 1 行 `EXISTS KEYS[2] OR EXISTS KEYS[5]` 检查的是 **冷却或 claim**，不是 owner：

1. **冷却 key**：60 秒内同一场景同一邮箱只能发一次。冷却过期 ≠ 旧码自动消失——码的 TTL 是 300 秒，两者独立。
2. **claim key**：只要某次注册/重置事务已经 `VERIFY` 成功并写了 claim，就**不允许发新码**——否则新码会覆盖旧码，正在事务里的那次预留就指向了一个已经不存在的码（COMPLETE 的 claim 校验也会失败）。
3. **owner 不是发码闸门**：它只是 issueId 的一份副本，给 SMTP 失败清理做"这轮状态是不是我写的"归属判断（见 2.3）。
4. **冷却过期且无 claim 时，即使旧码/owner 还在，也允许签发**：脚本用 `SET` 覆盖码、用新 issueId 覆盖 owner、`DEL attempts`，旧码作废。这是"用户可以重新发码"的正常路径。
5. **检查与写入在同一脚本内**：第一次成功后，只要冷却仍存在，后续并发发码就被拒（测试 `SecurityRedisIntegrationTest#onlyOneConcurrentSendAcquiresCooldown` 提交 8 个任务，断言 1 个成功）。脚本执行期间不插入其它 Redis 命令；但若脚本中途报错，前面已执行的命令不会自动回滚——具体哪些 key 写成功，静态阅读不能断言，需要现场观察。

### 2.3 SMTP 失败：清理什么、留下什么

```java
// service/base/EmailCodeService.java#sendEmail 的 catch 分支（真实节选）
} catch (Exception e) {
    log.error("Failed to send email to {}", email, e);
    String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
    String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
    redisTemplate.execute(CLEANUP, List.of(codeKey, cooldownKey, codeKey + ":owner"), issueId);
    throw new BusinessException(500, "邮件发送失败，请稍后重试");
}
```

```lua
-- CLEANUP（真实脚本）：KEYS=[码, 冷却, owner]，ARGV=[issueId]
if redis.call('GET', KEYS[3]) == ARGV[1] then redis.call('DEL', KEYS[1], KEYS[3]) end
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
return 1
```

按源码说清楚，不臆造：

- **KEYS 顺序**：`[码, 冷却, owner]`，`ARGV[1]=issueId`。owner 在这里才出场：它是"这轮状态属于谁"的凭证，用来避免旧请求误删新一轮状态。
- **删的条件**：`GET owner == issueId` 才删码 + owner；`GET 冷却 == issueId` 才删冷却。两个条件分别判断、分别删除。
- **不删**：`attempts` 不在清理范围；`claim` 也不在（CLEANUP 的 keys 只有三个），正在事务里预留的 claim 不会被 SMTP 清理碰到。
- **重试条件**：若 CLEANUP 成功删除冷却，客户端可以立刻重新发码；接口返回 500 "邮件发送失败，请稍后重试"。
- **旧失败碰上新发码**：SMTP 可能很慢。旧请求的异常回调执行时，如果用户已经成功发了新一轮（冷却已过期且无 claim 才可能），新一轮的 ISSUE 已经把码和 owner 覆盖成新 issueId。旧 CLEANUP 看到 owner/冷却都不等于自己的 issueId，**对相应 key 不删除**；它不会误删新码。反过来，若旧 CLEANUP 先执行、新发码后执行，旧状态被清掉、新状态正常写入——两种顺序都自洽。
- **CLEANUP 自身失败**：catch 里的 `execute` 没有 try/catch 包裹。Redis 异常时，脚本可能"一条命令都没执行"，也可能执行了一部分（Lua 错误不回滚已执行命令），**不能断言码/冷却/owner 的具体残留状态**；能确定的是它们都有 TTL 上限（默认码/owner 300 秒、冷却 60 秒，可配置），后续会自然消失。异常还会替代原 500 向上传播（框架映射，未实测）。
- 这不是"跨系统补偿"：SMTP 发送与 Redis 写入没有共同事务，代码选择的是"先写 Redis 占位，失败再尽力清理"，**没有自动重试**。

---

## 三、注册：验证码的"事务内预留"

### 3.1 调用链与 DTO

```text
POST /auth/register
  {"email":"xiaoyu@example.invalid","code":"482913","password":"<虚构密码>","username":"xiaoyu"}
  → RequestRateLimitInterceptor（IP 限流）
  → AuthController#register → AuthServiceImpl#registerByEmailCode（@Transactional）
  → 响应 Result.ok(userId.toString())（示意，未实测）
```

字段来自 `domain/dto/RegisterRequestDTO.java`：`email`、`code`、`password`、`username`（可选，缺省 `user_{userId}`）。

### 3.2 方法体真实节选

```java
// service/impl/AuthServiceImpl.java#registerByEmailCode（真实节选；省略日志）
@Transactional
public Long registerByEmailCode(RegisterRequestDTO request) {
    if (request == null) throw new BusinessException(400, "请求不能为空");
    validateEmail(request.getEmail());
    if (request.getPassword() == null || request.getPassword().isBlank()) throw new BusinessException(400, "密码不能为空");
    if (request.getUsername() != null && !isUsernameAvailable(request.getUsername())) throw new BusinessException(409, "用户名已存在");
    if (userAuthService.existsByEmailAndRole(request.getEmail(), UserRole.USER.name())) throw new BusinessException(409, "该邮箱已注册");

    if (!emailCodeService.verifyAndConsume(request.getEmail(), EmailScene.REGISTER, request.getCode())) {
        throw new BusinessException(400, "验证码无效或已过期");
    }

    Long userId = snowflakeIdWorker.nextId();
    UserAuth userAuth = UserAuth.builder()
            .userId(userId)
            .email(request.getEmail())
            .username(request.getUsername() != null ? request.getUsername() : "user_" + userId)
            .password(passwordEncoder.encode(request.getPassword()))   // BCrypt
            .role(UserRole.USER.name()).status(1).isDeleted(0)
            .build();
    userAuthService.createUser(userAuth);
    usernameBloomFilterService.addUsername(userAuth.getUsername());
    return userId;
}
```

顺序上的要点：

1. **先做纯参数检查**（400/409），**再消费验证码**。验证码是稀缺资源（一次一发），把它放在参数检查之后，避免"密码为空也烧掉一个码"。
2. `verifyAndConsume` 返回 false 的原因可能是：码不存在（没发过/已过期/已被消费）、claim 已被别人占住、错误次数 ≥ 5、码不匹配。Java 侧统一报 400 "验证码无效或已过期"，**不区分具体原因**。
3. **同一邮箱同一 REGISTER 码的并发，默认路径在 claim 处就分出胜负**：T1 的 VERIFY 写 claim → T2 的 VERIFY 看到 claim → 返回 0 → 400；T2 根本走不到 insert，因此**不会**出现"两个请求各自 insert、第二个靠唯一键失败"的默认剧情。测试 `verificationCodeCanOnlyBeConsumedOnceUnderConcurrency` 使用非事务 LOGIN 直接消费模式，不是该预留分支的并发测试。
4. **数据库唯一键是另一个独立兜底，但要有可达前提**：`sys_user` 有 `uk_user_id`、`uk_active_username_role`、`uk_active_email_role`（生成列 `active_username/active_email` 在 `is_deleted=1` 时为 NULL，见 `src/main/resources/sql/init.sql`）。一个干净的例子是**不同邮箱、同一用户名**的两笔注册：验证码 key 不同，两边都能各自完成预留，`isUsernameAvailable` 也可能都查到"暂无"（检查与插入之间没有锁），最后 `uk_active_username_role` 让后插入者 `DuplicateKeyException` → `GlobalExceptionHandler` 映射 409。同邮箱并发若要走到唯一键，需要前一笔事务跨过 claim/码 TTL、用户重新发码等特殊条件（见 8.4）。
5. **布隆过滤器在事务提交之前写**：`usernameBloomFilterService.addUsername(...)` 位于 `@Transactional` 方法体内、`createUser` 之后、方法返回之前，所以 MySQL 还没提交时 Redis 布隆已经写入。Redis 副作用不随 MySQL 回滚；若 `addUsername` 抛运行时异常，它自己也会让注册事务失败（异常穿出方法）。`isUsernameAvailable` 走的是数据库查询，布隆不在注册判定的主链（定位留给 A2）。

### 3.3 `verifyAndConsume`：两种消费模式

```java
// service/base/EmailCodeService.java#verifyAndConsume（真实节选；省略 try 结构细节）
public boolean verifyAndConsume(String email, EmailScene scene, String code) {
    if (email == null || code == null || !code.matches("[0-9]{6}")) return false;
    email = email.trim().toLowerCase(Locale.ROOT);
    String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
    List<String> keys = List.of(codeKey, codeKey + ":attempts", codeKey + ":claim", codeKey + ":owner");
    String claimId = java.util.UUID.randomUUID().toString();
    boolean transactional = TransactionSynchronizationManager.isActualTransactionActive();
    boolean verified = Long.valueOf(1).equals(redisTemplate.execute(VERIFY, keys,
            code, claimId, transactional ? "reserve" : "consume"));
    if (verified && transactional) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try { redisTemplate.execute(COMPLETE, keys, claimId,
                        status == STATUS_COMMITTED ? "commit" : "rollback"); }
                catch (RuntimeException e) { log.warn("验证码预留状态清理失败，将按原有效期失效", e); }
            }
        });
    }
    return verified;
}
```

```lua
-- VERIFY（真实脚本）：KEYS=[码, attempts, claim, owner]，ARGV=[code, claimId, mode]
local stored = redis.call('GET', KEYS[1])
if not stored then return 0 end
if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
if tonumber(redis.call('GET', KEYS[2]) or '0') >= 5 then return 0 end
if stored ~= ARGV[1] then
    local attempts = redis.call('INCR', KEYS[2])
    redis.call('PEXPIRE', KEYS[2], math.max(1, redis.call('PTTL', KEYS[1])))
    if attempts >= 5 then redis.call('DEL', KEYS[1]) end
    return 0
end
if ARGV[3] == 'reserve' then
    redis.call('SET', KEYS[3], ARGV[2], 'PX', math.max(1, redis.call('PTTL', KEYS[1])))
else redis.call('DEL', KEYS[1], KEYS[2], KEYS[4]) end
return 1
```

逐分支读：

- **码不存在 → 0**：没发过、已过期（TTL 到了 Redis 自动删）、或非事务模式下已被消费。
- **claim 存在 → 0**：有人在事务里预留了还没提交/回滚——这是并发消费的核心闸门。
- **错误次数 ≥ 5 → 0**：连错 5 次后即使输入正确码也失败（第 5 次错误时码已被 `DEL`，测试 `fiveWrongGuessesInvalidateEvenTheCorrectCode` 覆盖）。
- **码不匹配**：`INCR attempts` 并把 attempts 的 TTL 对齐到码的剩余存活时间；到 5 次直接删码。
- **码匹配**：模式为 `reserve`（调用方在 MySQL 事务里）就写入 claim 占住这枚码、码原样保留；模式为 `consume`（无事务）就直接删掉码、attempts、owner——一次用完。

COMPLETE 脚本在事务结束的 `afterCompletion` 里执行（F3 讲过回调时机：物理事务提交/回滚之后）：

```lua
-- COMPLETE（真实脚本）：KEYS=[码, attempts, claim, owner]，ARGV=[claimId, mode]
if redis.call('GET', KEYS[3]) ~= ARGV[1] then return 0 end
if ARGV[2] == 'commit' then redis.call('DEL', KEYS[1], KEYS[2], KEYS[4]) end
redis.call('DEL', KEYS[3])
return 1
```

COMPLETE 的每个动作都是有条件的，不能写成"提交必销码、回滚必退码"：

- **第一道条件：claimId 必须匹配**。`afterCompletion` 里用的 claimId 是本次 VERIFY 生成的那个；脚本开头 `GET KEYS[3] ~= ARGV[1]` 就返回 0。claim 已经过期、被删除或值变了，整个 COMPLETE 什么都不做。
- **第二道条件：码/claim 还没过期**。claim 的 TTL 在 reserve 时是"码的剩余 PTTL"，它与码大致同时到期。如果事务比剩余码寿命还长，afterCompletion 执行时 claim 很可能已消失 → COMPLETE 返回 0；码也可能已过期被 Redis 删除。此时"提交销码"和"回滚退码"都只是空操作。
- **第三道条件：事务状态是 `STATUS_COMMITTED` 才走 commit 分支**。`STATUS_ROLLED_BACK` 与 `STATUS_UNKNOWN`（提交/回滚结果未知）都会进入 rollback 分支，只删 claim、不删码。**UNKNOWN 不等于提交成功**，不能假设码已被消费。
- **第四道条件：回调实际执行且 Redis 命令成功**。`afterCompletion` 里 `redisTemplate.execute(COMPLETE, ...)` 被 try/catch 包住，异常只记 warn、不重试；脚本中途报错也不会自动回滚已执行命令。回调失败后，若旧 claim/码仍残留且未被其他操作修改，它们会按各自 TTL 过期；具体残留状态静态阅读无法断言。
- **提交分支**：claim 匹配时 `DEL 码/attempts/owner`，再 `DEL claim`。删除不存在的 key 不报错，所以"脚本返回 1"不等于"每个 key 都真实存在过"；它只表示 claim 匹配且脚本执行到了末尾。
- **回滚分支**：只删 claim。码/attempts/owner 原样保留——前提是它们还没过期、也没被新一轮发码覆盖；若已被覆盖，旧 claim 的删除不会碰到新码（但 claim 本身通常也已不匹配）。
- 三个场景对比：**注册、重置密码有 `@Transactional` → reserve；验证码登录没有事务 → consume**。同样是 `verifyAndConsume`，行为由调用方是否在事务里决定。
- 结论：验证码"一定被消费/退还"只在"claim 匹配 + 未过期 + 回调执行成功 + 对应事务状态 + Redis 命令成功"全部满足时成立；任一条件不满足，都只能按 TTL 兜底，没有自动补偿。

---

## 四、登录：密码与验证码两条路

### 4.1 密码登录

```text
POST /auth/login/password {"login":"xiaoyu@example.invalid","password":"<虚构密码>"}
  → AuthController#loginByPassword → AuthServiceImpl#loginByPassword（无 @Transactional）
```

`login` 字段（`PasswordLoginRequestDTO`）有三种形态（`AuthServiceImpl#parseLoginPrincipal`）：

| 形态 | 解析结果 | 说明 |
|---|---|---|
| `xiaoyu@example.invalid`（含 @） | email + USER | 邮箱登录，查 `findUserRoleByEmail` |
| `xiaoyu`（不含 @、#） | username + USER | 用户名登录，查 `findByUsernameAndRole` |
| `laochen#DRIVER`（含 #） | username + 指定角色 | 后台账号形态；禁止 email#role（400） |

```java
// service/impl/AuthServiceImpl.java#loginByPassword（真实节选；省略参数检查与日志）
UserAuth userAuth = findUserByPrincipal(principal);
if (userAuth == null) throw new UnauthorizedException("用户名或密码错误");

long version = tokenService.currentVersion(userAuth.getUserId());
userAuth = findUserByPrincipal(principal);
if (userAuth == null) throw new UnauthorizedException("账号状态已变化");
if (!passwordEncoder.matches(request.getPassword(), userAuth.getPassword())) {
    throw new UnauthorizedException("用户名或密码错误");
}
return issueLoginResultVO(userAuth, version);
```

- 查不到用户、密码不匹配都是 401（`UnauthorizedException`），消息一致，**不暴露"用户不存在"与"密码错误"的区别**。
- **版本号在密码校验前读取**（先 `currentVersion` 再二次查用户、验密码）。这个顺序有意义：后面 `saveToken` 会再比对一次版本（4.3），把"读版本 → 验密码 → 写令牌"整个区间都圈进版本栅栏。
- 用户名/邮箱不存在时**不消耗任何 Redis 状态**；BCrypt `matches` 只读数据库哈希。

### 4.2 验证码登录

```java
// service/impl/AuthServiceImpl.java#loginByEmailCode（真实节选）
if (!emailCodeService.verifyAndConsume(request.getEmail(), EmailScene.LOGIN, request.getCode())) {
    throw new BusinessException(400, "验证码无效或已过期");
}
UserAuth userAuth = userAuthService.findUserRoleByEmail(request.getEmail());
if (userAuth == null) throw new UnauthorizedException("账号不存在");
return issueLoginResultVO(userAuth, tokenService.currentVersion(userAuth.getUserId()));
```

三个差异要记住：

1. **无事务 → consume 模式**：码校验通过的一瞬间就被删除。之后 `findUserRoleByEmail` 查不到账号（401）也**不退码**——码已经烧掉，只能重发。
2. 邮箱验证码登录**只支持 USER 角色**：`findUserRoleByEmail` 硬编码 `role = 'USER'`。司机/客服/管理员要走密码登录的 `username#role` 形态。
3. **邮箱空格的处理不一致**：`verifyAndConsume` 内部会 `trim().toLowerCase()` 后再拼 Redis key，所以带首尾空格的验证码校验可能成功；但紧接着的 `findUserRoleByEmail(request.getEmail())` 只做 `toLowerCase()`、**不 trim**，可能查不到账号 → 401 "账号不存在"，而码已经被消费。重置密码路径同理（`sendEmailCode` 的 RESET_PASSWORD 预检查也不 trim，可能先返回 404）。注册路径的 `existsByEmailAndRole` 不 trim、`createUser` 会 trim，规范化边界也不一致。这不是"全链统一小写+去空格"。

### 4.3 签发令牌：`issueLoginResultVO` 与 SAVE 脚本

```java
// service/impl/AuthServiceImpl.java#issueLoginResultVO（真实节选；省略日志）
String token = tokenService.generateToken();          // UUID 去掉连字符
long now = System.currentTimeMillis();
long expiresAt = now + authProperties.getTokenTtlSeconds() * 1000;

UserToken userToken = UserToken.builder()
        .userId(userAuth.getUserId()).authId(userAuth.getId())
        .username(userAuth.getUsername()).email(userAuth.getEmail())
        .role(userAuth.getRole()).token(token)
        .issuedAtEpochMs(now).sessionVersion(version)
        .credentialStamp(CredentialStamp.of(userAuth.getPassword()))
        .expiresAtEpochMs(expiresAt)
        .build();
tokenService.saveToken(userToken);
userAuthService.updateLastLoginTime(userAuth.getUserId());

return LoginResultVO.builder()
        .token(token).expiresInSec(authProperties.getTokenTtlSeconds())
        .role(userAuth.getRole()).userId(userAuth.getUserId().toString())
        .build();
```

```lua
-- service/impl/TokenServiceImpl.java#SAVE（真实脚本）
-- KEYS=[auth:token:{token}, auth:user_tokens:{userId}, auth:session-version:{userId}]
-- ARGV=[UserToken JSON, ttl 秒, token, sessionVersion]
if (redis.call('GET', KEYS[3]) or '0') ~= ARGV[4] then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('SADD', KEYS[2], ARGV[3])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]) * 2)
return 1
```

- **版本栅栏在写入之前**：`sessionVersion` 与当前版本号不等 → 返回 0 → Java 抛 `UnauthorizedException("登录状态已变化，请重新登录")`。这挡住的是"撤销发生在 `currentVersion` 读取之后、`saveToken` 之前"的在途登录（测试 `revocationFencesLateTokenPublicationAndRemovesWholeIndex`）。
- 一次脚本写三样：token 正文（`EX` 默认 7 天，来自 `AuthProperties.tokenTtlSeconds`，可被 `auth.token-ttl-seconds` 覆盖）、Set 索引 member、Set 索引 TTL 重设为默认 14 天（令牌 TTL × 2）。`addTokenToUserIndex` 被显式禁止（`throw UnsupportedOperationException("令牌与索引必须由saveToken原子写入")`）——防止绕过版本检查单独加索引。
- **固定 TTL，非滑动续期**：`SET ... EX` 只在签发时执行一次；后续请求读令牌不会延长它。`expiresInSec` 返回给客户端的就是本次配置值。
- **签发之后还有一次 MySQL 写**：`userAuthService.updateLastLoginTime(...)` 在 `saveToken` 之后执行，更新 `last_login_time` 与 `update_time`。验证码登录同样走 `issueLoginResultVO`，所以它不是"只写 Redis、没有后续数据库写"。两步没有共同事务：token 已写入 Redis 后 `updateLastLoginTime` 若失败，接口可能抛异常，但 token 已存在。
- 接口 `TokenService#addTokenToUserIndex` 抛异常的实现是"负向保护"，不是可用功能。
- 响应结构（示意，未实测；`expiresInSec` 为默认配置值）：`{"code":200,"message":"success","data":{"token":"...","expiresInSec":604800,"role":"USER","userId":"..."},"total":0}`。

---

## 五、带令牌的请求：拦截器、ThreadLocal 与双层校验

### 5.1 调用链

```text
GET/POST 任意业务路径（如 /order/estimate、/chat/v2/c/{id}）
  ① RequestRateLimitInterceptor（仅 /auth/** 与 /order/estimate）
  ② AuthTokenInterceptor.preHandle
       提取 token（Authorization: Bearer xxx 优先，其次 X-Auth-Token）
       tokenService.getToken(token)
       有效且未过期 → UserTokenContext.set(userToken)
       过期 → tokenService.deleteToken(token)
       无 token / 无效 → 什么都不设
       正常路径 return true（放行；登录与否由 @RequirePermission 决定）
       getToken 抛异常时不会走到 return true，异常向上传播（见 5.2）
       ③ PermissionAspect（仅 @RequirePermission 方法；角色判断留给 A2）
       ④ 业务方法经 UserTokenContext 取身份
       ⑤ AuthTokenInterceptor.afterCompletion → UserTokenContext.clear()
       这是 preHandle 正常返回 true 后框架对本拦截器的回调；
       preHandle 抛异常时不能假定它一定执行（F3 同类的回调边界）
```

### 5.2 `getToken`：一次请求的完整校验

```java
// service/impl/TokenServiceImpl.java#getToken（真实节选；省略 try/catch 与日志）
UserToken parsed = objectMapper.readValue(json, UserToken.class);
if (parsed.getUserId() == null || parsed.getRole() == null || parsed.getCredentialStamp() == null
        || parsed.getSessionVersion() != currentVersion(parsed.getUserId())) return null;
// 密码与账号状态以数据库为准，撤销期间旧密码验证生成的令牌也不能复活。
UserAuth user = users.selectOne(new LambdaQueryWrapper<UserAuth>()
        .eq(UserAuth::getUserId, parsed.getUserId()).eq(UserAuth::getIsDeleted, 0).eq(UserAuth::getStatus, 1));
if (user == null || !Objects.equals(user.getRole(), parsed.getRole())
        || !Objects.equals(CredentialStamp.of(user.getPassword()), parsed.getCredentialStamp())) return null;
return parsed;
```

`getToken` 是**短路校验**，任一步失败就返回 null，后续步骤不执行：

1. Redis `GET auth:token:{token}`：不存在 → null（**不查版本、不查 MySQL**）；
2. Jackson 解析 JSON：格式坏/字段缺失 → null；
3. Redis `GET auth:session-version:{userId}`：与 `sessionVersion` 不等 → null（**不查 MySQL**）；
4. **只有前三步都通过**，才 `SELECT sys_user`（`is_deleted=0 AND status=1`），比对角色与 `CredentialStamp.of(密码哈希)`（**数据库层栅栏**）。

含义与边界：

- 不是"每个带 token 的请求都回查 MySQL"：token 不在 Redis、版本已变时，在第 1/3 步就短路了。只有"Redis 有 token 且版本匹配"的请求才付这一次数据库查询。
- "token 有效" = Redis 里还在 + 版本号没变 + 数据库里密码/角色/状态没变。改密、禁用、逻辑删除会让**下一次通过前两步的请求**失败；如果 REVOKE 已经把 token 删掉，请求会在第 1 步直接 401，根本到不了数据库检查。
- TTL 默认 604800 秒来自 `AuthProperties.tokenTtlSeconds`，`application.yaml` 的 `auth.token-ttl-seconds` 是同值，其它属性源可以覆盖；**是固定值，不滑动续期**。仓库没有"所有部署必然 7 天"的证据。
- `getToken` 里**不检查 `expiresAtEpochMs`**；过期检查在拦截器（`userToken.getExpiresAtEpochMs() > System.currentTimeMillis()`）。过期时拦截器顺手 `deleteToken`。一个"逻辑过期但 Redis key 未到 TTL"的窗口里，`getToken` 本身仍会返回 parsed，是拦截器把它挡下的；Redis TTL 到期后 key 自动消失，两条路径殊途同归。
- `preHandle` 的 `return true` 是**正常路径**：`getToken` 抛异常（如 Redis 不可用）时没有 try/catch，异常直接向上传播，不会走到 `return true`。`afterCompletion` 也是框架对本拦截器 `preHandle` 已正常返回 true 之后的回调；`preHandle` 抛异常时不能假定它一定执行。异常映射到 500 属静态推导，未实测。
- 因此不能保证"Redis 故障时新登录仍然成功"：密码登录要读 `currentVersion`、要执行 SAVE，两步都依赖 Redis；Redis 故障下新登录同样可能失败。登录态只有 Redis 一份，这是 F4 已建立的边界。

### 5.3 ThreadLocal 的生命周期

```java
// security/UserTokenContext.java（真实节选）
private static final ThreadLocal<UserToken> HOLDER = new ThreadLocal<>();
public static void set(UserToken token) { HOLDER.set(token); }
public static UserToken getRequired() {
    UserToken token = HOLDER.get();
    if (token == null) throw new UnauthorizedException("未登录或登录已过期");
    return token;
}
public static void clear() { HOLDER.remove(); }
```

- Tomcat 线程池会**复用工作线程**：`afterCompletion` 里的 `clear()` 防止上一个请求的身份"漏"给下一个复用同一线程的请求。
- `getRequired()` 未登录直接抛 401；`getNullable()` 返回 null 由调用方判断。业务代码（如 `ChatController#chat` 的 `UserTokenContext.getUserIdInString()`）用 `getRequired` 拿身份——**身份来自服务端解析的 token，不信任前端传的 userId**（这是 F1 就立下的规矩）。
- 边界：异步/后台线程不继承 ThreadLocal。F4 已见 `ChatController` 在 Tomcat 线程里把 userId 取成 `String` 参数传给后台——本项目所有跨线程身份传递都是显式传参。

---

## 六、退出、改密、注销：旧会话怎么失效

### 6.1 三个动作对比

| 动作 | 端点 | 事务 | Redis 变化 | 生效范围 |
|---|---|---|---|---|
| 登出 | `POST /auth/logout` | 无 | `deleteToken`：一个 Lua 同时 `DEL auth:token:{token}` 并从 `auth:user_tokens:{userId}` `SREM`（token 已不存在时提前返回；JSON 损坏时只删正文，索引 member 会残留） | **仅当前 token**；其它设备不受影响；版本号不变 |
| 重置密码 | `POST /auth/password/reset` | `@Transactional` | 密码更新 + `deleteAllTokensByUserId` 注册 `afterCommit` → REVOKE | **意图**是该用户全部 token；以提交成功、REVOKE 实际执行为前提（6.2） |
| 注销账号 | `DELETE /auth/account`（需登录） | `@Transactional` | 逻辑删除（`is_deleted=1, status=0`）+ REVOKE | 逻辑删除 + 意图删除全部 token；数据库状态比对是兜底 |

```lua
-- service/impl/TokenServiceImpl.java#REVOKE（真实脚本）
-- KEYS=[auth:user_tokens:{userId}, auth:session-version:{userId}]，ARGV[1]="auth:token:"
redis.call('INCR', KEYS[2])
local tokens = redis.call('SMEMBERS', KEYS[1])
for _, token in ipairs(tokens) do redis.call('DEL', ARGV[1] .. token) end
redis.call('DEL', KEYS[1])
return 1
```

REVOKE 做三件事：**版本号 +1**（挡住在途登录的 SAVE）、**逐个删除该用户所有 token 正文**（脚本里动态拼接 key——F4 提过这在 Redis Cluster 上需要改造，当前仓库只有单机配置）、**删除整个 Set 索引**。

`deleteAllTokensByUserId` 检查 `isActualTransactionActive()`：在事务里就注册 `afterCommit`（回滚时不撤销），没有事务就立即执行（F3 的"检查同步器"模式，与 `OrderDelayQueue#afterCommit` 同构）。

### 6.2 重置密码的完整时间线

```java
// service/impl/AuthServiceImpl.java#resetPasswordByEmailCode（真实节选；省略参数检查）
if (!emailCodeService.verifyAndConsume(request.getEmail(), EmailScene.RESET_PASSWORD, request.getCode())) {
    throw new BusinessException(400, "验证码无效或已过期");
}
UserAuth userAuth = userAuthService.findUserRoleByEmail(request.getEmail());
if (userAuth == null) throw new UnauthorizedException("账号不存在");
userAuthService.updatePasswordByUserId(userAuth.getUserId(), passwordEncoder.encode(newPassword));
tokenService.deleteAllTokensByUserId(userAuth.getUserId());
```

正常路径（每一步都有条件）：验证码预留（claim，claimId 记在本次调用栈）→ 查账号 → `updatePasswordByUserId`（事务内）→ `deleteAllTokensByUserId` 注册 afterCommit → 提交 → afterCommit 执行 REVOKE → afterCompletion 执行 COMPLETE 'commit'（claim 匹配且未过期时删码）。

但"旧 token 全部 401"不是无条件保证：

- **REVOKE 只在提交成功后执行**。若提交失败/回滚，afterCommit 不执行；旧 token 是否仍有效还取决于过期、账号状态和并发撤销等条件。
- **REVOKE 本身可能失败或部分执行**。它是一段 Lua：`INCR 版本 → SMEMBERS → 逐个 DEL → DEL 索引`。脚本中途报错不会自动回滚已执行的命令，所以可能出现版本已变但部分 token 未删；没有其他操作时不能把版本未增但脚本已删索引当成该脚本的中途执行结果。具体残留需结合失败位置判断。afterCommit 里的异常不会回滚已提交的 MySQL，但会向上传播（客户端可能收到 500，尽管密码已改；具体传播路径未实测）。
- **版本递增/全量删除是 REVOKE 的意图，不是数据库事务的一部分**。数据库提交与 Redis 撤销之间没有共同事务，也没有自动重试。
- **`updatePasswordByUserId` 不检查影响行数**：它按 `userId + status=1 + is_deleted=0` 更新，`UPDATE` 影响 0 行（例如账号在提交前被并发禁用/删除）也不会抛异常；流程继续 REVOKE 并返回成功，但密码实际没改。这是一个真实窗口，代码没有兜底。
- **`getToken` 的数据库检查有观察时点**：请求在 `preHandle` 通过后，就带着旧身份继续执行；即使随后密码被改、token 被撤销，这个**已经在执行**的请求不会中途重新校验，仍可能完成它的业务写入。新请求才会走新的校验。
- **不能保证 Redis 故障时新登录一定成功**：密码登录的 `currentVersion`（读 Redis）和 `saveToken`（写 Redis）都依赖 Redis；故障时新登录也可能失败。
- 若 afterCommit 的 REVOKE 失败但数据库密码/状态已改：残留 token 的下一次请求若通过 Redis 前两步，会在 MySQL 指纹/状态比对处失败；若 REVOKE 的版本递增恰好成功，则版本比对先失败。两者都依赖"请求还能读到 token"，具体哪道先触发取决于残留状态，不能一概而论。

回滚路径（例如密码更新后、提交前数据库异常）：MySQL 密码回滚；REVOKE 的 afterCommit **不会执行**；在没有并发改密/禁用/撤销、账号状态未变且旧 token 仍存在并未过期等条件下，旧 token 仍可通过校验。COMPLETE 'rollback' 在 claim 仍匹配时删 claim，码仍存在且未过期时才可能重试；claim 过期或回调失败时不能保证码可再用。**Redis 与 MySQL 没有共同事务**，两个栅栏各有生效条件，详见上文。

### 6.3 注销与多设备

- 注销在事务内执行逻辑删除并注册提交后的 REVOKE。提交后，如果某个 token 仍能通过 Redis 前两步，`getToken` 的数据库查询（`is_deleted=0 AND status=1`）会拒绝它——**第三道栅栏**；如果 REVOKE 已经把它删掉或版本已变，则更早被拒。逻辑删除与 REVOKE 都受提交/Redis 执行结果约束，不是无条件同时成功。
- 多设备登录互不影响：两次 `saveToken` 各写各的 token、`SADD` 到同一 Set、Set TTL 按默认 14 天重设（受 `auth.token-ttl-seconds` 覆盖）。登录**不递增**版本号——新登录不踢旧会话。
- 单设备登出只删自己的 token；要"全端下线"只能改密/注销（REVOKE）或等 token 自然过期。

---

## 七、401、业务响应与异常映射（本章最小集）

| 场景 | 抛出/返回 | HTTP 状态（`GlobalExceptionHandler`） | 客户端看到的 `Result` |
|---|---|---|---|
| 未登录访问 `@RequirePermission` 方法 | `UserTokenContext.getRequired` / `PermissionAspect` 抛 `UnauthorizedException(401)` | 401 | code=401 |
| 密码错误 / 账号不存在（密码登录） | `UnauthorizedException("用户名或密码错误")` | 401 | code=401 |
| 验证码无效/过期/超次数 | `BusinessException(400, "验证码无效或已过期")` | 400 | code=400 |
| 场景字符串非法 | `IllegalArgumentException` | 400 | code=400，message 为异常消息 |
| 发码太频繁 | `BusinessException(429, ...)` | 429 | code=429 |
| 发重置密码验证码但邮箱未注册（`POST /auth/email-code`，scene=RESET_PASSWORD） | `BusinessException(404, "账号不存在")` | 404 | code=404 |
| 重置密码时账号查不到（`POST /auth/password/reset`） | `UnauthorizedException("账号不存在")` | 401 | code=401 |
| 邮箱已注册 / 用户名已存在 / 唯一键冲突 | `BusinessException(409, ...)` / `DuplicateKeyException` | 409 | code=409 |
| SMTP 发送失败 | `BusinessException(500, "邮件发送失败...")` | 500 | code=500 |

规则：`GlobalExceptionHandler#response` 把 400～599 的业务 code 直接当 HTTP 状态，越界压到 400；`Result.code` 保留原始业务码。

- **公开端点**：发码/注册/登录/重置没有 `@RequirePermission`，拦截器在"没带 token / token 无效"时正常返回 true，请求继续执行——它们本身不会产生 401。若 `getToken` 抛异常，`preHandle` 不会走到 `return true`，异常向上传播（映射未实测）。
- **受保护端点**：`DELETE /auth/account` 等带 `@RequirePermission` 的方法，由 `PermissionAspect` 检查 `UserTokenContext.getNullable()`，为空抛 `UnauthorizedException(401)`；`getRequired()` 也会在业务方法内抛 401。
- 角色检查与资源归属（403 那一层）是 A2 的内容，本章不展开。

---

## 八、并发与失败推演

### 8.1 推演一：两个请求并发消费同一枚验证码

设定：林小雨的登录码 `482913` 已发到 `auth:email_code:login:xiaoyu@example.invalid`（默认 TTL 300 秒，可配置；scene 段小写）。她的浏览器卡顿，同一请求发了两次（T1、T2）。

```text
默认（claim 未过期、码未过期、回调成功）：
t0  T1 进入 VERIFY：码存在、claim 不存在、attempts<5、码匹配。
    场景 A（登录，consume）：DEL 码/attempts/owner，return 1 → 成功。
    场景 B（注册，reserve）：SET claim=T1claimId，PX=码的剩余 PTTL。
t1  T2 进入 VERIFY：
    场景 A：码已被删 → 0 → 400 "验证码无效或已过期"。
    场景 B：claim 存在 → 0 → 400；T2 没有写 MySQL，也没有注册 COMPLETE 回调。
t2  场景 B 的 T1 提交 → afterCompletion(COMMITTED) → COMPLETE 'commit'：
    claim 仍匹配且码还在 → 删码/attempts/owner + claim。
    若 T1 回滚 → COMPLETE 'rollback'：claim 仍匹配 → 只删 claim；
    码若仍未过期，T2 可以重新发一个请求用同一枚码再试（不是自动继续）。

不成立/未知的分支：
- T1 的 claim 到期（事务比码的剩余寿命还长）：COMPLETE 因 claim 不匹配
  而空操作；码通常也已到期，T2 不能再用它，只能重新发码。
- T1 已提交但 COMPLETE 失败（Redis 异常）：具体残留未知。
  若 claim 仍在，T2 会被拒；若 claim 已删除而码仍在，可能重新通过。
- 事务结果是 STATUS_UNKNOWN：afterCompletion 走 rollback 分支（只删 claim），
  不能认为码已消费。

保护点：VERIFY 是单段 Lua，claim 检查与写入之间不会被其它 Redis 命令穿插；
        claim 是"同一枚码在未过期期间只被一个事务预留"的闸门。
前提：两个请求打到同一 Redis（单机配置，见 F4 的 Cluster 讨论）；
      比较"同时到达"时，claim 和码都还没过期。
剩余窗口：T1 回滚到 claim 释放之间，T2 用同码会 400——用户感知是
        "码没错却提示无效"，需要稍等重试；claim 过期后则必须重新发码。
```

测试佐证（阅读，未运行）：`SecurityRedisIntegrationTest#verificationCodeCanOnlyBeConsumedOnceUnderConcurrency` 在线程池中提交 16 个任务，使用无事务的 LOGIN 直接消费模式，断言只有 1 个成功；它不验证事务内 claim 的并发预留。另两个事务同步测试手动触发提交或回滚回调，并非真实数据库并发事务。

### 8.2 推演二：重置密码 vs 在途登录（两道栅栏接力）

设定：林小雨的旧 token A 仍有效。攻击者（或她自己）恰好在重置密码提交的同一瞬间发起密码登录。

```text
正常情形（提交成功、REVOKE 成功、密码确实更新）：
t0  重置事务开始：VERIFY reserve 写 claim。
t1  登录请求并行：查用户 → currentVersion 读到 v=5 → BCrypt 校验通过。
t2  重置事务提交：MySQL 密码哈希更新；
    提交后 afterCommit 执行 REVOKE：INCR 版本 → v=6；
    SMEMBERS 删掉该用户全部 token（含旧 token A）；DEL 索引。
t3  登录请求走到 saveToken：SAVE 比对 (GET version or '0')=6 ≠ 5 → return 0
    → UnauthorizedException("登录状态已变化，请重新登录")。
    即"撤销之后才完成的签发"被版本号挡下——前提是 REVOKE 的 INCR 确实执行了。

REVOKE 失败/部分执行（状态未知）：
    可能版本已增但部分 token 未删，也可能版本未增、token 全在，
    还可能是其它组合——Lua 中途错误不回滚，静态阅读不能断言。
    若版本未增且 token A 残留：下一次带 A 的请求会先通过 Redis 两步，
    再在 MySQL 指纹/状态处失败（前提是密码确实变了）。
    若 REVOKE 已把 A 删掉：请求在第 1 步就 null → 401，根本不到数据库。
    afterCommit 的异常不会回滚已提交的 MySQL，但会向上传播；
    客户端可能收到错误而密码已改，具体表现未实测。

"密码确实变了"这个前提不是自动成立的：
    updatePasswordByUserId 不检查影响行数。如果更新影响 0 行
    （例如账号在提交前被并发禁用/逻辑删除），它不抛异常，流程继续
    REVOKE 并返回成功；此时旧密码哈希没变，指纹栅栏不成立。
    如果 REVOKE 也恰好没生效，旧 token A 仍可能通过完整校验。
    这是代码没有兜底的窗口，不能用"改密必然使旧 token 失效"概括。

在途请求：已经通过 preHandle 校验、正在执行的请求不会在业务中途
    重新检查密码/版本；改密或撤销无法中断它已经开始的业务写入。
    只有后续新请求才走新的校验。

Redis 故障：不能保证新登录成功——密码登录要读 currentVersion、
    要执行 SAVE，二者都依赖 Redis。旧 token 的拒绝也可能因 Redis
    不可用而变成异常，而不是干净的 401。
```

### 8.3 推演三：SMTP 失败、旧回调与新发码交错

```text
t0  sendCode(X)：ISSUE 通过（冷却不存在、claim 不存在）
    → SET 码(X)、SET 冷却=X、SET owner=X、DEL attempts。claim 此时仍为空。
t1  sendEmail 抛异常 → CLEANUP(issueId=X)：
    GET owner == X → DEL 码 + owner；
    GET 冷却 == X → DEL 冷却。
    attempts 不在清理范围。
t2  接口返回 500。若 t1 成功，用户可以立刻重发；若 t1 失败，
    具体残留未知；若仍存在且未被后续操作改变，按原 TTL 过期（默认码/owner 300 秒、冷却 60 秒，可配置）。
    脚本中途失败时不知道执行到哪一步，不能猜具体残留。

交错分支（SMTP 很慢，用户在冷却过期后又发了一次）：
t1' 冷却已过期且 claim 为空 → 新一轮 ISSUE(Y) 允许通过，
    SET 码(Y)、SET 冷却=Y、SET owner=Y、DEL attempts。
t2' 旧 SMTP 请求这时才抛异常 → CLEANUP(X)：
    GET owner = Y ≠ X，GET 冷却 = Y ≠ X → 两个条件都不满足，
    旧清理对码/冷却/owner 都不删除。新状态安全。
注意：旧码在 t1' 已被新码覆盖；CLEANUP 操作的是同一个 key，
     若无 issueId 匹配检查就可能删掉新码。保护靠的是归属检查。
```

结论：SMTP 失败后的可重试性取决于 **CLEANUP 是否成功、冷却是否已删、claim 是否为空**；旧失败回调与新发码交错时，issueId 归属检查防止误删，但**不能推定失败后的具体 key 状态**（Lua 错误不回滚），只能按 TTL 上限兜底。没有自动重试。

### 8.4 推演四：并发注册同一邮箱（按代码可达分支）

```text
默认情形（同一邮箱、同一 REGISTER 码、claim 未过期）：
t0  T1、T2 都通过参数检查与 existsByEmailAndRole（"不存在"）。
t1  T1 的 VERIFY（reserve）成功：SET claim=T1claimId，PTTL 对齐码剩余寿命。
t2  T2 的 VERIFY 看到 claim 存在 → 返回 0 → BusinessException(400)。
    T2 事务回滚（它没有写任何 MySQL 行，也没有注册 COMPLETE 回调，
    因为 verified=false）。
t3  T1 insert 成功并提交 → afterCompletion(COMMITTED) → COMPLETE 'commit'：
    claim 仍匹配 → 删码/attempts/owner + claim。
结果：T1 成功；T2 收到 400。T2 已收到响应，**必须重新发请求**；
     通常还需要重新发码（claim 与码的 TTL 大致同时到期，原码基本不可再用）。
     这条路径不会到达数据库唯一键。

T1 回滚：COMPLETE 'rollback' 只在 claim 仍匹配、码未过期时删 claim；
    码保留，T2 可以"重新发一个 register 请求"用同一枚码再试——
    这不是自动继续，而是新请求，且必须在码 TTL 内。

特殊情形 A（claim 过期）：T1 的事务超过 claim/码剩余寿命。
    claim 与码几乎同时过期，之后的 T2 请求会对"码不存在"返回 0；
    即使 T1 之后才完成，COMPLETE 也因 claim 不匹配而空操作。
    此时没有"退码"，只能重新发码。

特殊情形 B（长事务 + 重发新码，唯一键可达）：
t0  T1 预留码 X 后事务很慢，claim/码 X 到期；冷却也已过期。
t1  用户重新发码 Y（claim 为空、冷却为空 → ISSUE 允许），
    码 key 被 Y 覆盖，owner/冷却改为新 issueId。
t2  第二个注册请求 T3 用码 Y 预留成功、insert 并提交。
t3  T1 这时才提交 insert → 撞 uk_active_email_role → DuplicateKeyException
    → 409；T1 的 COMPLETE 用旧 claimId，claim 已不存在/不匹配 → 空操作，
    不会删掉码 Y 或新状态。
    这是"同邮箱并发最终由唯一键裁决"的一个可达例子，但前提是
    长事务跨过 TTL + 用户重发新码，不是默认时间线。

特殊情形 C（完成回调失败）：T1 已提交但 COMPLETE 抛异常/未执行，
    具体残留未知；若 claim 仍在，新请求会被拒；若已被删除而码仍在，
    则可能再次通过 VERIFY。残留旧 key 若不被其他操作修改会按 TTL 过期。

另一个独立、可达的唯一键兜底：不同邮箱、同一用户名的两笔注册。
    验证码 key 不同，两边都能各自预留并 insert，最后
    uk_active_username_role 让后插入者 409。这里验证码预留步骤没有被跳过，
    唯一键只是最后一道数据库裁决。

保护点：claim 是同一枚码的并发闸门；唯一键是独立的数据库兜底；
        两者作用层次不同，不能互相替代，也不能把唯一键当成默认并发路径。
剩余窗口：claim 过期/回调失败/长事务都会留下"码不能用但用户不知道原因"的
        体验问题；源码没有自动重试或告警补偿。
```

---

## 九、边界与未验证

**源码可证，但有条件**：

- 验证码一发一码；冷却默认 60 秒（`AuthProperties` 默认值，可被 `auth.email-code-cooldown-seconds` 覆盖）；错误 5 次作废；claim 在**未过期、码未过期**期间让同一枚码只被一个事务预留。claim/码过期、回滚回调失败等特殊分支见 8.1。
- 注册/重置在事务里 reserve、登录 consume；"提交销码/回滚退码"只在 claimId 匹配、码未过期、回调实际执行且 Redis 命令成功、事务状态符合预期时成立（3.3）。
- token 默认 7 天 TTL（`AuthProperties` 默认值，可配置覆盖）、固定不滑动；`getToken` 是短路校验，只有 Redis token 存在且版本匹配才查 MySQL（5.2）。
- 改密/注销的**意图**是版本号 +1、全量删 token、数据库指纹/状态比对三重失效；REVOKE 在 afterCommit，是否真正生效取决于提交与 Redis 执行结果（6.2）。
- 公开端点（发码/注册/登录/重置）不要求登录；受保护端点由 `@RequirePermission`/`getRequired` 产生 401。

**有前提或存在窗口（源码可推导）**：

- 预留模式下回滚到 claim 释放之间，其它请求用同码会 400；claim 过期后码通常也过期，只能重新发码。
- SMTP 失败清理若再失败，具体残留不可断言；若旧 key 仍存在且未被其他操作修改，按原 TTL 过期（码/owner 默认 300 秒，冷却默认 60 秒）；无自动重试补偿。
- `updatePasswordByUserId` 不检查影响行数：0 行时仍可能继续 REVOKE 并返回成功，密码实际未变，指纹栅栏不成立。
- 布隆过滤器在注册事务**提交前**写入 Redis：MySQL 回滚后布隆仍可能保留用户名；它不在 `isUsernameAvailable` 主链上，但属于跨系统副作用。
- 邮箱输入边界不一致：`EmailCodeService` 的 Redis key 走 `trim().toLowerCase()`；`UserAuthServiceImpl.findUserRoleByEmail` 等只做 `toLowerCase()`、**不 trim**，而调用方（登录/重置）传的是原始输入。带首尾空格的邮箱可能在验证码路径被 trim 后命中 Redis，却在账号查询时查不到（401/404），或让 `existsByEmailAndRole` 与 `createUser` 的规范化结果不一致。
- 验证码登录在签发 token 后还会 `updateLastLoginTime` 写 MySQL；它不是"只写 Redis、无后续数据库写"。
- 已通过 `preHandle` 的在途请求不会中途重新校验；改密/撤销只能影响它之后的请求。
- 数据库唯一键（`uk_active_username_role` 等）是独立兜底；不同邮箱同用户名的并发注册可以到达它，但同一邮箱同码的默认路径在 claim 处就失败，不能拿唯一键冒充默认时间线。
- Redis 故障时不能保证新登录成功：`currentVersion` 和 SAVE 都依赖 Redis。

**未验证（需要实测，本章未运行）**：真实 SMTP 行为与 CLEANUP 失败后的具体残留；429/404/409/500 的实际响应字节；Redis 故障时 `preHandle` 异常的实际映射；`afterCommit` 中 REVOKE 失败的真实表现与顺序；并发场景的实测时序；`auth:session-version` 在真实环境的存活时长（无 TTL，理论上永生，运维清空另说）；注销后用户名/邮箱复用（生成列 NULL 的唯一键行为，待核验）。

---

## 十、动手验证（只读源码追踪）

### 练习 A：从一封"邮件"追到一行 `sys_user`

目标：不启动服务，沿源码把注册成功链的每一步写出来。

| 步骤 | 打开/搜索 | 预期发现 | 判断依据 |
|---|---|---|---|
| 1 | `AuthController#register` | `POST /auth/register`，返回 `Result.ok(userId.toString())` | 方法注解与返回值 |
| 2 | `AuthServiceImpl#registerByEmailCode` 方法头 | `@Transactional`；参数检查在 `verifyAndConsume` 之前 | 语句顺序 |
| 3 | `EmailCodeService#verifyAndConsume` | `isActualTransactionActive()` 决定 reserve/consume；事务内注册 `afterCompletion` | 分支与回调注册 |
| 4 | `EmailCodeService` 的 VERIFY 脚本 | claim 检查在第 2 行；错误分支 INCR attempts | Lua 逐行 |
| 5 | `UserAuthServiceImpl#createUser` | email 小写化、role 大写化、insert | 方法体 |
| 6 | `src/main/resources/sql/init.sql` 的 `sys_user` | `uk_user_id` / `uk_active_username_role` / `uk_active_email_role` 与生成列 | DDL |
| 7 | `UserUsernameBloomFilterService#addUsername` | 在注册事务**提交前**写入布隆过滤器；可用性检查不查布隆 | 调用点位置（`@Transactional` 方法体内） |
| 8 | `SecurityRedisIntegrationTest` 中 `transactionCommitPermanentlyConsumesReservedCode` | 提交后同码不可再用；回滚用例则可 | 断言阅读 |

<details>
<summary>练习 A 参考答案</summary>

1. `AuthController#register` 只做参数透传，`userId` 以字符串放进 `Result.ok` 的 data。
2. 注册方法有 `@Transactional`，因此 `verifyAndConsume` 走 reserve 分支；参数校验（空请求/邮箱格式/密码/用户名/邮箱重复）全部在前，避免无谓烧码。
3. `verifyAndConsume` 先用正则 `[0-9]{6}` 在 Java 侧挡掉格式错误（不碰 Redis），再执行 VERIFY 脚本；事务激活时注册 `afterCompletion`，按 `STATUS_COMMITTED` 决定 COMPLETE 的 commit/rollback 参数。
4. VERIFY 的顺序：码存在 → claim 不存在 → attempts < 5 → 码匹配；任何一步失败都返回 0，Java 统一报 400。
5. `createUser` 把 email 先 `trim()` 再小写、role 大写后 `insert`；查询侧（`findUserRoleByEmail`）只做 `toLowerCase()`、不 `trim()`。因此"大小写不敏感"一致，但"首尾空格"不一致：Redis 的验证码路径会 trim，账号查询不会。这是输入边界，不是全链统一的规范化。
6. 唯一键用生成列实现"未删除才参与唯一"：`active_username/active_email` 在 `is_deleted=1` 时为 NULL，从 DDL 层挡住并发重复注册，也允许注销后用户名/邮箱被重新注册（NULL 不参与唯一比较——这是 MySQL 行为，仓库未显式测试，标待核验）。
7. 布隆过滤器在注册事务**提交前**、`createUser` 之后调用 `addUsername`；若后续提交失败/回滚，布隆里的用户名不会自动删除。`isUsernameAvailable` 走 `findByUsernameAndRole` 数据库查询，布隆不在注册判定路径上。这是跨系统副作用，不是"提交成功后才有"。
8. 提交用例断言提交后 `verifyAndConsume` 同码返回 false；回滚用例断言回滚后同码返回 true。两个用例只在"回调成功、claim 未过期、码仍存在"的默认条件下成立；测试未覆盖回调失败/claim 过期。本轮未运行，只读断言。
</details>

### 练习 B：三组 key 的生命周期 + 状态推演

目标：给下面故事线的每个时刻，写出 Redis/MySQL 里相关状态。人物：林小雨（已注册，USER）。

```text
T1  手机密码登录成功
T2  平板验证码登录成功（同账号）
T3  手机登出
T4  平板仍在用
T5  用验证码重置密码
T6  平板旧 token 再发请求
```

| 步骤 | 打开/搜索 | 预期发现 |
|---|---|---|
| 1 | `TokenServiceImpl#SAVE` | 两次登录各写一个 `auth:token:*`，SADD 同一 Set，Set TTL 按默认 14 天重设（`auth.token-ttl-seconds` 可覆盖） |
| 2 | `AuthServiceImpl#logout` → `TokenServiceImpl#deleteToken` | 只 DEL 当前 token + SREM；Set 里还有平板的 |
| 3 | `AuthServiceImpl#resetPasswordByEmailCode` | 事务内改密 + 注册 afterCommit REVOKE |
| 4 | `TokenServiceImpl#REVOKE` | 版本 INCR、按 Set 删全部 token、删 Set |
| 5 | `TokenServiceImpl#getToken` | T6 时平板 token 已被删（GET 为 null）→ 401；即使残留也过不了指纹比对 |

<details>
<summary>练习 B 参考答案</summary>

T1/T2：`auth:token:{A}`、`auth:token:{B}` 各自默认 7 天 TTL（`auth.token-ttl-seconds` 可覆盖）；`auth:user_tokens:{userId}` 含 A、B 两个 member，TTL 按默认 14 天（令牌 TTL×2，每次签发重设）；`auth:session-version:{userId}` 保持 v 不变（登录不递增版本）。
T3：`deleteToken(A)` → `DEL auth:token:{A}`、`SREM` A；B 与 Set、版本号不动。
T4：平板 token B 通过完整校验（Redis 有、版本一致、数据库密码/角色/状态没变）。
T5：提交成功后 afterCommit 执行 REVOKE：**若执行成功**，版本 v+1；A 已不在（登出时删过），B 被 `DEL`；Set 整个删除。密码更新**实际影响行**时旧 `credentialStamp` 失配；若 REVOKE 失败/部分执行，则版本与 token 的残留状态未知，不能断言"B 一定被删"。
T6：若 B 的 `GET` 为 null → `getToken` 返回 null → 拦截器不设上下文 → 受保护端点 401。若 B 残留：版本若已 v+1 → 版本失配拒绝；版本未变但密码已真改 → 指纹失配拒绝；若密码更新 0 行且 REVOKE 也未生效，B 可能仍通过——这是代码没有兜底的窗口。
</details>

### 可选隔离观察（本轮未运行）

若你以后用一次性 Redis 实例做过实验，可用只读命令观察（**不要对业务实例执行**；示例 key 均为虚构）：

```powershell
redis-cli -p <isolated-port> KEYS "auth:email_code:*"
redis-cli -p <isolated-port> TTL "auth:email_code:register:xiaoyu@example.invalid"
redis-cli -p <isolated-port> TTL "auth:token:0f1e2d3c4b5a69788796a5b4c3d2e1f0"
redis-cli -p <isolated-port> SMEMBERS "auth:user_tokens:1234567890"
redis-cli -p <isolated-port> GET "auth:session-version:1234567890"
```

预期（**未实测**）：码 key TTL ≤ 300；token key TTL ≤ 604800；Set 含该用户全部 token；版本 key 无 TTL（`TTL` 返回 -1）。本轮没有连接任何 Redis、没有发送任何邮件、没有调用真实模型。

---

## 十一、一页复习

- **验证码五 key**：码（默认 300s）、冷却（默认 60s）、attempts（对齐码 TTL，5 次作废）、owner（SMTP 清理的归属凭证）、claim（发码闸门 + 事务预留锁）。**ISSUE 检查的是冷却或 claim，owner 不是发码闸门**；冷却过期且无 claim 时新一轮覆盖旧码。
- **两种消费模式**：注册/重置在 `@Transactional` 里 → reserve + `afterCompletion`；登录无事务 → consume 即删。销码/退码只在 claimId 匹配、码与 claim 未过期、回调执行成功、事务状态符合预期时成立。
- **令牌三 key（默认值可被 `auth.token-ttl-seconds` 覆盖）**：`auth:token:{token}`（String，UserToken JSON，默认 7 天，固定不续期）、`auth:user_tokens:{userId}`（Set 索引，默认 14 天）、`auth:session-version:{userId}`（无 TTL，撤销时 INCR）。
- **短路双层校验**：先 Redis token，再 Redis 版本号，只有前两步通过才查 MySQL 指纹/角色/状态；不是每个带 token 的请求都回查。token 只在 Redis，Redis 清空即全员下线。
- **失效三动作**：登出删单个 token；改密/注销的**意图**是提交后 REVOKE（版本+1、全量删、删索引），实际生效受提交、afterCommit 执行、Redis 命令结果约束；数据库状态是兜底。重置后要注销需先重新登录。
- **三层响应**：401（未登录/凭证错）、`Result.code`（业务码原样保留）、`GlobalExceptionHandler` 把 400～599 业务码当 HTTP 状态。公开端点不产生 401；角色/归属是 A2 的内容。
- **不做的事**：无滑动续期、无"记住我"、无刷新令牌、无邮件重试队列、验证码错误无区分提示；布隆在注册事务提交前写、不随回滚撤销。

---

## 十二、自测与答案

**1. 发送验证码时，ISSUE 脚本检查什么、写入什么？owner 和 claim 各起什么作用？**

<details>
<summary>参考答案</summary>

KEYS 顺序是 `[码, 冷却, attempts, owner, claim]`，所以 ISSUE 检查的是 `KEYS[2]` 冷却或 `KEYS[5]` claim 是否存在（存在即 429），不是 owner。写入：码（默认 EX 300s）、冷却（默认 EX 60s，值为 issueId）、owner=issueId（默认 EX 300s）、`DEL attempts`。claim 是发码闸门之一：只要某次注册/重置已预留（claim 存在），就不允许发新码，否则新码会覆盖旧码、让在途事务的预留失效。owner 不是闸门，只是 SMTP 失败清理的归属凭证——CLEANUP 只在 owner/冷却的值仍等于自己的 issueId 时删除对应 key。冷却过期且无 claim 时，即使旧码/owner 还在，也允许签发新码并覆盖旧状态。
</details>

**2. 注册和验证码登录都调用 `verifyAndConsume`，两者行为有何不同？设计动机能证明吗？**

<details>
<summary>参考答案</summary>

注册（`registerByEmailCode` 有 `@Transactional`）走 reserve：VERIFY 匹配后写 claim 占住码，码保留；事务完成后的 COMPLETE 在 claimId 匹配且码/claim 未过期时，按 `STATUS_COMMITTED` 选 commit（删码/attempts/owner+claim）或 rollback（只删 claim）；回调失败则只记 warn，残留按 TTL 兜底。验证码登录无事务，走 consume：匹配即删码/attempts/owner，之后即使查不到账号（401）也不退码。**代码能证明的是"两种模式的效果"**；"为什么这样设计"没有作者记录，不能把"减少重放窗口/避免失败重收邮件"这些效果说成原始动机。可验证的代价是：登录后续失败时码已烧掉；注册失败回滚时 claim 释放，码可重试（前提是未过期、回调成功）。
</details>

**3. `UserToken` 里的 `sessionVersion` 和 `credentialStamp` 分别挡住什么？各自在什么时机比对？**

<details>
<summary>参考答案</summary>

`sessionVersion` 在两处比对：签发时 SAVE 脚本开头（`(GET version or '0') ~= ARGV[4]` 则拒绝）和 `getToken` 里（与当前版本号不等则返回 null）。它能挡住"撤销发生在版本读取之后、令牌写入之前"的在途登录；也能在 REVOKE 的版本递增**确实执行**后拒绝旧令牌——但 REVOKE 是 Lua，若中途失败，版本是否已增、token 是否已删都不能静态断言。`credentialStamp` 是密码 BCrypt 哈希的 SHA-256，只在 `getToken` 的 MySQL 分支比对，挡住"改密后 Redis 里残留且版本号未变"的旧令牌；它的前提是密码更新**实际影响了行**，而 `updatePasswordByUserId` 不检查影响行数。一个是 Redis 内栅栏，一个是数据库真值栅栏，各有条件。
</details>

**4. 林小雨在手机和平板各登录一次，然后只在手机上登出。平板的 token 会受什么影响？手机 token 会怎样？**

<details>
<summary>参考答案</summary>

两次登录各产生独立 token（UUID 不同），SADD 到同一 `auth:user_tokens:{userId}`，Set TTL 按默认 14 天重设（受 `auth.token-ttl-seconds` 覆盖）；`auth:session-version` 不变（登录不递增）。手机登出执行 `deleteToken(手机token)`：只 `DEL` 手机 token key 并 `SREM` 该 member；平板 token、Set 里剩余 member、版本号全部不动。平板继续可用。要让两台设备同时下线，**意图**是重置密码/注销（REVOKE 全量失效，前提见 6.2）或等 token 默认 7 天 TTL；不能保证 REVOKE 一定成功。
</details>

**5. 重置密码事务提交后、REVOKE 执行前的一瞬间，有人带着旧 token 发请求，会发生什么？REVOKE 执行失败呢？**

<details>
<summary>参考答案</summary>

正常情况下：REVOKE 注册在 `afterCommit`，提交后才执行。提交后到 REVOKE 执行前，旧 token 还在 Redis、版本未变，但数据库密码已更新（前提是 `updatePasswordByUserId` 实际影响了行）；`getToken` 通过 Redis 两步后会在 MySQL 指纹处返回 null → 401。若 REVOKE 失败/部分执行：状态未知——可能版本已增但 token 未删（版本栅栏拒），也可能版本未增、token 全在（指纹栅栏拒，前提是密码真改了）；若密码更新 0 行且 REVOKE 也未生效，旧 token 可能继续有效。afterCommit 异常不回滚已提交的 MySQL，但会向上传播，客户端可能收到错误而密码已改。不能保证"Redis 故障时新登录一定成功"，因为新登录也依赖 Redis 的版本读取与 SAVE。残留 token 按 TTL（默认 7 天，可配置）消失。该推演为静态推导，未实测。
</details>

**6. 为什么说 `getToken` 不是每次请求都查 MySQL？这与 F4 的"令牌只有 Redis 一份"矛盾吗？**

<details>
<summary>参考答案</summary>

`getToken` 是短路校验：先 Redis `GET auth:token:{token}`，不存在→null；再解析 JSON；再读版本号比对，不等→null；只有前几步都通过才查 `sys_user` 比对角色与密码指纹。所以 token 已删或版本已变的请求根本不会到 MySQL。不矛盾："令牌本体只有 Redis 一份"指登录态存储位置（MySQL 没有 token 表）；查 MySQL 查的是**凭据真值**（密码哈希、角色、status、is_deleted），用于让改密/禁用/注销在后续请求生效。代价是"Redis 两步都通过的请求"要付一次 MySQL 查询；这是可验证的效果，是否"安全性优先的取舍"是设计解读，不能当源码事实。
</details>

**7. 一个人 5 次输错验证码后输入了正确的码，结果是什么？为什么？**

<details>
<summary>参考答案</summary>

失败。VERIFY 脚本错误分支：每次 `INCR attempts` 并对齐 TTL，第 5 次错误时 `DEL` 码 key。此后再验证时 `GET 码` 为 nil，直接返回 0；即使码 key 因某种原因还在，`attempts >= 5` 的检查也会拒绝。用户只能重新发码（新一轮 ISSUE 会 `DEL attempts` 重新计数）。测试 `fiveWrongGuessesInvalidateEvenTheCorrectCode` 覆盖了"5 次错以后正确码也失败"。
</details>

**8. `AuthTokenInterceptor#preHandle` 的 `return true` 是"永远放行"吗？未登录请求是怎么被拦住的？`UserTokenContext.clear()` 放在 `afterCompletion` 有什么条件？**

<details>
<summary>参考答案</summary>

`return true` 是**正常路径**：没带 token、token 无效、token 过期（过期时顺手 deleteToken）时都正常走到 `return true`，请求继续；登录与否由 `@RequirePermission`（`PermissionAspect` 检查 `getNullable() != null`，必要时比对角色）和业务方法里的 `getRequired()`（无身份抛 401）决定。但 `getToken` 抛异常（如 Redis 不可用）时，`preHandle` 不会走到 `return true`，异常向上传播（映射未实测）。`afterCompletion` 是框架在本拦截器 `preHandle` 正常返回 true 后调用的清理点，用于 `clear()` 防止 Tomcat 线程复用把身份泄漏给下一个请求；`preHandle` 抛异常时不能假定它一定执行。
</details>

**9. 同一邮箱并发提交两次注册，最终会怎样？验证码会被烧掉吗？**

<details>
<summary>参考答案</summary>

按默认可达路径：两次都先通过参数检查与 `existsByEmailAndRole`；T1 的 VERIFY reserve 写 claim；T2 的 VERIFY 看到 claim → 返回 0 → 400。T2 没有写 MySQL，也没有注册 COMPLETE 回调；它必须**重新发请求**，且通常要重新发码（claim 与码的 TTL 大致同时到期）。T1 提交且 COMPLETE 成功时码被删；T1 回滚时 COMPLETE 只删 claim，码若未过期，T2 可以"重新发一个请求"用同一码再试——不是自动继续。特殊情形：T1 长事务跨过 claim/码 TTL，用户重新发码 Y，第二个注册用 Y 先 insert，T1 后提交撞 `uk_active_email_role` → 409，旧 COMPLETE 因 claim 不匹配空操作。另一个可达的唯一键例子是"不同邮箱、同一用户名"的两笔注册：各自预留各自邮箱的码，最后 `uk_active_username_role` 裁决。不能把唯一键写成同一邮箱同码并发的主路径。
</details>

**10. 本章哪些结论是"源码可证"，哪些是"未实测"？各举两例。**

<details>
<summary>参考答案</summary>

源码可证：VERIFY/ISSUE/SAVE/REVOKE 脚本的分支与 key（逐行阅读，注意 ISSUE 检查的是冷却/claim，不是 owner）；注册与重置在事务里走 reserve、登录走 consume（`isActualTransactionActive()` 分支）；`getToken` 的短路三层顺序（Redis token→Redis 版本→MySQL 指纹，前两步失败不查库）；`updatePasswordByUserId` 不检查影响行数；布隆在事务提交前写；`updateLastLoginTime` 在签发后执行。未实测：429/404/409/500 的实际响应字节；SMTP 真实失败时 CLEANUP 的执行效果与异常传播；Redis 故障时拦截器异常最终映射成什么状态；`afterCommit` 中 REVOKE 失败的真实表现与顺序；锁、码过期与回调失败的实测时序。所有测试（`SecurityRedisIntegrationTest` 等）均为断言阅读，本轮未运行。
</details>

---

## 十三、导航

- **上一章**：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——本章的令牌三 key、Lua 原子性与"Redis 丢失=登录态清零"都建立在它之上。
- **下一章**：[第 6 章 权限、归属与防护](02-authorization-and-defense.md)——角色检查、资源归属、两套限流、异常映射与流式拒绝边界。
- **相关专题**：
  - 布隆过滤器与用户名穿透的当前接线 → [第 6 章 权限、归属与防护](02-authorization-and-defense.md)；缓存穿透完整机制见 [第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)
  - 订单接口的角色边界 → [第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)
  - 对话单轮执行锁与身份传参 → [第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)
  - 一次性确认令牌（另一种"一次性消费"）→ [第 18 章 订单确认](../04-chat-and-agent/03-order-confirmation.md)

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 认证端点与 token 提取 | `controller/AuthController.java`（8 个端点与 `extractToken`） |
| 发码/注册/登录/重置/注销/登出主链 | `service/impl/AuthServiceImpl.java`（`sendEmailCode`、`registerByEmailCode`、`loginByPassword`、`loginByEmailCode`、`resetPasswordByEmailCode`、`logout`、`deleteAccount`、`issueLoginResultVO`、`parseLoginPrincipal`） |
| 验证码四段 Lua 与事务预留 | `service/base/EmailCodeService.java`（ISSUE/VERIFY/COMPLETE/CLEANUP、`sendCode`、`verifyAndConsume`、`sendEmail`） |
| 令牌三脚本与双层校验 | `service/impl/TokenServiceImpl.java`（SAVE/REVOKE/REMOVE、`saveToken`、`getToken`、`deleteToken`、`deleteAllTokensByUserId`） |
| 拦截器与 ThreadLocal | `interceptor/AuthTokenInterceptor.java`；`security/UserTokenContext.java`；`security/UserToken.java`；`security/CredentialStamp.java` |
| IP 限流与拦截器注册 | `interceptor/RequestRateLimitInterceptor.java`；`config/WebMvcConfig.java#addInterceptors`；`util/RedisScripts.java#SLIDING_WINDOW_RATE_LIMIT` |
| 用户数据访问 | `service/impl/UserAuthServiceImpl.java`；`mapper/UserAuthMapper.java`；`domain/entity/UserAuth.java` |
| DTO/VO/枚举 | `domain/dto/SendEmailCodeRequestDTO.java`、`RegisterRequestDTO.java`、`PasswordLoginRequestDTO.java`、`EmailCodeLoginRequestDTO.java`、`ResetPasswordRequestDTO.java`；`domain/vo/LoginResultVO.java`；`domain/enums/EmailScene.java`；`domain/response/Result.java` |
| 表结构与唯一键 | `src/main/resources/sql/init.sql`（`sys_user`：`uk_user_id`、`uk_active_username_role`、`uk_active_email_role` 与生成列） |
| TTL 配置来源 | `src/main/resources/application.yaml`（`auth.token-ttl-seconds=604800`、`auth.email-code-ttl-seconds=300`、`auth.email-code-cooldown-seconds=60`）；`config/AuthProperties.java`（同默认值） |
| 异常映射 | `exception/GlobalExceptionHandler.java`；`exception/BusinessException.java`；`exception/UnauthorizedException.java` |
| 布隆过滤器（注册事务提交前写入） | `service/base/UserUsernameBloomFilterService.java`；`constant/RedisKeyConstants.java#USER_USERNAME_BLOOM_KEY` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/SecurityRedisIntegrationTest.java`（并发消费、5 次作废、并发冷却、撤销栅栏、改密拒令牌、事务提交/回滚消费） |
