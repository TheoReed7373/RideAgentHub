# 第 23 章 三条完整业务旅程：把前 22 章串成一天

> 状态：**已通过复审（P7-1，2026-11-20），并于 2026-11-22 独立复核**。已按 P1-R1～R5 逐项修订，本次复核补正 `ORDER_NOT_FOUND` 这个 409 来源。逐章证据与检查记录见 [_meta/review-2026-11-22.md](../_meta/review-2026-11-22.md)。本章基于静态源码追踪与测试源码阅读，**实际运行：无**。

## 学习目标

读完本章并做完三组练习，你应该能：

1. **独立走通三条业务旅程**：对"登录后访问受保护入口""一句话经 Agent 完成下单（含司机接单）""一次客服咨询（含可选工单）"中的每一步，说出谁调用、输入来自哪里、读写什么状态、失败会怎样、下一跳是谁。
2. **在跨章衔接处不迷路**：说清"HTTP 直接下单"与"Agent 确认下单"共享哪一段代码、从哪一步开始分叉；说清一次 SSE 请求里"单轮执行锁、会话状态、一次性确认许可、订单状态"各自保护什么。
3. **从失败现象反查拦截位置**：拿到 401 / 403 / 同步 409 / 工具回执字符串 / SSE error 事件这几类现象，能指出它分别在哪一层、哪个类、哪个方法被拒绝，区分"同步 HTTP 码、工具回执、SSE 事件、客户端实收"四种可见性，并说出失败那一刻各存储里留下了什么。
4. **正确归类"提示词"的地位**：能举出旅程二、旅程三里"提示词只约束流程、代码守卫才拦人"的具体例子，不把模型的话当鉴权。

## 前置知识与阅读时间

**必读（硬前置，均已通过复审）**：

- [第 5 章 登录与会话](../01-auth/01-login-and-session.md)（A1）——验证码五 key、令牌签发与 `getToken` 双层校验；
- [第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)（O1）——状态机、创建与接单链路；
- [第 10 章 超时与恢复](../02-orders/04-timeouts-and-recovery.md)（O4）——无人接单/未到达/未支付三条超时迁移；
- [第 12 章 工单生命周期与权限](../03-support-and-cache/01-ticket-lifecycle.md)（S1）——工单状态机、归属四层检查；
- [第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)（C1）——单轮执行锁、五种事件、订阅边界；
- [第 18 章 订单确认、恢复与消费](../04-chat-and-agent/03-order-confirmation.md)（C3）——`quoteId=confirmationId`、`CONFIRM`/`CONSUME`、一次性许可。

**推荐先读**：[第 8 章 抢单与并发](../02-orders/02-grab-and-concurrency.md)（O2，旅程二经过接单竞态）、[第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)（O3，旅程二包含司机上线心跳）。

**选读（可跳过，正文给了最小概念）**：[第 19 章 三层记忆与工具结果反查](../04-chat-and-agent/04-memory-and-tool-results.md)（C4）、[第 20 章 外部工具与提示词边界](../04-chat-and-agent/05-external-tools-and-prompts.md)（C5）、[第 21 章 RAG 检索与答案溯源](../05-rag/01-retrieval-and-grounding.md)（R1）、[第 22 章 RAG 索引同步与检索预算](../05-rag/02-index-sync-and-budget.md)（R2）。

**[第 17 章 分类路由与工具循环](../04-chat-and-agent/02-routing-and-tool-loop.md)（C2）是可替代前置，不是无条件跳过**：课程依赖表（[`_meta/curriculum.md` §五](../_meta/curriculum.md#五依赖清单唯一口径)）中，本章的硬前置 [第 18 章 订单确认、恢复与消费](../04-chat-and-agent/03-order-confirmation.md)（C3）直接依赖 C2；因此 C2 对本章是**通过 C3 传递而来的硬前置**。下列两种读法都成立，任选其一：

- 完整读：先读 C2，再读 C3，然后读本章；
- 替代读：已有分类与工具循环能力者，逐条核对 C2 的学习目标并完成 C2 自测，正确率至少 5/6 且身份来源、工具回执、循环终止相关题全部正确，视为已具备 C2，再读 C3 与本章。

未满足替代条件却直接读本章，会在"模型为什么能调工具、工具回执为什么不是授权"两处缺前置。正文首次用到这些概念时会给一句就地解释，但不替代 C2 的完整推导。

跳过其余选读章时需要记住的最小事实（每条正文首次用到时会再解释一句）：

- **分类**：用户消息先由 `ChatServiceImpl#resolveClassification` 判成 `ORDER/DAILY/SUPPORT/OTHER/DANGER` 五选一，再路由给对应 Agent；没有独立分类器类。
- **工具循环**：Agent 把工具注册给模型，模型输出"工具调用请求"，服务端 `executeTool` 手写 `switch` 执行，结果拼成工具回执（`ToolResponseMessage`）再喂给下一轮模型。
- **记忆**：对话历史分 Heap/Redis/MySQL 三层读，MySQL 是真值；工具结果另存一份（`ToolResponseMemory`）供 `getCalling` 反查。
- **RAG 检索**：知识库问题走 Elasticsearch 双路检索（向量 + BM25），融合排序取 Top5，**答案永远回 MySQL 权威表 `sys_qa_info` 读**，ES 只负责"找到候选"；问题文本与 ES id 的映射在 `sys_qa_es`。

**阅读时间**：约 90～120 分钟（含三组只读练习）。

## 零、本章怎么读：一个乘客、一天、三条旅程

本章不再展开任何单个机制——那是前 22 章的事。本章只做一件事：**把已讲过的机制按真实调用顺序串起来**，每一步给你"角色、入口与数据来源、类与方法、守卫、读写状态、结果、下一跳"，并在关键位置留一个**停顿点**——一处你可以停下来、用源码自己验证的坐标（通常是"某个 key / 某张表此刻应该长什么样"）。

人物与虚构数据（本章全部为虚构，不是任何真实环境的数据）：

| 名称 | 虚构值 | 说明 |
|---|---|---|
| 乘客 林小雨 | userId `910001`，邮箱 `linxy@fictional-mail.example`，角色 `USER` | 已注册 |
| 司机 周师傅 | userId `920002`，角色 `DRIVER` | 在线接单 |
| 登录令牌 | `f3a9...e7b2`（32 位十六进制，形如去连字符 UUID） | Redis `auth:token:{token}` |
| 下单对话 | chatId `7f9c24e8-8b3d-4c1e-9a6f-2d5e8b1c4a70` | `POST /chat/v2/newuuid` 产出 |
| 客服对话 | chatId `a1b2c3d4-e5f6-4789-abcd-ef0123456789` | 同上 |
| 报价编号 | `c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e` | 同时充当 `confirmationId` |
| 订单号 | `5004` | 雪花 ID 的教学短写（真实订单 ID 是雪花长整数，JSON 里序列化为字符串，见 F2 §9.2） |
| 工单号 | `T202611192000042` | `T` + 日期 + 类型码 `2` + 5 位当日序号 |

一天的时间轴：

- **8:00 旅程一**：林小雨在手机上登录，随后查看自己的行程中订单——起点是"一个未登录的浏览器"，目标是"带身份的请求能过拦截链"。
- **8:40 旅程二**：她在 App 对话框里说"从云栖小镇到萧山机场，叫快车不加急"——起点是一句自然语言，目标是订单创建、司机接单、行程结束。
- **14:00 旅程三**：行程结束后她对费用有疑问，问客服 Agent"乘客取消订单要收违约金吗"——起点是一句咨询，目标是知识库答案；没解决再转工单。

三条旅程彼此独立成篇，可以分开读；章末的复习检查点把它们合起来对照。

---

## 一、旅程一：登录并访问一个受保护入口（上午 8:00）

### 1.1 起点与目标

- **起点**：一台没有保存任何令牌的手机。林小雨记得注册过，密码登录嫌麻烦，选择**邮箱验证码登录（OTP，一次性密码）**。
- **目标**：拿到令牌，并用它访问一个受保护入口 `GET /order/my/ongoing`（查询自己的行程中订单），全程能说清"谁在校验、校验什么、失败在哪一步被拒"。

先回答一个前置问题——**当前项目支持哪些登录方式？**（以源码为准，`controller/AuthController.java`）：

| 入口 | 说明 |
|---|---|
| `POST /auth/login/password` | 密码登录；乘客可用邮箱或用户名，后台角色用 `username#role` 形式（`AuthServiceImpl#parseLoginPrincipal`） |
| `POST /auth/login/email-code` | 邮箱验证码登录，**仅允许 USER 角色**（`AuthServiceImpl#loginByEmailCode` 注释与实现） |

本旅程选验证码登录。验证码的**发送**和**验证**是两个角色、两次请求，下面分别走。

### 1.2 正常时间线：从一封验证码邮件到带身份的请求

```text
前提：两个请求都先过 IP 限流（见 t2）；下面所有步骤都在“限流放行”的前提下成立。

t1  林小雨 ── POST /auth/email-code {email, scene:"LOGIN"} ──> 服务端
t2  RequestRateLimitInterceptor（拦 /auth/**）：IP 限流 Lua，放行（否则 429 到此为止，与验证码冷却的 429 不是同一处）
t3  AuthController#sendEmailCode → AuthServiceImpl#sendEmailCode
t4  校验邮箱格式；scene 解析为 EmailScene.LOGIN（LOGIN 场景不要求账号已存在）
t5  EmailCodeService#sendCode：ISSUE Lua 原子签发（冷却、防重发、防在途）
t6  SMTP 发信（外部服务，本轮虚构：邮件里是 6 位数字 438121）
t7  林小雨 ── POST /auth/login/email-code {email, code:"438121"} ──> 服务端（同样先过 IP 限流）
t8  AuthServiceImpl#loginByEmailCode → EmailCodeService#verifyAndConsume（VERIFY Lua，consume 模式）
    → 验证码本体/次数/owner 被删；冷却字段不由 VERIFY 删除
t9  查 MySQL：仅 USER 角色的账号存在；取 tokenService.currentVersion(userId)
t10 AuthServiceImpl#issueLoginResultVO：generateToken → 组装 UserToken → tokenService.saveToken（SAVE Lua）
    → userAuthService.updateLastLoginTime（MySQL）→ 构造 LoginResultVO
t11 AuthController#loginByEmailCode：Result.ok(LoginResultVO{token, expiresInSec=604800, role:"USER", userId:"910001"})
t12 林小雨 ── GET /order/my/ongoing，Header: Authorization: Bearer {token} ──> 服务端
t13 AuthTokenInterceptor#preHandle：提取令牌 → TokenServiceImpl#getToken（Redis + MySQL 校验）→ 写 ThreadLocal
t14 PermissionAspect（@RequirePermission，无角色要求）：上下文非空，放行
t15 RideOrderController#ongoingOrderIds → RideOrderServiceImpl#getUserInTripOrderIds
t16 afterCompletion：UserTokenContext.clear()
t17 返回 Result.ok([...])（此时林小雨没有未完成订单，应为空列表）
```

每一步都标了类与方法。下面把"守卫、读写状态"拆开讲。

### 1.3 逐步拆解：谁在校验、改了什么

**t2 IP 限流（第一道，不是身份）**。`config/WebMvcConfig#addInterceptors` 把 `RequestRateLimitInterceptor` 挂在 `/auth/**` 和 `/order/estimate` 上，`AuthTokenInterceptor` 挂在 `/**`（含 `/auth/**`，二者叠加，排除 `/error` 与 `/favicon.ico`）。IP 限流用滑动窗口 Lua，超限直接以业务码 429 拒绝——它保护的是"发码接口被刷"，与登录身份无关（A2 §5.1 讲过这套限流的故障策略）。**本章旅程一的所有 t 步骤都以"IP 限流放行"为前提**；否则 429 发生在 `EmailCodeService` 之前，与下面验证码冷却的 429 是两处不同代码。

**t5 发码的守卫与写入（OTP 的"发送"边界）**。`service/base/EmailCodeService#sendCode` 执行 `ISSUE` 脚本（源码节选）：

```java
// src/main/java/com/fancy/rideagenthub/service/base/EmailCodeService.java（节选）
if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[5]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])      -- 验证码本体
redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[3])      -- 冷却标记
...
```

- KEYS[2] 是冷却 key `auth:email_code:cd:login:{email}`（TTL 60 秒，`AuthProperties#emailCodeCooldownSeconds` 默认值）；脚本返回 0 时 `sendCode` 抛 `BusinessException(429, "验证码发送过于频繁，请稍后再试")`。**冷却从成功发码那一刻起算，不因码被消费或验证码过期而重置**。
- 验证码本体在 `auth:email_code:login:{email}`，TTL 300 秒（`emailCodeTtlSeconds` 默认值）。
- **角色边界**：`AuthServiceImpl#sendEmailCode` 还有一个场景级守卫——`RESET_PASSWORD` 场景必须先确认账号存在（404 拒绝向未注册邮箱发重置码）；`LOGIN` 场景没有这层。
- **SMTP 失败**（外部依赖，带前提的虚构推演）：假设 `ISSUE` 已返回 1（此时冷却 key `auth:email_code:cd:login:...` 与 owner key 都已写入，值均为 issueId），随后 SMTP 抛异常；`sendEmail` 的 catch 执行 `CLEANUP` 脚本，它**先按 `GET owner == issueId` 才删验证码本体与 owner，再按 `GET 冷却 == issueId` 才删冷却**，最后抛 `BusinessException(500, "邮件发送失败，请稍后重试")`。按脚本文本推演：若两个 key 的当前值都仍等于本次 issueId，则本体/owner/冷却都被删，允许立即重试；若期间被其他发码覆盖（值不再是本次 issueId），对应 `GET != ARGV` 比较不成立、那组 key 不动；若 `redisTemplate.execute(CLEANUP, ...)` 本身抛 Redis 异常，则 Java 不会执行后面的 500 业务异常抛出，Redis 端清理结果仍需按失败点判断。这些都是按源码与前提推演，真实 Redis 执行结果未实测（A1 §2.3）。

> **停顿点 1**：读 `EmailCodeService#sendCode`，确认"验证码冷却的 429 判定发生在 `ISSUE` 脚本返回 0 之后、`sendEmail` 调用之前"；它和 t2 的 IP 限流 429 是两个不同拦截点。冷却 key 在 `ISSUE` 里同步写入，所以"上一封还在发（SMTP 阻塞中）"时第二次请求会被它拦下——**前提是第一次 `ISSUE` 的返回值确实为 1 且冷却 key 仍在有效期内**。

**t8 验证的守卫与写入（OTP 的"验证"边界）**。`verifyAndConsume` 的 `VERIFY` 脚本先读本体、检查 claim、读 attempt 次数（≥5 直接返回 0）；码值比对失败时 `INCR` 计数并把计数 key 的 TTL 对齐本体剩余寿命，**第 5 次错误只 `DEL` 验证码本体**——attempts、owner 及冷却 key 都不在这次删除列表里；码值正确时按模式分叉：`reserve` 写入 claim key（PX 对齐本体剩余寿命），`consume` 删除本体、attempts、owner 三个 key。冷却 key 从头到尾不由 VERIFY 处理：它从发码起算，成功或失败都不能提前解除，重发条件要读 `ISSUE` 对冷却/claim 的当前存在性检查——所以**第 5 次删本体后能否立即重发取决于冷却 key 是否还在，不是"再等 60 秒"**（剩余冷却可能已不足 60 秒）。`loginByEmailCode` **没有 `@Transactional`**，走 consume 模式（两种模式的差别在 A1 §3.3，注册与重置密码走事务内预留 `reserve` + `COMPLETE`）。

**t10 令牌签发与存储**。`AuthServiceImpl#issueLoginResultVO` 的真实顺序是：生成令牌 → 组装 `UserToken` → `tokenService.saveToken`（Redis SAVE）→ `userAuthService.updateLastLoginTime`（MySQL）→ 构造 `LoginResultVO` → 由控制器包成 `Result.ok(...)`；`loginByEmailCode` 没有事务注解，Redis 写与 MySQL 写不共享事务。

- 令牌本体 `tokenService.generateToken()` = 去连字符 UUID（32 位十六进制），**不是 JWT**，服务端不签名，真值在 Redis。
- `TokenServiceImpl#saveToken` 的 `SAVE` Lua **先 `GET` 版本 key 并与令牌内 `sessionVersion` 比较**，不等直接返回 0（Java 抛 `UnauthorizedException("登录状态已变化，请重新登录")`）；相等才写两类 key（源码在 `service/impl/TokenServiceImpl.java`）：

```text
GET auth:session-version:{userId} == 令牌内 sessionVersion ？否则返回 0
auth:token:{token}        SET（JSON 的 UserToken，EX 604800 秒 = 7 天）
auth:user_tokens:{userId} SADD token（EXPIRE 2×TTL = 1209600 秒）
```

**版本 key 自身不由 SAVE 写入**，只被读取；写入索引时同时刷新索引 key 的过期时间。

- 令牌里同时存了 `credentialStamp`（密码 BCrypt 哈希的指纹）与 `sessionVersion`——这是后面 401 失败分支的伏笔。

> **停顿点 2**：签发成功后，Redis 里应能查到 `auth:token:f3a9...e7b2`（7 天 TTL）和 `auth:user_tokens:910001`（14 天 TTL，集合含该 token）；版本 key 的内容没有因为这次签发而改变。两个 key 的差别在 A1 §1.3。

**登录时间持久化的边界**：`updateLastLoginTime` 在 SAVE 之后执行，且这个方法不在事务里。若 MySQL 更新调用抛异常而 Redis SAVE 已成功，**令牌已经写入 Redis，可能仍然可用**；但 `LoginResultVO` 尚未构造、控制器也不会返回它——此时"未返回令牌"不等于"未写入令牌"。反过来，如果 SAVE 的版本比较返回 0（例如 `currentVersion` 在读取后、SAVE 执行前被撤销递增），Java 抛 401 业务异常、`loginByEmailCode` 提前结束，既不会写令牌也不会更新登录时间，但**验证码已被 t8 消费**。`updateLastLoginTime` 未检查更新行数，Mapper 返回 0 且未抛异常时仍继续构造 VO。

**t13 每个请求的身份复核**。`interceptor/AuthTokenInterceptor#preHandle` 按顺序做：

1. `extractToken`：`Authorization: Bearer xxx` 优先，其次 `X-Auth-Token` 头；没有令牌或空白 → 什么也不设置 → `return true`；
2. `TokenServiceImpl#getToken(token)`：读 Redis JSON（读不到 → 返回 null）→ 解析（格式异常 → 记录日志并返回 null）→ **查当前 sessionVersion 是否仍等于令牌里的**（不等 → 返回 null）→ **回 MySQL 查 `sys_user`**（`is_deleted=0` 且 `status=1`，查不到 → 返回 null）→ 比对 `role` 与 `credentialStamp`（新密码指纹不符 → 返回 null）。这里要分清三种"读不到身份"：**① 令牌 key 不存在；② key 存在但版本不符；③ 版本仍旧、但 MySQL 里已是新密码指纹**。它们的第一个失败点不同，但对外同样是"上下文里没有身份"；
3. `userToken != null` 时再比 `expiresAtEpochMs > now`，过期则顺手 `deleteToken`；
4. 通过才 `UserTokenContext.set(userToken)` 写入 ThreadLocal；正常的"认不出身份"路径都 `return true` 放行。

**普通鉴权不通过与依赖抛异常是两回事**：`preHandle` 只把"读不到/校验不过"当作 null 处理并放行；如果 `redisTemplate` 或 mapper 调用本身抛出未捕获异常（连接故障、查询异常等），它不会变成 null，而是从 `preHandle` 冒泡出去——这与"没有身份、放行到切面被 401 拒绝"不是同一条路径。拦截器正常路径**不是 401 的来源**；拒绝发生在下一层。

**t14 拒绝层**。`GET /order/my/ongoing` 标注 `@RequirePermission`（不指定角色），`aspect/PermissionAspect#checkPermission` 在方法执行前检查：`UserTokenContext.getNullable() == null` → 抛 `UnauthorizedException("请先登录")`；注解带角色时再比对角色，不符抛 `ForbiddenException`。异常经 `exception/GlobalExceptionHandler` 映射：`UnauthorizedException` → HTTP 401、`ForbiddenException` → 403，响应体是 `Result{code, message}`。

**t15 业务归属**。`getUserInTripOrderIds` 用 `UserTokenContext.getUserIdInLong()` 取 userId，查询条件 `userId = 当前用户 AND is_deleted = 0 AND orderStatus NOT IN (PAID, CANCELLED)`——注意它不是"仅 IN_TRIP"，而是"所有非终态"。如果 `userId == null` 抛 `BusinessException(403)`（防的是上下文意外缺失，不是主要的越权防线）。

**t16 上下文清理**。`afterCompletion` 里 `UserTokenContext.clear()`——ThreadLocal 不清理会污染复用的容器线程（A2 §1.4 讲过它与后台线程的关系：**Agent 的 `chatExecutor` 线程里 ThreadLocal 是空的**，旅程二会用到这一点）。

### 1.4 两个失败分支：401 与 403 分别发生在哪

**分支 A：没带令牌（401，拒绝于 AOP 层）**。林小雨退出重进，本地 token 丢了，直接调 `GET /order/my/ongoing`：

1. `AuthTokenInterceptor#preHandle`：`extractToken` 返回 null → 什么也不写 → `return true` **放行**；
2. `PermissionAspect`：`getNullable()` 为 null → `UnauthorizedException("请先登录")`；
3. `GlobalExceptionHandler` → HTTP 401，`Result{code:401, message:"请先登录"}`。

失败在**方法进入前的切面**，控制器方法体根本没执行。这条同步 HTTP 401 的结论限定于：**请求到达这个注解端点、身份确实为空、且异常映射链正常执行**；SSE 后台任务内的异常不走这条同步路径。

**分支 B：令牌曾经有效，但密码改过了（401，拒绝于 getToken 内部）**。林小雨中午在网页端重置了密码（`resetPasswordByEmailCode` 带 `@Transactional`，最后调用 `deleteAllTokensByUserId`）。按源码有三个时点要分开：

1. **撤销执行前**（改密事务尚未提交，`REVOKE` 挂在 `afterCommit`）：Redis 的 token key 与版本 key 都还是旧值，旧令牌此时仍能通过 t13 的校验——"改密请求已发出"不等于"令牌已失效"；
2. **撤销成功提交后**：`REVOKE` Lua `INCR auth:session-version:{userId}` 并遍历 `auth:user_tokens` 删光旧 token。此时第一个失败点通常是 `getToken` 的 `sessionVersion != currentVersion`（版本不等），且 token key 也已被删；
3. **版本尚未来得及递增**（例如改密事务已提交但 `afterCommit` 回调的 Redis 调用失败）：若 token key 仍在、版本仍旧，则 `getToken` 继续到 MySQL 查 `sys_user`，第一个失败点是**新密码的 `credentialStamp` 与令牌里的旧指纹不一致**。这里描述的是"回调执行失败"的条件，不是假设脚本运行时会读到"半个 `REVOKE`"的中间状态——Lua 脚本在 Redis 内的执行本身是原子的。

三个时点的共同结局都是 `getToken` 返回 null → PermissionAspect 抛 401；不同点只在**谁先挡住**。A1 §6.2 有"重置 vs 在途登录"双栅栏接力（版本在 SAVE 前比对、凭据在 getToken 里比对）的完整时间线。

**附加分支（403，拒绝于角色层）**：令牌完全有效，但林小雨拿 USER 令牌去调 `POST /order/driver/accept`（该端点 `@RequirePermission({"DRIVER"})`）：`PermissionAspect` 的角色比对失败 → `ForbiddenException("无权访问该资源")` → `GlobalExceptionHandler` → HTTP 403。同样是**同步 HTTP** 结论，限定于该注解端点且异常映射正常执行。

### 1.5 分支推导：改错验证码的第三次与第五次

给一个带推导的分支（练习 A 会让你自己做）：林小雨手滑，连续输错验证码。

- 第 1～4 次错误：`VERIFY` 脚本 `INCR auth:email_code:login:...:attempts`，并用 `PEXPIRE` 把计数 key 的 TTL 对齐验证码剩余寿命；**验证码本体还在**，下一次尝试仍可对；
- 第 5 次错误：`attempts >= 5` → 脚本**只 `DEL` 验证码本体**（`KEYS[1]`）→ 再尝试不会通过；但 attempts、owner 与冷却 key 都还在，重发时机要看 `ISSUE` 对冷却/claim 的当前检查，**不是"再等 60 秒"**；
- 若第 3 次输对了：consume 模式 `DEL` 本体、attempts、owner 三个 key，冷却 key 保留到其从发码起算的 TTL 到期。

**推导要点**：失败不是立刻拒绝一切重试，而是**消耗一个有限次的机会**；"5 次"与"删本体"都在同一段 Lua 里原子完成（`service/base/EmailCodeService.java` 的 VERIFY 脚本第 48～52 行附近）。删除哪些 key、保留哪些 key 要看 `DEL` 的参数列表，不要把"验证码无效"脑补成"所有相关 key 都被清掉"。

### 1.6 练习 A（只读，约 15 分钟）

**虚构输入与前提**：邮箱 `linxy@fictional-mail.example`，场景 `LOGIN`，连发两次发码请求（间隔 10 秒）；**假设两次请求都已通过 `RequestRateLimitInterceptor` 的 IP 限流**（否则第一行就返回限流 429，走不到验证码冷却）。

1. 读 `AuthController#sendEmailCode` → `AuthServiceImpl#sendEmailCode` → `EmailCodeService#sendCode`，回答第二次请求在哪一行被拒、HTTP 状态码与 `Result.code` 是多少，并说明这次 429 与 IP 限流 429 的区别；
2. 读 `ISSUE` 脚本，列出第一次成功后 Redis 里多出的 key 名与各自 TTL（用 `AuthProperties` 默认值换算成秒）；
3. 若第二次请求发生在第一次的 SMTP 邮件还没发出时（`sendEmail` 阻塞中，且第一次 `ISSUE` 返回 1、冷却 key 仍在），`ISSUE` 会怎么判？再推一步：若第一次的验证码已经被**事务内预留**（`reserve` 模式，`claim` key 仍在）但事务还没提交，此时又来一次发码请求呢？（提示：看 `ISSUE` 对 KEYS[2]/KEYS[5] 的检查；属于 A1 §8.1/§8.3 的交错，只推演不实测。）

**容易读错的地方**：把两种 429 混成一处（IP 限流在拦截器，验证码冷却在 `ISSUE`）；把 `PEXPIRE` 对齐的对象想成冷却 key（对齐的是 attempts key，见 VERIFY 脚本）；把 claim key 想成发码时写入（它只在**验证预留**时由 `VERIFY` 写入）；把第 5 次失败后的重发说成"再等 60 秒"（冷却从首次发码起算，剩余时长不等于满 60 秒）。

<details>
<summary>参考答案与推导</summary>

1. 题设已排除 IP 限流。第二次请求在 `sendCode` 执行 `ISSUE` 后：`issued != 1` → `BusinessException(429, "验证码发送过于频繁，请稍后再试")`。`GlobalExceptionHandler#handleBusiness` 用异常自带 code 429 作 HTTP 状态码与 `Result.code`。注意 `Result.code` 与 HTTP 状态在此处相等是因为 `handleBusiness` 直接透传，两者不是恒等（F2 §7 讲过这两套码）。
2. 三类 key：验证码本体 `auth:email_code:login:linxy@fictional-mail.example`（EX 300）；冷却 `auth:email_code:cd:login:...`（EX 60）；owner key `auth:email_code:login:...:owner`（值为 issueId，EX 300）。数值来自 `AuthProperties`：`emailCodeTtlSeconds=300`、`emailCodeCooldownSeconds=60`，覆盖途径是 `auth.*` 配置键（A1 已核验）。
3. SMTP 阻塞时第二次发码：冷却 key 已在第一次 `ISSUE` 里同步写入且未到 TTL → `EXISTS KEYS[2]` 命中 → 返回 0 → 同样 429。预留场景：`VERIFY` 的 reserve 分支写入 claim key → `ISSUE` 的 `EXISTS KEYS[5]` 命中 → 429。**前提**：对应 key 的当前状态如题设；SMTP 失败时 CLEANUP 是否清掉它们要看 `GET owner/冷却 == 本次 issueId` 的比较与 `redisTemplate.execute` 的执行结果，不能无条件断言"已清掉"或"一定立即重试成功"。**推导基于脚本文本，未实测**。
</details>

---

## 二、旅程二：完成一次订车，包含 Agent 下单（上午 8:40）

### 2.1 起点与目标、所选情境

- **起点**：林小雨拿到令牌后打开 App 的对话框，输入："从云栖小镇叫快车到萧山机场，不加急"。
- **目标**：订单创建、周师傅接单、行程推进到结束。

**为什么选这个情境**：它是当前源码里可完整走通的正常路径——即时单（`IS_RESERVATION=0`，不触发预约时间校验）、快车（`VEHICLE_TYPE=1`）、不加急（`IS_EXPEDITED=0`）。预约单会引入 `SCHEDULED_TIME` 校验（`OrderTool#isNewOrderReady` 里 `TimeUtil.parse` 与"必须晚于当前"），本旅程不展开。

### 2.2 两条创建路径先分清：HTTP 与 Agent

项目里有**两个创建订单的入口**，共用同一个服务方法（O1 §10.1 有对照表）：

| | 普通 HTTP 创建 | Agent 确认创建 |
|---|---|---|
| 入口 | `POST /order`（`RideOrderController#create`） | 对话内模型调用 `createOrder` 工具（`OrderTool#createOrder`） |
| 身份来源 | `UserTokenContext.getUserIdInString()`（控制器写入 DTO） | `ToolContext` 里的 `USER_ID`（`ChatController` → `ChatRequestRunner.run(userId,...)` → Agent `invoke` 注入） |
| 谁能下单 | 登录用户直接下 | 必须先过**一次性确认许可**（`consume` Lua） |
| 价格 | 服务端重算（不信客户端） | 同样服务端重算（DTO 从 Redis 槽位组装） |

关键差异只有一个：**Agent 路径在 `createOrder` 工具里多一道 `ReadyForCreate` 检查 + `OrderConfirmation#consume` 门禁**（C3 §6.3），提示词只是引导模型"确认后才能建单"，拦人的是 Lua。HTTP 路径**直接调用 `RideOrderServiceImpl#createOrder`，源码里没有 `consume`、没有 `quoteId`、没有 `break` 检查**；"用户按下按钮即确认"只是对接口协议的解释，仓库源码不能证明页面一定存在某个按钮或某种交互——这里只按"客户端直接提交创建请求"陈述事实。这是 O1 §10 与 C3 §8.4 的交叉点。

### 2.3 正常时间线：从一句话到订单创建

分两段：**A 段**是用户消息进入系统、分类、工具循环、报价、暂停等待确认；**B 段**是用户点"确认"后恢复、消费许可、创建订单。

```text
A 段（第一轮 SSE，POST /chat/v2/c/{chatId}）
a1  App 先调 POST /chat/v2/newuuid 拿 chatId（7f9c...4a70）
a2  POST /chat/v2/c/7f9c...4a70 {prompt:"从云栖小镇叫快车到萧山机场，不加急"}
a3  PermissionAspect（@RequirePermission）→ userId=910001
a4  ChatRequestRunner#run：UUID 合法性 → Redisson 锁 chat:execution:{chatId}（tryLockAsync(0,-1)）
a5  ChatManager#assertOwned(allowNew=true)；sink 以 autoConnect(2) 发布——凑不齐两个订阅时后台任务不启动
a6  HTTP 订阅建立 → chatExecutor 线程执行 ChatServiceImpl#chat（AgentStreams.guard 包裹）
a7  isLocked? 否 → canStartChat（prompt 非空、ChatRateLimiter 未超限）→ assertOwned → break 检查（新会话无 break）
a8  memory.get(userId, chatId, 100)（三层记忆，新会话为空）→ resolveClassification("SINGLE_TURN", null, prompt)
a9  分类缓存未命中 → 分类模型（qwen3-max-preview）→ "ORDER" → notify 事件
a10 消息为空历史 → chatManager.initChat（写 sys_chat 行）
a11 switch("ORDER") → OrderAgent#invoke：读历史 → ORDER_AGENT_SYS_PROMPT（含时间、林小雨位置）→ runLoopStep
a12 模型（deepseek-chat）流式返回 → 中途调用工具：saveNewOrderParam（写 chat:info:{chatId} 槽位）
a13 → isNewOrderReady（校验槽位，写 ReadyforRoute）→ getEstRouteAndPrice：
    高德路径规划（外部，未接真实地图；虚构返回 10.000km/600s）→ RidePricing 计价
    → 预览价 30.60 元、倍率 1.00、EST_DISTANCE_KM=10.000/EST_TIME=600
    → Mongo 预览轨迹（虚构 traceId a7f3e2c1-9b84-4d50-8e6a-1c2d3e4f5a6b）
    → quoted()（写 quoteId=c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e/ReadyforConfirm/quoteCreatedAt）
a14 模型收到工具回执后调用 notifyUser → OrderAgent#handleToolCalls 特判：
    orderConfirmation.begin（检查 quoteId+ReadyforConfirm，写 pendingToolCall、break=yes，返回 quoteId）
    → saveNewMessages（持久化到 MySQL+Redis）→ break=yes → AgentEvent.comfirm(orderJson) → tryEmitComplete
a15 SSE 流结束（本段可能出现的真实事件类型：`notify`（分类、提示）、`tool_start`（工具名）、`message`（模型文本）、`confirm`（报价确认卡片，一次）；见 C1 §6.1）

B 段（第二轮 SSE，POST /chat/v2/r/{chatId}）
b1  App 显示 a14 发来的 orderJson 快照（confirmationId=c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e、
    EST_PRICE=30.60 等）——这只是快照，不是合同；用户点"确认"
b2  POST /chat/v2/r/7f9c24e8-8b3d-4c1e-9a6f-2d5e8b1c4a70
    {confirmationId:"c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e", confirmed:true}
b3  ChatRequestRunner#run（resume=true）：同一把单轮执行锁 → assertOwned(allowNew=false)
b4  ChatServiceImpl#resume：prompt 空则置为"确认" → canStartChat → assertOwned
    → orderConfirmation.confirm（CONFIRM Lua：quoteId 匹配 + 严格 >900000ms 的年龄检查（仅 yes）+ 状态迁移）
    → orderAgent.resume（break=resuming 前置检查 → getPersisted 历史 → 补 ToolResponseMessage → 删 break/pendingToolCall/confirmationAccepted → runLoopStep）
b5  模型下一轮：markOrderReadyForCreate（只读 isConfirmed）→ createOrder 工具：
    ReadyForCreate 检查 → 组装 CreateOrderDTO（estPrice=30.60 只是 DTO 携带值）
    → consume（CONSUME Lua，一次性消费；同样严格 >900000ms）
    → RideOrderServiceImpl#createOrder：二次高德（虚构 10.500km/600s）重算，入库 est_price=31.70
    → 锁会话 → 写 OrderId → 工具回执读 Redis 旧预览 30.60/10.000km（与入库值不同）
b6  模型生成结论文本 → saveNewMessages → tryEmitComplete → SSE 结束
```

### 2.4 逐步拆解：分类、报价、暂停、确认、恢复、消费

这一段每一步的"守卫 / 读写状态 / 下一跳"，逐条给坐标。

**a4 单轮执行锁**。`service/base/ChatRequestRunner#run` 对 `chat:execution:{chatId}` 加 Redisson 锁，`tryLockAsync(0, -1)`：等待 0 秒（不排队）、leaseTime=-1（看门狗续期）。拿不到锁立即返回 `Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"))`——这是**流内 error 事件**，不是 HTTP 409。锁在 `doFinally` 里 `unlockAsync`；客户端断开不提前释放（内部订阅保持到终态，C1 §5.2/§7.1）。

**a7/a8 分类前守卫**。顺序固定（`ChatServiceImpl#chat`）：`isLocked`（会话被 `lockChat` 锁死后拒绝新消息，见 b5）→ `canStartChat`（空 prompt → error 事件；`ChatRateLimiter` 超限 → notify + complete）→ `assertOwned`（会话必须属于当前用户）→ `break==yes` 时拒绝新消息"请先确认或拒绝当前订单"。**注意 a7 的 break 检查在 a4 锁之后、分类之前**——这就是"确认等待期间换个话题说"会被挡住的那道门（C1 §8.5 有旧 `classRedis=ORDER` 分支的另一个变体）。

**a8 分类**。新会话的输入是 `("SINGLE_TURN", null, prompt)`；有历史时是 `(上轮分类, 最后一条 Assistant 文本, prompt)`。先查 `chat:classify:` 缓存（内容指纹，**不含 userId**，跨用户共享，C2 §3.1），未命中才调分类模型，规范化成五选一。失败返回 null → "路由失败，请换种方式问问题"事件 + complete，**不建会话、不写分类**（C2 §2.5 的顺序）。

**a11～a13 工具循环与报价**。`OrderAgent` 注册六个工具（`OrderTool`、`POISearchTool`、`ToolRepPointerTool`、`TrafficTool`、`UserPOISearchTool`、`GeoRegeoTool`），`internalToolExecutionEnabled(false)`——模型只发"工具调用请求"，服务端 `executeTool` 手写 switch 执行（C2 §5.2）。本旅程走到的三个工具：

- `saveNewOrderParam`：把模型从对话里抽出的槽位写进 Redis Hash `chat:info:{chatId}`（起点/终点经纬度、地址、车型、加急、预约标志），同时**删除旧报价残留**（`ReadyforRoute/ReadyforConfirm/ReadyForCreate/quoteId/confirmedQuote/EST_TIME/EST_DISTANCE_KM`）——改单重来；
- `isNewOrderReady`：逐槽位校验（车型 1/2/3、坐标范围等），齐了写 `ReadyforRoute=true`；
- `getEstRouteAndPrice`：调高德（**外部服务未接入本教材演练**；本节虚构返回里程 10.000km、耗时 600s）→ `RidePricing.calculate` 计价（公式与手算在 O5 §2）→ 预览价 **30.60 元**、倍率 1.00 → Mongo 写预览轨迹（`OrderRouteService#addOrder`，虚构 traceId `a7f3e2c1-9b84-4d50-8e6a-1c2d3e4f5a6b`，`MONGO_TRACE_ID` 存 Hash）→ `orderConfirmation.quoted(chatId)`。工具从 Redis 读回并回执这些预览值，**它们不是最终入库金额**。

`quoted()`（`service/base/OrderConfirmation.java`）写三样：`quoteId`（新 UUID，本例 `c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e`）、`ReadyforConfirm=true`、`quoteCreatedAt=当前毫秒`，并 `expire` 整个 key 24 小时；它同时删 `ReadyForCreate/confirmedQuote`。**这是时间点①：报价已写入，但会话尚未暂停**（C3 §2.2/§2.4 讲了四个改 TTL 的路径）。

**a14 暂停等待确认**包含三个不同时间点：

- **时间点②（begin）**：模型调用 `notifyUser` 时 `handleToolCalls` **特判**——不走 `executeTool`；`OrderConfirmation#begin` 只检查"`quoteId` 与 `ReadyforConfirm` 都存在"（不查年龄、不查金额），写 `pendingToolCall`（工具调用 id）与 `break=yes`，**返回 quoteId 当 confirmationId**（`quoteId=confirmationId`，C3 §1.2 的七个编号之一）。此刻 `break=yes` 已设置，但新增消息尚未保存、确认卡片尚未发出；
- **时间点③（保存）**：`saveNewMessages` 把本轮新增消息写入 MySQL（`sys_messages`）与 Redis/Heap（各层失败边界见 C4）；
- **时间点④（发送尝试）**：`sink.tryEmitNext(AgentEvent.comfirm(orderJson))` 只是**尝试**发出确认卡片，随后 `tryEmitComplete`。`comfirm` 的 orderJson 是 `handleNotifyUser` 从 `chat:info` 读出的订单参数 + confirmationId 的一份展示快照（C3 §2.6），**没有签名或金额绑定**；它和模型此前输出的摘要文本可能不一致。事件是否送达客户端未实测。

> **停顿点 3**：时间点②～④都完成后，Redis `chat:info:7f9c24e8-8b3d-4c1e-9a6f-2d5e8b1c4a70` 应有 `quoteId=c8a17e2d-6b4f-4a5c-8e9d-0f1a2b3c4d5e`、`ReadyforConfirm=true`、`quoteCreatedAt=<毫秒>`、`break=yes`、`pendingToolCall=<id>`、`EST_PRICE=30.60`、`MONGO_TRACE_ID=a7f3e2c1-9b84-4d50-8e6a-1c2d3e4f5a6b`，Mongo 有一条预览轨迹，MySQL `sys_messages` 有"到 notifyUser 为止"的消息（实体 `ChatMessage` 映射表 `sys_messages`）。若时间点③失败而②已写，可能留下 `break=yes` 但历史未落库的状态——这正是 C3 §3.4 的"暂停时到底保存了什么"要区分的。

**b4 确认（CONFIRM Lua）**。用户带 `confirmationId + confirmed` 打 `/r`。`OrderConfirmation#confirm` 执行的 Lua 逐行语义（C3 §四）：

1. `quoteId` 不等于传入编号 → 返回 0（Java 抛 `BusinessException(409, "确认已失效或已被使用...")`；它在后台线程的呈现方式见 §2.8 的口径）；
2. `confirmed=yes` 且 **`now - quoteCreatedAt > 900000` 严格大于**（15 分钟；正好 900000ms 不拒绝）→ 返回 0（**报价年龄**检查，只对接受生效；拒绝不受此限）。前提是 `quoteCreatedAt` 字段存在且为数字，否则 `tonumber(nil)` 会让 Lua 运行时错误而非返回 0（C3 §4.5）；
3. `break=resuming` 时：同决定重放返回 1，相反决定返回 0（`resuming` 窗口；同决定重放**不执行任何写命令**）；
4. `break=yes` 时：写 `break=resuming`、`confirmationAccepted`，yes 再写 `confirmedQuote=quoteId、ReadyForCreate=quoteId`；no 则删这两个字段。

**b4 恢复**。`OrderAgent#resume`：先检查 `break == "resuming"`（CONFIRM 已成功迁移状态；否则 error 事件"非法的恢复请求"）→ `messageMemory.getPersisted`（**读数据库**，因为内存/Redis 可能没有，C3 §5.6）→ 给 `pendingToolCall` 补一条 `ToolResponseMessage`（内容是"用户已确认当前报价，可以创建订单。确认"；已有应答则不重复补）→ **删 `break/pendingToolCall/confirmationAccepted`** → 继续 `runLoopStep`。**这一步的清字段解释了练习 B 的一个关键分支：正常恢复完成后 `break` 不再是 `resuming`；若后续又出现 `resuming`，只能来自新的 `begin` + `CONFIRM`，不是"正常 complete 后残留"。**

**b5 消费（CONSUME Lua）与创建**。模型下一轮调用 `createOrder` 工具（`agentbase/tool/OrderTool.java`）：

```java
// OrderTool#createOrder（节选，注释为本章所加）
if (redisGetObj(chatInfoKey, "ReadyForCreate") == null) return "订单非可创建态";  // 第一道：状态
...
orderConfirmation.consume(chatId);          // 第二道：一次性许可（Lua）
String orderId = rideOrderService.createOrder(orderDTO);  // 第三道：服务端重算 + 落库
...
```

**两条失败路径不要接错**：

1. **缺 `ReadyForCreate`**：模型重放 `createOrder` 时最常见的分支——在**进入 `consume` 之前**就 `return "订单非可创建态"`，没有 Lua 调用、没有异常、没有 `Tool execution failed:` 前缀。这个字符串作为正常工具回执进入历史（C3 §6.3 的表）。
2. **到达 `consume` 才失败**：`CONSUME` 要求年龄 ≤900000ms **且** `confirmedQuote == quoteId == ReadyForCreate`，成功则 HDEL 两字段返回 1；返回 0 时 `consume` 抛 `BusinessException(409, "尚未取得有效的用户确认，或本次确认已使用")`，Redis 命令本身抛异常时同样向外传播。**这发生在 `OrderAgent#executeTool` 的 try 内**，会被它 catch 成 `"Tool execution failed: " + e.getMessage()` 文本回执（源码 `OrderAgent#executeTool` 末尾的 catch）。**它不是 `ChatServiceImpl#resume` 里 `confirm` 的异常，不经过 `AgentStreams.guard`；不能套用 resume 的失败路径，也不能保证 HTTP 200 或事件送达**——客户端可见性取决于异常抛出点、SSE 是否已建立及 `tryEmit*`，未实测。

这就是"一次性许可"：哪怕模型被提示词诱导再调一次 createOrder，也过不了 `ReadyForCreate` 或 `consume`（提示词不是鉴权，C3 §8.3）。关于测试边界：`SecurityRepairRegressionTest#modelCannotGrantItselfCreationPermission` 的实际断言只有两项——`markOrderReadyForCreate` 在 `isConfirmed` 为 false（mock）时返回含"尚未"的文本，且 `verifyNoInteractions(redis)`；它**没有**测 `createOrder` 重放、`consume` 或 Lua，不能当作"模型无法自行授权创建订单"的全覆盖证据（测试未运行，仅读源码）。

`RideOrderServiceImpl#createOrder`（旅程二的**核心落库**，O1 §3.2 完整讲过）：

- 身份：`UserTokenContext.isLoggedIn()` 时覆盖 DTO 的 userId——**Agent 后台线程里 ThreadLocal 为空**，所以用 `ToolContext` 注入的 userId（这正是 1.3 t16 说的"后台线程没有上下文"）；
- 价格：**服务端重算**。DTO 里的 `estPrice=30.60` 只随对象传入，会被 `validateAndSupplement` 的副本覆盖；本例虚构二次高德返回 10.500km/600s，按 O5 公式重算并入库 `est_price=31.70`、`priceRadio=1.00`。工具后续从 Redis 读回的仍是预览 30.60/10.000km——**预览回执和 DTO 都携带 30.60，入库金额按第二次规划计算**（见 O5 §3.2/§3.3 与 O5 练习 C）；
- 副作用顺序：高德再规划一次 → Mongo 写正式轨迹（虚构新 traceId `b8e4d3c2-0a95-4e61-9f7b-2d3e4f5a6b70`）→ MySQL `insert`（失败时删 Mongo 轨迹补偿）→ `syncOrderStatus(CREATED)`（当前无外层事务时直接尝试写 `order:status:5004`）→ `orderDelayQueue.schedule(5004, 15 分钟)`（ZSet 待办）→ `orderGeoPool.add`（失效附近订单池）。**`createOrder` 方法本身没有 `@Transactional`，这些副作用不是本地事务的一部分**；
- 回到 `OrderTool#createOrder`：删 Mongo **预览**轨迹 `a7f3e2c1-...`（失败只记日志）→ 读取 Redis 的里程、耗时、金额展示字段 → `chatManager.lockChat` 先由 `ChatInfoService` 写 DB，再执行 Redis 的 HSET+EXPIRE 原子脚本（`locked=true`，60 分钟）→ 写 `OrderId` → 返回工具回执（读 Redis 旧预览值 30.60/10.000km，与入库的 31.70/10.500km 不同）。

`lockChat` 之后，这个会话再收到 `/c` 新消息会被 a7 的 `isLocked` 挡住（"开启新对话继续吧"）——**会话状态与订单状态在这里分道扬镳**。

> **停顿点 4**：创建成功后：假设后续 Redis 写入及轨迹清理均成功，MySQL `sys_ride_order` 一行（`order_status=10 CREATED`、`user_id=910001`、`driver_id=null`、`est_price=31.70`、`est_distance=10.500`、`price_radio=1.00`，均来自服务端二次规划，**不是**确认卡片的 30.60）；Redis `chat:info:...` 有 `OrderId=5004`、`locked=true`（60 分钟 TTL，先写 DB 后执行 Redis 原子脚本）、`quoteId` 还在但 `ReadyForCreate/confirmedQuote` 已被 consume 删掉、`EST_PRICE` 仍为旧预览 30.60；`order:status:5004=10`；`delay:order:timeout` ZSet 有 member=5004。当前创建入口无外层事务，这两项直接尝试写入；Mongo 预览轨迹 `a7f3e2c1-...` 已删、正式轨迹 `b8e4d3c2-...` 在。

### 2.5 司机侧：上线心跳、接单、状态推进

**在线是持续状态，不是一次性开关**：`DriverLocationServiceImpl#reportLocation` → `DriverGeoIndex#goOnline` 的每次上报都会写 GEO 池 `driver:geo:online` 与心跳 Hash `driver:online:beat`（成员级 TTL **60 秒**，共享 key 不设整体 TTL，O3 §1.1/§2.2）。7:50 的一次上报只能维持到约 7:51；要让本例 8:41 仍能发现在线司机并刷新位置，假设司机端**每隔不到 60 秒持续上报**（这是本节的显式前提，不是源码自动保证）。若心跳已过期，`refreshIfExist` 的 Lua 会先比对 beat（过期或 GEO 成员缺失则删两者并返回 0），不会把司机凭空重新上线。

8:41 周师傅的司机端列表刷新，看到新单，点接单：`POST /order/driver/accept {orderId:"5004", currentLat, currentLng}`（`@RequirePermission({"DRIVER"})`）→ `RideOrderServiceImpl#driverAcceptOrder`：

1. `@Transactional(READ_COMMITTED)` + `withOrderLock("5004")`（Redisson `order:lock:5004`，持锁到事务完成回调）；`OrderGrabService#tryGrab` 带 `Propagation.MANDATORY`，必须在已有事务里运行；
2. `tryGrab` 的真实顺序：**先按数据库恢复占位**——`FOR UPDATE` 锁司机账号（DRIVER/status=1/未删除），再 `FOR UPDATE` 查该司机未结束的活跃单（排除 50/60/90），把 `driver:active:920002` 写成 `__IDLE__`（无活跃单）**或该活跃单的真实 id**，缓存 5 分钟；然后检查 `order:status:5004`，缺失时回源 DB 并 `setIfAbsent`；最后执行 `GRAB_ORDER_ATOMIC` Lua（要求 status=`10 CREATED` 且司机占位=`__IDLE__`，成功才 SET 占位为目标单并设 5 分钟 TTL）。**不是"数据库预检直接把占位写成目标订单"，也不是"先 Redis 拦截全部请求"**（O2 §2.3）；
3. Lua 结果：`1` = 预检占位成功，注册 `afterCompletion` 回调（非 `COMMITTED` 则 `rollbackGrab`）；`-2` = DRIVER_BUSY（409"司机已有未结束订单，无法接单"）；`-1` = ORDER_NOT_GRABBABLE（409"订单已被其他司机接走或已取消"）；`null` 会抛 `IllegalStateException`。另有一条不经过 Lua 的失败：`order:status` 缺失且回源 DB 查不到订单 → `ORDER_NOT_FOUND`（409"订单不存在"）；
4. DB 条件更新：`update ... where order_id=5004 and is_deleted=0 and order_status=10 and driver_id is null set driver_id, order_status=20, driver_accept_time`——**影响行数才是最终裁决**；0 行则立即尝试 `rollbackGrab`，并登记提交后 `refreshOrderStatus`，再返回 false。注意第 2 步的 Lua 预检成功**不等于**这步成功；
5. 成功后（同事务内）：先登记 `syncOrderStatus(20)`，再调用 `orderGeoPool.remove(5004)` 登记索引失效；`driverGeoIndex.refreshIfExist(driverId,currentLng,currentLat)` 是**条件性附带动作**——返回值不参与是否接单的判断，失败只记日志（`DriverGeoIndex#update` catch RuntimeException 返回 false）；`orderDelayQueue.schedule(5004, 10 分钟)` 注册提交后 ZADD（同一 ZSet member=5004 的新 score 会覆盖旧的 15 分钟待办）。状态缓存推进、索引失效和 ZADD 都取决于各自的回调执行结果。

之后状态按 `driverArriveStart`（→30 DRIVER_ARRIVED，无 `@Transactional`，`withOrderLock` + 条件更新，失败返回 false）→ `startRide`（→40 IN_TRIP，同型）→ `finishRide`（→50 FINISHED_WAIT_PAY，轨迹解析与账单在 O1 §六/O5 §3.5，无 `@Transactional`）推进。到 50 后本旅程正常时间线**停在这里**；后续支付/超时都是带前提的条件分支（§2.6）。「到达」超时还有 20→10 的回池分支（`handleArriveTimeout`，见 §2.9），不是只有前进。

### 2.6 位置上报与支付在本情境的适用性

- **乘客位置**：是另一套数据（Redis Hash `user:loc:{userId}`，前端上报，无 MySQL 副本），用途是拼进 Agent 系统提示词的"当前乘客位置"（`OrderAgent#buildLocationString`）——**不是**司机接单或派单的输入（C5 §9 讲过它的接线情况）。本情境里林小雨的坐标已在 a11 进了提示词。
- **支付**：行程结束订单停在 `FINISHED_WAIT_PAY(50)`。`POST /order/{orderId}/pay` 的链路（`RideOrderServiceImpl#payOrder`）：归属 403 → 状态必须为 50（否则 409）→ 调用 `paymentVerifier`。**503 兜底不是无条件抛出的**：`config/PaymentConfig` 用 `@ConditionalOnMissingBean(PaymentVerifier.class)` 提供默认 Bean，只有"请求通过前面守卫、确实调用到这个 Bean"时才抛 `BusinessException(503, "支付渠道尚未接入，无法核验支付结果")`；若请求在归属或状态守卫处已被拒绝，就看不到这条 503。本旅程正常时间线**到 50 为止**。带前提的虚构示例：**假设**部署方注册自定义 `PaymentVerifier` 且核验通过，才走 `payOrderUnderLock` 条件更新 50→60、在当前无外层事务的 HTTP 路径中尝试 `syncOrderStatus(PAID)` 与 `orderDelayQueue.cancel`。至于未支付关单，必须满足：支付超时任务或 DB 补偿扫描实际运行、拿到订单锁、重读到 50 且当前时间达到或超过 `finishTime + 30 分钟`、`systemCancel` 条件更新 50→90 成功——不能说"30 分钟后一定取消"（O4 的条件分支）。

### 2.7 四种"保护"不要混

旅程二一路遇到四种名字里带"锁/状态/许可"的东西，它们保护的对象完全不同（C3 §8.1 的信任来源表 + C1 §2.3）：

| 名称 | 存储与键 | 谁设置 / 谁消费 | 保护什么 |
|---|---|---|---|
| 单轮执行锁 | Redisson `chat:execution:{chatId}` | `ChatRequestRunner#run` 取锁 / `doFinally` 释放 | 同一会话同一时刻只有一轮对话在跑 |
| 会话状态 | Redis Hash `chat:info:{chatId}` 的 `break/locked/classification/OrderId` | `begin/confirm/lockChat` 写 / `chat/resume` 读 | 流程阶段：等待确认？已锁定？归哪类？ |
| 一次性确认许可 | 同一 Hash 的 `quoteId/confirmedQuote/ReadyForCreate` | `quoted/begin/confirm` 写 / `consume` **删除** | "这次报价只被消费成一张订单" |
| 订单状态 | MySQL `sys_ride_order.order_status`（缓存 `order:status:`） | `driverAcceptOrder/cancelOrder/handleTimeoutOrder` 等条件更新 | 业务事实的唯一真值 |

**提示词不是其中任何一员**。`ORDER_AGENT_SYS_PROMPT` 会告诉模型"用户确认后才能创建订单"，但拦住重放/越权的是 `consume` Lua 和 `ReadyForCreate` 检查。

### 2.8 守卫失败前后的存储状态

以"报价超龄才确认"为例（b4 的失败分支，静态推导）。**注意：`quoted`（时间点①）、`begin`（②）、`saveNewMessages`（③）、`tryEmitNext(confirm)`（④）是四个不同时点**，下表分开列：

| 时点 | MySQL `sys_ride_order` | Redis `chat:info` | Mongo | 客户端可见 |
|---|---|---|---|---|
| ① `quoted()` 刚返回（a13 末） | 无订单行 | `quoteId/ReadyforConfirm/quoteCreatedAt`，**尚无 `break`/`pendingToolCall`**，无 `confirmedQuote/ReadyForCreate` | 预览轨迹一条 | 还没有 confirm 事件 |
| ② `begin()` 刚返回、③④ 未发生 | 无订单行 | 加上 `break=yes`/`pendingToolCall` | 同上 | 尚未发出确认卡片；本轮消息可能未保存 |
| ③④ 完成（A 段结束） | 无订单行 | 同②，消息已落到 `sys_messages` | 同上 | `tryEmitNext(AgentEvent.comfirm(...))` **尝试**发出 confirm 事件并 complete；是否送达未实测 |
| 报价已严格超过 900000ms 后点确认 | 仍无订单行 | 时间未变、字段不变 | 同上 | `confirm` 抛 `BusinessException(409)`：发生在 **chatExecutor 后台线程**，被 `AgentStreams.guard` 捕获 → 尝试发通用 error 事件 + 错误终态；**不是**同步 HTTP 409（C3 §4.6 口径：未映射成控制器层同步 400/409，客户端实际收到什么未实测） |
| 紧接着用户点拒绝（未超龄或拒绝不受年龄限） | 仍无订单行 | `break=yes → resuming`、`confirmationAccepted=no`、无 `confirmedQuote/ReadyForCreate` | 同上 | 拒绝可行（只有 yes 查年龄）→ 正常恢复流 → `OrderAgent#resume` 会删 `break/resuming` 字段 → 模型得知"用户拒绝" |

**另一条异常链（`consume`，与 `confirm`/guard 无关）**：到达 `OrderTool#createOrder` 的 `consume` 才失败时（例如前置检查通过后两字段被并发清理、`confirmedQuote` 不匹配，或年龄 >900000ms），异常发生在工具执行处，被 `OrderAgent#executeTool` 的 catch 转成 `"Tool execution failed: 尚未取得有效的用户确认，或本次确认已使用"` 文本回执；若缺 `ReadyForCreate` 则更早 `return "订单非可创建态"`，连 Lua 都不会调用。两者都只是工具回执，**不能套用 `ChatServiceImpl#resume` 的 guard 失败路径，也不能保证 HTTP 200 或事件送达**。同步 HTTP 状态码、SSE 事件与工具文本是三种不同的可见性（C1 §6.2/C3 §4.6）。

### 2.9 失败/并发推导：超时释放与司机接单的竞争

以下两条时间线都基于同一事实：`OrderDelayQueue` 的 ZSet **member 只有 orderId**，`schedule` 是提交后 `ZADD` 覆盖 score；`claimDue` 是 `ZRANGEBYSCORE + ZREM`（原子领取，领取即移除）。订单锁 `order:lock:5004` 由 `withOrderLock` 持有到事务完成回调。

**时间线 A：接单先提交，覆盖 score 后才到旧到期时间（可成立）**

```text
前提：CREATED(10)，ZSet member=5004 的 score=T+15m；OrderTimeoutJob 每 5 秒 fixedDelay 轮询。
t1  T+14m59s 周师傅 accept 到达；withOrderLock(5004) 成功
t2  tryGrab 依 DB 把 driver:active:920002 恢复为 __IDLE__，Lua 占位成功
t3  条件更新 10→20 成功；事务提交，锁仍由接单方持有
t4  提交后回调执行：ZADD member=5004 score=调用 schedule 时算好的截止时间（假设调用发生在 T+14m59s，则为 T+24m59s），覆盖旧 score
    → afterCompletion 尝试解锁；旧 T+15m 的 score 已被覆盖，T+15m05s 的 ZRANGEBYSCORE 不再返回 5004
t5  T+25m 左右任务才可能被领取：重读 DB 状态=20，handleArriveTimeout 以 driverAcceptTime 为锚点
    若未到 10 分钟 → reschedule(剩余时间) 重新入队；若已过 → 20→10 回池（不是取消）
```

**关键点**：不能让 t5 在 t4 之后仍按旧 score 领取同一 member——那与 `ZADD` 覆盖语义冲突。若想演示"旧待办已被领取"，必须让领取先发生（见时间线 B）。

**时间线 B：旧待办先被领取，随后接单与超时争锁（可成立）**

```text
前提同上，且 8:41 的司机心跳持续有效（否则 refreshIfExist 返回 false，但不阻止接单）。
t1  T+15m03s 轮询的 claimDue 先从 ZSet 移除 member=5004（此时尚未进入 handleTimeoutOrder）
t2a 若超时线程先拿到 order:lock:5004：重读 CREATED → 锚点=updateTime 已超 15 分钟
    → systemCancel 带 expectedStatus=10 + updateTime 条件更新 10→90 成功 → 提交后 syncOrderStatus(90)、
      orderDelayQueue.cancel、orderGeoPool.remove；周师傅随后 accept：
      tryGrab 回源/缓存看到 order:status=90 → Lua 第一关不等 10 → ORDER_NOT_GRABBABLE
      → BusinessException(409,"订单已被其他司机接走或已取消")（这是缓存预检分支）
t2b 若接单先拿到锁：tryGrab 通过、10→20 条件更新成功并提交 → 提交后 ZADD 覆盖（但 member 已被 t1 领走，
    这里是重新入队到 T+新10m）；超时线程随后拿锁、重读状态=20 → handleArriveTimeout
    未到 10 分钟 → reschedule 剩余时间（有副作用），不是"默认什么都不做"
t2c 若 tryGrab 通过但 DB 条件更新 0 行（状态已被推进）：rollbackGrab + refreshOrderStatus，
    方法返回 false（不是 409）；本例 t2a 的 409 来自预检，另有司机忙或等锁失败等分支
```

**推导要点**：`handleTimeoutOrderUnderLock` 的 Javadoc 明说"判定一律以重新读到的状态 + 该状态下锚点时间为准"，不信任入队时的意图；未到期就 `reschedule`（`OrderDelayQueue#schedule` 重新入队），已到期才按当前状态处理，状态 10/50 可进入 `systemCancel`，状态 20 则按条件回池，且成功提交后才执行缓存/索引/队列回调（回调失败只记日志，见 `OrderGrabService#afterCommit` 与 `OrderDelayQueue#afterCommit`）。这些回调与 ZSet 操作都是提交后、带失败吞掉的行为；真实并发顺序、`FOR UPDATE` 等待与 Redis 执行结果未实测（O4 §4.3 的补偿窗口仍存在）。

### 2.10 练习 B（只读，约 25 分钟）

**虚构输入**：林小雨在同一个对话里先说"从云栖小镇到萧山机场，快车"，拿到报价后又说"太贵了，改成专车吧"（未确认旧报价）。

1. 追踪第二次 `/c` 请求：`ChatServiceImpl#chat` 的哪一道守卫先拦截或放行？读 t7 处 `break==yes` 的分支，说出用户会看到什么事件、流是否结束；
2. 若模型随后调用 `saveNewOrderParam`（改车型槽位）再走 `getEstRouteAndPrice`：读 `saveNewOrderParam` 开头的 `opsForHash().delete(...)`，列出被清掉的旧报价字段；再读 `quoted()`，说出新的 `quoteId` 与旧的什么关系（提示：同 key 新值；旧 quoteId 的"确认资格"去哪了）；
3. 假设林小雨**不**改车型，在两个浏览器标签页分别点"确认"和"拒绝"：先判断"同时"是否真能并行进入 `confirm`。请分别推导 **(a) 第二个请求到达时首轮仍持有执行锁**、**(b) 第一个请求正常恢复完成、`OrderAgent#resume` 已删 `break/resuming` 字段后第二个才到**、**(c) 确实仍处于 `break=resuming`（首轮在清字段前失败/中断，或另有 `begin`+`CONFIRM` 重新进入该状态）** 三种时序：各自有没有走到 Lua、`confirmedQuote/ReadyForCreate` 最终是什么、`createOrder` 还能不能成功。不要写"正常 `complete` 后仍一定 `resuming`"——去 `OrderAgent#resume` 找清字段的那一行。

**容易读错的地方**：把 a7 的 `break` 检查当成"单轮执行锁"的一部分（它是会话状态检查）；把"改车型"当成需要新建对话（`saveNewOrderParam` 的清理使旧报价失效，可以在同一会话内重报）；把 `resuming` 当成正常恢复完成后的残留状态（正常路径会删字段）；把"两个标签页"当成一定同时进入 Lua（`/r` 也要过单轮执行锁）。

<details>
<summary>参考答案与推导</summary>

1. `break==yes` 的守卫在 `chatManager.assertOwned` 之后、分类之前（`ChatServiceImpl#chat` 前几行）：发出 notify 事件"请先确认或拒绝当前订单，再继续对话"，随后 complete，**不进分类、不调 Agent**。但注意：模型改槽位的工具链发生在**这之后的新一轮**——实际顺序是用户必须先确认或拒绝旧报价；若用户拒绝了（break 走完 no 分支并 resume），新消息才能进分类。题目给的是"未确认旧报价直接改车型"，所以第一次就被挡住。**推导前提**：这段代码顺序直接读自 `ChatServiceImpl#chat` 第 95～99 行附近。
2. 被清字段：`ReadyforRoute/ReadyforConfirm/ReadyForCreate/quoteId/confirmedQuote/EST_TIME/EST_DISTANCE_KM`（`OrderTool#saveNewOrderParam` 第 58～59 行）。新 `quoted()` 生成**全新 UUID** 的 `quoteId` 并覆盖写入同一 Hash——旧 quoteId 从此对不上任何 confirmationId，`CONFIRM` 第一行 `HGET quoteId ~= ARGV[1]` 即拒绝（409）。旧 quoteId 没有显式"吊销"动作，是**被覆盖**而失效的。
3. 三种时序分别推：

- **(a) 锁仍占用**：`/r` 走 `ChatRequestRunner#run`，同一把 `chat:execution:{chatId}` 锁、等待 0 秒；第二个请求拿不到锁 → `Flux.just(AgentEvent.error("会话正在处理中..."))` 的单事件流，**根本没进 `confirm`**；`break/confirmedQuote` 由持锁的首轮决定；
- **(b) 首轮正常恢复完成**：先到的确认在 CONFIRM 成功后由 `OrderAgent#resume` 删掉 `break/pendingToolCall/confirmationAccepted`，许可 `confirmedQuote/ReadyForCreate` 留给模型去 `createOrder`；后到的拒绝此时读到的 `break` 已不是 `resuming`，`CONFIRM` 会因为 `state ~= 'yes'` 返回 0（若消费已完成，连许可也被删）；若先到的是拒绝，则许可从一开始未写。结论是**不能拿"正常 complete 后仍 resuming"推导**；
- **(c) 确实仍 `break=resuming`**：同决定（`yes` 且未严格超 900000ms，或 `no`）重放返回 1 且不写任何字段；相反决定返回 0，`confirm` 抛 `BusinessException(409)` 于 chatExecutor 后台线程，由 `AgentStreams.guard` 尝试转成流内 error（C3 §4.6，业务码不直达 HTTP、是否送达未实测）。若 `createOrder` 已 `consume` 掉许可，后续重放即使返回 1 也没有 `ReadyForCreate`，工具会 `return "订单非可创建态"`（不进 Lua）。

**关键**：`resuming` 窗口只能在"CONFIRM 已受理且 `OrderAgent#resume` 尚未清字段"时成立，正常路径会清；以上均为源码静态推导，未实测。
</details>

---

## 三、旅程三：客服咨询——知识库与可选工单（下午 14:00）

### 3.1 起点与目标

- **起点**：林小雨对上午的订单有费用疑问，在**新对话**里问："乘客取消订单要收违约金吗？怎么算的？"
- **目标**：SupportAgent 基于知识库回答；问题没解决时，把咨询升级成一张工单。

**先确认当前支持什么**：SupportAgent 注册的工具是 `OrderSearchTool`（查单/取消/改目的地）、`TicketTool`（建单/查单/升级/补充）、`ToolRepPointerTool`（getCalling）、`RagTool`（知识库检索）——本旅程用到 `searchKnowledgeBase` 与 `createTicket` 两个（C2 §5.4 有完整注册表）。

### 3.2 正常时间线：从一句咨询到知识库答案

```text
c1  新对话：POST /chat/v2/newuuid → chatId a1b2...6789
c2  POST /chat/v2/c/a1b2...6789 {prompt:"乘客取消订单要收违约金吗？怎么算的？"}
c3  与旅程二相同的入口链：PermissionAspect → ChatRequestRunner（锁/归属）→ ChatServiceImpl#chat
c4  分类："乘客取消订单要收违约金吗" → SUPPORT（静态推演；真实分类输出未实测）
c5  chatManager.initChat → switch("SUPPORT") → SupportAgent#invoke
c6  SupportAgent 工具循环：模型判断这是业务规则问题 → 调用 searchKnowledgeBase
    （queryStr 可能被改写为"乘客取消订单违约金规则"——参数由模型生成，不可信输入）
c7  RagTool#searchKnowledgeBase → RagService#searchAnswers：
    Embedding（DashScope，外部）→ 向量 + BM25 两路并行（ragExecutor）→ RRF 融合 → 按 groupId 组内去重、取前五
    → authoritative 回查 MySQL（QaElasticMap → sys_qa_es；QaInfo → sys_qa_info）→ 答案覆盖为本次回查读到的 sys_qa_info.answer
c8  工具回执文本："找到以下相关知识库条目：--- 条目 1 --- 参考问题：... 标准回答：..."
c9  ToolResponseMessage 进历史 → 下一轮模型 → AgentEvent.message（给林小雨的解释）
c10 saveNewMessages → tryEmitComplete → SSE 结束
```

### 3.3 逐步拆解：分类和工具选择的前提

**分类的前提**。c4 不是必然：分类模型可能输出别的标签（比如把"违约金"当成日常闲聊给 DAILY）；`resolveClassification` 只保证五选一或 null。真实分布未实测。**旧会话续问**时分类输入是 `(上轮分类, 最后 Assistant 文本, prompt)`——本旅程用新对话避开这个变量。

**工具选择的前提**。`searchKnowledgeBase` 的 `@Tool` description 写着"当用户询问关于系统操作、业务规则或常见问题时，必须调用此工具……请勿凭空编造答案"——这**只是提示词层面的引导**。模型可能：调用它、不调用它直接回答（编造风险）、或先调 `searchOrderById` 查上午的订单。**没有代码强制"咨询必查知识库"**。这正是"提示词不是鉴权"的镜像面：提示词约束的是**该做什么**，代码守卫约束的是**能不能做**（C2 §5.7、C5 §8.4）。

### 3.4 双路检索、候选回查与工具回执

`RagService#searchAnswers` 的六步（R1 §三是逐行导读，这里只给旅程视角）：

1. **Embedding 串行等待**：`embeddingModel.embed(queryStr)`，共享整个检索的 deadline（默认 `searchTimeoutMillis` 8000ms，`@Value` 默认值）；
2. **双路并行**：向量检索（ES `qa_knowledge_base` 索引，`knn`）与 BM25（`match`）各取 `WINDOW_SIZE=100` 条候选，跑在 `ragExecutor` 上；
3. **RRF 融合**：`score = 1/(60 + rank)`（rank 从 1 起）两路累加（R1 §3.8 有手算例子）；
4. **排序去重**：按分数降序、**按 `groupId` 组内去重**、取满 5 条停——能进入回查的只是这两路召回窗口里融合排序靠前的候选，未召回或不在这前五名中的文档不参与；
5. **回查权威库**：`authoritative(finalResults)`——用候选的 ES 文档 id 查 MySQL 映射表 `sys_qa_es`（实体 `QaElasticMap`），再按 `groupId` 查真值表 `sys_qa_info`（实体 `QaInfo`），**五条件全部匹配**（mapping 存在、`mapping.question == doc.question`、groupId 相等、info 存在等，详见 R1 §3.6）的候选才保留，并且 `doc.setAnswer(info.getAnswer())` **用本次回查读到的 `sys_qa_info.answer` 覆盖文档里的副本**；
6. **失败路径**：任何一步异常（超时、ES 挂、Embedding 失败）→ 外层 catch → `throw new RuntimeException("RRF Search failed", e)`。

**结果如何进入 Agent 流**：`RagTool#searchKnowledgeBase` 拿到 `List<QaDocument>`：空 → 返回文本"知识库未找到有效的相关信息，请明确告知用户并按需转人工处理。"；非空 → 拼成 c8 的多段文本。正常工具循环先经 `toolRepPointerTool.recordCalling` 尝试保存字符串，再把它作为 `ToolResponseMessage` 的一部分加入内存历史（C4 §7.1）。模型继续时可转述、截断或解释这段文本，只有它生成文本且服务端成功发出并送达 `AgentEvent.message` 时，林小雨才会看到相应消息。

> **停顿点 5**：读完 `searchAnswers` 后去读 `authoritative`（`agentbase/rag/RagService.java`）：确认最终答案来自本次回查 `sys_qa_info` 的 `doc.setAnswer(info.getAnswer())`，**没有任何一处把 ES 里的 answer 副本直接发给用户**——这是下面两条交错成立的原因。反向也成立：某一次回查读到新答案，或某个 ES 文档/任务表快照是新的，都不足以证明整次索引重建已完整成功（R2 §2.3）。

### 3.5 两个交错：改答案后旧候选返回新答案、改问题后旧候选校验失败

知识库有一条问答（虚构）：`sys_qa_info` 行 `groupId=301`，问题组含"乘客取消订单违约金怎么算"，答案原文"司机接单超过 5 分钟后取消收违约金……"。管理员在 13:55 用 `POST /rag/admin/qa/update-answer` 把答案改成新版（补了封顶规则）。

**写路径的事务事实**：`RagService#updateAnswer` 与 `#updateQuestion` **都带 `@Transactional`**。前者更新 `sys_qa_info.answer` 与 `syncJobs.enqueue(groupId)` 在同一事务；后者先 `FOR UPDATE` 锁 `sys_qa_es` 映射行，再更新该行的 `question`，同一事务内入队。ES 重建是提交后由 `QaIndexSynchronizer` 异步领取的任务，不在这两个事务内（R2 §2.0～§2.3）。

**交错 A（改答案，13:56 林小雨提问）**——一条完整、自洽的时间线：

```text
13:55:00  管理员 updateAnswer：事务 T1 内 UPDATE sys_qa_info.answer=新文本 + enqueue(groupId=301)
13:55:01  T1 提交；此刻 MySQL 已是新答案，ES 仍是旧文档（旧 answer 副本）
13:55:xx  【本时间线的关键前提】同步器尚未完成重建与 refresh：
          或任务未被领取、或已领取但删旧/写新/refresh 未全部完成；ES 搜索看到的仍是旧文档
13:56:00  林小雨提问 → 两路召回：旧文档 question/groupId 未变，词面/向量是否命中取决于分词与相似度，
          本例假设它确实进入召回窗口并进入按 groupId 去重后的前五
13:56:0x  authoritative 回查：按 ES id 查 sys_qa_es 得到映射（question 与 doc.question 一致、groupId 一致），
          按 groupId 查 sys_qa_info —— 回查发生在 T1 提交之后，读到新答案
          → doc.setAnswer(新答案) → 工具回执"标准回答"是【新答案】
```

**前提与边界**：① 旧候选仍可见且被召回、通过全部五条件；② 本次回查观察到了已提交的 `sys_qa_info` 新行；③ 重建是否完成**与这次回答无关**——不能把"刷新成功"画在同一次搜索之前又用旧文档解释它。若回查在 T1 提交之前发生，读到旧答案，也同样自洽。最终答案是"本次权威回查"决定的，**单个新答案、单个 ES 文档或任务表单点观察都不足以证明同步整体完成**。

**交错 B（管理员改的是"问题"文本，13:55）**：`updateQuestion` 只更新 **`sys_qa_es.question`**（问题文本在映射表，`sys_qa_info` 只有 groupId 与 answer），同一事务入队；ES 重建同样异步。13:56 检索时至少有三种可成立结果，必须分开写：

```text
情形 1（旧文档仍可见且确实被召回）：
  authoritative：mapping.question（sys_qa_es 新文本） != doc.question（ES 旧文本）→ 五条件不满足
  → 该候选 continue 剔除；若全部候选被剔除 → 空列表 → RagTool 返回"知识库未找到有效的相关信息..."
情形 2（窗口期处于"旧文档已删、新文档尚未 refresh 可见"）：
  两路召回可能都没有这一条 -> 不是"校验失败"，而是根本无候选；词面一致也不保证 BM25 命中
情形 3（新文档已 build+refresh 可见）：
  若查询词与新文档匹配、被召回并进入前五，回查看到新的 sys_qa_es.question 与之一致 -> 校验通过
```

**结论**：改问题**可能**使旧候选不匹配（情形 1），也可能无旧候选可校验或命中已重建的新文档（情形 2/3）；BM25 与向量两路都是条件化推导，不能从"旧词/新词"断言一定召回。两条交错的差别正是 R1 §5.3（T2）与 §5.4（T3）的差别。

### 3.6 咨询转工单：真实触发条件

**先澄清一个容易写错的点：不存在"咨询无法解决就自动转工单"的代码路径。** 会话内只有**模型实际调用 `createTicket` 工具**才进入创建逻辑——通常发生在用户明确说"我要投诉/转人工/建工单"，或模型按 `SUPPORT_AGENT_SYS_PROMPT` 的引导判断该建单。提示词写着引导，但**没有自动转单的分支、没有定时器、没有"检索为空就建单"的代码**。项目**另有直接 HTTP 入口** `POST /ticket/submit`，那是客户端直接提交，不是"咨询自动转单"。

两条入口的身份与角色来源不同，别写反：

- **HTTP `TicketController#submit`**：`@RequirePermission`，`req.setUserId(String.valueOf(UserTokenContext.getUserIdInLong()))` 覆盖客户端传入值，`userType = hasAnyRole("DRIVER") ? 2 : 1`（司机提交就是 2，不是固定 1）；
- **工具 `TicketTool#createTicket`**：身份来自 `ToolContext.USER_ID`（服务端注入），**固定 `userType=1`**（乘客）——工具没有按角色切 2 的分支，也没有非乘客身份来源。

`orderId` 有五种结果，按"在哪一层结束"分：

| 情况 | 结束位置与结果 |
|---|---|
| 缺省 | `TicketTool`：跳过 `Long.valueOf`，`linkedOrder=null`，工单不带关联继续创建 |
| 有效数字（本例 `5004`，属林小雨） | `submitTicket` 归属校验通过，写入 `order_id=5004` |
| 格式错误（字母等） | `TicketTool` 直接 `return "关联订单号必须是数字，请核对订单编号"`——**不抛异常、不进服务** |
| 数字但订单不存在 / 非本人（如 `5005`） | `submitTicket` 查 `sys_ride_order` 后 `BusinessException(403, "只能关联自己的订单")`，不 insert |
| 空白字符串 | `TicketTool` 同样跳过解析，关联继续为 null。 |

林小雨对知识库答案不满意，说"我要建工单投诉费用问题，关联订单 5004"。**假设模型调用** `createTicket`，且 `SupportAgent#executeTool` 解析出合法参数：

1. `TicketTool` 取服务端注入的 userId=`910001`、固定 `userType=1`，构造 `TicketCreateReqDTO`：标题"费用争议"、内容"……"、**合法类型 type=2（费用争议）**、优先级 `priority=1`（普通；只有安全问题 type=4 允许非普通优先级）、`orderId=5004`；
2. `TicketServiceImpl#submitTicket`：先校验订单归属通过 → 参数校验 → `generateTicketId` 用 Redis INCR 取当日序号拼出 `T202611192000042` → `ticketMapper.insert`（`ticket_status=0 PENDING_ASSIGN`）→ 插入**系统消息** `senderId=0`、`senderRole=SYSTEM`、内容"工单已创建，等待客服处理" → `afterTicketMutation`：提交后 `ticketPoolIndex.syncByTicketId(ticketId)` 推进工单池索引，再执行统计缓存的延迟双删失效（`evictTicketStatistics`）；
3. `TicketTool` 返回字符串 `"工单已创建，工单编号：T202611192000042"`；`SupportAgent#handleToolCalls` 先调用 `toolRepPointerTool.recordCalling` 尝试通过 `ToolResponseMemory` 存储回执，再把批次回执组成 `ToolResponseMessage` 加入内存历史，最后递归下一轮模型。模型是否把编号转述给林小雨、转述成什么文案，源码不保证。

工单之后的状态推进（认领/处理/结单）属于 S1 §四/§五，本旅程到"工单进入池子"为止。

### 3.7 两个失败分支：检索失败与工单校验失败

先明确**可见性的四个层次**，不要混为一谈：① 工具方法返回的字符串（源码可确认）；② `ToolResponseMessage` 写入内存历史、并由 `recordCalling` **尝试**写入 `ToolResponseMemory`（MySQL + Redis，失败只记日志）；③ 下一轮模型是否把这段字符串转述成 message 事件及用什么文案（源码不保证）；④ 客户端是否真的收到 SSE 事件、HTTP 响应是否已提交（`tryEmit*` 只是尝试，未实测）。**"异常被转成文本回执"只说明它没有当场终结流，不等于 SSE 一定 200、模型一定转述、辅助存储一定成功**。

**分支一：检索失败（工具内部 catch）**。假设 c7 的 Embedding 调用超时：

1. `searchAnswers` 的 `embeddingFuture.get(remaining(deadline))` 抛超时 → 外层 catch 包装为 `RuntimeException("RRF Search failed", e)`；
2. `RagTool#searchKnowledgeBase` **自己的 `catch (RuntimeException e)`** 先接住 → 返回源码原文 **"知识库暂时不可用。"**——异常没有传到 `SupportAgent#executeTool` 的 catch，所以不会带 `Tool execution failed:` 前缀；
3. 先由 `recordCalling` 尝试写辅助存储，再将工具回执加入内存历史；下一轮模型看到它，可能向林小雨道歉或建议转人工，也可能忽略它——具体文案与是否发出 message 事件源码不保证。

**分支二：工单归属失败（业务异常被 Agent 的 executeTool catch）**。林小雨说"帮我给订单 5005 建个投诉工单"（别人的订单，虚构）：

1. `TicketTool#createTicket` 把 `orderId=5005` 转成 Long 成功 → DTO 进入 `submitTicket`；
2. 归属校验失败 → `BusinessException(403, "只能关联自己的订单")`，发生在 `ticketMapper.insert` 之前；
3. `SupportAgent#executeTool` 的 `catch (Exception e)` 捕获 → 返回 `"Tool execution failed: " + e.getMessage()`，即源码可确认的 **"Tool execution failed: 只能关联自己的订单"**；
4. 同样先调用 `recordCalling` 尝试存储，再将回执加入内存历史。**MySQL 没有工单行、Redis 没有 INCR 编号**（`generateTicketId` 在校验之后）——但"辅助存储是否写入"取决于 `ToolResponseMemory.save` 的执行结果，`recordCalling` 捕获存储的 `RuntimeException` 并记日志，正常返回后继续组装回执。

两个分支的共性：异常都在**工具层**转成字符串，因而不走 `AgentStreams.guard`、不会直接终结 SSE 流；差异在于 catch 在哪一层（工具方法内部 vs Agent 的 `executeTool`），前缀不同。对比旅程二：`consume` 抛异常也是被 `OrderAgent#executeTool` catch 成文本；而 `ChatServiceImpl#resume` 里的 `confirm` 异常发生在后台任务主体（不在工具 catch 内），才由 `AgentStreams.guard` 处理。源码能确认的只是回执字符串本身；客户端可见性未实测。

### 3.8 练习 C（只读，约 20 分钟）

**虚构输入**：知识库里有一条 `groupId=302` 的问答，`sys_qa_es.question` 是"怎么开发票"，`sys_qa_info.answer` 为 v1。管理员 14:00 调 `POST /rag/admin/qa/update-question` 把 `sys_qa_es.question` 改成"如何开具行程发票"（答案不变）。林小雨 14:02 问"怎么开发票"。

1. 读 `RagService#updateQuestion`（`@Transactional`）与 `QaIndexSynchronizer#synchronizeGroup`（重建），列出 14:02 检索时"旧文档仍可见"与"旧文档确实被召回"各自的前提，并说明两者不是同一件事（提示：任务领取、删旧/写新/refresh 时机、召回窗口与组内去重前五；R2 §4.3）；
2. 读 `authoritative` 的五条件比对，推导：若旧文档确实被召回且回查看到新的 `sys_qa_es.question`，会发生什么？若回查仍看到旧文本（读时点未知）呢？
3. 如果林小雨 14:02 问的是新文本"如何开具行程发票"呢？BM25 与向量两路各自可能怎么召回（不要从词面直接断言一定命中；标"取决于真实分词与相似度，未实测"）。

**容易读错的地方**：把交错 B 的"校验失败"想成"抛异常"（它是 continue 剔除候选，方法正常返回空/部分列表）；把"旧文档仍可见"当成"一定被召回"（还要过双路召回、RRF、组内去重与前五）；把 3.7 分支二的 403 想成 HTTP 403（它是工具回执文本；不能保证客户端收到任何 SSE 事件或 HTTP 200）。

<details>
<summary>参考答案与推导</summary>

1. "旧文档仍可见"的前提：更新与入队在 T1 内提交后，同步任务尚未被领取，或已领取但 `deleteByQuery/写入/refresh` 未完成/未可见（fixedDelay 不是上界，R2 §4.3）；此时也可能处于旧文档已删、新文档尚不可见的窗口。"被召回"是另一个前提：候选必须进入向量或 BM25 的窗口、RRF 排名靠前，并按 groupId 组内去重后进入前五。两者都要成立才谈得上"五条件校验失败"。
2. 旧文档被召回 + 回查看到新文本：`mapping.question`（`sys_qa_es` 新文本）与 `doc.question`（ES 旧文档）不等 → 候选被 `continue` 剔除；若全部候选被剔除 → 空列表 → RagTool 返回"知识库未找到有效的相关信息..."。若回查仍读到旧文本（源码没有显式的跨查询快照，`qaElasticMapMapper.selectList` 的读时点未知，R2-R1 已登记），则该候选有机会通过五条件，并返回本次答案查询读到的答案（本例没改答案）——两条推导都由回查读时点决定，不能只写一种。
3. 问新文本：若新文档已 refresh 可见且两路之一召回它、进入前五，回查看到新 `sys_qa_es.question` 与之一致 → 校验通过；若 ES 仍是旧文档，BM25 词面可能失配（也可能因分词仍命中），向量路按语义可能仍召回旧文档（然后被校验剔除）。**真实结果取决于分词器与相似度，未实测**——这是 R1 §5.8 "静态推导"与"实测"的边界。注意无论哪种结果，只凭某次回答不能证明整次重建完成。
</details>

---

## 四、跨旅程复习检查点

### 4.1 正向：从入口追到状态与副作用

对每条旅程，合上书回答下面六问（答不出回到括号里的章节）：

| 检查点 | 旅程一 | 旅程二 | 旅程三 |
|---|---|---|---|
| 入口与身份来源 | `Authorization: Bearer` 头（A1 §5.1） | `/c`、`/r` 的 `@RequirePermission` + `userId` 注入（C1 §1.3） | 同左 |
| 第一道守卫 | IP 限流（发码）→ `getToken` 双层（A1 §5.2） | 单轮执行锁 → 会话检查 → 分类（C1 §3.1） | 同左 |
| 读写的主要状态 | Redis 令牌族 + `sys_user` | `chat:info`、`chat:execution`、`sys_ride_order`、Mongo 轨迹（C3 §8.1） | 三层记忆 + `sys_qa_es`/`sys_qa_info` + ES（R1 §一） |
| 一次性/原子语义 | 验证码 5 次与一次性消费（A1 §3.3） | `CONFIRM/CONSUME` Lua（C3 §四/§6.3） | —（检索无一次性语义） |
| 落库位置 | Redis 令牌与索引（SAVE）+ `sys_user` 最后登录时间（验证码在登录前已消费）；两者不在同一事务 | `sys_ride_order` 行 + `sys_messages` | `sys_messages`、（转单时）`sys_ticket` |
| 副作用的清理者 | `REVOKE`（改密/注销） | Mongo 预览轨迹删除、`releaseDriver`、超时任务（O4） | —（读路径为主） |

### 4.2 反向：从失败现象反查拦截位置

拿到现象，说出层、类、方法（这是本章自测的核心能力）：

先说方法：反查时按"① 源码可确认的工具回执字符串 → ② 内存历史与辅助存储 → ③ 模型可能转述 → ④ 客户端实际收到什么"逐层核对；下表"证据"列只写①层能确认的东西，③④ 一律标条件。**异常被转成文本回执不等于 SSE 200、不等于模型照抄、不等于落库成功**。

| 现象 / 要反查的回执 | 拦在哪一层 | 源码可确认的证据（①层）与未知 |
|---|---|---|
| HTTP 401 `Result{401,"请先登录"}` | AOP 切面（方法前） | `PermissionAspect#checkPermission` → `GlobalExceptionHandler`；限定同步注解端点、身份为空、映射正常执行 |
| HTTP 403 `Result{403,"无权访问该资源"}` | AOP 角色层 | `PermissionAspect`（注解带角色）；同样是同步 HTTP 结论 |
| HTTP 409 `订单已被其他司机接走或已取消` | 同步业务守卫 | `driverAcceptOrder` → `tryGrab` 的 `ORDER_NOT_GRABBABLE` 分支（缓存状态不符）；另需区分 DB 条件更新 0 行（返回 false，不是 409） |
| HTTP 429（发码） | 两处不同：IP 限流拦截器 / `EmailCodeService#ISSUE` | 前者在 `RequestRateLimitInterceptor`，后者在验证码冷却/claim 检查，错误消息相同但代码路径不同 |
| HTTP 503 `支付渠道尚未接入` | 配置兜底 Bean | `PaymentConfig` 的 `@ConditionalOnMissingBean` 实现；仅当请求通过归属与状态守卫、确实调用到 Bean 时可见 |
| SSE 流内 error 事件"会话正在处理中" | 单轮执行锁 | `ChatRequestRunner#run` 拿不到 `chat:execution:{chatId}` 时 `Flux.just(error(...))` |
| SSE 流内 error 事件"处理失败，请稍后重试" | 后台 guard | `AgentStreams#guard`：`ChatServiceImpl#resume` 里的 `confirm` 409 属这条链；**`executeTool` 内的 consume 异常不在此列** |
| 工具回执"订单非可创建态" | `OrderTool#createOrder` 开头 | 缺 `ReadyForCreate`，进入 `consume` 之前直接 return，无 Lua 调用 |
| 工具回执"Tool execution failed: 尚未取得有效的用户确认，或本次确认已使用" | `OrderAgent#executeTool` catch | `OrderConfirmation#consume` 抛 409 后转成文本；不能套用 resume 的 guard 路径 |
| 工具回执"知识库暂时不可用。" | `RagTool#searchKnowledgeBase` 内部 catch | `RagService#searchAnswers` 的 `RuntimeException` 被工具方法自己接住；模型是否转述、是否落辅助库未知 |
| 工具回执"Tool execution failed: 只能关联自己的订单" | `SupportAgent#executeTool` catch | `TicketServiceImpl#submitTicket` 的 403 被 Agent catch；无工单行、无 INCR，但辅助存储写入结果未知 |

**方法**：先分清现象出现在**同步 HTTP 响应**、**工具回执字符串**、**SSE 事件**还是**模型的话**里——不同层级对应不同失败路径（A2 §6.2、C1 §6.2）。"模型回复里出现源码字符串"不是可保证的观察结果，只能作为条件化示例。

## 五、一页复习

- **一天三旅程**：登录（令牌从 Redis 与 `sys_user` 双栅栏复核）→ Agent 下单（SSE 事件流 + 工具循环 + 一次性确认许可 + 条件更新落库）→ 客服咨询（分类路由 + RAG 双路检索 + 答案以 MySQL 为准 + 可选工单）。
- **身份链**：`AuthTokenInterceptor`（认人，总是放行）→ `UserTokenContext`（ThreadLocal，请求线程独有）→ `PermissionAspect`（拒人：401/403）→ 业务归属（403，数据层）。Agent 后台线程没有 ThreadLocal，身份靠 `ToolContext.USER_ID` 注入。
- **旅程二的四层保护**：单轮执行锁（同时只有一轮）、会话状态（`break/locked`）、一次性许可（`quoteId→confirmedQuote→consume` 删掉）、订单状态（MySQL 真值）。提示词不在其中。
- **HTTP 与 Agent 下单的分叉点**：`OrderTool#createOrder` 的 `ReadyForCreate` 检查与 `consume` Lua；之后汇合到同一个 `RideOrderServiceImpl#createOrder`（服务端重算价格）。
- **订单状态迁移**（本旅程走过）：10 创建 → 20 接单 → 30 到达 → 40 行程中 → 50 待支付；本教程主线**止于 50**。60 已支付、90 超时取消都是有额外前提的条件分支（支付核验 Bean、任务/扫描+锁+条件更新）。创建无本地事务；接单有 `@Transactional(READ_COMMITTED)`；到达/开始/结束无方法级事务，但都过 `withOrderLock` + 条件更新 + 提交后缓存同步；20 未到达超时还会 20→10 回池。
- **RAG 的一句话**：ES 只负责"找到候选"（双路召回→RRF→组内去重取前五），`authoritative` 用 `sys_qa_es`/`sys_qa_info` 五条件校验并把本次回查的 `sys_qa_info.answer` 覆盖到结果；索引落后影响召回，但答案由本次回查决定（改问题还可能触发校验剔除）；单个 ES/任务快照不能证明整次同步完成。写答案/写问题方法均带 `@Transactional`，与 enqueue 同事务。
- **工具失败的可见性**：工具内部/Agent `executeTool` catch → 回执字符串（源码可确认）→ `recordCalling` 尝试写辅助存储 → 内存历史 → 模型可能转述 → `tryEmit*` 尝试发事件；后台任务主体（`resume`/`confirm`）抛出 → `AgentStreams#guard` → 尝试发 SSE error；同步注解端点抛出 → `GlobalExceptionHandler` → HTTP 状态码。字符串、事件、HTTP 码、客户端实收是四层，不要混。
- **HTTP 与工具建单**：HTTP `POST /ticket/submit` 覆盖身份、`userType=DRIVER?2:1`；工具取 `ToolContext.USER_ID`、固定 1；只有模型实际调用才走工具分支，项目另有直接 HTTP 入口。
- **必须带走的警告**：不把提示词当守卫；不把"回执文本"当"客户端一定看到"；不把"流结束"当"事件必达"；不把"读过代码"当"跑过"。

## 六、自测与答案（6 题）

<details>
<summary>1. 林小雨的令牌在 Redis 里还活着（TTL 未到），但她在另一台设备重置了密码。旧令牌的下一个请求会得到什么？按 getToken 的顺序指出第一个失败的检查，并说明三种时点。</summary>

401（`UnauthorizedException`），但第一个失败点取决于时点。先排除一种误解：`preHandle` 在"令牌读不到/校验不过"时返回 null 并放行，由切面抛 401；若 Redis/DB 依赖本身抛异常，则是另一条冒泡路径。对"旧令牌仍活着"这一题设：

- **撤销执行前**（改密事务未提交、`afterCommit` 的 `REVOKE` 未运行）：版本与 token key 都还是旧值，旧令牌仍可通过校验，不会失败；
- **撤销成功提交后**：`REVOKE` 先 `INCR auth:session-version:{userId}` 再删 token key；`getToken` 的第一个失败点是 `sessionVersion != currentVersion`，通常 token key 也已被删；
- **撤销回调失败、token key 与版本都保留**：第一个失败点后移到 MySQL 比对 `credentialStamp`——新密码指纹与令牌里的旧指纹不等。

`getToken` 顺序：读 Redis JSON → 解析 → 版本比对 → MySQL 查 `sys_user`（`is_deleted=0/status=1`）→ role 与 credentialStamp 比对 → 过期检查在 `preHandle`。依据：`TokenServiceImpl#getToken/#saveToken/#deleteAllTokensByUserId`、`AuthServiceImpl#resetPasswordByEmailCode`、A1 §6.2。Lua 脚本本身原子，"部分失败"指回调未完整执行；未实测时序。
</details>

<details>
<summary>2. 旅程二 a4 的单轮执行锁和 a7 的 `break` 检查分别保护什么？如果把 a7 的检查删掉，哪个既有保证会被破坏？</summary>

a4（`chat:execution:{chatId}`）保证同一会话同一时刻只有一轮"分类→Agent→工具循环→保存"在执行，防止两轮并发写历史与 Redis 槽位。a7 的 `break==yes` 是**会话流程状态**检查：报价等待确认期间不接受新话题，防止模型在许可未决时被新输入带偏。删掉 a7：用户新消息会进入分类与 `OrderAgent#invoke`——历史与报价状态可能被改写（`saveNewOrderParam` 会删 `quoteId`），"等待确认"的暂停语义失效。但**一次性许可本身不破坏**（`consume` 仍拦创建）——这正说明两层保护的对象不同。依据：`ChatRequestRunner#run`、`ChatServiceImpl#chat` 前段、C1 §2.3、C3 §8.1。
</details>

<details>
<summary>3. `POST /order`（HTTP 创建）与 Agent 的 `createOrder` 工具最终都调用 `RideOrderServiceImpl#createOrder`。列出 Agent 路径在调用它**之前**多出的两道守卫，并说明 HTTP 路径为什么"不需要"它们。</summary>

两道：`OrderTool#createOrder` 开头的 `ReadyForCreate` 状态检查（缺它时直接 `return "订单非可创建态"`，不进 Lua），以及 `OrderConfirmation#consume`（CONSUME Lua：严格 `>900000ms` 拒绝 + `confirmedQuote==quoteId==ReadyForCreate`，成功则 HDEL 许可）。HTTP 路径**直接调用服务，没有这两道门**；它不经过报价/确认工具链，也就不存在"服务端保存一个可重放的中间许可"的场景。"用户按下按钮即确认"是对接口协议的一种解释，**源码不证明页面一定有这样的按钮**。依据：`OrderTool#createOrder`、`OrderConfirmation#consume`、C3 §6.2/§6.3、O1 §10.1。注意 `SecurityRepairRegressionTest#modelCannotGrantItselfCreationPermission` 只断言 `markOrderReadyForCreate` 的"尚未"文本与无 Redis 交互，不证明 `createOrder` 重放被它覆盖。
</details>

<details>
<summary>4. 周师傅的接单请求和无人接单超时任务竞争（2.9 的时间线）。请说出"重新读状态"发生在哪个方法；若接单先提交并成功覆盖了 ZSet 中同一 member 的 score，旧待办还能按旧时间被领取吗？若旧待办已被 claimDue 领走，接单与超时谁先拿到订单锁分别会怎样？</summary>

`RideOrderServiceImpl#handleTimeoutOrderUnderLock` 在锁内 `selectOne` 重读 `sys_ride_order` 当前状态，`switch (status)` 只对 CREATED/DRIVER_ACCEPTED/FINISHED_WAIT_PAY 做超时处理；Javadoc 明说"判定一律以重新读到的状态 + 该状态下锚点时间为准"，不信入队时的意图。

- **接单先提交且 ZADD 成功覆盖**（`schedule` 用 orderId 作 member、提交后覆盖 score）：旧到期时间不再被 `ZRANGEBYSCORE` 命中，自然不会被领取；若尚未到新期限，任务到点重读状态=20 后走 `handleArriveTimeout`，未到期会 `reschedule`，已到期则 20→10 回池；
- **旧待办已被 `claimDue` 领走**（`ZRANGEBYSCORE + ZREM` 原子移除）：谁先拿到 `order:lock:5004` 谁先改状态。超时先胜出：10→90、cancelRole=3、提交后同步缓存/清队列/移池，接单的 `tryGrab` 看到 `order:status=90` 返回 ORDER_NOT_GRABBABLE → 409；接单先胜出：10→20 并提交后重新入队，超时线程重读=20 后走未到达分支（未到期则重排，有副作用）。若 `tryGrab` 通过但条件更新 0 行，则 `rollbackGrab + refreshOrderStatus` 并返回 false，不是 409。

依据：`RideOrderServiceImpl#handleTimeoutOrderUnderLock/#handleAcceptTimeout/#handleArriveTimeout/#systemCancel`、`OrderDelayQueue#schedule/#claimDue`、`OrderGrabService#tryGrab`。真实并发与锁等待未实测。
</details>

<details>
<summary>5. 管理员 13:55 在 updateAnswer 事务里改了 sys_qa_info.answer 并入队，13:56 林小雨的检索返回了新答案。这个结果能证明 ES 同步已经完成吗？请给出成立前提。</summary>

不能。`authoritative` 对通过五条件校验的候选执行 `doc.setAnswer(info.getAnswer())`——答案是**本次回查 `sys_qa_info` 读到的行**，与 ES 文档里的 answer 副本无关。"返回新答案"成立的前提是：旧 ES 候选仍可见、确实被至少一路召回并进入组内去重后的前五、`sys_qa_es` 映射与 doc 的 question/groupId 一致、且本次答案查询实际读到管理员事务提交的新行。该结果可以在 ES 重建完成前出现（R2 §2.3 的两条独立时间线）；反过来，只看到一个 ES 文档或任务表快照是新的，也不能证明整次重建全部完成。依据：`RagService#updateAnswer/#authoritative`、R2-R1/R2-R4。
</details>

<details>
<summary>6. SupportAgent 转工单时，`TicketTool#createTicket` 的 userId 从哪里来？给出两个证据说明"模型无法用工具参数冒充别人"。</summary>

userId 来自 `toolContext.getContext().get(ToolContextKeyConstants.USER_ID)`，这个 map 由 `SupportAgent#runLoopStep` 构造（值来自 `invoke(sink, chatId, userId, ...)` 的调用链），源头是 `ChatController` 里 `UserTokenContext.getUserIdInString()`——即登录身份。证据一：`ToolContext` 不在模型的输入消息里，模型看到的只有工具 schema 与历史，工具参数只能影响 `executeTool` 解析出的 `rootNode`（title/content/orderId/priority/type），`userId` 不在其中；证据二：`TicketTool#createTicket` 固定 `userType=1`、`submitTicket` 的归属校验独立复核——带 orderId 时按 userType 比对 `order.userId`（403 拒绝）；HTTP 入口 `POST /ticket/submit` 则由服务端覆盖 userId 并按 `hasAnyRole("DRIVER")` 决定 userType=2/1，两条路都不信任客户端/模型提供的身份字段。注意工具回执是否转述、辅助存储是否落库都是后续条件，不是"模型无法冒充"的证据链的一部分。依据：`SupportAgent#runLoopStep`、`TicketTool#createTicket`、`TicketServiceImpl#submitTicket`、`TicketController#submit`、S1 §2.3。
</details>

## 七、导航与源码入口

- **本章主讲源码**（相对 `src/main/java/com/fancy/rideagenthub/`，只汇总不重复讲解）：
  - 旅程一：`controller/AuthController.java`、`service/impl/AuthServiceImpl.java`、`service/base/EmailCodeService.java`、`service/impl/TokenServiceImpl.java`、`interceptor/AuthTokenInterceptor.java`、`aspect/PermissionAspect.java`、`security/UserTokenContext.java`、`config/WebMvcConfig.java`、`config/AuthProperties.java`、`exception/GlobalExceptionHandler.java`；
  - 旅程二：`controller/ChatController.java`、`service/base/ChatRequestRunner.java`、`agents/AgentStreams.java`、`service/impl/ChatServiceImpl.java`、`agents/OrderAgent.java`、`agentbase/tool/OrderTool.java`、`service/base/OrderConfirmation.java`、`controller/RideOrderController.java`、`service/impl/RideOrderServiceImpl.java`（`createOrder`/`driverAcceptOrder`/`cancelOrder`/`payOrder`/`handleTimeoutOrder`）、`service/base/OrderGrabService.java`、`service/base/OrderDelayQueue.java`、`service/base/OrderTimeoutJob.java`、`service/impl/DriverLocationServiceImpl.java`、`service/base/DriverGeoIndex.java`、`config/PaymentConfig.java`、`config/RideOrderTimeoutProperties.java`、`agentbase/chatinfo/ChatManager.java`、`constant/RedisKeyConstants.java`；
  - 旅程三：`agents/SupportAgent.java`、`agentbase/tool/RagTool.java`、`agentbase/rag/RagService.java`（`searchAnswers`/`authoritative`/`updateAnswer`/`updateQuestion`）、`agentbase/rag/QaIndexSynchronizer.java`、`controller/RagController.java`、`agentbase/tool/TicketTool.java`、`controller/TicketController.java`、`service/impl/TicketServiceImpl.java`（`submitTicket`/`generateTicketId`）。
- **上一章**：[第 22 章 RAG 索引同步与检索预算](../05-rag/02-index-sync-and-budget.md)（R2）——旅程三 3.5 的交错窗口由它的调度与重建机制解释。
- **下一章**：[第 24 章 四层实验与清理](02-isolated-labs.md)（P2，已通过复审）——本章的练习都是只读追踪，下一章给出按层选择的验证卡。
- **相关专题**：[第 1 章 业务地图](../00-foundations/01-project-map.md)（三条旅程的鸟瞰版）、[第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)、[第 18 章 订单确认、恢复与消费](../04-chat-and-agent/03-order-confirmation.md)、[第 12 章 工单生命周期与权限](../03-support-and-cache/01-ticket-lifecycle.md)。

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置、SQL、测试与 API 文档写完整仓库相对路径。定位优先用"类 + 方法"，行号会漂移。

**状态：已通过复审（P7-1，2026-11-20），2026-11-22 独立复核。** 见 [最终检查记录](../_meta/review-2026-11-22.md)。
