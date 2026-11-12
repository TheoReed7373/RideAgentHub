# 聊天与人工确认接口

所有接口均要求 Authorization: Bearer 或 X-Auth-Token 登录，chatId 必须是 /chat/v2/newuuid 返回的 UUID。用户只能操作自己的会话。同一会话一次只处理一轮，请等待结束事件后再发下一条。

- POST /chat/v2/c/{chatId}：请求示例 {"prompt":"我要打车"}。等待订单确认时不能通过该接口追加普通聊天。
- POST /chat/v2/r/{chatId}：请求示例 {"confirmationId":"确认事件返回的编号","confirmed":true,"prompt":"可选补充说明"}。confirmed 必须是布尔值，false 表示拒绝。prompt 可以省略。
- POST /chat/v2/lock/{chatId}：锁定本人的会话。
- GET /chat/v2/restore、POST /chat/v2/restore：检查并恢复当前用户最近的可恢复订单会话。

订单确认事件的 JSON 内容增加 confirmationId。前端必须原样回传，不能从用户文字推断 confirmed。报价有效期 15 分钟；修改订单参数或重新报价会使旧确认失效。相同决定在恢复尚未完成时可以重试，创建权限只能消费一次。

模型工具 markOrderReadyForCreate 只查询确认状态，不再签发创建权限。历史工具查询 schema 名称统一为 getCalling，参数为 callId。

原 /order/chat/{id}、/order/resume/{id}、/order/daily/chat/{id} 和 /test/rag 测试入口已移除。

参数和权限错误使用实际 HTTP 400/401/403/409/410 状态。流开始发送后的错误通过 error 事件与流终态表达。会话清空采用永久停用原 chatId 的语义，继续交流需新建会话；本次未新增对外清空接口。
