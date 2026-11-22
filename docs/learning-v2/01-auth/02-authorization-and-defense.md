# 第 6 章 权限、归属与防护：谁能做什么，能对哪条数据做

> A1 解决了"身份从哪来"：服务端从 Redis 里的 token 解析出用户、角色与会话版本。本章解决下一层问题：**这个身份能进入哪段代码？进入后能读写哪条数据？被拒绝时，客户端看到的是什么？**
>
> 本章继续使用虚构人物：乘客林小雨（`USER`，userId 占位 `9001001`）、司机陈师傅（`DRIVER`，`7002002`）、客服苏晴（`SUPPORT`，`6003003`）、管理员老周（`ADMIN`，`5004004`）。订单号、工单号、请求与响应均为虚构占位；**没有发送任何真实请求**，状态码与响应体是源码静态推导，未实测。

## 学习目标

读完本章，你应该能：

1. 区分**身份认证**（你是谁）、**角色授权**（这类角色能不能做）与**资源归属**（这条数据是不是你的 / 是不是分配给当前人的），并说明三层分别由哪个类、哪个方法检查。
2. 对至少 8 个真实端点判断：请求能不能进入 Controller 方法体；若进入，是切面、Controller 辅助检查还是 Service 业务检查把它拒掉；拒绝可能是 401、403、400、404、409 中的哪一个。
3. 跟踪两套限流的触发位置、key、窗口、额度、返回值和 Redis 故障策略，解释"IP 限流 429"与"聊天限流发一条 notify 事件"为什么不是一回事。
4. 说明 `@RequirePermission` 的覆盖范围和边界：它只作用于带注解的 Spring Bean 方法；Service 方法、工具回调、自调用和后台线程不会自动继承它，提示词不是鉴权。
5. 区分同步异常、`Flux.defer` 中的 `Flux.error`、流内 `AgentEvent.error`、`sink.tryEmitError` 以及响应已提交后的观察边界，不承诺所有拒绝都能变成整齐的 HTTP 状态。

## 前置知识与阅读时间

- 必读：[第 5 章 登录与会话](01-login-and-session.md)——token 的提取、Redis 会话版本、MySQL 凭据指纹、`UserTokenContext` 的基本用法。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——条件更新与事务边界。
- 推荐：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)（Lua、SSE、`Flux` 终态）。
- 不需要：Spring Security 的过滤器链知识。本项目没有引入 Security 框架，认证/授权由拦截器、ThreadLocal 与自定义切面完成。
- 阅读时间：约 70～90 分钟（含两组练习与自测）。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**："仓库实现"来自当前源码；"测试阅读"表示读过测试声明与断言但未运行；"未实测"表示没有启动服务、发 HTTP 请求或连接中间件。

---

## 零、三个案例先看结果

| 案例 | 真实端点 | 谁会拒绝 | 预期结果（静态推导，未实测） |
|---|---|---|---|
| 乘客林小雨查看自己的订单 | `GET /order/{orderId}` | 无人拒绝；Service `assertCanViewOrder` 走 `default`（USER）分支，`order.userId == 当前 userId` | 进入方法体，200 + 订单详情 |
| 司机陈师傅查看林小雨的订单 | `GET /order/{orderId}` | Controller 的 `@RequirePermission` 只要求登录，**放行**；Service `assertCanViewOrder` 的 `DRIVER` 分支发现 `order.driverId != 当前司机` | 进入方法体后被拒：`BusinessException(403)` → HTTP 403 |
| 客服苏晴认领工单时指定别人为处理人 | `POST /ticket/admin/assign/{ticketId}?handlerId=7002002` | 切面只检查 `ADMIN/SUPPORT`，**放行**；Service `assignTicket` 发现当前角色是 `SUPPORT` 且目标不是自己 | 进入方法体后被拒：`IllegalArgumentException` → HTTP 400 |

这三行已经说明本章的主线：**"有注解"不等于"方法一定安全"，"角色对"不等于"这条数据归你"，"业务拒绝"也不一定映射成 403。**

---

## 一、身份、角色、归属不是一件事

### 1.1 四个概念与四层检查

| 概念 | 回答的问题 | 本项目的主要落点 | 失败通常意味着 |
|---|---|---|---|
| 身份认证 | 这个请求是谁发的？token 是否有效？ | `AuthTokenInterceptor` + `TokenServiceImpl#getToken`（A1 已讲） | 401 / 未登录 |
| 角色授权 | 这个角色能不能调用这个操作？ | `PermissionAspect` + `@RequirePermission` | 401（无身份）或 403（角色不符） |
| 资源归属 | 这条订单/工单/会话是不是当前用户或当前处理人的？ | 各 Service 的 `assertCanViewOrder`、`assertOwned`、`owner` 比较等 | 403/404/400/410，取决于实现 |
| 数据范围 | 列表/分页能看见谁的数据？ | 查询条件按 `userId`、`driverId` 加 `WHERE` | 通常不是异常，而是"少看到一些行" |

身份是"谁"，角色是"哪一类人"，归属是"哪一条数据"。三者不能互相替代：

- 有身份、角色也对，不代表能看任意一条订单——司机只能看分配给自己的订单。
- 角色不对，方法体根本进不去——司机调用 `@RequirePermission({"USER"})` 的用户定位接口会在切面被拒。
- 角色检查通过但归属不对，方法体已进入，可能已经读了一部分数据——工单详情就是先查再在 Controller 里判断。

### 1.2 从请求头到业务方法：真实顺序

以 `GET /order/2026111400000001` 为例（Spring Framework 6.2.15 的 `DispatcherServlet#doDispatch` 顺序）：

```text
请求头 Authorization: Bearer <虚构 token>
  → getHandler：按路径选择 RideOrderController#detail，并得到 HandlerExecutionChain
       （处理器 + 该请求适用的拦截器链；没有 handler 就没有拦截器）
  → getHandlerAdapter：选择支持该 handler 的适配器
  → applyPreHandle（正序）：
       RequestRateLimitInterceptor?  只在 /auth/** 与 /order/estimate 上；订单详情不经过
       AuthTokenInterceptor（所有路径，除 /error、/favicon.ico）
           从 Authorization: Bearer 或 X-Auth-Token 提取 token
           tokenService.getToken(token)
           有效且未过期 → UserTokenContext.set(UserToken)
           无 token / 无效 → 不设置，但正常 return true
  → HandlerAdapter 解析参数（path variable、@RequestBody 等）
       参数绑定/类型转换/校验失败 → 直接抛给 HandlerExceptionResolver，
       Controller 方法和 PermissionAspect 都不会执行
  → 通过 AOP 代理调用 Controller 方法：PermissionAspect
       UserTokenContext.getNullable()
       null → UnauthorizedException(401 "请先登录")
       requiredRoles 为空 → 只要求登录，直接放行
  → Controller 方法体：rideOrderService.getOrderDetail(orderId)
       Service 再查订单，按当前角色与订单的 userId/driverId 判断归属
  → 返回值处理 / postHandle → processDispatchResult（异常解析、render）
  → afterCompletion 清理 ThreadLocal
```

关键补充：**参数解析发生在切面之前**。对一个 `POST` 端点，即使没有 token，只要 JSON 无法反序列化或缺少必填参数，客户端也可能先看到 400，而不是切面的 401/403。`GET /order/{id}` 没有请求体，主要参数是路径变量，通常能走到切面；`GET /ticket/detail/{id}` 同理。`POST /order/{id}/pay`、`/cancel`、`/ticket/submit` 等带 `@RequestBody` 的端点，讨论"401 还是 403"前要先满足"请求体可解析、必填字段可绑定"。

对应源码：

```java
// aspect/PermissionAspect.java（真实节选）
@Around("@annotation(com.fancy.rideagenthub.annotation.RequirePermission)")
public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    RequirePermission annotation = signature.getMethod().getAnnotation(RequirePermission.class);

    UserToken userToken = UserTokenContext.getNullable();
    if (userToken == null) {
        throw new UnauthorizedException("请先登录");
    }

    String[] requiredRoles = annotation.value();
    if (requiredRoles.length > 0) {
        String currentRole = userToken.getRole();
        boolean hasPermission = currentRole != null && Arrays.stream(requiredRoles)
                .map(String::toUpperCase)
                .anyMatch(role -> role.equals(currentRole.toUpperCase()));
        if (!hasPermission) {
            throw new ForbiddenException("无权访问该资源");
        }
    }
    return joinPoint.proceed();
}
```

```java
// config/WebMvcConfig.java#addInterceptors（真实节选）
registry.addInterceptor(rateLimiter).addPathPatterns("/auth/**", "/order/estimate");
registry.addInterceptor(authTokenInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns("/error", "/favicon.ico");
```

注意三个事实：

- **两个拦截器的顺序由注册顺序决定**：IP 限流先于 token 解析。对 `/order/estimate`，一个没有 token 的请求也可能先拿到 429；对 `/auth/**`，公开端点也会被 IP 限流。
- `AuthTokenInterceptor` 的 `return true` 是正常路径。`getToken` 抛异常（如 Redis 不可用）时不会走到它，异常交给框架异常路径（静态推导 500，未实测）。
- `PermissionAspect` **只读 ThreadLocal**，不查 MySQL/Redis，也不读请求体里的角色字段。它看到的角色来自 A1 的 token 校验链。

### 1.3 公开、仅登录、角色受限、业务自主检查

按当前控制器源码统计：7 个 Controller、74 个 HTTP mapping，其中 67 个带 `@RequirePermission`；`AuthController` 的 8 个 mapping 中只有 `DELETE /auth/account` 带注解。这个统计只说明入口覆盖，不说明方法内部是否还有归属检查。

| 类型 | 例子 | 谁检查 | 拒绝形态 |
|---|---|---|---|
| 完全公开 | `POST /auth/email-code`、`/auth/register`、`/auth/login/password`、`/auth/login/email-code`、`/auth/password/reset`、`GET /auth/username/available`、`POST /auth/logout` | 无注解；仅参数/业务校验 | 400/401/404/429 等 |
| 仅需登录 | `POST /order/estimate`、`GET /order/{id}`、`POST /order/page`、`GET /chat/v2/restore`、`POST /ticket/submit` | `@RequirePermission`（value 为空）+ 部分 Service 检查 | 401；通过后在 Service 里可能 403/400/404 |
| 角色受限 | `POST /order/driver/accept`（DRIVER）、`GET /user/loc`（USER）、`POST /user/admin/create`（ADMIN）、`POST /ticket/admin/reassign/{id}`（ADMIN） | 切面角色检查 | 403 |
| 业务自主检查 | `GET /ticket/detail/{id}` 的 `assertTicketReadable`；`GET /order/{id}` 的 `assertCanViewOrder`；`ChatRequestRunner` 的 `chats.assertOwned` | Controller/Service 自己 | 403/400/404/410 |
| 完全依赖调用方 | Service 方法本身（没有注解） | 谁调用谁负责 | 无统一保证 |

最后一行很重要：`@RequirePermission` **不在 Service 层**。`TicketServiceImpl#getTicketDetail` 只负责查详情；HTTP 路径由 `TicketController` 在查完后调用 `assertTicketReadable` 判断。工具路径 `TicketTool#queryTicketProgress` 也自己检查归属，但它是"先查再判断字符串"，不是 HTTP 403。

### 1.4 ThreadLocal、后台线程与自调用

- `UserTokenContext` 是 `ThreadLocal`，只在当前 Tomcat 请求线程有效。`AuthTokenInterceptor#afterCompletion` 清理，防止线程池复用把上一个用户身份泄漏给下一个请求。
- `ChatController` 在 **Tomcat 线程**里调用 `UserTokenContext.getUserIdInString()`，把 userId 作为 `String` 传给 `ChatRequestRunner`。后台 `chatExecutor` 线程没有继承 ThreadLocal；它使用的是显式传参。`ChatServiceImpl` 不会在后台重新读 `UserTokenContext`。
- `@RequirePermission` 的切点是"注解方法"，因此：
  - Service 方法没有注解，直接被工具或其它 Service 调用时**不经过切面**；
  - 同一个 Bean 内部的自调用不会经过代理，注解不会再次生效；
  - 本仓库没有发现 Controller 方法互相自调用，但这是机制边界，不是"全项目自动保护"的承诺。
- 提示词、工具描述和模型输出都不是身份来源。`OrderTool#createOrder` 从 `ToolContext` 取 userId，`OrderSearchTool` 用 `ToolContext` 的 userId 加 `UserRole.USER` 调 Service；文本里的"我是管理员"不会改变这些值。

---

## 二、真实端点对照

### 2.1 订单与对话

**表中结果的默认前提**：token 有效；对 `POST` 端点，请求体可解析、必填字段可绑定（否则参数解析阶段可能先 400，切面尚未执行）；路径/查询参数合法；Redis 可用；资源存在。行内再标状态与归属条件；不满足前提时，可能先走 400/404/409/429，而不是表中的 403。

| 端点 | 注解要求 | 谁检查 / 拒绝点 | 可达结果（静态推导，未实测） |
|---|---|---|---|
| `POST /order/estimate` | 仅登录 | 先过 IP 限流；进入方法后调用计价服务 | 请求体合法且未超配额：无 token 401；超 IP 配额 429；正常 200；请求体坏可能 400 先于切面 |
| `GET /order/{orderId}` | 仅登录 | Service `getRequiredOrder`（缺 404）+ `assertCanViewOrder` | 订单存在：USER 非本人 403；DRIVER 非本单 403；ADMIN/SUPPORT 全部可看；订单不存在 404；token 无效时切面先 401 |
| `POST /order/page` | 仅登录 | Service `getOrderHistoryPage` 按角色加查询条件 | USER 只看 `user_id=当前`；DRIVER 只看 `driver_id=当前`；ADMIN/SUPPORT 不加限制；不是逐行 403 |
| `POST /order/{orderId}/pay` | 仅登录 | Service `payOrder`：参数非空 → 订单存在 → 本人 → 状态 `FINISHED_WAIT_PAY` → 支付核验 | 请求体/支付参数非法 400；订单不存在 404；非本人 403（在状态检查前）；本人状态不对 409；核验未配置 503；条件更新 0 行返回 200+false |
| `POST /order/{orderId}/cancel` | 仅登录 | Controller 根据当前角色计算 role；请求的 `cancelRole` 非空时校验一致，再将计算出的 role 传给 Service → 输入校验 → 抢 `order:lock`（最多 3 秒）→ 订单存在 → 状态 → 归属 → 条件更新 | 请求 `cancelRole` 缺失时使用当前角色推导值；为 3 或与角色不符 403；orderId/operatorId 空 400；锁等待超时 409；订单不存在 404；已支付/已取消/结算阶段 400（先于归属）；状态可取消但非本人/非本单 403；条件更新 0 行 409 |
| `POST /order/driver/accept` | 仅 DRIVER | 切面角色；Service 抢单事务（订单状态、司机行锁、Redis 占位、条件更新） | 非 DRIVER 403；已被抢/状态不对 409；司机忙等业务错误 |
| `GET /order/driver/current` | 仅 DRIVER | Service 按当前 token userId 查"司机当前订单" | 非 DRIVER 403；没有当前订单时返回空/业务空结果 |
| `POST /chat/v2/c/{id}` | 仅登录 | 切面登录；`ChatRequestRunner` 在 `Flux.defer` 内 `chats.assertOwned`；后台还会再检查一次 | 无 token 401；非法 UUID 400（登录通过后，方法体内同步异常）；他人的已存在会话在订阅阶段 `Flux.error`（未提交时**可能** 403，已提交则不能保证）；新会话允许；`resume` 缺失会话同理为 `Flux.error`/可能 404 |
| `GET /chat/v2/restore` | 仅登录 | `ChatManager` 按当前 userId 查最近可恢复会话 | 无 token 401；没有可恢复会话时返回 false/空结果，不是 403 |

### 2.2 工单、用户与知识库

**同一组前提**：token 有效；`POST` 请求体可解析；工单/账号等资源存在；状态满足方法要求。否则 400/403/409 的先后会改变。

| 端点 | 注解要求 | 谁检查 / 拒绝点 | 可达结果（静态推导，未实测） |
|---|---|---|---|
| `POST /ticket/submit` | 仅登录 | Controller 用 token 覆盖 `userId/userType`；Service 若带 `orderId` 校验订单归属 | 关联他人订单 403；参数不合法 400 |
| `GET /ticket/detail/{ticketId}` | 仅登录 | Service 查详情；Controller `assertTicketReadable`：ADMIN/SUPPORT 放行，否则 `detail.userId` 必须等于当前 userId | 工单存在且非本人 403；工单不存在 `IllegalArgumentException` → 400（不是 404） |
| `POST /ticket/cancel/{ticketId}` | 仅登录 | Service `cancelTicket`：先查工单 → 归属 → 状态 `CLOSED` | 工单存在且非本人 → `IllegalArgumentException` 400（不是 403）；本人已关闭 400；工单不存在 400 |
| `POST /ticket/my/append` | 仅登录 | Service `appendUserMessage`：先查工单 → 归属 → 未完结 | 工单存在且非本人 400；本人已完结 400；工单不存在 400 |
| `POST /ticket/my/escalate` | 仅登录 | Service `escalateTicket`：归属 → 状态 → 目标级别 → 当前优先级 | 非本人 400；已完结 400；非法级别/已达标 400；工单不存在 400 |
| `POST /ticket/admin/assign/{ticketId}` | ADMIN/SUPPORT | Service `assignTicket`：工单存在 → 状态必须待分配 → SUPPORT 只能把 `handlerId` 设为自己；ADMIN 可指定任何 handlerId | 请求体无关，参数为 query/path；非待分配先 400；SUPPORT 指定他人 400；ADMIN 无目标角色校验，成功时返回 true |
| `POST /ticket/admin/reassign/{ticketId}` | ADMIN | Service `reassignTicket`：工单存在 → 状态不能已完成/已关闭 → 不能分配给当前处理人 | 非 ADMIN 403；目标等于当前处理人 400；目标用户是否存在/是否为客服，源码未校验 |
| `POST /ticket/admin/process`（及 `/reply`） | ADMIN/SUPPORT | Service `processTicket`：工单存在 → handlerId 非空且等于当前 operatorId → 状态处理中/待确认 | 未分配 400；非本单处理人 400；状态不对 409 |
| `GET /user/loc` | 仅 USER | 切面角色检查 | DRIVER/ADMIN/SUPPORT 一律 403，方法体不进入 |
| `POST /user/admin/create` | 仅 ADMIN | 切面角色；Service 校验用户名+角色重复、角色枚举 | 非 ADMIN 403（请求体可解析后）；重复 409；角色非法 400；ADMIN 可创建 ADMIN 账号 |
| `POST /user/rebuild`（布隆重建） | 仅 ADMIN | 切面角色检查 | 非 ADMIN 403；方法体不进入；成功返回重建数量 |
| `POST /rag/qa/page` 等 7 个 RAG 端点 | 仅 ADMIN | 切面角色 | 非 ADMIN 403 |

**不能从这张表推出"所有接口全局受保护"**：表内的"仅登录"是这些方法有 `@RequirePermission`；Service 方法、工具调用、内部任务不在切面覆盖内。具体 Service 是否检查归属，要逐方法看，不能看类名猜。

### 2.3 角色空值、管理员/客服例外

- `PermissionAspect` 在 `requiredRoles.length > 0` 时要求 `currentRole != null` 且（大小写不敏感）匹配；token 的 role 为 null 会得到 403。`requiredRoles` 为空时只要求 token 非 null，role 为 null 也能过切面。
- Service 层 `UserRole.fromString(null/blank)` 返回 `USER`；非法角色字符串抛 `IllegalArgumentException`（400）。`assertCanViewOrder` 与 `getOrderHistoryPage` 都把 null 角色当 USER 处理，这是"默认最小权限"，不是"默认管理员"。
- `assertCanViewOrder` 的 `ADMIN, SUPPORT` 分支明确允许查看全部订单；`getOrderHistoryPage` 同样对 ADMIN/SUPPORT 不加用户/司机过滤。这是当前实现的例外，不表示所有后台接口都天然拥有该能力。
- `PermissionAspect` 只读 ThreadLocal；修改数据库里的角色不会改变一个**已通过切面、正在执行**的请求。后续请求的 `getToken` 会把 token 里保存的 role 与数据库当前 role 比较：匹配才返回原 token，**不会把数据库新角色写回旧 token**；不匹配则返回 null。后续公开端点仍可继续执行（拦截器只是不设置上下文，公开端点不依赖它）；受保护的 `@RequirePermission` 端点会因为没有身份而 401。要让“新角色”生效，通常需要重新登录获取新 token，而不是继续用旧 token。

---

## 三、三条正常路径

### 3.1 乘客查自己的订单

```text
林小雨 USER 9001001
GET /order/2026111400000001
Authorization: Bearer <虚构 token>
→ IP 限流不覆盖该路径
→ AuthTokenInterceptor 解析 token，set UserToken
→ 切面：@RequirePermission 无角色值，只要登录 → 放行
→ Service getOrderDetail：
      getRequiredOrder 查 MySQL（不存在 404）
      assertCanViewOrder：
          operatorRole = USER
          order.userId == 9001001 → 放行
      orderRouteService.getByTraceId(...) 读 Mongo 轨迹（归属检查之后）
→ 200 Result(data=RideOrderVO)
```

边界：`getOrderDetail` 先从 `UserTokenContext` 取 userId 和 role，再调用三参重载；Controller 不能传一个"想看的 userId"进来。`orderRouteService` 的 Mongo 读取发生在归属检查之后，归属失败不会读轨迹。

### 3.2 司机查自己的任务

`GET /order/driver/tasks?isFinished=false` 先由切面要求 DRIVER；Controller 取 token 里的 `driverId`，Service `getDriverOrderTasks(driverId, ...)` 按该 driverId 查。**请求参数没有 driverId**，司机不能通过改 query 看别人的任务列表。

`GET /order/driver/current` 同理，Service 用 token 的 driverId 查当前订单。`POST /order/driver/arrive`/`start`/`finish` 会在 Service 再比对 `order.driverId == 当前 driverId`，不是只靠"角色是 DRIVER"。

### 3.3 客服读工单

`GET /ticket/detail/{ticketId}` 只要求登录。Service 先查详情；Controller 的 `assertTicketReadable` 再判断：

```java
// controller/TicketController.java（真实节选）
private void assertTicketReadable(TicketDetailVO detail) {
    if (detail == null) {
        return;
    }
    if (UserTokenContext.hasAnyRole("ADMIN", "SUPPORT")) {
        return;
    }
    Long currentUserId = UserTokenContext.getUserIdInLong();
    if (detail.getUserId() == null || !String.valueOf(currentUserId).equals(detail.getUserId())) {
        throw new ForbiddenException("无权访问该工单");
    }
}
```

客服苏晴（SUPPORT）直接放行；乘客只能看 `detail.userId` 等于自己的工单；司机如果发起过工单（`userType=2`）也能看自己的。注意：**Service 的 `getTicketDetail` 自身没有归属检查**；如果另一个非 HTTP 调用方直接调用它，就必须自己补检查。

---

## 四、越权与失败案例

### 4.1 案例一：司机查看别人的订单

- **身份**：陈师傅，token role=DRIVER，userId=7002002。
- **输入**：`GET /order/2026111400000001`；该订单乘客 userId=9001001，driverId=7002002 以外的人（或 null）。
- **检查顺序**：
  1. 路径不在 IP 限流范围；token 由拦截器解析；
  2. `@RequirePermission` 无角色值 → 通过；
  3. Controller 进入 `detail`；
  4. `getRequiredOrder` 查到订单（不存在则 404）；
  5. `assertCanViewOrder` 的 DRIVER 分支：`order.driverId == null` 或 != 7002002 → `BusinessException(403, "无权限查看该订单")`。
- **状态**：只读；没有修改订单，也没有读 Mongo 轨迹（路由读取在判断之后）。
- **结果**：HTTP 403，`Result.code=403`。若订单不存在，HTTP 404；若 token 过期/无效，切面先给 401，方法体不进入。
- **边界**：ADMIN/SUPPORT 查看同一订单会**通过**（`assertCanViewOrder` 的例外）；这不是"所有人都不能看"，而是"角色能力不同"。

**同一个"他人订单"，取消时可能先 400 而不是 403。** `POST /order/{id}/cancel` 的真实顺序比详情长：

```text
t0 Controller：role = hasAnyRole("DRIVER") ? 2 : 1
   body.cancelRole 非空且 != role → ForbiddenException(403)
t1 Controller 把计算出的 role（1 或 2）传给 Service#cancelOrder，
   请求体 cancelRole 缺失时也使用该计算值。
   Service 独立检查收到的 cancelRole 不在 {1,2}（包括 null/3）→ BusinessException(403)；
   但这不是上述 HTTP 正常调用会传入的值。
   rawOrderId 空白 → IllegalArgumentException(400)
   operatorId 空白 → IllegalArgumentException(400)
t2 withOrderLock(orderId)：等待最多 3 秒
   中断 → BusinessException(409)；未获得 → BusinessException(409)
t3 cancelOrderUnderLock：
   getRequiredOrder → 不存在 BusinessException(404)
   status 为 null → BusinessException(400)
   status == PAID 或 CANCELLED → BusinessException(400)
   status >= FINISHED_WAIT_PAY → BusinessException(400)
t4 按 cancelRole 校验归属：
   乘客角色且 order.userId != operatorId → BusinessException(403)
   司机角色且 order.driverId != operatorId → BusinessException(403)
   归属通过后再看 USER_MAX_CANCEL/DRIVER_MIN_CANCEL → 否则 400
t5 条件 UPDATE（WHERE status=读到的 status）
   影响 0 行 → BusinessException(409)
```

因此：

- **他人订单 403 的条件**：token 有效、`cancelRole` 与当前角色一致且为 1（或司机场景为 2）、orderId/operatorId 非空、锁在 3 秒内拿到、订单存在、订单处于可取消状态（不是 PAID/CANCELLED，且未进入结算阶段）、归属不匹配。此时才在 t4 抛 403。
- **先 400 的反例**：同一笔他人订单如果已经 `PAID`、`CANCELLED` 或处于 `FINISHED_WAIT_PAY` 及之后，t3 先把状态拒绝成 400，根本不会走到 t4 的归属检查。订单不存在则先 404；锁竞争先 409；请求体的 `cancelRole` 为 3 或与角色不符则更早 403，缺失则采用 Controller 推导的角色。
- **并发窗口**：t5 的条件更新以 t3 读到的状态为条件；期间状态变化会让影响行数为 0，得到 409。t3 到 t4 之间订单归属字段被并发修改的情况，源码没有额外锁定字段，仍以这次读到的 `order` 对象判断。

### 4.2 案例二：客服指定他人认领工单

- **身份**：苏晴，SUPPORT，userId=6003003。
- **输入**：`POST /ticket/admin/assign/T20261114001?handlerId=7002002`（虚构工单，目标 handlerId 是司机陈师傅）。
- **检查顺序**：
  1. 切面要求 ADMIN/SUPPORT → 苏晴通过；
  2. Controller 计算 `targetHandlerId = handlerId != null ? handlerId : currentUserId`；
  3. Service `assignTicket` 用 `getTicketByTicketId` 查工单（`@Transactional`，当前事务内 `FOR UPDATE`）；
  4. 工单必须 `PENDING_ASSIGN`，否则 400；
  5. `SUPPORT` 且 `handlerId != 当前 userId` → `IllegalArgumentException("客服只能认领工单，不能分配工单")`；
  6. 不执行后续 update，返回失败。
- **结果**：HTTP 400，`Result.code=400`，不是 403。当前代码用 `IllegalArgumentException` 表达业务拒绝，`GlobalExceptionHandler` 统一映射 400。
- **管理员例外**：同样的请求换成 ADMIN 老周，切面通过，Service 不进入 SUPPORT 分支，**会把 handlerId 设为 7002002 并更新工单**。当前 `assignTicket` 没有校验目标存在、目标角色或目标是否可处理；`reassignTicket`（仅 ADMIN）同样没有目标角色校验。这不是"客服可随意分配"，但确实是管理员路径的剩余窗口。

### 4.3 案例三：乘客操作别人的工单，为什么有时 403、有时 400

- `GET /ticket/detail/T20261114001`：Controller 的 `assertTicketReadable` 抛 `ForbiddenException` → 403。
- `POST /ticket/cancel/T20261114001`：Service `cancelTicket` 的归属判断抛 `IllegalArgumentException("无权操作此工单")` → 400。
- `POST /ticket/my/append`、`/my/escalate`：同样是 Service 的 `IllegalArgumentException` → 400。
- 这说明 **HTTP 状态不是由"越权"这个词决定的，而是由抛出的异常类型决定的**。`ForbiddenException`/`BusinessException(403)` 才是 403；`IllegalArgumentException` 是 400；不要用消息文案反推状态码。

补充：工单不存在时 `getTicketByTicketId` 抛 `IllegalArgumentException("工单不存在")` → 400，不是 404；订单不存在则是 `BusinessException(404)`。同一类"找不到"在不同模块的映射不同，必须以异常类型为准。

### 4.4 在途请求、并发与非 HTTP 调用

- **在途请求**：切面在 Controller 方法前执行一次；已进入方法体的请求不会因为角色被修改而中途重新检查。订单/工单的 Service 归属判断发生在方法体内，是第二次检查；但通过后到写操作之间仍可能发生并发变更，靠状态条件更新或行锁兜底，不靠"运行中重新鉴权"。
- **并发变更**：订单支付/取消用状态条件更新，影响 0 行时支付返回 false（HTTP 200，data=false）或取消抛 409；工单的 `processTicket` 用 `getTicketByTicketId` 的 `FOR UPDATE` + 状态条件，`requireUpdated` 在 resolve/transfer/reject 分支把 0 行转成 409；`assignTicket` 在更新 0 行时只返回 false（HTTP 200），`cancelTicket` 也只返回 false，不抛 409。不同方法不一致，不能概括成"并发冲突统一 409"。
- **非 HTTP 调用**：`OrderSearchTool#searchOrderById` 把 `ToolContext` 的 userId 与 `UserRole.USER` 传给 `getOrderDetail(orderId, userId, UserRole.USER)`，所以 Service 会按 **USER/乘客语义**检查归属；它不是通用司机查询工具，工具调用者不会因为 token 是 DRIVER 就自动切换成司机视角。`TicketTool#queryTicketProgress` 先 `getTicketDetail(ticketId)` 再自己比较 `detail.userId`，越权时返回"您无权查看此工单。"，而不是 403。提示词不能替代 ToolContext 或 Service 检查。

对话归属失败还有一个**未验证的锁边界**：`ChatRequestRunner` 先抢 `chat:execution:{chatId}`，`chats.assertOwned` 抛异常后，catch 只在 `lock.isHeldByThread(owner)` 为真时调用 `unlockAsync(owner)`；`owner` 是传给 `tryLockAsync` 的同一个随机 long。源码能证明的只有"存在这个条件调用"，不能证明该判断一定成立、也不能证明异步解锁一定成功；本仓库没有运行 Redisson 或测试该路径。因此既不能断言锁已释放，也不能断言一定泄漏；后续同会话请求的实际结果未实测。

---

## 五、两套限流：同一条 Lua，不同的触发点与故障策略

### 5.1 IP 请求限流：`/auth/**` 与 `/order/estimate`

```java
// interceptor/RequestRateLimitInterceptor.java（真实节选）
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) return true;
    String key = "request:rate:" + CredentialStamp.of(request.getRemoteAddr());
    Long remaining = redis.execute(RedisScripts.SLIDING_WINDOW_RATE_LIMIT, List.of(key),
            "60000", "120", UUID.randomUUID().toString());
    if (remaining == null || remaining < 0) throw new BusinessException(429, "请求过于频繁，请稍后重试");
    return true;
}
```

- **触发位置**：`WebMvcConfig` 把 `RequestRateLimitInterceptor` 注册在 `/auth/**` 和 `/order/estimate`，并排在 token 拦截器之前。`/order/estimate` 没有 token 也会先经过它；`/auth/**` 的公开接口也受它限制。
- **key**：`request:rate:` + `CredentialStamp.of(request.getRemoteAddr())`。`CredentialStamp.of` 是 SHA-256 十六进制；代码本身不读取 `X-Forwarded-For`，仓库也没有发现 `ForwardedHeaderFilter`/`server.forward-headers-strategy` 配置。实际 `getRemoteAddr()` 是直连地址还是被容器/部署配置改写后的地址，取决于运行环境；不能无条件断言所有部署都会让客户端共享同一配额，但"按真实客户端 IP 限流"也没有被当前配置证明。
- **窗口/额度**：硬编码 `60000` 毫秒、`120` 次；不读 `ChatGuardProperties`。
- **返回**：`null` 或 `remaining < 0` → `BusinessException(429)`，通常由异常处理器变成 HTTP 429。`OPTIONS` 直接放行。
- **拒绝分支 TTL**：脚本 `used >= limit` 时直接 `return -1`，不执行 `ZADD`、不执行 `PEXPIRE`，所以拒绝请求不登记、不刷新 key TTL。
- **Redis 异常**：`preHandle` 没有 catch，异常向上传播，交给框架异常路径（静态推导 500，未实测）；不是"失败放行"。
- **注意**：IP 限流和角色/归属无关；它挡的是请求频率，不是身份。

### 5.2 聊天用户限流：`ChatServiceImpl#canStartChat`

```java
// service/impl/ChatServiceImpl.java#canStartChat（真实节选）
if (!chatRateLimiter.tryAcquire(userId)) {
    log.warn("用户对话频次超限，已拒绝: userId={}, chatId={}", userId, id);
    sink.tryEmitNext(AgentEvent.notify("消息发送过于频繁，请稍后重试。"));
    sink.tryEmitComplete();
    return false;
}
```

- **触发位置**：`ChatServiceImpl#chat` / `#resume` 的后台执行体里，`canStartChat` 方法内；此时 `ChatRequestRunner` 已经完成锁获取、归属检查并提交了后台任务。它不是 HTTP 拦截器，也不在 Controller 切面之前。
- **key**：`chat:ratelimit:{userId}`（`RedisKeyConstants.chatRateLimitKey`），按用户，不是按 IP。
- **窗口/额度**：`chat.guard.rate-limit-window-seconds`（默认 60 秒）与 `chat.guard.rate-limit-max`（默认 30 次）；`application.yaml` 当前也是 60/30。可通过配置覆盖；`windowMillis <= 0` 或 `rateLimitMax <= 0` 时直接放行。
- **member**：每次 `UUID.randomUUID()`，避免同一毫秒同 member 被 `ZADD` 去重。
- **返回/拒绝**：超限时不是 429，而是尝试 `sink.tryEmitNext(AgentEvent.notify("消息发送过于频繁，请稍后重试。"))`，随后尝试 `tryEmitComplete()`。`tryEmitNext` 的返回值只表示这次尝试是否被接受，不代表客户端已收到；如果响应已按 `text/event-stream` 提交，客户端可能看到流内事件，也可能因连接/写入状态看不到；具体线格式与提交时点未实测。
- **拒绝分支 TTL**：同样不执行 `ZADD`/`PEXPIRE`，不登记、不刷新窗口。
- **Redis 异常/`null`**：`ChatRateLimiter` 捕获异常并返回 true；`remaining == null` 也返回 true。它只让限流判定放行，不保证后续 Redisson 锁、`chat:info`、`MessageMemory` 的 Redis 依赖都可用。
- **锁先于限流**：Runner 先抢 `chat:execution:{chatId}`，再提交后台；因此超限用户仍然可能先经历抢锁/3 秒等待，限流拒绝不阻止锁获取。不能把聊天限流当成"请求入口的 429"。

### 5.3 对比表与故障策略

| 维度 | `RequestRateLimitInterceptor` | `ChatRateLimiter` |
|---|---|---|
| 触发层 | Servlet `HandlerInterceptor`，Controller 之前 | 后台 `ChatServiceImpl#canStartChat`，Controller 之后 |
| 覆盖路径 | `/auth/**`、`/order/estimate` | `POST /chat/v2/c/{id}`、`/chat/v2/r/{id}` 中真正进入 `chat/resume` 的调用 |
| key | `request:rate:{SHA-256(remoteAddr)}` | `chat:ratelimit:{userId}` |
| 窗口/额度 | 硬编码 60000ms / 120 | 默认 60s / 30，可由 `chat.guard.*` 覆盖 |
| 超限返回 | `BusinessException(429)` → HTTP 429 | `AgentEvent.notify` + 完成流，不是 429 |
| Redis `null` | 429 | 放行 |
| Redis 异常 | 未捕获，向上传播（静态推导 500） | catch 后放行 |
| 拒绝分支 TTL | 不 `ZADD`/不 `PEXPIRE`，不刷新 | 同左 |
| 反向代理 | 依赖 `getRemoteAddr()`，未配置转发头处理 | 不依赖 IP，按 userId |
| 身份来源 | 请求 IP | token 中的 userId；userId 为空直接放行 |

**不要把两者混为一谈**：IP 限流的 `null` 与超限都走 429，Redis 异常则向上传播；聊天限流的 `null`/异常都放行。一个是同步 HTTP 429，一个是尝试发流内 notify；一个按 IP，一个按用户。它们共用 `SLIDING_WINDOW_RATE_LIMIT` 的"清理 → 计数 → 超限不登记 → 放行才登记并 PEXPIRE"顺序，但业务语义与故障策略不同。

**聊天限流不是"每个聊天 HTTP 请求都计数"**：`ChatServiceImpl#chat` 在进入 `canStartChat` 前会先 `isLocked`；`ChatRequestRunner` 的锁竞争、归属失败会在后台任务提交前结束；`canStartChat` 还会先拒空 prompt。只有真正走到 `ChatRateLimiter#tryAcquire` 的调用才可能登记，因此"第 31 次 HTTP 请求"与"第 31 次限流登记"不是同一个计数。

---

## 六、异常、HTTP 状态与流式边界

### 6.1 映射表

`GlobalExceptionHandler#response` 的规则：`code` 在 400～599 时作为 HTTP 状态，否则压成 400；`Result.code` 保留原始 code。

| 异常/来源 | HTTP | `Result.code` | 典型场景 |
|---|---|---|---|
| `UnauthorizedException` | 401 | 401 | 切面无 token；`getRequired()` 无身份 |
| `ForbiddenException` | 403 | 403 | 切面角色不符；`ChatInfoService.assertOwned` 他人会话；`TicketController.assertTicketReadable` |
| `BusinessException(403)` | 403 | 403 | 订单详情/支付/取消的归属失败 |
| `BusinessException(404)` | 404 | 404 | 订单不存在、`resume` 会话不存在、可恢复会话为空 |
| `BusinessException(409)` | 409 | 409 | 订单/工单状态冲突、抢单已被抢 |
| `BusinessException(410)` | 410 | 410 | 会话已清空 |
| `IllegalArgumentException` | 400 | 400 | 工单归属/不存在、客服越权分配、非法参数 |
| `DuplicateKeyException` | 409 | 409 | 唯一键冲突 |
| 其它未列异常 | 500 | 500 | 兜底 |

因此："司机查别人订单"是 403（`BusinessException(403)`），"乘客取消他人工单"是 400（`IllegalArgumentException`），"工单不存在"是 400，不是 404。消息文案不能替代异常类型。

### 6.2 同步、Flux.error、SSE error 事件、已提交响应

| 层 | 例子 | 能确定什么 | 不能保证什么 |
|---|---|---|---|
| 参数绑定/校验（`preHandle` 之后、切面之前） | `@RequestBody` 反序列化失败、类型转换失败、`@Valid` 失败 | 处理器已选、拦截器 `preHandle` 已执行；参数解析失败直接交给 `HandlerExceptionResolver` | Controller 方法和 `PermissionAspect` 都没执行；此时可能先 400，不是切面的 401/403 |
| 切面（Controller 方法调用前） | `@RequirePermission` 无 token/角色不符 | 参数已解析；切面抛 401/403，响应未提交时由 advice 映射 | 方法体尚未执行；若参数解析已失败，切面不会出现 |
| Controller 方法体内同步异常 | `UUID.fromString` 非法、业务参数校验 | 方法已进入，`PermissionAspect` 已通过；异常仍可走同步异常映射 | 不是“方法前”异常；若异常前未提交响应，才可能改变状态 |
| `Flux.defer` supplier 内的 `Flux.error` | `ChatRequestRunner` 的 `chats.assertOwned` 抛 `ForbiddenException`/`BusinessException(404)` | 外层订阅时收到 Publisher 错误；切面和 Controller 方法已进入 | 若响应头已提交，不能再改 HTTP 状态；是否变成 SSE 错误行未实测 |
| `sink.tryEmitNext` / `tryEmitComplete` | 聊天限流拒绝、普通消息 | `tryEmitNext` 只是尝试发出一个元素并返回结果；`tryEmitComplete` 只是尝试完成 | 不保证客户端收到事件、不保证 HTTP 已提交、不保证帧已写出 |
| 流内 `AgentEvent.error` | 空 prompt、模型错误、`guard` 兜底 | 是响应体里的普通数据元素 | 不保证送达；不是 HTTP 状态 |
| `sink.tryEmitError` | `AgentStreams` 错误回调、Runner `executor.execute` 失败 | 若被接受并到达上游，错误终止可能触发 `doFinally` | 不保证客户端观察到特定错误对象；`doFinally` 的 `unlockAsync` 只是异步发起，不保证成功 |
| 响应已提交 | SSE 已写出首个事件 | 只能通过流内事件/终态/连接关闭表达后续失败 | 不能改成 403/500；具体字节未抓包 |

对本章的结论：**切面 401/403 发生在参数解析之后、Controller 方法调用之前；参数绑定失败更早，`UUID.fromString` 在方法体内；`ChatRequestRunner` 的归属失败在 `Flux.defer` 订阅阶段；ChatRateLimiter 的拒绝只是尝试发 notify。** 不能用一个"客户端会看到 403"或"客户端已收到 notify"概括所有路径。F4 的"流内 error、错误终态、HTTP 状态、响应是否已提交"分层在这里继续适用。

---

## 七、用户名布隆过滤器：存在，但不在当前判定主链

- key：`auth:user:username:bloom`（`RedisKeyConstants.USER_USERNAME_BLOOM_KEY`），Redisson `RBloomFilter`，`tryInit(1_000_000, 0.01)`。
- 写入：`AuthServiceImpl#registerByEmailCode` 在 `@Transactional` 方法体内、`createUser` 之后调用 `addUsername`——**MySQL 未提交时 Redis 已写入**；若事务回滚，布隆里的用户名不会自动删除。`AuthServiceImpl#createAccount` 没有 `@Transactional`，它在 `createUser` 之后调用 `addUsername`（仅当创建 `USER` 角色）；没有统一事务把它和 MySQL 插入包在一起，插入与布隆写入是两个独立步骤。
- 重建：`POST /user/rebuild`（`UserController` 类级 `@RequestMapping("/user")` + 方法级 `@PostMapping("/rebuild")`，仅 ADMIN）调用 `rebuild()`：`delete()` → `tryInit` → `selectUsernamesForBloom()`（SQL 只选 `role='USER'`、`status=1`、`is_deleted=0`）→ 逐个 `add`，返回去重后的数量。
- 判定：`mightContain(username)` 在当前 `src/main/java` 中**没有调用方**；`isUsernameAvailable` 走 `UserAuthServiceImpl#findByUsernameAndRole` 数据库查询，注册重复检查也走数据库/唯一键。布隆**不在当前注册可用性或登录判定主链**。
- 因此：
  - 它当前不提供任何"挡住用户名穿透"的实际保护；旧文的定位是设计线索，不是现状。
  - 若未来把 `mightContain` 接到判定路径，必须接受布隆的假阳性与"注册事务提交前写入/回滚残留"；`mightContain == false` 才是"可能不存在"，不能反过来当存在性证明。
  - `rebuild` 与并发注册/回滚之间没有协调；重建期间的 `delete/reinit` 可能丢掉并发新增。仓库没有 `mightContain` 调用，也没有测试覆盖这些交错（详见 evidence）。
- 缓存穿透的完整机制留给 S3，本章只确认"它存在、谁写、谁重建、当前不在主链"。

---

## 八、设计边界与未验证

**源码可证（有条件）**：

- 切面只对带 `@RequirePermission` 的 Spring Bean 方法生效；无 token 401，角色不匹配 403；空角色值只要求登录。
- 订单详情/分页/支付/取消、工单详情/取消/追加/升级/分配/处理、聊天会话归属分别有 Service 或 Controller 检查；不同方法抛不同异常，HTTP 状态因此不同。
- IP 限流 429 与聊天限流 notify 是两套独立策略；共用 Lua 的"拒绝不登记、不刷新 TTL"分支。
- 布隆写入发生在注册事务提交前；`mightContain` 当前没有主源码调用方；重建仅 ADMIN。

**有前提或存在窗口**：

- `PermissionAspect` 不查 DB/Redis；它在请求开始时读取 ThreadLocal 的角色。已进入方法体的请求不会中途重新鉴权。
- 工单详情先查数据、再在 Controller 判归属；Service 直接调用者必须自己检查。`TicketTool` 是先查再字符串判断，不产生 403。
- `TicketServiceImpl#assignTicket` 对 ADMIN 不校验目标 handlerId 是否存在/是否客服；`reassignTicket` 同样。SUPPORT 的自我限制只覆盖"不能分配给别人"。
- 工单归属失败多为 400，订单多为 403；这不是统一语义。
- `RequestRateLimitInterceptor` 使用 `getRemoteAddr()`，没有转发头处理；反向代理后的真实客户端识别未验证。
- ChatRateLimiter 在锁之后执行；超限仍可能先抢锁；拒绝是流内 notify，不是 429。
- 流式响应的状态码/错误行取决于提交时点；未抓包、未用 MockMvc 验证。

**未验证**：真实 HTTP 响应头/响应体、反向代理下 IP 限流的实际 key、Redis 故障时切面/拦截器的最终状态码、两套限流在真实并发下的配额、ADMIN 分配不存在 handler 的后续表现、布隆重建与并发注册的交错、工单 Service 直接调用路径的真实异常传播。测试均为只读断言，**本轮未运行**。

---

## 九、动手验证

### 练习 A：给 8 个端点标"拒绝层"

目标：不启动服务，只读源码，为下面 8 个端点标注"能进入方法体 / 切面拦 / Controller 辅助检查拦 / Service 拦"，并写出预期状态码。

| # | 端点 | 角色 | 输入与前提 |
|---|---|---|---|
| 1 | `GET /order/2026111400000001` | DRIVER（非本单） | token 有效；订单存在，状态不限 |
| 2 | `GET /order/2026111400000001` | SUPPORT | token 有效；订单存在 |
| 3 | `POST /order/2026111400000001/pay` | USER（非本人） | token 有效；body 可解析且 payChannel/tradeNo 非空；订单存在；锁可用 |
| 4 | `POST /order/2026111400000001/cancel` | USER（非本人） | token 有效；body 可解析且 `cancelRole=1`；订单存在且状态可取消（不是 PAID/CANCELLED，且未到结算阶段）；锁在 3 秒内获得 |
| 5 | `POST /ticket/admin/assign/T20261114001?handlerId=7002002` | SUPPORT | token 有效；工单存在且状态为待分配；handlerId 非自己 |
| 6 | `GET /ticket/detail/T20261114001` | USER（非发起人） | token 有效；工单存在 |
| 7 | `POST /ticket/cancel/T20261114001` | USER（非发起人） | token 有效；工单存在（归属检查在状态检查之前） |
| 8 | `POST /chat/v2/c/{他人已存在会话}` | USER | token 有效；chatId 是合法 UUID；会话存在且属于别人；锁可用 |

<details>
<summary>练习 A 参考答案</summary>

1. 只经过登录切面，进入 Service；`assertCanViewOrder` 的 DRIVER 分支不匹配 → `BusinessException(403)` → 403。订单不存在则 404。
2. 切面只要求登录，进入 Service；`assertCanViewOrder` 的 ADMIN/SUPPORT 分支放行 → 200。说明"客服能看全部订单"是当前实现的例外。
3. 切面只要求登录，进入 Service；`payOrder` 先校验支付参数，再 `getRequiredOrder`，再比较 `order.userId`；在订单存在、参数合法、锁可获得的前提下，非本人 → `BusinessException(403)`（在状态检查之前）。订单不存在 404；body 坏可能 400；锁竞争 409；状态不对只在本人时 409。
4. 切面只要求登录；Controller 先算 `role=1`（非 DRIVER），与 body 的 `cancelRole=1` 一致；Service 再检查 `cancelRole∈{1,2}`、输入非空、抢锁，然后查订单/状态/归属。只有在订单存在、状态可取消、锁获得的条件下，`cancelOrderUnderLock` 的用户分支才发现 `order.userId != 当前` → 403。若订单已支付/已取消/进入结算阶段，会先在状态检查处得到 400；订单不存在 404；锁等待超时 409。
5. 切面 ADMIN/SUPPORT 通过，进入 Service；`assignTicket` 先查工单、要求 `PENDING_ASSIGN`，再判断 SUPPORT 的目标是否自己。只有工单存在且待分配时，才会因 `handlerId != 当前 userId` 抛 `IllegalArgumentException` → 400；工单不存在或非待分配会先 400。
6. 切面只要求登录，进入 Service 查详情；Controller `assertTicketReadable` 非 ADMIN/SUPPORT，`detail.userId != 当前` → `ForbiddenException` → 403。工单不存在会在 Service 先抛 `IllegalArgumentException` → 400。
7. 切面只要求登录，进入 Service；`cancelTicket` 先查工单，再比较 `ticket.userId.equals(userId)`；在工单存在的前提下，非本人 → `IllegalArgumentException` → 400。和第 6 题对比：同样是"别人的工单"，详情 403、取消 400。
8. 切面只要求登录，进入 `ChatRequestRunner`；Runner 先尝试锁，再在 `Flux.defer` 内 `chats.assertOwned(userId, chatId, true)`。在合法 UUID、会话存在且属于别人、锁可用的前提下，抛 `ForbiddenException` → `Flux.error`；若响应尚未提交，可能映射 403；若已提交，不能保证状态码（未实测）。若 chatId 不是合法 UUID，`UUID.fromString` 在 Controller 方法体内、`run` 返回 Flux 前同步抛 `IllegalArgumentException`，登录通过后是 400；这不是方法前的参数绑定/切面异常。
</details>

### 练习 B：两套限流的逐步观察

目标：只读源码，按顺序写出下面两条路径分别在什么时候、用什么 key、返回什么。**先固定前提**：路径 1 的同一 IP 限流窗口起初无记录，130 次都落在同一个 60 秒窗口内、每次都到达 `preHandle`、Redis 正常；路径 2 只统计真正进入 `canStartChat` 的调用，且同一窗口内已有 30 次成功登记。若前提不成立，次数结论不能照搬。

```text
路径 1：未登录客户端连续请求 POST /auth/email-code 130 次（虚构邮箱）
路径 2：已登录 USER 在 60 秒内发起 31 次 POST /chat/v2/c/{id}，
        但只有成功抢锁、归属通过、非锁定、prompt 非空的那几次才真正尝试限流
```

| 步骤 | 打开/搜索 | 预期观察 |
|---|---|---|
| 1 | `WebMvcConfig#addInterceptors` | 路径 1 先过 `RequestRateLimitInterceptor`；路径 2 不经过它 |
| 2 | `RequestRateLimitInterceptor#preHandle` | key=`request:rate:`+SHA-256(remoteAddr)，60000/120，null 或 -1 → 429 |
| 3 | `ChatRequestRunner#run` | 路径 2 先尝试 `chat:execution:{id}` 锁、再 `chats.assertOwned`；失败会提前结束，不进入后台 |
| 4 | `ChatServiceImpl#chat` 与 `#canStartChat` | `chat` 先 `isLocked`，再 `canStartChat`；`resume` 无 `isLocked`；`canStartChat` 先拒空 prompt，再调用限流 |
| 5 | `ChatRateLimiter#tryAcquire` | key=`chat:ratelimit:{userId}`，默认 60s/30，异常/null → true |
| 6 | `RedisScripts.SLIDING_WINDOW_RATE_LIMIT` | 两条路径共用清理/计数；超限 return -1 前不 ZADD/PEXPIRE |

<details>
<summary>练习 B 参考答案</summary>

路径 1：在"130 次同一窗口、每次到达 preHandle、Redis 正常"的前提下，前 120 次每次脚本计数后登记；第 121 次起 `used >= 120` 返回 -1，抛 `BusinessException(429)`。`null` 也走 429；Redis 异常没有 catch，向上传播（静态推导 500），不能当作"失败放行"。若 130 次跨越窗口，窗口外的旧 member 会被清掉，拒绝次数不能简单按 121 起算。key 的 IP 部分来自 `getRemoteAddr()`；其真实值是直连地址还是代理地址取决于容器/部署，仓库没有配置转发头处理，不能无条件断言所有部署共享同一配额。

路径 2：Runner 先抢 `chat:execution:{chatId}` 并提交后台；`ChatServiceImpl#chat` 先查 `isLocked`，再 `canStartChat`（先拒空 prompt，再调 `ChatRateLimiter`）；`resume` 没有 `isLocked`，但同样经过 `canStartChat`。只有抢锁成功、归属通过、非锁定、prompt 非空的调用才会让限流器执行 `tryAcquire`。若同一 60 秒窗口内已有 30 次成功登记，第 31 次**有效**调用返回 false；代码尝试 `sink.tryEmitNext(notify)` 和 `tryEmitComplete()`，但 `tryEmitNext` 只表示尝试发出、不保证客户端收到；如果 SSE 已提交，这通常表现为流内事件而不是 HTTP 429（未实测）。HTTP 上发了 31 次请求，不等于 31 次都登记；锁竞争、锁定会话、空 prompt、归属失败都会提前返回。Redis 异常/null 时 `tryAcquire` 返回 true，只越过限流，不保证后续锁/会话/记忆可用。
</details>

> 可选实验：只有在你拥有**一次性隔离 Redis** 且确认没有业务数据时，才可用虚构 key 观察 ZSet 的 score/TTL；不要对业务 Redis 执行 `KEYS`、`FLUSHALL` 或批量删除。本轮没有连接任何 Redis。

---

## 十、一页复习

- **三层**：认证（token/401）→ 角色（切面/403）→ 归属（Controller/Service/403、400、404、410）。有身份不等于有角色，有角色不等于拥有这条数据。
- **切面范围**：`@RequirePermission` 只在带注解的 Spring Bean 方法上生效；Service、工具回调、自调用和后台线程不自动继承；提示词不是鉴权。
- **顺序**：`getHandler`/`getHandlerAdapter` → `preHandle`（`/auth/**`、`/order/estimate` 先 IP 限流，再 token 解析）→ 参数解析/校验 → 切面登录/角色检查 → Controller 方法体。参数解析失败可能先 400，切面不会执行。
- **端点差异**：订单详情/支付多为 403；订单取消只有"订单存在、状态可取消、锁获得、身份不符"时才 403，已支付/已取消/结算阶段先 400；工单取消/追加/分配/处理多为 400；工单不存在 400，订单不存在 404；并发状态冲突可能是 409 或 `data=false`。
- **例外**：ADMIN/SUPPORT 可看全部订单；SUPPORT 只能认领自己，ADMIN 可分配任意 handlerId（未校验目标角色）；`GET /user/loc` 只允许 USER，DRIVER 403。
- **两套限流**：IP 限流 60000ms/120，key 为 remoteAddr 哈希，null/超限 429，异常向上；聊天限流默认 60s/30，key 为 userId，null/异常放行，拒绝是尝试发流内 notify；拒绝分支都不登记/不刷新 TTL。
- **流式边界**：切面 401/403 在参数解析之后、方法体之前；`Flux.defer` 内归属失败可能是流错误；`AgentEvent.error` 只是数据；`tryEmitNext`/`tryEmitComplete` 只是尝试；`tryEmitError` 是错误终态；`unlockAsync` 异步、不保证成功；响应已提交后不能改状态码。
- **布隆**：`auth:user:username:bloom` 存在；`registerByEmailCode` 在事务提交前写入，`createAccount` 无事务、独立写入；ADMIN 可 `POST /user/rebuild`，但 `mightContain` 当前没有主源码调用方，`isUsernameAvailable` 走数据库，布隆不在当前判定主链。

---

## 十一、自测与答案

**1. 身份、角色授权、资源归属各回答什么问题？各自由哪层检查？**

<details>
<summary>参考答案</summary>

身份认证回答"这个请求是谁"，由 `AuthTokenInterceptor` + `TokenServiceImpl#getToken` 完成（A1）。角色授权回答"这个角色能不能调用这个操作"，由 `PermissionAspect` + `@RequirePermission` 在参数解析之后、Controller 方法调用之前完成。资源归属回答"这条订单/工单/会话是不是当前用户或当前处理人的"，由 Controller 辅助方法（如 `assertTicketReadable`）或 Service（`assertCanViewOrder`、`assertOwned`、`cancelOrderUnderLock`）完成。身份/角色通过不代表归属通过；归属检查不通过时方法体可能已经进入并读过部分数据。
</details>

**2. `GET /order/{id}` 对 DRIVER 和 SUPPORT 分别会发生什么？**

<details>
<summary>参考答案</summary>

切面对两者都只要求登录（`@RequirePermission` 无角色值），所以都会进入 Controller/Service。`assertCanViewOrder` 对 DRIVER 要求 `order.driverId` 等于当前 userId，否则 `BusinessException(403)`；对 ADMIN/SUPPORT 直接放行，能看到任意订单。订单不存在时 `getRequiredOrder` 先抛 `BusinessException(404)`。这是当前实现的"客服可看全部订单"例外，不是所有后台接口的通用规则。
</details>

**3. 为什么 `GET /user/loc` 对 DRIVER 是 403，而不是 401？**

<details>
<summary>参考答案</summary>

DRIVER 有有效 token，`AuthTokenInterceptor` 能设置 `UserTokenContext`，身份认证通过；`@RequirePermission({"USER"})` 在参数解析之后、Controller 方法调用之前比较角色，DRIVER 不在允许列表，抛 `ForbiddenException` → 403。401 是"没有身份/身份无效"；403 是"身份有效但角色不允许"。切面在方法体前执行，所以方法体不会进入；但如果参数解析先失败，可能先得到 400。
</details>

**4. 为什么同一句"无权操作此工单"，详情接口给 403，取消接口给 400？**

<details>
<summary>参考答案</summary>

因为抛出的异常类型不同。`TicketController#assertTicketReadable` 抛 `ForbiddenException`（`BusinessException(403)`），映射 403；`TicketServiceImpl#cancelTicket` 抛 `IllegalArgumentException("无权操作此工单")`，`GlobalExceptionHandler` 映射 400。HTTP 状态由异常类型决定，不由消息文案决定。工单不存在同样是 `IllegalArgumentException` → 400，而订单不存在是 `BusinessException(404)`。
</details>

**5. `@RequirePermission` 能否保护 Service 方法？工具回调、后台线程和自调用呢？**

<details>
<summary>参考答案</summary>

不能自动保护。切点是 `@annotation(RequirePermission)`，只对带注解的 Spring Bean 方法生效；本仓库的注解只在 Controller 方法上。Service 方法没有注解，被工具、Agent 或其它 Service 直接调用时不经过切面；同一 Bean 内自调用不经过代理；后台线程不继承 ThreadLocal，`ChatServiceImpl` 使用显式传入的 userId。工具路径必须自己传/校验 userId（如 `OrderSearchTool` 传 `UserRole.USER`，`TicketTool` 先查再比字符串）。提示词不是鉴权。
</details>

**6. IP 限流和聊天限流的 key、额度、拒绝结果、Redis 故障策略分别是什么？**

<details>
<summary>参考答案</summary>

IP 限流：`RequestRateLimitInterceptor` 挂在 `/auth/**` 与 `/order/estimate`，key 为 `request:rate:` + SHA-256(`getRemoteAddr()`)，硬编码 60000ms/120；`null` 或 `-1` 抛 `BusinessException(429)`；Redis 异常未捕获，向上传播（静态推导 500）；不读转发头。聊天限流：`ChatRateLimiter` 在后台 `canStartChat` 中调用，key 为 `chat:ratelimit:{userId}`，默认 60s/30（可配置）；超限发 `AgentEvent.notify` 后完成流，不是 429；`null`/异常放行；在 Runner 抢锁之后执行。两者共用 Lua 的"拒绝不 ZADD/不 PEXPIRE"分支。
</details>

**7. 为什么聊天超限时客户端可能看到 200 而不是 429？**

<details>
<summary>参考答案</summary>

聊天限流在 Controller 之后、后台 `ChatServiceImpl#canStartChat` 里执行。Controller 已经返回 `Flux`；如果 Spring MVC 已按 `text/event-stream` 提交响应并开始写事件，代码只能**尝试**发流内 `AgentEvent.notify` 并尝试 `tryEmitComplete`，不能再改状态码；`tryEmitNext` 的结果不代表客户端收到，连接/写入失败时可能看不到。如果响应尚未提交，最终状态/线格式取决于框架协商与提交时点；仓库没有 HTTP 测试，不能保证一定是 200 或某个 SSE 字节形态。
</details>

**8. 一个已通过切面的请求，在执行中角色被管理员改成 USER，会发生什么？**

<details>
<summary>参考答案</summary>

当前请求不会中途重新执行 `PermissionAspect`，已经进入的方法体会继续按进入时 token 里的角色执行；它能保证的只有方法内已有的 Service 归属检查和数据库条件更新。下一次请求的 `getToken` 会把 token 中保存的角色与 MySQL 当前角色比较：角色不匹配时返回 null，不会把新角色写回旧 token；受保护端点因此 401，公开端点仍可继续但不会获得“新身份”。数据库 role 与 token 里的 role 重新一致只消除了角色不匹配这一障碍，仍需通过版本、账号状态、密码指纹和有效期等检查；不存在“数据库换成新角色、旧 token 自动携带新角色”的路径。并发修改角色没有“立即中断在途请求”的机制。
</details>

**9. 用户名布隆过滤器当前真的在挡缓存穿透吗？它的写入时机有什么副作用？**

<details>
<summary>参考答案</summary>

没有证据表明它在当前主链生效：`mightContain` 在 `src/main/java` 中没有调用方，`isUsernameAvailable` 使用数据库 `findByUsernameAndRole`，注册重复由数据库检查和唯一键兜底。布隆的 key 是 `auth:user:username:bloom`；`registerByEmailCode` 在 `@Transactional` 方法体内、`createUser` 之后、提交前 `addUsername`，`createAccount` 没有事务、只在创建 USER 时写入；MySQL 回滚不会删除 Redis 中的布隆项。`POST /user/rebuild` 仅 ADMIN，会 delete + tryInit + 重新加入 active USER 用户名。因此它目前既不是权限检查，也不是可用的穿透防护；若未来接入，需处理假阳性、回滚残留和重建并发。
</details>

**10. 订单详情和工单详情在"谁检查归属"上有什么关键差异？对非 HTTP 调用意味着什么？**

<details>
<summary>参考答案</summary>

订单详情：`RideOrderServiceImpl#getOrderDetail` 内部调用 `assertCanViewOrder`，Service 自己检查归属；工具 `OrderSearchTool` 也走这个重载，所以非 HTTP 调用仍然受检查。工单详情：`TicketServiceImpl#getTicketDetail` 只查数据，归属检查在 `TicketController#assertTicketReadable`；HTTP 路径会检查，但直接调用 Service 的非 HTTP 路径不会自动检查。`TicketTool#queryTicketProgress` 自己先查再比较 `detail.userId`，越权时返回字符串而不是 HTTP 403。这说明"Service 方法本身是否自带授权"必须逐方法核实，不能按模块统一假设。
</details>

---

## 十二、导航

- **上一章**：[第 5 章 登录与会话](01-login-and-session.md)——token 如何签发、校验、失效；本章默认你已经理解 token 与 `UserTokenContext`。
- **下一章**：[第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)——报价、创建、接单/到达/开始/结束、取消与支付；A2 的归属/异常边界在这里落到具体状态。
- **相关专题**：
  - 抢单竞态与数据库裁决 → [第 8 章 抢单与并发](../02-orders/02-grab-and-concurrency.md)
  - 工单生命周期与客服处理 → [第 12 章 工单生命周期与权限](../03-support-and-cache/01-ticket-lifecycle.md)
  - 对话单轮锁与身份传参 → [第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)
  - 用户名布隆与缓存穿透 → [第 14 章 缓存问题与并发窗口](../03-support-and-cache/03-cache-races.md)
  - 一次性确认令牌与工具权限 → [第 18 章 订单确认](../04-chat-and-agent/03-order-confirmation.md)

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 权限注解与切面 | `annotation/RequirePermission.java`；`aspect/PermissionAspect.java`；`pom.xml`（`spring-boot-starter-aop`） |
| 身份上下文与拦截器 | `security/UserTokenContext.java`；`security/UserToken.java`；`interceptor/AuthTokenInterceptor.java`；`service/impl/TokenServiceImpl.java#getToken` |
| 拦截器注册与顺序 | `config/WebMvcConfig.java#addInterceptors` |
| 异常到 HTTP 映射 | `exception/GlobalExceptionHandler.java`；`exception/BusinessException.java`、`UnauthorizedException.java`、`ForbiddenException.java` |
| 订单归属与角色分支 | `controller/RideOrderController.java`；`service/impl/RideOrderServiceImpl.java#getOrderDetail/#assertCanViewOrder/#getOrderHistoryPage/#payOrder/#cancelOrder/#cancelOrderUnderLock`；`domain/enums/UserRole.java` |
| 工单归属与处理人 | `controller/TicketController.java`（`assertTicketReadable`、`assign`、`reassign`、`process`）；`service/impl/TicketServiceImpl.java#submitTicket/#getTicketDetail/#cancelTicket/#assignTicket/#reassignTicket/#processTicket/#escalateTicket/#appendUserMessage` |
| 会话归属 | `service/base/ChatInfoService.java#assertOwned/#findOwned`；`service/base/ChatRequestRunner.java#run`；`controller/ChatController.java` |
| IP 限流 | `interceptor/RequestRateLimitInterceptor.java`；`config/WebMvcConfig.java`；`security/CredentialStamp.java#of`；`util/RedisScripts.java#SLIDING_WINDOW_RATE_LIMIT` |
| 聊天限流 | `service/base/ChatRateLimiter.java#tryAcquire`；`service/impl/ChatServiceImpl.java#canStartChat`；`config/ChatGuardProperties.java`；`src/main/resources/application.yaml`（`chat.guard.*`） |
| 布隆过滤器 | `service/base/UserUsernameBloomFilterService.java`；`service/impl/AuthServiceImpl.java#registerByEmailCode/#createAccount`；`controller/UserController.java#rebuildUsernameBloom`；`mapper/UserAuthMapper.java#selectUsernamesForBloom`；`constant/RedisKeyConstants.java#USER_USERNAME_BLOOM_KEY` |
| 非 HTTP 工具调用 | `agentbase/tool/OrderSearchTool.java#searchOrderById`；`agentbase/tool/TicketTool.java#queryTicketProgress`；`agentbase/tool/OrderTool.java#createOrder` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/SecurityRepairRegressionTest.java`（`foreignChatIsRejectedBeforeAnyCachedHistoryRead`、`realHttpStatusesAreReturned`、`closedTicketsCannotReenterProcessing`、`foreignOrderCannotBeAttachedToTicket`）；`ChatGuardRegressionTest`（限流降级/禁用）、`ChatGuardRedisIntegrationTest`（滑动窗口并发/过期/TTL） |
