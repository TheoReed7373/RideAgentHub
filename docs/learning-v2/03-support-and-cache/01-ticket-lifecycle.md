# 第 12 章 工单生命周期与权限：从费用争议到确认结单

> 第 11 章看到订单费用有争议，但工单不是“改价按钮”。本章沿 `TicketController → TicketService/TicketServiceImpl → MySQL/Redis` 追踪一条工单的提交、认领、沟通、处理、转交、升级、确认/评价或取消。重点不是背 0～4 状态，而是分清**当前登录身份、工单发起人、当前处理人**和**状态守卫**分别在哪一层检查。
>
> 继续使用虚构人物：乘客林小雨（USER，`userId=9001001`）、司机陈师傅（DRIVER，`7002002`）、客服苏晴（SUPPORT，`6003003`）、管理员老周（ADMIN，`5004004`）——与第 6 章一致；6.1 时间线里“另一位客服”另用占位 ID `6003004`。订单 `6001`、工单号、时间和响应均为示意；本章**没有启动服务、运行测试、连接 MySQL/Redis 或发送 HTTP 请求**，一切结果是源码静态推导，未实测。

## 学习目标

读完本章，你应该能：

1. 按源码列出工单的 5 个状态码、3 个优先级、5 个工单类型和 4 种消息发送者角色，画出实际允许的迁移。
2. 逐步追踪工单提交与可选关联订单，说明 token、DTO 覆盖、订单归属、编号生成、主表/聊天表写入、提交后回调和返回值。
3. 区分 Controller 的 `@RequirePermission`、Controller 查询后的 `assertTicketReadable`、Service 内的发起人/处理人比较，以及 Agent `ToolContext` 的显式 user ID。
4. 写出用户取消、追加消息、升级、确认/评价，以及客服认领、回复、处理完成、转交、驳回、管理员重新分配的状态守卫、条件更新和 0 行/异常结果。
5. 用可达时间线解释两个客服同时认领、管理员转交与当前处理人处理、用户取消与客服处理完成之间的数据库锁定读和提交后回调边界。
6. 说明聊天记录、`process_result`、关联订单与支付退款之间实际有什么、没有什么；阅读相关测试但不把测试阅读写成实际运行。

## 前置知识与阅读时间

- 必读：[第 2 章 请求路径与配置](../00-foundations/02-request-path-and-config.md)——DTO/Entity/VO、`Result`、异常映射。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——`@Transactional` 代理、`FOR UPDATE`、条件更新、afterCommit、跨库无共同事务。
- 必读：[第 6 章 权限、归属与防护](../01-auth/02-authorization-and-defense.md)——token → `UserTokenContext` → `PermissionAspect` → 资源归属。
- 必读：[第 7 章 订单生命周期](../02-orders/01-order-lifecycle.md)——订单状态与关联订单的字段含义。
- 推荐：[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)——费用来源与支付门禁；本章费用争议示例只需订单金额字段，不依赖计价公式。
- 阅读时间：约 100～120 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；配置与 SQL 写完整仓库相对路径。
- **证据约定**：源码静态事实、测试只读断言、实际运行三者分开；本章没有任何实际运行。

---

## 零、先记住八个事实

1. **MySQL `sys_ticket.ticket_status` 是状态真值**；Redis 工单池索引与统计缓存是读侧加速，不是状态机。
2. 状态码是 `0 待分配 / 1 处理中 / 2 待用户确认 / 3 已完成 / 4 已关闭`，不是枚举声明顺序；`handler_id` 是另一个字段，两者共同决定能否处理。
3. C 端端点都用无 value 的 `@RequirePermission`（只要求登录，不限角色）；B 端端点用 `{"ADMIN","SUPPORT"}` 或 `{"ADMIN"}`。**注解只检查角色，不检查这条工单属于谁**。
4. `submitTicket` 会先校验可选订单归属，再校验字段；Controller 用 token 覆盖 `userId/userType`，Service 本身不重新读取 token。
5. 工单号不是雪花 ID：`T + yyyyMMdd + 类型码 + Redis INCR 当日序号`；Redis 自增不在 MySQL 事务里。
6. 用户“升级”改的是 `priority`，不是状态；确认/评价只把满意度、评分、评价文字写进一条系统聊天记录，没有独立评分字段。
7. 有状态变更的 Service 方法带 `@Transactional`；状态更新普遍带 `ticket_status = 读到的旧值` 条件。`0 行` 时 `cancel/confirm/assign/reassign/escalate*` 返回 `false`，`processTicket` 的 RESOLVE/TRANSFER/REJECT 要求恰好 1 行、否则 409，`REPLY` 与 `appendUserMessage` 不检查行数（见 6.4）。
8. 工单写入成功后，通过 `afterCommit` 整池失效工单索引、删除统计缓存并登记 500ms 延迟删除；这些动作不在 MySQL 事务内，失败不回滚数据库。

---

## 一、业务故事、入口与最少概念

### 1.1 故事：一笔费用争议

林小雨发现订单 `6001` 的账单比预估多，于是：

1. 提交“费用争议”工单，关联自己的订单 `6001`；
2. 客服苏晴从工单池认领，回复核实说明；
3. 林小雨追加一段文字说明、把优先级升级到“紧急”（源码没有附件字段）；
4. 若管理员老周认为需要换人，重新分配给另一处理人；否则苏晴处理完成，等待用户确认；
5. 林小雨确认结单并评价，或者在问题解决后主动取消工单。

本章要回答：“每一步是谁调用、身份从哪来、读哪一行、改哪些字段、插入什么聊天记录、失败后返回什么、Redis 何时收到失效动作”。

### 1.2 入口总表

HTTP 基路径都是 `/ticket`。`@RequirePermission` 无 value 表示**任意已登录角色**；B 端注解不代表 Service 内也有角色检查。

| 分类 | 方法与路径 | 注解角色 | 主要输入 | Service 入口 |
|---|---|---|---|---|
| C 提交 | `POST /submit` | 仅登录 | `TicketCreateReqDTO`；Controller 覆盖 `userId/userType` | `submitTicket` |
| C 取消 | `POST /cancel/{ticketId}` | 仅登录 | 路径 ID、token userId | `cancelTicket` |
| C 确认/评价 | `POST /feedback` | 仅登录 | `TicketFeedbackReqDTO`；覆盖 userId | `confirmAndRate` |
| C 我的分页 | `POST /my/page` | 仅登录 | `TicketQueryReqDTO`；覆盖 userId | `getUserTicketPage` |
| C 详情/聊天 | `GET /detail/{ticketId}`、`GET /{ticketId}/chat` | 仅登录 | 路径 ID；Controller 查询后判可读 | `getTicketDetail` |
| C 未完结列表 | `GET /my/unfinished?limit=` | 仅登录 | 可选 limit，默认 10 | `getUnfinishedTickets` |
| C 最近未完结 | `GET /my/latest-unfinished` | 仅登录 | token userId | `getLatestUnfinishedTicket` |
| C 追加消息 | `POST /my/append` | 仅登录 | `TicketAppendMessageReqDTO` | `appendUserMessage` |
| C 升级优先级 | `POST /my/escalate` | 仅登录 | `TicketEscalateReqDTO` | `escalateTicket` |
| B 工单池 | `POST /admin/page` | ADMIN/SUPPORT | `TicketQueryReqDTO` | `getAdminTicketPage` |
| B 认领/分配 | `POST /admin/assign/{ticketId}?handlerId=` | ADMIN/SUPPORT | 可选目标处理人，默认当前用户 | `assignTicket` |
| B 重新分配 | `POST /admin/reassign/{ticketId}?handlerId=` | ADMIN | 可选目标处理人，默认当前用户 | `reassignTicket` |
| B 处理/回复 | `POST /admin/process`、`POST /admin/reply` | ADMIN/SUPPORT | `TicketHandleReqDTO`；覆盖 operatorId/operatorRole | `processTicket` |
| B 升级优先级 | `POST /admin/escalate` | ADMIN/SUPPORT | `TicketEscalateReqDTO` | `escalateTicketByAdmin` |
| B 统计 | `GET /admin/statistics` | ADMIN/SUPPORT | 无 | `getTicketStatistics` |

当前 `SupportAgent` 还把 `TicketTool` 的 5 个工具注册给模型：`createTicket`、`queryTicketProgress`、`queryUnfinishedTickets`、`escalateTicket`、`appendUserMessage`。它们从 `ToolContext` 的 `USER_ID` 取身份；工具本身没有 `@RequirePermission`。聊天入口在 `ChatController` 有 `@RequirePermission`，`userId` 由 token 显式传入 `ChatRequestRunner → ChatServiceImpl → SupportAgent → ToolContext`。注意回调列表并不只有这 5 个：`SupportAgent#runLoopStep` 用 `ToolCallbacks.from(orderSearchTool, ticketTool, toolRepPointerTool, ragTool)` 同时注册另外三个工具对象，并设置 `internalToolExecutionEnabled(false)`；模型请求的工具名由 `SupportAgent#executeTool` 按 `case` 手写分派（其中 5 个分支调 `TicketTool`）。C2 再深入工具循环；本章只把 TicketTool 这 5 个分支当作另外一组调用方。

### 1.3 最少概念

- **状态（status）与处理人（handler）**：`sys_ticket.ticket_status` 和 `sys_ticket.handler_id` 是两个列；状态 1 但处理人是别人，仍不能处理。
- **优先级（priority）**：1 普通、2 紧急、3 特急。用户或客服的“升级”只提高这个数字，不会把状态改成“已升级”。
- **聊天记录**：`sys_ticket_chat` 一行一条消息；`sender_role` 0 系统、1 乘客、2 司机、3 客服。系统动作也常写入角色 0 的消息。
- **锁定读**：`getTicketByTicketId` 在**实际事务活跃**时拼接 `FOR UPDATE`；只有 `TransactionSynchronizationManager.isSynchronizationActive()` 而无真实事务时不会拼。F3 已说明当前读与普通快照读的区别。
- **条件更新**：把读到的旧状态写进 `WHERE`；影响行数 0 表示守卫不再成立。`cancelTicket` / `confirmAndRate` / `assignTicket` / `reassignTicket` / 两个 `escalate*` 返回 `false`，`processTicket` 的 `RESOLVE/TRANSFER/REJECT` 用 `requireUpdated` 抛 409，`REPLY` 和 `appendUserMessage` 不检查行数（见 6.4）。
- **提交后回调**：`afterCommit` 注册时不执行；只有事务成功提交后运行。无事务同步时，`TicketServiceImpl#afterCommit` 直接执行动作。回调里**已捕获**的异常只记日志，**未捕获**的异常可能向调用方传播，但两者都不会回滚已提交的 MySQL；捕获范围与影响见 3.4、6.4。
- **读侧索引/统计缓存**：`TicketPoolIndex` 按状态保存排序 ZSet；`ticket:statistics:{yyyyMMdd}` 保存仪表盘数据。S2 讲 score 编码、版本/ready/重建锁和缓存竞态；本章只讲写路径何时触发失效。

---

## 二、数据、角色与真实状态机

### 2.1 表与枚举

`src/main/resources/sql/init.sql#sys_ticket`：

| 列 | 类型/约束 | 含义 |
|---|---|---|
| `id` | `bigint AUTO_INCREMENT PRIMARY KEY` | MySQL 内部主键；工单池索引用它 |
| `ticket_id` | `varchar(16) UNIQUE` | 对外业务编号，如 `T20261115200001` |
| `user_id` / `user_type` | `bigint NOT NULL` / `tinyint DEFAULT 1` | 发起人；1 乘客、2 司机 |
| `order_id` | `bigint NULL`，`KEY idx_order_id` | 可选关联订单，**无物理外键** |
| `ticket_type` | `tinyint NOT NULL` | 1 物品遗失、2 费用争议、3 服务投诉、4 安全问题、5 其他 |
| `priority` | `tinyint DEFAULT 1` | 1 普通、2 紧急、3 特急 |
| `ticket_status` | `tinyint DEFAULT 0` | 0 待分配、1 处理中、2 待用户确认、3 已完成、4 已关闭 |
| `handler_id` | `bigint NULL` | 当前处理客服/管理员 ID |
| `title/content` | `varchar(128)/varchar(1536) NOT NULL` | 标题和初始描述 |
| `process_result` | `varchar(512) NULL` | 处理完成/驳回的摘要 |
| `created_at/updated_at` | `DATETIME` | `updated_at` 有 `ON UPDATE CURRENT_TIMESTAMP` |

`sys_ticket_chat`：`id` 自增，`ticket_id varchar(16)`（无外键），`sender_id`、`sender_role`、`content varchar(1536) NOT NULL`、`created_at`。Mapper 都只是 MyBatis-Plus `BaseMapper`，没有自定义 XML SQL。

枚举来自 `domain/enums/TicketStatus.java`、`TicketType.java`、`TicketPriority.java`、`TicketSenderRole.java`。`TicketStatus.fromCode` 在 `TicketServiceImpl` 中用于描述转换，`TicketPoolIndex#slice` 还用它校验查询的 status；两者都不等于完整状态机白名单，Service 主要直接比较整数。`getTicketStatusDesc` 对非法数字返回“未知”，不拒绝读取。

### 2.2 状态迁移表（按实际动作，不按枚举想象）

下表"失败"列的 HTTP 状态指**异常经 HTTP 异常处理器映射后**的结果；Service 或 Agent 工具直接调用只会抛出异常，不会自动附带 HTTP 状态（见 2.3）。

| 起点 | 动作 | 守卫 | 终点 / 字段变化 | 失败 |
|---|---|---|---|---|
| 无 | `submitTicket` | 登录；字段；可选订单归属 | 新建 0，`handler_id=NULL`，默认优先级 1 | 400/403/503，见第三节 |
| 0 | `assignTicket` | ADMIN/SUPPORT 注解；状态 0；SUPPORT 目标必须是自己 | 1，`handler_id=目标` | 非 0 → 400；0 行 → `false` |
| 0/1/2 | `reassignTicket` | ADMIN 注解；状态非 3/4；目标不同于当前 handler | 1，`handler_id=目标` | 3/4 或同人 → 400；0 行 → `false` |
| 1/2 | `processTicket REPLY` | 当前 handler 等于 operatorId | 状态不变，插聊天、改 `updated_at` | 未分配/非处理人 400；状态否则 409 |
| 1/2 | `processTicket RESOLVE` | handler 相等；条件更新 1 行 | 2，`process_result=请求内容` | 0 行 → 409；事务回滚 |
| 1/2 | `processTicket TRANSFER` | handler 相等；条件更新 1 行 | 0，`handler_id=NULL` | 0 行 → 409 |
| 1/2 | `processTicket REJECT` | handler 相等；条件更新 1 行 | 4，`process_result="驳回: "+内容` | 0 行 → 409 |
| 2 | `confirmAndRate` | 发起人；状态 2；条件更新 | 3，评价写入系统聊天 | 非本人/非 2 → 400；0 行 → `false` |
| 0/1/2/3 | `cancelTicket` | 发起人；状态非 4；条件更新 | 4，插一条系统聊天 | 非本人/已关闭 → 400；0 行 → `false` |
| 0/1/2 | `appendUserMessage` | 发起人；状态非 3/4 | 状态不变，插入用户消息、改 `updated_at` | 非本人/已完结 → 400 |
| 0/1/2 | 用户或 B 端 `escalate*` | 状态非 3/4；目标 2/3；当前优先级低于目标；用户侧还要发起人 | 状态不变，`priority=目标` | 400；0 行 → `false` |

要注意：

- **守卫有两种写法**：`assignTicket` 要求 `status == 0`、`confirmAndRate` 要求 `== 2`、`processTicket` 用 `List.of(1, 2)` 白名单；而 `reassignTicket` 只排除 3/4，`cancelTicket` 只排除 4，`appendUserMessage` 与两个 `escalate*` 只排除 3/4。表中 `0/1/2` 这类起点指**正常枚举值里可通过的那些**，不是对任意整数的完整白名单。
- **异常状态值不构成已验证的合法迁移**：若 DB 里出现 `99` 之类不在枚举中的 `ticket_status`，上述排除型守卫不会仅因它不在枚举中而拒绝。在其他条件满足且写入成功时，`reassign` 可改成 1、`cancel` 可改成 4；`append` 只写聊天和更新时间，`escalate` 只改优先级，都不改状态。本章只按正常 0～4 推导业务迁移，未对异常数据做过运行验证；`getTicketStatusDesc` 对非法码只返回“未知”，也不阻止读写。
- **状态 3 不是所有动作都禁止**：`cancelTicket` 只拒绝 4，所以状态 3 仍能被发起人改为 4；`appendUserMessage`/升级拒绝 3 和 4。
- **枚举里没有“升级中”“已转交”状态**：升级改 `priority`；`TRANSFER` 是回到 0 且清空处理人；`reassign` 是设为 1 并指定处理人。
- **不存在独立评价表或评分列**：`satisfied/rating/feedbackContent` 被格式化进一条 `sender_role=0` 的系统聊天消息。Service 未校验 `rating` 是否为 1～5，`satisfied=null` 会显示“不满意”，`rating=null` 显示 0，空评价显示“无”。
- **不存在自动重开接口**：`TicketService` 接口注释和未接 Controller 的 `sendMessage` 提到“状态 2 用户发消息可回到 1”，但当前 C 端实际调用的是 `appendUserMessage`，它**不改状态**；`TicketTool` 也调用 `appendUserMessage`。不能把 `sendMessage` 的注释当成当前用户入口。
- **不存在退款、自动改订单状态、通知或外部渠道调用**：`TicketServiceImpl` 没有 `PaymentVerifier/RideOrderService/@Async/MQ/邮件/短信` 调用；`TicketService` 注释里的“异步通知客服系统”在当前实现中找不到对应代码。

### 2.3 角色与归属的四层检查

| 层 | 代码 | 检查什么 | 不检查什么 |
|---|---|---|---|
| 拦截器 | `AuthTokenInterceptor#preHandle` | 有合法未过期 token 才写 `UserTokenContext`；自身始终放行 | 角色、工单归属 |
| 切面 | `PermissionAspect#checkPermission` | 注解方法至少有身份；有 value 时大小写不敏感匹配角色 | 请求体里的 userId、具体工单 |
| Controller | `TicketController#submit/cancel/...` 用 token 覆盖字段；`assertTicketReadable` | 覆盖 C 端 userId；详情/聊天中 ADMIN/SUPPORT 放行，其他人只看自己的 | Service 内若被别的调用方直接调用，注解不生效 |
| Service | `cancelTicket/confirmAndRate/appendUserMessage/escalateTicket` 比较 userId；`processTicket` 比较 handlerId；`assignTicket` 比较 SUPPORT 角色 | 具体资源归属/处理人 | 多数方法不重新读取 token，也不校验目标 handler 是否真存在或角色正确 |

示例：`POST /ticket/admin/assign/{id}` 的注解允许 ADMIN/SUPPORT；`assignTicket` 再用 `UserTokenContext.getRole().equals("SUPPORT")` 精确、大小写敏感地判断 SUPPORT 只能传自己。若 token 角色写成小写 `support`，切面可大小写不敏感放行，但 Service 的精确比较不会命中 SUPPORT 限制；教学上应以当前源码的精确比较为准，不能声称“所有写法都等价”。

补充四类“注解之外”的失败分支。下表把**异常本身**与**异常进入 HTTP 异常处理器后的状态**分开：非 Web 调用（直接调 Service、Agent 工具）只抛出异常，不自动获得 HTTP 状态；HTTP 状态只在异常确实经 `GlobalExceptionHandler` 映射时才成立（源码静态推导，未实测）。继承关系：`UnauthorizedException` / `ForbiddenException` 继承 `BusinessException`（后者继承 `RuntimeException`），`NumberFormatException` 继承 `IllegalArgumentException`；Spring MVC 异常处理器按最接近的异常类型匹配。

| 场景 | 抛出的异常（非 Web / 工具调用） | HTTP 路径映射（仅当异常进入 handler） |
|---|---|---|
| 无身份：`UserTokenContext` 为空 | `getRequired()` 抛 `UnauthorizedException`；`assignTicket` 的 `getRole()` 会先走它。工具路径里异常沿工具调用链传播，具体呈现留 C1/C2 | 带 `@RequirePermission` 的端点先由 `PermissionAspect` 抛 `UnauthorizedException("请先登录")`；即使绕过切面，Controller 的 `getUserIdInLong()` 也抛同类异常 → `@ExceptionHandler(UnauthorizedException.class)` → **401** |
| 非数字 ID：`Long.parseLong(...)` / `Long.valueOf(...)` | 抛 `NumberFormatException`（`IllegalArgumentException` 子类）；`TicketTool#createTicket` 对 `orderId` 的解析异常有自己的 catch（见 3.1），其他工具的 `Long.valueOf(userId)` 未 catch，异常沿工具链上抛 | 按 `IllegalArgumentException` 匹配 `@ExceptionHandler(IllegalArgumentException.class)` → **400**，不是兜底 500 |
| `role = null`（`assignTicket`） | `getRole()` 返回 null，`.equals("SUPPORT")` 抛 `NullPointerException` | 兜底 `@ExceptionHandler(Exception.class)` → **500** |
| `actionType = null`（`processTicket`） | `switch (actionType)` 抛 `NullPointerException`；非空但不匹配四个常量才走到 `default` 抛 `IllegalArgumentException` | 前者兜底 **500**；后者按 `IllegalArgumentException` → **400** |

HTTP 正常路径里，无身份会在切面被拦住，`userId` / `operatorId` 又被 Controller 覆盖成 token 里的 `Long` 字符串；但 HTTP `/admin/process` 请求仍可能漏传 `actionType`。若异常发生在 `@Transactional` 方法内，未捕获的 `RuntimeException` 按默认规则回滚事务。结论：角色切面只决定“能不能进方法体”，不决定方法体抛出什么异常，更不决定非 Web 调用方看到什么状态；Service 的归属检查依赖传入的 ID，而 ID 是否可信取决于调用方是谁（回看 A2 的两层模型）。

---

## 三、提交工单：身份、订单归属、编号与写入

### 3.1 HTTP 示意

```http
POST /ticket/submit
Authorization: Bearer <TOKEN>
Content-Type: application/json

{
  "orderId": 6001,
  "ticketType": 2,
  "priority": 1,
  "title": "费用争议",
  "content": "行程结束后账单高于预估，请核对。"
}
```

示意响应（未实测；`Result.ok(ticketId)` 的结构来自 `domain/response/Result.java`）：

```json
{
  "code": 200,
  "message": "success",
  "data": "T20261115200001",
  "total": 0
}
```

`TicketCreateReqDTO` 还有 `userId/userType` 字段，但 Controller 用 token 覆盖：`userId=UserTokenContext.getUserIdInLong()`；`userType=2` 仅当 `hasAnyRole("DRIVER")`，否则 1。因此 ADMIN/SUPPORT 若调用 C 端提交，也会被记为 `user_type=1`；Service 自己不查 token。请求中的其他字段按 DTO 绑定；`GlobalExceptionHandler` 对 JSON 解析/类型错误返回 400。这里还有一个**未实测的绑定前提**：`TicketCreateReqDTO` 只显式标注 Lombok `@Data/@Builder`（`pom.xml` 的 Lombok 版本为 1.18.34），源码没有无参构造器或 `@JsonCreator`；而 `CreateOrderDTO` 显式声明了 `@NoArgsConstructor/@AllArgsConstructor`。`pom.xml` 的 `maven-compiler-plugin` 开了 `<parameters>true</parameters>`，但 Lombok 生成的全参构造器能否被 Jackson 借参数名模块用作创建器，取决于编译产物与运行时 Jackson 配置，本章**没有运行验证**。若绑定失败，请求会在进入 `submit` 方法体之前被 `HttpMessageNotReadableException` 拦下并由 `GlobalExceptionHandler` 映射为 HTTP 400。所以上面的请求/响应只展示字段契约，不承诺这次请求一定能到达 Service——这正是“示意，未实测”的具体含义。

`ToolContext` 路径不同：`TicketTool#createTicket` 硬编码 `userType=1`，`userId` 来自 `ToolContext` 的 `USER_ID`（上游由登录用户显式传入），`priority` 为 null 时设 1。它最终调用同一个 `TicketService#submitTicket`，所以经过 Service 代理时 `@Transactional` 生效。**关联订单是可选的**（参数标注 `@ToolParam(required = false)`），按 `orderId` 的取值分三条路径：

| `orderId` 输入 | `TicketTool#createTicket` | `submitTicket` |
|---|---|---|
| `null` 或空白字符串 | 跳过 `Long.valueOf`，`linkedOrder=null` | `req.getOrderId() == null`，**不查订单表、不做归属校验**，直接做字段校验并创建不关联订单的工单 |
| 合法数字字符串（如 `"6001"`） | `Long.valueOf(orderId.trim())` 得到 `linkedOrder=6001` | 按 `order_id=6001, is_deleted=0` 查订单；工具固定 `userType=1`，因此比较 `order.userId` 与发起人；不存在或不是本人 → `BusinessException(403)`，工具里没有 catch，异常沿工具调用链上抛 |
| 非空但无法解析（如 `"abc"`） | `NumberFormatException` 被 catch，返回文本“关联订单号必须是数字，请核对订单编号” | **不会调用 Service**，也就没有编号生成与落库 |

所以 3.1 的 HTTP 请求里带 `orderId` 只是其中一条路径：省略它同样能提交工单，只是 `sys_ticket.order_id` 为 NULL。Service 只在订单号非 null 时才检查关联订单，HTTP 和工具两条入口都遵守这一点。

### 3.2 校验顺序与订单归属

```java
// service/impl/TicketServiceImpl.java#submitTicket（真实节选）
if (req.getOrderId() != null) {
    var order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
            .eq(RideOrder::getOrderId, req.getOrderId())
            .eq(RideOrder::getIsDeleted, 0));
    Long owner = req.getUserId() == null ? null : Long.valueOf(req.getUserId());
    Long related = order == null ? null
            : (Integer.valueOf(2).equals(req.getUserType()) ? order.getDriverId() : order.getUserId());
    if (related == null || !related.equals(owner)) throw new BusinessException(403, "只能关联自己的订单");
}
if (req.getUserId() == null) throw new IllegalArgumentException("用户ID不能为空");
if (req.getTicketType() == null) throw new IllegalArgumentException("工单类型不能为空");
if (!StringUtils.hasText(req.getTitle())) throw new IllegalArgumentException("工单标题不能为空");
if (!StringUtils.hasText(req.getContent())) throw new IllegalArgumentException("工单内容不能为空");

if (req.getPriority() != null && req.getPriority() != TicketPriority.NORMAL.getCode()) {
    if (TicketType.SAFETY_ISSUE.getCode() != req.getTicketType()) {
        throw new IllegalArgumentException("非安全问题类型不允许设置高优先级，工单优先级已强制设为普通");
    }
}
```

顺序要点：

1. `orderId != null` 时先查 `sys_ride_order`：`is_deleted=0`，但不检查订单状态；订单不存在、关联字段为 null 或不等于 `owner` 都抛 `BusinessException(403)`（HTTP 路径映射 403；工具路径只抛异常）。
2. `user_type=2` 比的是 `order.driver_id`，否则比 `order.user_id`。所以司机只能关联自己作为司机的订单；乘客/其他非 DRIVER 角色按乘客字段比较。
3. 字段校验抛 `IllegalArgumentException`，在 HTTP 路径经 handler 映射 400，Service/工具直接调用只抛异常。非安全问题类型传非 1 优先级会抛异常；异常文案说“已强制设为普通”，但代码实际是**拒绝请求**，没有改值。
4. 代码不调用 `TicketType.fromCode` 或 `TicketPriority.fromCode` 校验范围；安全问题类型传 `priority=99` 会被当前校验放行。枚举定义不等于所有入口都做枚举范围检查。
5. `orderId` 为 null 时不会查订单，也不校验关联归属；工具路径的 null/空白 `orderId` 因此能创建不关联订单的工单（见 3.1 三路径表）。这里判断的是 `req.getOrderId() != null`，不是“字段必须存在”：HTTP 请求体省略 `orderId` 或绑定成 null 都走这条分支（实际绑定结果未实测）。

`SecurityRepairRegressionTest#foreignOrderCannotBeAttachedToTicket` 用 mock 构造 `userId=1`、订单 `userId=2`，断言 `submitTicket` 抛 `BusinessException(403)` 且 `ticketMapper.insert` 未被调用；这是测试阅读，未运行。

### 3.3 编号生成：Redis INCR 与 MySQL 不在同一事务

```java
// service/impl/TicketServiceImpl.java#TICKET_SEQUENCE / generateTicketId（真实节选）
local number = redis.call('INCR', KEYS[1])
if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], 172800) end
return number
```

- key 是私有常量 `TICKET_NO_PREFIX + LocalDate.now().format("yyyyMMdd")`，即 `ticket:no:20261115`；脚本只有 `KEYS[1]`，没有 ARGV。
- `INCR` 返回当日序号；只有 `TTL < 0` 时设 `EXPIRE 172800` 秒（48 小时），不会每次刷新 TTL。Java 收到 null 抛 `BusinessException(503,"工单编号服务不可用")`。
- 编号格式 `T + yyyyMMdd + ticketType + %05d序号`。示例：类型 2、序号 1 → `T20261115200001`（1 个前缀 + 8 位日期 + 1 位类型码 + 5 位序号，共 15 字符）。`%05d` 是最小宽度，序号超过 99999 时会变长；`ticket_id` 列为 `varchar(16)`，实际溢出边界未实测。这里 `LocalDate.now()` 用 JVM 默认时区，而 `created_at/updated_at` 用 `BusinessTime.now()` 的 Asia/Shanghai；多实例系统时区不同可能带来日期边界差异，未实测。
- 这条 Redis 写发生在 `submitTicket` 的 `@Transactional` 方法内，但 Redis 不参与 MySQL 事务。若后续 `ticketMapper.insert` 或聊天插入失败，MySQL 回滚，已递增的 Redis 序号不会撤销；工单号可能跳号。调用异常/响应丢失时 Redis 是否自增也不能凭 Java 异常断言。

### 3.4 主表、聊天表与提交后动作

Service 构造 `Ticket`：`status=0`、`handler_id=NULL`、`priority` 默认 1、`user_type` 默认 1、`created_at/updated_at=BusinessTime.now()`，然后：

1. `ticketMapper.insert(ticket)`；
2. 插入 `TicketChat(sender_id=0, sender_role=0, content="工单已创建，等待客服处理")`；
3. `afterTicketMutation(ticketId)`；
4. 返回 `ticketId`，Controller 包成 `Result.ok(ticketId)`。

`afterTicketMutation` 注册两个独立的 `afterCommit` 回调：

```java
// service/impl/TicketServiceImpl.java（真实节选）
private void afterTicketMutation(String ticketId) {
    if (StringUtils.hasText(ticketId)) {
        afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId));
    }
    evictTicketStatistics();
}
```

- **已捕获的异常**：`TicketPoolIndex#syncByTicketId` 用 `try/catch (RuntimeException)` 包住 `INVALIDATE_TICKET_POOLS`（`SET` 新版本 UUID + `DEL` 全部状态的 ready/pool key），Redis 侧运行时异常只记日志；`evictTicketStatistics` 的回调把第一次 `stringRedisTemplate.delete(cacheKey)` 放进 `try/catch (RuntimeException)`，失败只记日志，之后仍调用 `delayedCacheEvictor.evictAfter(key, 500ms)`；`DelayedCacheEvictor#evictAfter` 捕获 `RejectedExecutionException`，延迟任务内部再捕获 `delete` 的 `RuntimeException`，二者都只记日志、没有重试，任务由单线程 daemon 执行，进程退出可能丢失。
- **未被通用 catch 覆盖的异常**：`TicketServiceImpl#afterCommit` 注册的 `TransactionSynchronization#afterCommit()` 只调用 `action.run()`，本类**没有** try/catch；无事务同步时更是直接 `action.run()`。因此超出上述 catch 范围的异常（例如回调里其他运行时异常、`Error`，或 `evictAfter` 抛出非 `RejectedExecutionException` 的异常）会逃出回调；所有 catch 声明的都是 `RuntimeException` / `RejectedExecutionException`，不覆盖 `Error`。
- **对 DB、客户端与后续动作的影响**：物理提交已经完成，任何回调异常都不会回滚 MySQL 主表/聊天；若未捕获异常沿 HTTP 调用链传到 `GlobalExceptionHandler`，客户端可能收到错误响应而不是“业务成功”（具体异常类型与响应结构是静态推导，未实测）。两个动作注册成两个独立的同步回调，前一个回调的未捕获异常可能中断同一线程同步列表里后续回调的执行（Spring 回调机制的静态推导，未实测），因此统计缓存失效/延迟双删不一定已登记。回滚时不执行任何回调；提交结果未知、进程退出或 Redis 故障时也不能保证索引/统计缓存已失效。S2 深入读侧重建与竞态。

---

## 四、C 端动作：取消、追加、升级、确认/评价

以下有状态变更的 Service 方法都带 `@Transactional`；纯查询方法没有。经 Controller 注入的 `TicketService` 代理进入时是真实事务，`getTicketByTicketId` 会加 `FOR UPDATE`；若被测试直接 `new TicketServiceImpl(...)` 且没有 `TransactionTemplate`，实际事务不活跃，就不会加 `FOR UPDATE`。

### 4.1 用户取消

`POST /ticket/cancel/{ticketId}`，Controller 传 token userId。`cancelTicket`：

1. 锁定读工单；不存在抛 `IllegalArgumentException` → 400。
2. `ticket.userId.equals(userId)`，否则 400（不是 403）。
3. 只拒绝状态 4；0/1/2/3 都允许。
4. 条件更新 `WHERE ticket_id=? AND ticket_status=读到的旧值`，设 4 和 `updated_at`。
5. 影响 1 行才插入 `sender_id=userId, sender_role=0` 的系统消息“用户已关闭工单”，并 `afterTicketMutation`；返回 `rows > 0`。

0 行返回 `Result.ok(false)`（HTTP 200，body `data=false`），不抛 409；异常向上抛，事务按默认规则回滚。取消不检查 handler、不改 `process_result`、不触发退款。

### 4.2 追加消息

`POST /ticket/my/append`，Controller 只取 `req.ticketId/content` 和 token userId；`appendUserMessage`：

- 锁定读工单；发起人必须匹配，状态 3/4 拒绝；
- `sender_role` 按**工单保存的** `user_type` 决定：`==1` 是乘客，否则司机（非法值也落到司机）；
- 插入 `TicketChat(sender_id=userId, sender_role, content)`；
- `updateTicketTime` 只按 `ticket_id` 更新 `updated_at`，不检查影响行数；
- 调 `afterTicketMutation`，返回 `true`。

HTTP 路径没有空白内容校验；`TicketTool#appendUserMessage` 会先检查 `ticketId/content` 非空再调用 Service。当前追加消息**不会**把状态 2 自动改回 1；`TicketService#sendMessage` 有这个逻辑，但搜索当前 Controller、Tool、Agent 调用方没有找到 HTTP/工具入口，不能把它算作已接入功能。

### 4.3 升级优先级

| 入口 | 角色注解 | Service 额外检查 | 更新 |
|---|---|---|---|
| `POST /my/escalate` | 仅登录 | 发起人匹配、状态非 3/4 | `priority=targetLevel`、`updated_at`，插系统消息 |
| `POST /admin/escalate` | ADMIN/SUPPORT | 状态非 3/4；不检查发起人、不检查当前 handler | 同上，系统消息带 operatorId |

共同守卫：`targetLevel` 必须为 2 或 3；当前 `priority >= targetLevel` 拒绝；条件更新带 `ticket_status=旧值`；0 行返回 `false`。`reason` 在 Service 里没有非空校验；`TicketTool#escalateTicket` 会检查工单号、原因和目标级别再调用 Service，并把 `IllegalArgumentException`/其他异常转成文本返回。升级不改变 `ticket_status`。

### 4.4 确认结单与评价

`POST /ticket/feedback`，Controller 用 token 覆盖 `req.userId`。`confirmAndRate`：

1. 锁定读工单，发起人必须匹配，状态必须是 2；
2. 条件更新 `ticket_status=旧值` → 3、`updated_at`；
3. 影响 1 行才插入 `sender_role=0` 的系统消息：

```text
用户确认结单 | 满意度: 满意/不满意 | 评分: 1..5或0 | 评价: 文本或无
```

4. `afterTicketMutation`，返回 `rows > 0`。

评价没有独立字段，`satisfied/rating/feedbackContent` 也没有 Service 范围校验；“不满意”仍然把状态改为 3，不会自动重开工单。`process_result` 保持客服处理时写入的内容。

### 4.5 查询与归属

- `getUserTicketPage`：Controller 覆盖 `req.userId`；Service 用 MyBatis-Plus `Page` 按 userId 及可选 status/type/keyword 查，`updated_at DESC`。**但仓库没有配置 `MybatisPlusInterceptor`/`PaginationInnerInterceptor`**（全仓 0 处），因此 `selectPage` 实际不会生成 `LIMIT`、`total` 也不会来自 count：`POST /ticket/my/page` 当前会返回该用户全部匹配工单且 `total=0`。B 端 `getAdminTicketPage` 的 DB 回退自己写了 `selectCount` + `limit`，不受影响；这是代码缺口，不是教材推荐做法。
- `getUnfinishedTickets`：按 token userId 查状态不在 3/4，`updated_at DESC`；limit 非正数时用默认 10。
- `getLatestUnfinishedTicket`：按 userId 取最近一条未完结工单，再查聊天；无结果返回 null。
- `getTicketDetail` / `getChatHistory`：**Service 本身不检查调用者**；HTTP 的 `detail` 和 `/{ticketId}/chat` 都先 `getTicketDetail`，再由 `assertTicketReadable` 判断。ADMIN/SUPPORT 可读全部；其他角色只有 `detail.userId` 等于当前 token userId 才放行，否则 `ForbiddenException` → 403。`getTicketByTicketId` 找不到工单抛 400。
- TicketTool 的 `queryTicketProgress` 对显式 `ticketId` 先取详情再比较 `detail.userId` 与 `ToolContext` userId，不匹配返回文本“您无权查看此工单”；不传 ID 时走 `getLatestUnfinishedTicket(userId)`，查询本身按 userId 过滤。工具返回字符串，不是 HTTP 403。注意工具里的 `if (detail == null) return "工单不存在。"` 实际不可达：`getTicketDetail` 内部的 `getTicketByTicketId` 找不到工单会先抛 `IllegalArgumentException`；`Long.valueOf(userId)` 对非数字 `USER_ID` 也会抛异常。这些异常如何呈现给聊天客户端属于 C1/C2 的工具循环与 SSE 错误处理，本章不推断。

---

## 五、B 端动作：认领、重新分配、回复与处理

### 5.1 认领/分配

`POST /ticket/admin/assign/{ticketId}?handlerId=<可选>`：

```java
// service/impl/TicketServiceImpl.java#assignTicket（真实节选）
if (!ticket.getTicketStatus().equals(TicketStatus.PENDING_ASSIGN.getCode())) {
    throw new IllegalArgumentException("工单已被认领或已处理");
}
if (UserTokenContext.getRole().equals("SUPPORT")) {
    if (!handlerId.equals(UserTokenContext.getUserIdInLong())) {
        throw new IllegalArgumentException("客服只能认领工单，不能分配工单");
    }
}
```

- 注解允许 ADMIN/SUPPORT；Controller 未传 `handlerId` 时用当前用户。
- Service 精确比较角色字符串 `"SUPPORT"`：SUPPORT 只能认领给自己；ADMIN 可传任意 Long。目标用户是否存在、是否真有客服/管理员角色，源码不校验。
- 条件更新两次写 `ticket_status=0`（读到的旧值和常量），没有 `handler_id IS NULL` 守卫；设 `handler_id=目标`、`status=1`、`updated_at`。
- 1 行才插 `sender_id=handlerId, sender_role=0` 的“客服已接单，正在处理中”，`afterTicketMutation`；0 行返回 `false`。

### 5.2 管理员重新分配

`POST /ticket/admin/reassign/{ticketId}?handlerId=<可选>` 注解只允许 ADMIN，但 `reassignTicket` **Service 内没有角色检查**；直接调用 Service 的其他代码不会自动继承注解。

- 状态 3/4 拒绝，目标等于当前 `handler_id` 拒绝；状态 0/1/2 允许。
- 条件更新只带 `ticket_id` 和 `ticket_status=读到的旧值`，不带当前 handler，设新 `handler_id`、`status=1`、`updated_at`。
- `unHandled = status==0` 只决定系统消息里“开始/继续”的措辞。
- `resolveHandlerRoleDesc` 查 `sys_user` 中 `user_id=目标,status=1,is_deleted=0 LIMIT 1`；没有记录或 role 空返回“客服”，role **忽略大小写**等于 ADMIN 返回“管理员”，其余返回“客服”。它只生成文案，不校验目标。
- 1 行才插系统消息并 `afterTicketMutation`；0 行返回 `false`。

`SecurityRepairRegressionTest#reassignmentHandlesUnassignedTicket` 用 mock 状态 0、无 handler、update=1，断言重新分配返回 true 且能处理未分配工单；这是 mock 断言，不验证真实行锁或角色。

### 5.3 `processTicket` 的统一守卫与四种动作

Controller 的 `/admin/process` 和 `/admin/reply` 都设置：

```java
// controller/TicketController.java（真实节选）
req.setOperatorId(String.valueOf(operatorId));
req.setOperatorRole(TicketSenderRole.CUSTOMER_SERVICE.getCode());
```

`/admin/reply` 还强制 `actionType=REPLY`。注意请求体里的 `operatorId/operatorRole` 会被覆盖；`operatorRole` 在 `processTicket` 内并不用于鉴权。

```java
// service/impl/TicketServiceImpl.java#processTicket（真实节选）
if (ticket.getHandlerId() == null) throw new IllegalArgumentException("工单未分配，无法处理");
if (!ticket.getHandlerId().equals(Long.parseLong(req.getOperatorId()))) {
    throw new IllegalArgumentException("无权处理此工单");
}
if (!List.of(TicketStatus.PROCESSING.getCode(), TicketStatus.WAIT_USER_CONFIRM.getCode())
        .contains(ticket.getTicketStatus())) {
    throw new BusinessException(409, "当前工单状态不允许处理");
}
```

| actionType | 条件更新守卫 | 主表变化 | 聊天记录 |
|---|---|---|---|
| `REPLY` | 无额外条件更新；`updateTicketTime` 不检查行数 | 只改 `updated_at` | 插 `sender_role=3` 的请求内容 |
| `RESOLVE` | `status=旧值`、`handler_id=旧值`，要求 1 行 | 2、`process_result=请求内容`、`updated_at` | 客服内容 + 系统“等待用户确认” |
| `TRANSFER` | 同上 | 0、`handler_id=NULL`、`updated_at` | 客服内容 + 系统“等待其他客服处理” |
| `REJECT` | 同上 | 4、`process_result="驳回: "+内容`、`updated_at` | 客服内容 + 系统“工单已驳回关闭” |

`requireUpdated(rows)` 对 `RESOLVE/TRANSFER/REJECT` 要求 `rows == 1`，否则抛 `BusinessException(409,"工单状态已发生变化，请刷新后重试")`；此时聊天记录尚未插入，异常按默认规则回滚事务。非空但未知的 `actionType` 走 `default` 抛 `IllegalArgumentException`（HTTP 路径 400）；`actionType=null` 则是 `switch` 抛空指针（见 2.3 表格），两者不是同一分支。内容没有 Service 层非空校验。所有成功分支（包括 REPLY）最后都调 `afterTicketMutation` 并返回 `true`。

`SecurityRepairRegressionTest#closedTicketsCannotReenterProcessing` 用 mock 状态 4 的工单，断言 `RESOLVE` 抛 409 且 `ticketMapper.update` 从未调用。

### 5.4 管理端查询与 S1 需要的缓存调用点

`GET /ticket/admin/statistics` 调 `getTicketStatistics`：

- 读 `ticket:statistics:{yyyyMMdd}`；命中且 JSON 可反序列化就返回；坏 JSON 删除缓存。
- 未命中则用 `RedisLock.tryLock("ticket:statistics:lock:{yyyyMMdd}", 5s)`，拿到锁后双检、查 4 个 `selectCount`、写缓存（20 秒 + 0～10 秒随机抖动），finally 按 token 释放锁。
- 没拿到锁时最多等 3 次、每次 60ms，仍无缓存就直接查 DB，不写缓存。
- 统计项：状态 0 数量；状态 1 且 `handler_id` 非空；今日创建；今日更新且状态 3。时间边界用 `LocalDate.now()` 默认时区。

`POST /ticket/admin/page` 调 `getAdminTicketPage`：仅当 `status != null` 且 type/userType/handlerId/keyword 都为空时尝试 `TicketPoolIndex#slice`；索引不可用、回表数量不足、状态不一致或版本变化则整页回退 DB。DB 路径用 `selectCount` + `ORDER BY priority DESC, updated_at DESC, id DESC LIMIT offset,size`。S2 会深讲 score、ready、版本、10 秒重建锁；这里只需知道写工单后由 `afterCommit` 整池失效，而不是同步改一条 ZSet 成员。

---

## 六、可达并发/失败时间线

前提：以下都假设请求经 Spring 代理调用 `@Transactional` 的 Service 方法，`isActualTransactionActive()` 为真，因此 `getTicketByTicketId` 带 `FOR UPDATE`；MySQL/Redis 正常、行锁等待未超时、无其他外部写。注解没有写隔离级别，使用数据源默认值，不能把它说成 F3 中显式声明的 `READ_COMMITTED`。`afterCommit` 判断的是 `isSynchronizationActive()`，与 `FOR UPDATE` 判断的 `isActualTransactionActive()` 不是同一个条件。若提交/响应丢失，客户端看到的异常不足以判断数据库最终状态，必须重查 DB。

### 6.1 时间线 A：两名 SUPPORT 同时认领同一工单

| 时刻 | T1 苏晴 `assign(self=6003003)` | T2 另一位客服 `assign(self=6003004)` | MySQL/Redis |
|---|---|---|---|
| a0 | 通过 ADMIN/SUPPORT 注解，进入事务 | 同样进入事务 | 工单 0，`handler_id=NULL` |
| a1 | `SELECT ... FOR UPDATE` 获得行锁，读 0 | `SELECT ... FOR UPDATE` 等待同一行锁 | 0 |
| a2 | SUPPORT 目标=self 通过；条件更新 `status=0` → 1，`handler_id=6003003` | 等待 | 1 未提交 |
| a3 | 插“客服已接单”系统消息；注册两个 afterCommit 回调 | 等待 | 聊天未提交 |
| a4 | 物理提交释放行锁；提交前注册的 afterCommit 回调由 T1 线程在提交后执行 | T2 可在 T1 回调完成前或完成后获得行锁；锁定读看到已提交的 1 | 1，`handler=6003003` |
| a5 | 返回 `true` | 状态非 0 → `IllegalArgumentException` 400；事务回滚，不插消息、不注册回调 | 1 不变 |

防护点：数据库行锁 + `WHERE status=旧值` 条件更新；`FOR UPDATE` 是锁定读/当前读，等待锁释放后看到已提交值，不需要把隔离级别假定为 RC。T1 的 afterCommit 与 T2 唤醒没有固定先后：前者只保证在 T1 物理提交后执行，不保证早于 T2 读。剩余窗口要按条件说：`syncByTicketId` 只捕获 `RuntimeException` 并记日志；若失效回调没执行、执行失败或只执行了一部分，旧 ready/pool key 可能残留。`INDEX_TTL = 2 小时` 只在 `PUBLISH_TICKET_POOL` 成功发布时通过 `SET ready ... PX` 与 `PEXPIRE pool` 设到 ready/pool key，共享版本 key `ticket:pool:version` 无 TTL；TTL 也可能被外部改动或因重新发布而重算。因此只能说“旧 key 仍存在且其 TTL 未被改动时，它有自己的到期时间”，不能承诺旧数据在 2 小时后一定消失或被修复；过期也不等于下一次查询一定重建成功——`slice` 还要拿到 10 秒重建锁、通过版本/ready 校验、查 DB 并在发布时校验版本与锁令牌，任一不满足就整页回退 DB（S2 深讲）。延迟删除任务还可能因进程退出丢失。若行锁等待超时或提交结果未知，T2 可能收到数据库异常，而非 a5 的 400。

### 6.2 时间线 B：管理员重新分配与当前处理人 `RESOLVE` 竞争

前提：状态 1、`handler_id=6003003`；T1 苏晴执行 `RESOLVE`，T2 老周执行 `reassign(handlerId=5004004)`。

| 时刻 | T1 处理完成 | T2 重新分配 | 结果 |
|---|---|---|---|
| b0 | 事务开始，`FOR UPDATE` 拿到行锁，读 1/苏晴 | 事务开始，`FOR UPDATE` 等待 | 1/苏晴 |
| b1 | handler 匹配；条件更新 `status=1,handler=苏晴` → 2，写 `process_result` | 等待 | 2 未提交 |
| b2 | 插客服消息和系统消息；注册 afterCommit | 等待 | 未提交 |
| b3 | 物理提交释放行锁；提交前注册的 afterCommit 回调随后执行 | T2 可在 T1 回调前或后拿到行锁；锁定读看到 2/苏晴 | 2/苏晴 |
| b4 | — | `reassignTicket` 只拒绝 3/4；目标不同；条件 `status=2` 更新为 1、handler=老周，插一条由 `resolveHandlerRoleDesc` 决定称谓的转交系统消息；注册自己的 afterCommit | 1/老周，原 `process_result` 保留 |
| b5 | 用户看到过“待确认”或查询时已变，取决于读取时刻 | 提交后执行 T2 的 afterCommit | 最终状态以 DB 为准 |

这不是“后到者一定被状态拒绝”：`reassignTicket` 允许状态 2，并会把它改回 1。若 T2 先拿锁改为 1/老周，T1 随后锁定读发现 `handler_id != operatorId`，抛 400，事务回滚。两轮 afterCommit 与另一事务的读/写没有跨事务固定顺序，可能都成功、某一轮失败或调度延迟；不能据此承诺缓存和 DB 强一致。

### 6.3 时间线 C：用户取消与客服 `RESOLVE` 竞争

前提：状态 1、handler 苏晴、发起人是林小雨；T1 为 `processTicket(RESOLVE)`，T2 为 `cancelTicket(林小雨)`。

| 时刻 | T1 客服处理 | T2 用户取消 | 结果 |
|---|---|---|---|
| c0 | `FOR UPDATE` 拿锁，读 1 | `FOR UPDATE` 等待 | 1 |
| c1 | 条件更新 1→2，写处理结果和两条聊天 | 等待 | 2 未提交 |
| c2 | 提交前注册 afterCommit；物理提交释放行锁，提交后执行回调 | 可在 T1 回调前或后获得锁；锁定读看到 2 | 2 |
| c3 | — | 发起人通过；状态 2 不是 4；条件 `status=2` 更新为 4，插“用户已关闭工单”；注册自己的 afterCommit | 4，`process_result` 保留 |
| c4 | — | 提交后执行 T2 的 afterCommit | 4 |

反向：若 T2 先拿到锁并把 1→4，T1 随后读到 4，`processTicket` 的状态白名单检查抛 `BusinessException(409)`，事务回滚。两种顺序都可达；结果未知/数据库异常/回调失败需按实际查询判定。`RepairDatabaseIntegrationTest#closingTicketPreventsConcurrentConfirmationFromOverwritingClosedState` 用真实隔离 MySQL 和 `TransactionTemplate` 构造了状态 2 下“取消先提交、确认结单随后读到 4 并抛异常”的相关竞态，但它不是 HTTP 或完整状态机测试；本章未运行该测试。

### 6.4 0 行、异常与提交后动作对照

| 场景 | 本地事务 | 主表/聊天 | afterCommit | 返回 |
|---|---|---|---|---|
| `cancelTicket` / `confirmAndRate` / `assignTicket` / `reassignTicket` / `escalateTicket` / `escalateTicketByAdmin` 条件更新 0 行 | 正常提交（没有变更） | 不插新消息 | 不注册（`afterTicketMutation` 都在 `rows > 0` 分支里） | `Result.ok(false)`（HTTP 200） |
| `processTicket` 的 `RESOLVE` / `TRANSFER` / `REJECT` 更新 0 行 | `requireUpdated` 抛 `BusinessException(409)`，回滚 | 无 | 不注册 | 异常在 HTTP 路径经 handler 映射 409 |
| `processTicket` 的 `REPLY` / `appendUserMessage` 的 `updateTicketTime` 0 行 | 方法不检查行数，仍正常提交 | 聊天已插入；`updated_at` 未改 | 仍注册 | `true` |
| 聊天插入抛异常 | 默认回滚 | 主表与聊天都回滚 | 还没注册 | 异常 |
| 提交成功、`syncByTicketId` 的 Redis 调用抛 `RuntimeException` | 已提交 | 主表/聊天保留 | 该回调内部 catch 只记日志；另一个回调仍可能执行 | 通常仍返回业务成功（若没有其他未捕获异常） |
| 提交成功、首次统计缓存 `delete` 抛 `RuntimeException` | 已提交 | 不变 | catch 记日志后仍尝试登记 500ms 延迟删除 | 同上 |
| 提交成功、回调抛出**未捕获**异常（`Error`、超出 catch 范围的运行时异常等） | 已提交，不回滚 | 不变 | 异常可能中断同步列表里后续回调并向调用方传播 | HTTP 路径下按异常类型由 handler 映射，客户端可能收到错误响应而非成功（静态推导，未实测） |
| 延迟任务被拒绝 / 任务内 `delete` 抛 `RuntimeException` / 进程退出 | 已提交 | 不变 | 分别只记日志 / 只记日志 / 任务丢失，均无重试 | 业务响应一般不受影响，但缓存可能仍有旧值 |
| 提交结果未知/响应丢失 | 未知 | 未知 | 可能已执行或未执行 | 不能凭客户端异常断言，重查 DB/Redis |

第一行只列**返回 `false` 的方法**：`processTicket` 的三分支由 `requireUpdated` 单独处理，`REPLY` 和 `appendUserMessage` 根本不检查更新行数。这就是"0 行多数返回 false"不能概括成所有带状态条件方法的原因。

---

## 七、聊天、处理结果、订单与退款边界

- **聊天**：`sys_ticket_chat` 是独立表，以字符串 `ticket_id` 关联，没有外键；系统动作、客服内容、用户补充、评价都写入这里。查询按 `created_at ASC`。
- **处理结果**：`sys_ticket.process_result` 只被 `RESOLVE` 和 `REJECT` 写入；`confirmAndRate`、`cancelTicket`、`reassignTicket`、`appendUserMessage` 不改它。因此重新分配或关闭后，旧摘要仍可能保留。
- **关联订单**：`sys_ticket.order_id` 只是 nullable bigint，无物理外键；提交时检查订单存在、未删除和发起人/司机归属，没有检查订单状态，也没有把金额、路线、支付结果复制进工单。之后的状态流转不再读该订单。
- **支付退款**：`TicketServiceImpl` 没有调用 `PaymentVerifier`、`RideOrderService`、金额字段或退款接口；工单处理完成不等于订单改价、退款、支付状态变化或账务冲正。第 11 章已说明当前默认支付门禁也没有真实渠道清算。
- **通知**：`TicketService` 接口注释提到“异步通知客服系统”，但 `TicketServiceImpl` 当前只写日志和 DB 聊天记录；该类没有 `@Async`、MQ、邮件、短信或 Webhook 调用。仓库别处的邮件验证码服务不等于工单通知已接入，不能从接口注释推断已发送外部通知。
- **缓存**：工单池 ZSet 和统计缓存是读侧派生数据。S1 写路径通过 `afterCommit` 整池失效+延迟双删；它们不是工单状态真值，也不能恢复未提交的 MySQL 变更。失效动作只捕获 `RuntimeException` / `RejectedExecutionException`，而 `TicketServiceImpl#afterCommit` 没有通用 catch：未捕获异常不回滚已提交 DB，却可能让客户端收到错误响应，并影响同一线程后续回调（见 3.4、6.4）。ready/pool key 的 2 小时 TTL 只在 `PUBLISH_TICKET_POOL` 成功发布时设置，共享版本 key 无 TTL；失效或重建失败时不能承诺旧数据到点消失（见 6.1，S2 深讲）。`INVALIDATE_TICKET_POOLS` 是多 key Lua；若部署为 Redis Cluster，所有 key 必须同槽，当前 key 没有显式 hash tag，实际部署条件需核验。

---

## 八、测试阅读：覆盖什么、没覆盖什么

本章只读了测试源码，**没有运行任何测试**；没有连接业务 MySQL/Redis，也没有调用 HTTP/Agent。

### 8.1 不需要外部端口的 mock/回归

| 测试 | 门控、mock | 与 S1 相关断言 |
|---|---|---|
| `TicketPoolRegressionTest` | 无外部端口；`TableInfoHelper` 初始化 Ticket 元数据；mock Mapper/索引/Redis | 索引顺序保持、附加过滤整页回 DB、缺行/错状态/版本变化回退、空热池不查 DB、大 offset 不溢出、非法分页、实际事务活跃时不能重建公共索引、Redis 读失败降级 |
| `SecurityRepairRegressionTest` | 无外部端口；mock TicketMapper 等 | 状态 4 的 `processTicket` 抛 409 且不 update；未分配状态 0 可 `reassign`；关联他人订单 `submitTicket` 403 且不 insert |
| `CacheConsistencyRegressionTest` | 无外部端口；多数用例 mock Redis/DelayedCacheEvictor/TicketPoolIndex 并手工 `initSynchronization`；但 `delayedEvictorSurvivesDeleteFailuresAndShutsDown` 用**真实** `DelayedCacheEvictor`（Redis 仍为 mock） | `submitTicket` 在 afterCommit 前不删统计缓存、不调索引/延迟任务；手动触发回调后首次删除异常仍调索引与延迟 evictor；回滚状态不触发失效；真实 evictor 首个删除抛异常不影响后续任务，关闭后再调度不抛 |

这些测试直接 `new TicketServiceImpl(...)`，不经过 Spring 的 `@Transactional` 代理；`CacheConsistencyRegressionTest` 手工初始化的是事务同步器，不是实际数据库事务。因此它们验证 Java 编排和回调注册，不验证真实 `FOR UPDATE`、提交/回滚或 Redis 网络。

### 8.2 需要隔离中间件的集成测试

| 测试 | 门控与真实组件 | 创建/删除影响 | 主要断言 |
|---|---|---|---|
| `TicketPoolDatabaseIntegrationTest` | `@EnabledIfSystemProperty(named="test.mysql.port", matches="\\d+")`，但代码还读取 `test.redis.port`（未单独门控）；真实 MySQL/Redis/Mapper，mock 聊天 Mapper/UserAuthMapper/DelayedCacheEvictor | 在 127.0.0.1 专用端口创建随机 schema，从 `sql/init.sql` 抽取 `sys_ticket` 建表；每轮 `DELETE FROM sys_ticket` 并插入 4 条；每轮删除 `ticket:pool:*` key；结束后 drop schema | 索引页与 DB 页顺序/total 一致、附加过滤走 DB、删除/改状态成员整页回退、优先级变更后显式失效重排、超大 offset |
| `TicketPoolRedisIntegrationTest` | `@EnabledIfSystemProperty(named="test.redis.port")`；真实 Redis/RedisLock，mock TicketMapper | 每轮删除 `ticket:pool:*`；不创建 MySQL schema | 冷启动不把单条当整池、score 排序、空池 ready、跨状态失效、旧成员/坏成员、DB 读期间变更、锁过期、版本丢失、并发冷读、TTL 对齐 |
| `RepairDatabaseIntegrationTest` | `@EnabledIfSystemProperty(named="test.mysql.port")`；真实 MySQL + `TransactionTemplate(READ_COMMITTED)`；mock Redis/缓存组件 | 创建随机 schema 并执行完整 `sql/init.sql`；每轮清空 `sys_ticket/sys_ticket_chat` 等多张表；结束后 drop schema | 其他数据一致性；其中一条用例验证状态 2 工单在“取消持有行锁、确认结单等待”后最终为 4 |

集成测试的主机 `127.0.0.1`、JDBC URL 参数与连接用户名等是源码里的字面量，端口则由系统属性 `test.mysql.port` / `test.redis.port` 注入，并不是固定数字；这里不列出具体连接值，也不代表它们适用于任何共享环境。只有指向一次性专用端口/实例才安全。`ticket:pool:*` 删除会影响该 Redis 实例中所有工单池索引/版本/锁 key，不能指向共享实例。数据库测试会建库、清表、删库，不能指向业务库。

### 8.3 未覆盖

没有找到覆盖完整 HTTP/Agent 状态机、真实 `@RequirePermission` 切面、`TicketTool` 工具调用、所有 `processTicket` 分支、外部通知/退款或真实 Redis 回调延迟的测试。mock 断言不能证明生产 MySQL 行锁、提交结果或 Redis 最终状态。

> 可选实验边界：若未来运行上述集成测试，必须同时提供一次性专用 MySQL 与 Redis 实例和端口，接受“创建/删除随机 schema、清空测试表、删除 `ticket:pool:*`”的影响；不要对业务库执行 `DELETE`/`DROP`，不要 `FLUSHALL`，不要调用真实模型或外部服务。本章没有运行实验。

---

## 九、动手验证

### 练习 A：只读追踪一次提交

输入：林小雨 USER、订单 6001 的 `user_id=9001001`、工单类型 2、优先级 1、标题和内容非空。请列出从 token 到编号、MySQL、Redis、返回值的顺序，并说出订单不存在时的错误。

<details>
<summary>练习 A 参考答案</summary>

`AuthTokenInterceptor` 从 `Authorization: Bearer` 或 `X-Auth-Token` 解析 token 并写 `UserTokenContext`；`PermissionAspect` 见 `@RequirePermission` 无角色值，只要求已登录。`TicketController#submit` 覆盖 `req.userId=9001001`、`userType=1`，调 `submitTicket`。Service 先按 `order_id=6001,is_deleted=0` 查订单，按非 DRIVER 比较 `order.userId`；不存在或归属不符抛 `BusinessException(403)`。再校验 userId/type/title/content 和高优先级限制；对 `ticket:no:yyyyMMdd` 执行 INCR，首次设置 48 小时 TTL，拼 `T日期类型序号`。插入 `sys_ticket(status=0,handler=NULL,priority=1)` 和系统聊天；注册 afterCommit 的索引整池失效和统计缓存双删；提交后执行回调，返回 ticketId，Controller 包 `Result.ok`。Redis INCR 不在 MySQL 事务内，回滚不会撤销。本题带了 `orderId=6001`，所以有"先查订单归属"这一步；若请求省略 `orderId`（工具传 null/空白同理），Service 会跳过该步，仍创建 `order_id=NULL` 的工单——关联订单是可选输入，不是必填。
</details>

### 练习 B：判断五个操作

前提：工单状态 1、handler 苏晴、发起人林小雨、优先级 1。判断谁可操作、状态怎么变。

1. 苏晴 `REPLY`；2. 另一位 SUPPORT `RESOLVE`；3. 林小雨 `append`；4. 老周 ADMIN `reassign` 给苏晴自己；5. 林小雨 `feedback`。

<details>
<summary>练习 B 参考答案</summary>

1. 允许：handler 匹配、状态 1 在白名单；插客服聊天，状态仍 1，只改 `updated_at`。2. 拒绝：`handler_id` 不等于 operatorId，抛 400“无权处理此工单”；即使角色注解放行也不会写库。3. 允许：发起人匹配、状态非 3/4；插入角色按 `user_type` 决定的消息，状态仍 1，不会回到 0。4. 拒绝：目标等于当前 handler，`reassign` 抛 400；且 ADMIN 注解只是入口检查。5. 拒绝：`confirmAndRate` 要求状态 2，当前 1 会抛 400。
</details>

### 练习 C：两名客服认领并发

请按 6.1 写出 T1、T2 的真实读锁顺序，并说明为什么不能把 Redis 缓存当成裁决者。

<details>
<summary>练习 C 参考答案</summary>

两个事务都经代理进入，各自 `getTicketByTicketId` 带 `FOR UPDATE`。先拿行锁的 T1 读状态 0，条件更新为 1 并插消息，注册 afterCommit，提交后释放锁。T2 的锁定读等待，拿到时看到已提交的 1，状态守卫抛 400 并回滚；没有消息、没有回调。裁决来自数据库行锁和 `WHERE status=旧值` 更新。Redis 工单池/统计缓存只在提交后失效，既不阻止并发，也不参与 MySQL 提交；失效失败时 DB 状态仍是 1。
</details>

### 练习 D：转交与处理竞争

状态 1、handler 苏晴；`RESOLVE` 与 `reassign` 几乎同时到达。请说明两种顺序的最终主表字段。

<details>
<summary>练习 D 参考答案</summary>

RESOLVE 先拿行锁并提交：状态变 2、`process_result` 写客服内容；reassign 随后锁定读看到 2，因只拒绝 3/4 且目标不同于当前 handler，会条件更新为状态 1、handler 新目标，保留原 `process_result`，插转交系统消息。reassign 先拿锁：改为状态 1、handler 新目标；RESOLVE 随后看到 handler 不匹配，抛 400 并回滚，不写处理结果。具体顺序取决于数据库锁；提交/回调异常时结果需查 DB，不能凭客户端异常断言。
</details>

### 练习 E：六个判断

1. `@RequirePermission({"ADMIN","SUPPORT"})` 会验证当前人是工单 handler。
2. 用户取消只拒绝状态 4。
3. `confirmAndRate` 有独立评分列。
4. `appendUserMessage` 在状态 2 时会自动回到 1。
5. `processTicket` 的 `REJECT` 条件更新 0 行会返回 false。
6. 工单处理完成会自动退款。

<details>
<summary>练习 E 参考答案</summary>

1. 错。切面只检查角色；`processTicket` 才比较 handlerId。2. 对，`cancelTicket` 只拒绝 CLOSED(4)，状态 3 也可改为 4；这是“排除型”守卫而非白名单，异常状态值也会被放行，不能当成已验证的合法迁移。3. 错，满意度/评分/评价只写进一条系统聊天文本。4. 错，当前 HTTP/Tool 调用的 `appendUserMessage` 不改状态；`sendMessage` 有类似逻辑但当前未接 Controller/Tool。5. 错，`requireUpdated` 要求恰好 1 行，0 行抛 409 并回滚；返回 `false` 的是 `cancel/confirm/assign/reassign/escalate*` 那几个方法。6. 错，TicketServiceImpl 没有支付、退款或订单状态写入调用。
</details>

---

## 十、一页复习

- **真值**：MySQL `sys_ticket.ticket_status/handler_id/priority/process_result`；Redis 池与统计是读侧缓存。
- **状态**：0 待分配、1 处理中、2 待用户确认、3 已完成、4 已关闭；没有“升级中/已转交”状态。守卫分“等于 / 白名单”与“排除 3/4（或只排除 4）”两类，状态表起点只覆盖正常枚举值，异常状态值不算已验证迁移。
- **角色**：C 端注解多只要求登录；B 端注解 ADMIN/SUPPORT 或 ADMIN；归属/处理人要在 Controller 或 Service 另查。
- **提交**：token 覆盖 userId/userType；Service 只在 `orderId` 非 null 时查订单存在/未删除/归属，工具传 null 或空白会转成 null；Redis INCR 生成编号；插主表+系统聊天；afterCommit 失效缓存；返回 ticketId。
- **C 端**：取消 0/1/2/3→4；追加消息不改状态；升级只改 priority；确认/评价 2→3 且评价只在聊天文本。
- **B 端**：认领 0→1；重新分配 0/1/2→1；REPLY 不改状态；RESOLVE→2；TRANSFER→0 且清 handler；REJECT→4。
- **失败**：`cancel/confirm/assign/reassign/escalate*` 更新 0 行返回 `false`（HTTP 200）；`processTicket` 的 RESOLVE/TRANSFER/REJECT 0 行抛 409；`IllegalArgumentException`（含 `NumberFormatException`）400，`BusinessException` 按 code，`UnauthorizedException` 401，空指针兜底 500（仅异常进入 HTTP handler 时）。
- **注解之外**：注解放行不等于方法体走完。异常进入 HTTP handler 时，无身份 → 401（`UnauthorizedException`），非数字 ID → 400（`NumberFormatException` 继承 `IllegalArgumentException`），`role=null` / `actionType=null` 的空指针 → 兜底 500；非 Web / 工具调用只抛异常、不自动获得 HTTP 状态。`TicketCreateReqDTO` 的 Jackson 绑定未实测。
- **并发**：实际事务中的 `FOR UPDATE` 串行化同工单；条件更新带旧状态；提交后回调才失效缓存，已捕获异常只记日志、未捕获异常可能向调用方传播并影响后续回调，都不回滚 MySQL；ready/pool 的 2 小时 TTL 只在成功发布时设置，不承诺旧数据到点修复。
- **边界**：没有自动退款、订单改价、外部通知、独立评分列或当前已接入的 `sendMessage` HTTP 入口；S2 才深入索引/统计缓存。

---

## 十一、自测与答案

**1. 状态码 2 的工单，用户追加消息后状态是什么？**

<details>
<summary>参考答案</summary>

仍是 2。`appendUserMessage` 的实现是“排除 3/4”：正常枚举里 0/1/2 都可追加，但这不是完整白名单；它只插聊天、更新 `updated_at`，不改 `ticket_status`。`TicketService#sendMessage` 注释和实现里有 2→1 的逻辑，但当前 Controller 和 TicketTool 没调用它；不能按接口注释推断当前入口行为。若 DB 里出现 `99` 之类的异常状态值，该守卫同样不会拒绝，本章未验证这类数据。
</details>

**2. 一名 SUPPORT 给 `/admin/assign/{id}?handlerId=别人` 传别人 ID，哪一层拒绝？**

<details>
<summary>参考答案</summary>

切面看到角色 SUPPORT 会放行；`assignTicket` 用 `UserTokenContext.getRole().equals("SUPPORT")` 精确比较，再要求 `handlerId` 等于当前用户 ID，否则抛 `IllegalArgumentException` → HTTP 400。ADMIN 不受这条 Service 限制，可指定任意 Long，代码也不校验目标用户存在或角色。
</details>

**3. 工单状态 3，用户调用取消会怎样？**

<details>
<summary>参考答案</summary>

`cancelTicket` 的守卫是“只排除 4”，不是 `List.of(0,1,2,3)` 白名单，因此 3 会通过；发起人匹配后条件更新 3→4，插一条系统聊天，提交后失效缓存并返回 true。`appendUserMessage` 与升级排除 3/4、`confirmAndRate` 要求等于 2，所以状态 3 不能追加、升级或确认结单；这体现“不同动作守卫不同”。状态表列出的起点只覆盖正常枚举状态，异常值不算已验证的迁移。
</details>

**4. 两名客服同时认领，为什么后到者一般抛 400 而不是返回 false？**

<details>
<summary>参考答案</summary>

两者都经 `@Transactional` 代理，`getTicketByTicketId` 是带 `FOR UPDATE` 的锁定读。后到者等到先到者提交后，当前读看到状态 1，进入 `assignTicket` 的状态守卫并抛 `IllegalArgumentException` 400。0 行返回 false 是条件更新本身没抢到时的分支；若两者都没有真实事务、`FOR UPDATE` 不生效，可能都先读到 0，再由条件更新决定其中一次影响 1 行、另一次 0 行。
</details>

**5. `REJECT` 和 `cancelTicket` 都到状态 4，有什么不同？**

<details>
<summary>参考答案</summary>

`REJECT` 是 B 端 `processTicket` 分支，要求 ADMIN/SUPPORT 注解、handler 等于 operator、状态 1/2，条件更新还带 handler，写 `process_result="驳回: "+内容` 和两条聊天，0 行抛 409。`cancelTicket` 是 C 端发起人动作，只拒绝状态 4，条件更新只带旧状态，写“用户已关闭工单”系统消息，0 行返回 false，不改 `process_result`。
</details>

**6. 确认结单时 `satisfied=false` 会阻止状态变 3 吗？**

<details>
<summary>参考答案</summary>

不会。只要发起人匹配、状态 2 且条件更新影响 1 行，就设为 3。`satisfied` 只用于拼“满意/不满意”文本；`rating` 和 `feedbackContent` 也只进系统聊天，Service 未校验评分范围，没有“差评自动重开”。
</details>

**7. `afterCommit` 删除统计缓存失败，工单主表会回滚吗？**

<details>
<summary>参考答案</summary>

不会。回调在事务**物理提交之后**运行；`evictTicketStatistics` 捕获第一次 `delete` 的 `RuntimeException` 并记日志，随后仍尝试登记 500ms 延迟删除，MySQL 主表和聊天不因回调异常回滚。索引整池失效是另一个独立回调，`syncByTicketId` 也只捕获 `RuntimeException` 并记日志。但要区分：如果抛出的是这些 catch **之外**的异常（`Error`、其他未捕获的运行时异常），`TicketServiceImpl#afterCommit` 没有通用保护，异常可能向调用方传播、中断同一线程后续回调，HTTP 客户端也可能收到错误响应而不是“提交成功”（静态推导，未实测）。缓存旧值是否消失还取决于失效是否真的执行、旧 key 是否仍存在及其 TTL 是否被改动，不能说“到 TTL 就一定修复”（见 6.1、S2）。
</details>

**8. `TicketTool#createTicket` 与 HTTP `/ticket/submit` 的身份有什么不同？**

<details>
<summary>参考答案</summary>

HTTP 由 `@RequirePermission` 和 `UserTokenContext` 取当前 token，Controller 覆盖 `userId`，并按 DRIVER 角色决定 `userType=2`、其他为 1。工具没有方法级权限注解；`userId` 由登录聊天链路显式写入 `ToolContext`，`TicketTool` 硬编码 `userType=1`，参数 `orderId/title/content/type/priority` 由模型工具调用提供；只有 `orderId` 非 null 时才复用同一个 `submitTicket` 的订单归属校验。提示词和模型参数不是身份来源。另外工具路径的 `orderId` 是 `@ToolParam(required = false)`：null/空白时不查订单且能成功创建工单，合法数字才做归属校验，非空但不可解析会返回文本且不调用 Service（见 3.1）。
</details>

**9. 工单存在、当前处理人就是该客服、状态为 1；他调用 `/admin/process` 时只传 `ticketId` 和 `content`，漏了 `actionType`，会返回 400 吗？**

<details>
<summary>参考答案</summary>

不会按“未知操作类型”返回 400（题目是 HTTP 路径）。`TicketController#process` 只覆盖 `operatorId/operatorRole`，`actionType` 保持 null；`processTicket` 通过 handler 与状态守卫后执行 `switch (actionType)`，对 null 抛 `NullPointerException`，异常进入 `GlobalExceptionHandler` 后由兜底 `@ExceptionHandler(Exception.class)` 映射为 HTTP 500（`Result.code` 也为 500），事务按默认规则回滚。`default` 分支的 400“未知的操作类型”只在传入非空但不匹配四个常量的字符串时出现，此时抛 `IllegalArgumentException` → 按 handler 映射 400。若同样的 null 输入来自非 Web 调用方，只会抛出 NPE，没有 HTTP 状态。结论：角色切面只决定“能不能进方法体”，异常类型与是否映射成 HTTP 状态要按调用路径分别判断；本结论为源码静态推导，未实测。
</details>

**10. 模型调用 `TicketTool#createTicket` 时，`orderId` 分别传空白字符串、`"6001"`、`"abc"`，各会发生什么？**

<details>
<summary>参考答案</summary>

`orderId` 是 `@ToolParam(required = false)` 的可选参数。① 空白（或 null）：工具跳过 `Long.valueOf`，`linkedOrder=null`；`submitTicket` 的 `req.getOrderId() != null` 不成立，不查订单也不校验归属，直接做字段校验并创建 `order_id=NULL` 的工单。② `"6001"`：解析成 `6001`，Service 按 `order_id=6001, is_deleted=0` 查订单；工具固定 `userType=1`，比较 `order.userId` 与 `ToolContext` 的 userId，不存在或不是本人抛 `BusinessException(403)`（工具没有 catch，异常沿工具调用链传播）。③ `"abc"`：工具内 `Long.valueOf` 抛 `NumberFormatException`，被自己的 catch 转成文本“关联订单号必须是数字，请核对订单编号”，**不会调用 Service**，没有编号生成与落库。注意 ② 的 403 是业务异常的 code；只有异常进入 HTTP handler 才谈 HTTP 状态，工具路径没有。本结论为源码静态推导，未实测。
</details>

---

## 十二、常见误区与导航

### 12.1 常见误区

- 把 `priority=2/3` 叫“升级状态”：它仍是同一个 `ticket_status`。
- 看到 ADMIN/SUPPORT 注解就认为“可处理任意工单”：`processTicket` 还比较 `handler_id`。
- 把 `reassign` 与 `TRANSFER` 混为一谈：前者指定目标并设为 1，后者清空 handler 回到 0。
- 把 C 端非本人访问都说成 403：多数 Service 用 `IllegalArgumentException` → 400；详情读取被 Controller 拒绝才是 `ForbiddenException` → 403。
- 把“返回结果 false”当异常：`Result.ok(false)` 仍是 HTTP 200，且只有 `cancel/confirm/assign/reassign/escalate*` 的条件更新会返回 false；`processTicket` 三分支由 `requireUpdated` 抛 409，`REPLY` / `appendUserMessage` 不检查行数。
- 把 Redis INCR、工单池失效、统计缓存删除都算进 MySQL 事务：前两者是独立 Redis 动作，后两者在 afterCommit。
- 把接口注释“异步通知客服系统”和 `sendMessage` 的 2→1 逻辑写成已接入功能。
- 把 `@RequirePermission` 放行当成“方法体一定成功、异常必为 400/403”：按异常进入 HTTP handler 后的类型，无身份是 401、非数字 ID 是 400、空指针（`role=null` / `actionType=null`）才是兜底 500；非 Web / 工具调用只抛异常，没有 HTTP 状态。`TicketCreateReqDTO` 能否被 Jackson 绑定也未经实测。

### 12.2 导航与源码入口

- **上一章**：[第 11 章 计价与支付](../02-orders/05-pricing-and-payment.md)——费用争议的输入来源、订单金额与支付门禁。
- **下一章**：[第 13 章 工单池索引与统计缓存](02-ticket-index-and-cache.md)——工单池 ZSet score、版本/ready/重建锁、整页回退与统计缓存双删。
- **相关专题**：
  - 工具循环与 ToolContext → [第 17 章 分类路由与工具循环](../04-chat-and-agent/02-routing-and-tool-loop.md)
  - 延迟双删与缓存竞态 → [第 14 章 缓存问题与并发窗口](03-cache-races.md)

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL 写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| HTTP 入口与身份覆盖 | `controller/TicketController.java#submit/#cancel/#feedback/#detail/#my/*/#admin/*/#assertTicketReadable` |
| 权限链 | `interceptor/AuthTokenInterceptor.java`；`aspect/PermissionAspect.java`；`security/UserTokenContext.java`；`annotation/RequirePermission.java` |
| Service 契约与实现 | `service/TicketService.java`；`service/impl/TicketServiceImpl.java#submitTicket/#cancelTicket/#confirmAndRate/#assignTicket/#reassignTicket/#processTicket/#appendUserMessage/#escalateTicket/#escalateTicketByAdmin/#afterTicketMutation/#afterCommit/#evictTicketStatistics/#generateTicketId/#getTicketByTicketId/#requireUpdated` |
| 状态/类型/优先级/角色 | `domain/enums/TicketStatus.java`、`TicketType.java`、`TicketPriority.java`、`TicketSenderRole.java` |
| 实体、DTO、VO、Mapper | `domain/entity/Ticket.java`、`TicketChat.java`；`domain/dto/Ticket*ReqDTO.java`；`domain/vo/Ticket*VO.java`；`mapper/TicketMapper.java`、`TicketChatMapper.java` |
| 表结构 | `src/main/resources/sql/init.sql#sys_ticket/#sys_ticket_chat` |
| 工具与 Agent 调用点 | `agentbase/tool/TicketTool.java#createTicket/#queryTicketProgress/#queryUnfinishedTickets/#escalateTicket/#appendUserMessage`；`agents/SupportAgent.java#runLoopStep/#invoke/#executeTool`；`service/base/ChatRequestRunner.java`；`service/impl/ChatServiceImpl.java#chat` |
| DTO 绑定前提（未实测） | `domain/dto/TicketCreateReqDTO.java`（`@Data/@Builder`）；`domain/dto/CreateOrderDTO.java`（显式 `@NoArgsConstructor/@AllArgsConstructor`）；`pom.xml`（Lombok 1.18.34、`maven-compiler-plugin` 的 `<parameters>true</parameters>`） |
| 索引/缓存必要调用点 | `service/base/TicketPoolIndex.java#syncByTicketId/#slice/#isCurrent`（`INDEX_TTL`、`PUBLISH_TICKET_POOL` 的 `PX`/`PEXPIRE`）；`service/base/DelayedCacheEvictor.java#evictAfter/#shutdown`；`service/base/RedisLock.java`；`util/RedisScripts.java#INVALIDATE_TICKET_POOLS/#PUBLISH_TICKET_POOL/#READ_TICKET_POOL/#RELEASE_LOCK_IF_MATCH`；`constant/RedisKeyConstants.java` |
| 异常与响应 | `domain/response/Result.java`；`exception/BusinessException.java`（`RuntimeException` 子类）、`UnauthorizedException.java`、`ForbiddenException.java`；`exception/GlobalExceptionHandler.java`；`security/UserTokenContext.java#getRequired/#getRole` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/TicketPoolRegressionTest.java`、`TicketPoolDatabaseIntegrationTest.java`、`TicketPoolRedisIntegrationTest.java`、`SecurityRepairRegressionTest.java`、`CacheConsistencyRegressionTest.java`、`RepairDatabaseIntegrationTest.java` |
