# 聊天与人工确认接口

> 文中 `Result<T>` 仅表示 data 的类型；源码 Result 非泛型，字段为 code、message、data、total。分页记录和总数位于 data.records / data.total，外层 total 通常为 0。

所有接口均要求 Authorization: Bearer 或 X-Auth-Token 登录。`POST /chat/v2/c/{id}`、`POST /chat/v2/r/{id}` 经 `ChatRequestRunner.run`，chatId 必须为 UUID 格式（`UUID.fromString` 校验，不登记签发来源，`/chat/v2/newuuid` 只是便捷入口）；`POST /chat/v2/lock/{id}` 只校验归属、**不做 UUID 格式校验**，`/restore` 与 `/newuuid` 无 chatId 路径参数。已有会话必须属于当前用户；/c 允许不存在的 chatId 进入首次对话，/r 与 /lock 不允许。/c 与 /r 共用 `chat:execution:{chatId}` 单轮执行锁，竞争请求会收到 error 事件；/lock 与 /restore 未经过此锁。请等待当前流结束后再发下一条。

> 下文路径统一按源码的 `@PathVariable String id` 写作 `{id}`（URL 与 `{chatId}` 写法等价，但按文档写测试/代码时参数名不同）。

- POST /chat/v2/c/{id}：请求示例 {"prompt":"我要打车"}。等待订单确认时不能通过该接口追加普通聊天。
- POST /chat/v2/r/{id}：请求示例 {"confirmationId":"确认事件返回的编号","confirmed":true,"prompt":"可选补充说明"}。confirmed 的业务含义是布尔决定（DTO 为 Boolean、无 @NotNull），false 表示拒绝；缺失或 null 且带非空 prompt 时，执行到 OrderConfirmation.confirm 会抛 BusinessException(400)，随后被后台 guard 转为通用 error 事件与流错误终态。若 prompt 也为空，canStartChat 会先发「消息内容不能为空」error 并正常结束。prompt 可以省略，服务端会把它**原样拼接**在固定 feedback 之后传给 `OrderAgent.resume`，因此仍应视为不可信输入。
- POST /chat/v2/lock/{id}：锁定本人的会话。
- GET /chat/v2/restore：**仅检查**是否存在可恢复订单会话，返回 `Result<Boolean>`；命中时把会话 key 续期 60 分钟，不返回历史消息。
- POST /chat/v2/restore：**执行恢复**，返回 `Result<RestoreChatVO>`（chatId + 按最近 100 条目标窗口截取的消息（为保留完整工具批次可能超过 100 条），再经 `MessageParser.simpleParse` 处理的消息，TOOL 消息被丢弃）；会 `unlockChat` 并对 `chat:info` 执行 `persist`，使该会话 key 不再有 TTL。
- POST /chat/v2/newuuid：生成新的 chatId（便捷入口；服务端不登记签发来源，任何合法 UUID 都可新建会话）。

> GET 与 POST `/restore` 的返回类型和副作用不同，不能合并成一条描述：前者判定并续期 60 分钟，后者解锁并 persist；两者都会改变会话 key 的生命周期。

确认事件的 type 为 confirm，payload 实际是包含 confirmationId 的 **JSON 字符串**，不是已展开的对象。先解析外层事件，再对 payload 做一次 JSON 解析。省略其他订单字段的示例：

```json
{"type":"confirm","payload":"{\"confirmationId\":\"服务端返回的报价编号\",\"START_ADDRESS\":\"杭州东站\"}"}
```

订单槽位字段使用 OrderInfoEnum 的大写名称，值为字符串；MONGO_TRACE_ID 被排除，EST_TIME 存在时另行加入。前端必须原样回传 confirmationId，不能从用户文字推断 confirmed。确认接受与创建权限消费要求报价不超过 15 分钟；拒绝不校验报价年龄，但仍要求编号匹配且状态有效；修改订单参数或重新报价会使旧确认失效。仅在 break=resuming、状态尚未清理且决定相同时，CONFIRM Lua 支持重放；接受还需通过报价时效校验。并发请求可能先被执行锁拒绝，OrderAgent.resume 补应答后会清理 break 等字段，因此不能承诺整个恢复请求可任意重试。创建权限最多消费一次。

模型工具 markOrderReadyForCreate 只查询确认状态，不再签发创建权限。历史工具查询 schema 名称统一为 getCalling，参数为 callId。

统一使用 `/chat/v2/**` 端点；仓库中不存在其他对话或测试入口。

同步控制器异常由全局处理器映射 HTTP 状态。流式端点另有后台边界：UUID 在 Runner 返回 Flux 前同步校验；抢锁与归属异常返回 Flux.error；业务主体在 chatExecutor 内执行，确认失败等 BusinessException 被 guard 捕获后发通用 error 并以错误终态结束，不能承诺均返回对应 HTTP 状态。异常定义为：`ChatInfoService.assertOwned` 按 allowNew、归属及 historyDeleted 判断 —— allowNew=false 时会话不存在抛 `BusinessException(404)`；allowNew=true 时返回新会话判定；**会话不属于当前用户（或身份无效）抛 ForbiddenException，业务码为 403**；historyDeleted=true 抛 BusinessException(410)，不是普通 locked=true 就返回 410。全局处理器仅将 HTTP 状态限制在 400–599，越界改为 400；Result.code 仍保留原始业务码。此规则不等于后台流内异常都会进入全局处理器。流开始发送后的错误通过 error 事件与流终态表达。会话清空采用永久停用原 chatId 的语义，继续交流需新建会话；本次未新增对外清空接口。
