# 共享 Session / Token：Redis 集中式会话 + 反向索引踢下线

> **Redis 考点**：把会话（Token）集中存进 Redis（String + JSON），再用一个 Set 建「用户 → 全部 token」的反向索引，实现按用户批量踢下线。
> **来源**：`docs/01-redis-application-points.md` §2.1（登录态与验证码）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/TokenServiceImpl.java`

---

## 一、业务场景

多实例部署下，传统 `HttpSession` 有两条老路：粘性会话（sticky session）或者会话复制。两者都把「用户登录态」钉在某一台机器上。集中式会话的形态是：

1. 登录成功 → 生成一个 UUID 作为 token（`generateToken`），把整个用户上下文序列化成 JSON 写进 Redis；
2. 客户端每次请求携带 `Authorization: Bearer <token>` 或 `X-Auth-Token: <token>`；
3. 任意一个实例收到请求，拿 token 去 Redis 换回用户上下文 —— **实例之间不需要共享任何本地状态**。

两个真实业务点让「反向索引」成为刚需：**重置密码后要把该用户所有设备的登录态一起作废**、**注销账号后同样**。这不是「一个 token 登出」能满足的。

链路四段：

| 阶段 | 入口 |
|---|---|
| 签发 | `AuthServiceImpl.issueLoginResultVO` |
| 存储 | `TokenServiceImpl.saveToken`（`SAVE` Lua） |
| 校验 | `AuthTokenInterceptor.preHandle` → `TokenServiceImpl.getToken`（每个请求） |
| 失效 | 登出 `AuthServiceImpl.logout`、重置密码 / 注销账号 → `deleteAllTokensByUserId`（`REVOKE` Lua） |

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `auth:token:{token}` | String（`UserToken` 的 JSON） | `auth.token-ttl-seconds`，默认 **604800s（7 天）** | 会话主体：`userId` / `authId` / `username` / `email` / `role` / `token` / `sessionVersion` / `credentialStamp` / `issuedAtEpochMs` / `expiresAtEpochMs` |
| `auth:user_tokens:{userId}` | Set | token TTL **× 2**（默认 14 天），由 `SAVE` Lua 统一设置 | 反向索引：该用户已签发的全部 token |
| `auth:session-version:{userId}` | String（整数） | **全量撤销首次 INCR 后无 TTL；未撤销过可不存在** | 会话版本围栏：全量撤销时 `INCR`，改密 / 撤销后旧令牌失效 |

- Key 常量与构建方法：`RedisKeyConstants.tokenKey` / `userTokensKey`；`auth:session-version:` 在 `TokenServiceImpl.versionKey()` 就地拼接。
- TTL 来源：`config/AuthProperties`（默认 604800），配置在 `application.yaml` 的 `auth` 段。

---

## 三、代码落点

| 类 / 方法 | 职责 |
|---|---|
| `TokenServiceImpl.generateToken` | `UUID.randomUUID()` 去掉横线 |
| `TokenServiceImpl.saveToken` | `SAVE` Lua 原子写入：校验会话版本 → `SET` 主体 → `SADD` 索引 → `EXPIRE` 索引（TTL×2） |
| `TokenServiceImpl.getToken` | 读 + 反序列化 + 三重校验（见 4.2） |
| `TokenServiceImpl.deleteToken` | 单 token 登出：先 `GET` 取 userId，再 `REMOVE` Lua（`DEL` 主体 + `SREM` 索引）原子执行 |
| `TokenServiceImpl.deleteAllTokensByUserId` | 按用户踢下线：事务活跃时挂 `afterCommit` 再执行 `REVOKE`（`INCR` 版本 + 遍历删全部令牌 + 删索引） |
| `TokenServiceImpl.currentVersion` | 读 `auth:session-version:{userId}`，不存在按 0 |
| `TokenServiceImpl.addTokenToUserIndex` | 直接抛 `UnsupportedOperationException` —— 禁止绕过 Lua 单独写索引 |
| `security/CredentialStamp` | 凭据摘要（基于密码散列），写入 token 载荷并在校验时比对 DB |
| `interceptor/AuthTokenInterceptor.preHandle` | 每请求校验，写入 `UserTokenContext`（ThreadLocal） |
| `aspect/PermissionAspect` + `@RequirePermission` | 角色权限判定（不查 DB、不查 Redis） |

---

## 四、实现拆解

### 4.1 固定 Redis TTL 与载荷时间戳

签发时载荷记录 `issuedAtEpochMs` / `expiresAtEpochMs`，Redis 主体设置默认 7 天 TTL。但当前 `TokenServiceImpl.getToken` **未比较 expiresAtEpochMs 与当前时间**，有效期由 Redis TTL 实际执行；载荷时间戳只是记录，不能描述为第二道过期校验。读取也不刷新 TTL。

### 4.2 三重校验：会话版本 + DB 状态 + 凭据摘要

`getToken` 反序列化后依次校验，任一不符返回 null（客户端表现为 401）：

1. userId、role、credentialStamp 非 null，且 `sessionVersion == currentVersion(userId)`；代码未逐项校验 authId、username、email 或载荷内 token——全量撤销（`REVOKE` 已 `INCR` 版本）后，旧令牌立即失效；
2. DB 里 `is_deleted=0` 且 `status=1` 且 `role` 匹配——注销 / 停用 / 改角色立即生效；
3. `CredentialStamp.of(user.getPassword())` 与载荷一致——**改密后旧令牌立即失效**，防止「密码重置期间旧验证请求产生有效会话」。

这意味着**每次读令牌都有一次 Redis + DB 往返**——没有独立权限缓存的代价。好处是凭据与账号状态的变更零延迟生效。

### 4.3 反向索引：为什么必须是 Set

只有 `auth:token:{token}` 时，「作废某人的所有会话」唯一可行的办法是 `KEYS auth:token:*`（阻塞单线程，生产禁用）或 `SCAN`（O(全库 key 数)）。`auth:user_tokens:{userId}` 把「按用户」这个查询维度变成可直接寻址的索引，代价从 O(全库) 降到 O(该用户的 token 数)。

选 Set 而不是 Hash / JSON 数组：`SADD` 天然幂等去重、`SREM` 是成员级原子命令；JSON 数组则需要读-改-写。

### 4.4 索引 TTL 为什么是 token TTL 的 2 倍

约束的本质是**单向的**：索引必须比它指向的所有 token 活得更久。

| 方向 | 后果 |
|---|---|
| 索引**先**过期，token 还活着 | 批量删除会漏掉实体 key；但 `REVOKE` 仍递增会话版本，旧 token 在 `getToken` 校验时失效。索引用于实体清理，版本围栏负责撤销兜底 |
| 索引**晚**过期，token 已过期 | 索引里留下指向已消失 key 的僵尸成员；`DEL` 不存在的 key 返回 0，无副作用。只是少量空间浪费 |

2 倍余量保证往「晚过期」的方向偏，往这个方向偏不产生错误。

### 4.5 三段 Lua：把多命令收成原子

| 脚本 | KEYS | 行为 |
|---|---|---|
| `SAVE` | token 主体 / 索引 / 版本 key | 先校验 `ARGV[4]`（会话版本）与当前一致，再 `SET` 主体 + `SADD` 索引 + `EXPIRE` 索引（TTL×2）；版本不符返回 0 → `saveToken` 抛 401「登录状态已变化」 |
| `REVOKE` | 索引 / 版本 key（`ARGV[1]` 是 token 前缀） | `INCR` 版本（围栏先立起来）→ `SMEMBERS` 遍历 `DEL` 全部令牌 → `DEL` 索引 |
| `REMOVE` | token 主体 / 索引 | `DEL` 主体 + `SREM` 索引，一步完成 |

`saveToken` 与撤销并发时，版本校验保证：正在撤销的用户不会又签出一个「逃过围栏」的新令牌。`deleteAllTokensByUserId` 在事务活跃时挂 `afterCommit`——业务回滚后不应误撤销。

### 4.6 拦截器没有做滑动续期

`AuthTokenInterceptor` 命中校验后只写 `UserTokenContext`，**没有任何 `EXPIRE` 动作**。7 天是绝对有效期，活跃用户也不会被续期。另外两个细节：

- 拦截器**始终 `return true`**：认证失败不在这里拒绝，只是不写上下文；真正的拒绝发生在后续权限切面。「未登录」与「登录了但没权限」是两条不同的代码路径；
- `TokenServiceImpl` 三段 Lua 内联声明在类里（不在 `RedisScripts` 集中清单中），做脚本统计时容易漏计。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 自研 token + Redis（客户端携带） | Spring Session + Redis（`JSESSIONID`） | 项目是前后端分离 + 多角色，需要「指定 token 登出」和「按用户批量踢下线」；自研 key 结构更直观，也便于演示反向索引 |
| 值存 JSON String 而非 Hash | `HSET auth:token:{token} userId ...` | 会话是「整体读写」，没有字段级更新需求；一次 `GET` 拿到全部字段。Hash 的字段级优势（单字段 TTL、字段计数）用不上 |
| 索引用 Set | `userId → [token...]` 的 JSON 数组 | `SADD`/`SREM` 原子且天然去重；数组需要读-改-写，并发下必须再加锁 |
| 索引 TTL = token TTL × 2 | 索引不设 TTL | 不设 TTL 会给每个登录过的用户永久留一个 key；2 倍余量已保证方向正确（4.4） |
| `auth:token:` 用明文 token 作 key | 存 `sha256(token)` | 明文便于 `redis-cli GET` 排查与教学演示。代价是 Redis 数据泄露等于会话可直接冒用——生产可换成哈希后作 key，索引同步改造 |
| 会话版本 + DB 凭据双重校验 | 只信 Redis 里的载荷 | 改密 / 停用 / 撤销要零延迟生效；代价是每次校验多一次 DB 往返 |
| 滑动续期不实现 | 每次访问刷新 TTL | 固定 7 天使会话生命周期可预测、可审计；续期需要同步刷新载荷里的时间戳，否则载荷记录的到期时间会与 Redis 的实际 TTL 不一致；当前并无载荷时间戳校验 |

---

## 六、边界与已知问题

1. **滑动续期未实现**：7 天绝对过期，活跃用户也会被登出。
2. **`deleteToken` 的读改窗口**：先 `GET` 取 userId、再执行 `REMOVE` Lua，两步之间 token 被撤销时 `SREM` 一个已不存在的索引，无害；但 `GET` 成功后、`REMOVE` 执行前若进程崩溃，本次登出尚未完成，token 与索引都可能仍有效，直到自然过期或再次撤销。
3. **索引成员不随 token 的 TTL 自动清理**：token 自然过期后 Set 里的成员仍在，`SMEMBERS` 拿到的是「历史上出现过的 token」；`REVOKE` / `DEL` 不存在的 key 不抛异常，无需容忍逻辑。
4. **没有「同一用户并发会话数上限」**：反复登录就是不断 `SADD`。反向索引已具备实现「只允许一台设备在线」的全部信息（新增 token 前先踢旧的），当前没做。
5. **每次校验一次 DB 往返**：`getToken` 查 `UserAuth` 表做状态与凭据校验。这是「改密零延迟失效」的代价；若将来成为热点，可以引入带 TTL 的短缓存，但要重新评估失效时效。

---

## 七、如何验证

```bash
# 1. 登录后观察 token 主体与 TTL
redis-cli GET auth:token:<token>          # 期望：UserToken 的 JSON（含 sessionVersion/credentialStamp）
redis-cli TTL auth:token:<token>          # 期望：接近 604800

# 2. 反向索引与版本 key
redis-cli SMEMBERS auth:user_tokens:<userId>
redis-cli TTL auth:user_tokens:<userId>   # 期望：约为 token TTL 的两倍（≈1209600）
redis-cli TTL auth:session-version:<userId>   # 期望：撤销前可为 -2（不存在），首次全量撤销后为 -1

# 3. 登出后：主体消失、索引成员被移除（REMOVE Lua 一步完成）
redis-cli EXISTS auth:token:<token>                     # 期望 0
redis-cli SISMEMBER auth:user_tokens:<userId> <token>  # 期望 0

# 4. 踢下线：同一账号多设备登录后调用重置密码接口
redis-cli SMEMBERS auth:user_tokens:<userId>         # 期望：(empty array)
redis-cli GET auth:session-version:<userId>          # 版本号已递增，旧 token 全部失效

# 5. 验证「无滑动续期」：连续访问业务接口，TTL 只减不增
redis-cli TTL auth:token:<token>   # 反复执行，数值单调下降
```

---

## 八、延伸阅读

- [`02-email-code-cooldown.md`](02-email-code-cooldown.md) —— 同一 `auth:` 前缀下的验证码 TTL 与发送冷却设计
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 注册/用户名查重链路上的布隆过滤器
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 全项目 Lua 脚本总表（含本篇三段内联脚本）
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 另一处「校验 + 动作」的原子合并：令牌锁释放
- [`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md) —— 完整鉴权链与异常到 HTTP 状态的映射
- Redis 官方文档：[SET with options](https://redis.io/commands/set/)、[EXPIRE](https://redis.io/commands/expire/)、[SADD](https://redis.io/commands/sadd/)
