# 聊天与人工确认接口

所有接口均要求 Authorization: Bearer 或 X-Auth-Token 登录。`POST /chat/v2/c/{id}`、`POST /chat/v2/r/{id}` 经 `ChatRequestRunner.run`，chatId 必须为 UUID 格式（`UUID.fromString` 校验，不登记签发来源，`/chat/v2/newuuid` 只是便捷入口）；`POST /chat/v2/lock/{id}` 只校验归属、**不做 UUID 格式校验**，`/restore` 与 `/newuuid` 无 chatId 路径参数。所有带 chatId 的接口均要求会话属于当前用户。同一会话一次只处理一轮（`chat:execution:{chatId}` 单轮执行锁），请等待结束事件后再发下一条。

> 下文路径统一按源码的 `@PathVariable String id` 写作 `{id}`（URL 与 `{chatId}` 写法等价，但按文档写测试/代码时参数名不同）。

- POST /chat/v2/c/{id}：请求示例 {"prompt":"我要打车"}。等待订单确认时不能通过该接口追加普通聊天。
- POST /chat/v2/r/{id}：请求示例 {"confirmationId":"确认事件返回的编号","confirmed":true,"prompt":"可选补充说明"}。confirmed 必须是布尔值（DTO 为 `Boolean` 且无 `@NotNull`，可为 null），false 表示拒绝；缺失或为 null 时由 `OrderConfirmation.confirm` 抛 400。prompt 可以省略，服务端会把它**原样拼接**在固定 feedback 之后传给 `OrderAgent.resume`，因此仍应视为不可信输入。
- POST /chat/v2/lock/{id}：锁定本人的会话。
- GET /chat/v2/restore：**仅检查**是否存在可恢复订单会话，返回 `Result<Boolean>`；命中时把会话 key 续期 60 分钟，不返回历史消息。
- POST /chat/v2/restore：**执行恢复**，返回 `Result<RestoreChatVO>`（chatId + 最近 100 条经 `MessageParser.simpleParse` 处理的消息，TOOL 消息被丢弃）；会 `unlockChat` 并对 `chat:info` 执行 `persist`，使该会话 key 不再有 TTL。
- POST /chat/v2/newuuid：生成新的 chatId（便捷入口；服务端不登记签发来源，任何合法 UUID 都可新建会话）。

> GET 与 POST `/restore` 的返回类型和副作用不同，不能合并成一条描述：前者只读判定，后者才恢复并改变会话 key 的生命周期。

订单确认事件的 JSON 内容增加 confirmationId。前端必须原样回传，不能从用户文字推断 confirmed。报价有效期 15 分钟；修改订单参数或重新报价会使旧确认失效。相同决定在恢复尚未完成时可以重试，创建权限只能消费一次。

模型工具 markOrderReadyForCreate 只查询确认状态，不再签发创建权限。历史工具查询 schema 名称统一为 getCalling，参数为 callId。

原 /order/chat/{id}、/order/resume/{id}、/order/daily/chat/{id} 和 /test/rag 测试入口已移除。

参数和权限错误使用实际 HTTP 400/401/403/404/409/410 状态：`ChatInfoService.assertOwned` 区分三种结果 —— 会话不存在抛 `BusinessException(404)` → HTTP 404；**会话不属于当前用户（或身份无效）抛 `ForbiddenException` → HTTP 403**；已清空/停用会话抛 `BusinessException(410)` → HTTP 410。`BusinessException.code` 落在 400–599 时直接透传，超出范围被钳制为 400。流开始发送后的错误通过 error 事件与流终态表达。会话清空采用永久停用原 chatId 的语义，继续交流需新建会话；本次未新增对外清空接口。
