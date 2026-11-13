# 验证码与发送冷却：多组 key 的 TTL 语义 + 原子消费

> **Redis 考点**：用多组 key 的 TTL 表达「验证码有效期」与「发送冷却」两种时间语义；校验、失败计数、事务预留全部 Lua 原子化。
> **来源**：`docs/01-redis-application-points.md` §2.1（登录态与验证码）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/EmailCodeService.java`

---

## 一、业务场景

注册 / 登录 / 重置密码三个场景（`EmailScene` 枚举）共用邮箱验证码。要满足的约束：

1. **验证码 5 分钟有效**，过期作废；
2. **发送冷却 60 秒**——同一邮箱 1 分钟内不能重复发送（防轰炸，也节省 SMTP 配额）；
3. **同一验证码最多成功消费一次**——并发提交两个相同验证码，只能有一个通过；
4. **进入 VERIFY Lua 的 6 位数字错误码累计 5 次后验证码作废**；格式不合法的输入在 Java 层返回 false，不计数；
5. **发送失败要能重试**——SMTP 挂了不能把用户锁在冷却里；
6. **注册 / 重置密码在事务中预留验证码**——业务回滚后验证码应可再次使用，提交成功后才真正消费。

前两条是「同一邮箱上的两种独立时间语义」，用两个 key 分别表达；后四条都是「多命令的原子性」问题，用四段内联 Lua 解决。

---

## 二、Redis 结构选型

以 `auth:email_code:{scene}:{email}`（`RedisKeyConstants.emailCodeKey`）为主 key，派生出五组：

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `auth:email_code:{scene}:{email}` | String | 300s（`auth.email-code-ttl-seconds`） | 验证码本体 |
| `auth:email_code:cd:{scene}:{email}` | String | 60s（`auth.email-code-cooldown-seconds`） | 冷却标记，**值为 issueId（UUID）** |
| `auth:email_code:{...}:attempts` | String | 与验证码同寿（校验失败时续到验证码剩余寿命） | 错误计数 |
| `auth:email_code:{...}:owner` | String | 与验证码同寿 | 本轮发送归属（issueId），发送失败按它清理 |
| `auth:email_code:{...}:claim` | String | 与验证码同寿 | 事务预留标记（claimId） |

「有效期」与「冷却」**分两个 key** 的理由：两者时长不同（300s vs 60s）、生命周期不同步（验证码被消费后冷却还在）、失效动作不同（冷却到期自动解除，验证码被 DEL）。

---

## 三、代码落点

| 方法 | 职责 |
|---|---|
| `EmailCodeService.sendCode` | SecureRandom 生成 6 位验证码 → ISSUE Lua 判定冷却并保存 Redis 状态 → Java 调用 SMTP 发邮件，失败走 CLEANUP；Lua 不发送邮件 |
| `EmailCodeService.verifyAndConsume` | `VERIFY` Lua 校验 + 计数 + 消费/预留；事务场景挂 `afterCompletion` 走 `COMPLETE` |
| `EmailCodeService.isInCooldown` | 只读检查冷却 key 是否存在 |
| `EmailCodeService.sendEmail` | 发送邮件；异常时按 issueId 归属清理（`CLEANUP`）后抛 500 |
| 四段 Lua `ISSUE` / `VERIFY` / `CLEANUP` / `COMPLETE` | 内联声明在类里，不在 `RedisScripts` 集中清单中 |

邮箱在所有入口统一 `trim().toLowerCase(Locale.ROOT)` 规范化，避免 `A@b.com` 与 `a@b.com` 被当成两个身份。

---

## 四、实现拆解

### 4.1 `ISSUE`：冷却判定与三组 key 的一次性写入

```
KEYS = [code, cooldown, code:attempts, code:owner, code:claim]
若 cooldown 或 claim 已存在 → return 0（冷却中 / 有事务预留未决）
SET code = 验证码, EX 300
SET cooldown = issueId, EX 60
SET code:owner = issueId, EX 300
DEL code:attempts            ← 新一轮验证码重置计数
```

`claim` 也参与冷却判定：事务预留期间（见 4.3）不允许重复发送。

### 4.2 `VERIFY`：校验、计数与消费的原子合并

```
KEYS = [code, code:attempts, code:claim, code:owner]
不存在 code → 0（未发送或已过期）
存在 claim → 0（已被并发请求预留/消费）
attempts >= 5 → 0
验证码不匹配 → INCR attempts（PEXPIRE 对齐验证码剩余寿命）；
               达到 5 次时直接 DEL 验证码（作废）→ 0
验证码匹配：
  事务模式（'reserve'）→ SET claim = claimId（预留，不消费）
  非事务模式（'consume'）→ DEL code, attempts, owner（一次性消费）
  → 1
```

关键点：**「校验 + 计数 + 消费」在同一次 Lua 内完成**。并发提交两个相同验证码时，Redis 单线程保证只有一个走到 `DEL` 分支并返回 1，另一个拿到 0。attempts 的 TTL 被对齐到验证码剩余寿命——验证码过期后计数自然消失，不会影响下一轮。

### 4.3 事务预留：claim / reserve 两阶段

`verifyAndConsume` 检测当前处于数据库事务时，`VERIFY` 走 `reserve` 分支（写 claim 标记、不删验证码），并注册 `TransactionSynchronization.afterCompletion`：

- **提交**：`COMPLETE`（`commit` 分支）→ 校验 claim 归属 → `DEL code, attempts, owner` + `DEL claim`——验证码此刻才真正消费；
- **回滚**：`COMPLETE`（`rollback` 分支）→ 只 `DEL claim`——验证码回到可用状态，用户改完参数可以再用同一个码提交。

`COMPLETE` 校验 `claim` 的值等于本次 claimId 才动手，防止误删其他请求的预留。回调执行失败只 `log.warn`，预留 key 由自身 TTL（对齐验证码寿命）兜底过期。

### 4.4 `CLEANUP`：发送失败按归属清理

SMTP 发送异常时，`CLEANUP` 校验 `owner` / `cooldown` 的值等于本次 issueId 才删除——**只清理本次发送留下的状态**。若清理前用户已通过别的路径触发了新一轮发送（owner 已换人），旧的失败清理不会误删新状态。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 双 key 分别表达有效期与冷却 | 单 key 存 JSON（含两时间戳） | 两个 TTL 独立自然过期，无需后台对账；单 key 则要每次读取后手工判断时间，且无法利用 TTL 的自动清理 |
| 冷却 key 的值存 issueId | 存 `"1"` 标记 | 值承载归属信息，`CLEANUP` 能精确清理本轮状态而不误删并发的新一轮 |
| `SecureRandom` 生成验证码 | `java.util.Random` | 验证码是安全凭证，`Random` 的种子可预测 |
| 四段 Lua 就地声明在类里 | 集中到 `RedisScripts` | 脚本与 key 组强绑定（五组派生 key 只在本类使用），就近声明可读性更好；代价是做全库脚本统计时会漏计（见 [10 篇](10-lua-scripts-overview.md) 的内联清单） |
| 5 次错误上限作废验证码 | 无限次尝试 + 验证码 TTL 自然过期 | 6 位数字空间 10^6，300 秒内无限尝试必然被穷举；5 次让穷举不可行 |
| 事务先预留、提交后消费 | 校验成功立即消费 | 回滚后用户不用重新收一次邮件；代价是多一段 `COMPLETE` 脚本与 claim key 的复杂度 |

---

## 六、边界与已知问题

1. **冷却粒度是「邮箱 + 场景」**：同一邮箱可以同时给 REGISTER 和 LOGIN 各发一次（不同 key）。若要按邮箱总限流，需要再叠加一层。
2. **`CLEANUP` / `COMPLETE` 失败靠 TTL 兜底**：预留或 owner key 的寿命对齐验证码（300 秒），回调执行失败时最多让该邮箱 5 分钟内不能重发，不会永久锁死。
3. **验证码 6 位数字**：在 5 次上限 + 300 秒窗口下安全性足够；若提升安全等级可加位数或换字母数字混合，属业务选择。
4. **`isInCooldown` 是只读旁路**：真正的冷却是 `ISSUE` Lua 里的原子判定，这个方法只用于前端提示，不参与判定（以 Lua 为准）。

---

## 七、如何验证

```bash
# 1. 发送后观察五组 key（scene 以 REGISTER 为例，key 中转换为小写 register）
redis-cli GET auth:email_code:register:<email>                 # 期望：6 位数字
redis-cli TTL auth:email_code:register:<email>                 # 期望：≤ 300
redis-cli GET auth:email_code:cd:REGISTER:<email>        # 期望：issueId（UUID），不是 "1"
redis-cli TTL auth:email_code:cd:REGISTER:<email>        # 期望：≤ 60
redis-cli GET auth:email_code:register:<email>:owner           # 期望：与冷却 key 相同的 issueId

# 2. 冷却期内重复发送 → 接口返回 429
# 3. 连续输错 5 次
redis-cli GET auth:email_code:register:<email>:attempts   # 计数递增；第 5 次后验证码被 DEL
redis-cli EXISTS auth:email_code:register:<email>         # 期望 0（作废）

# 4. 并发提交两个相同验证码（非事务场景）：一个成功，另一个失败
# 5. 注册故意触发回滚（如重名）：验证码仍可用（claim 已被 DEL，code 还在）
redis-cli EXISTS auth:email_code:register:<email>:claim   # 期望 0
redis-cli EXISTS auth:email_code:register:<email>         # 期望 1
```

---

## 八、延伸阅读

- [`01-shared-session-token.md`](01-shared-session-token.md) —— 同一认证域的三段 Lua 与会话版本围栏
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 全项目 Lua 脚本总表（含本篇四段内联脚本）
- [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) —— 认证入口的来源地址限流（120 次/分钟）
- [`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md) —— 验证码一次性消费在安全测试中的覆盖
- Redis 官方文档：[SET with options](https://redis.io/commands/set/)、[EVAL script](https://redis.io/commands/eval/)
