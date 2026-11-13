# 订单安全约定

POST /order/estimate 现在要求登录，并与认证接口一起受来源地址频率限制。经纬度按数值范围校验；预约时间必须晚于当前业务时间，业务 DATETIME 使用 Asia/Shanghai。

POST /order 的 userId 一律取当前身份，客户端传入的预估价、倍率、里程和 Mongo 轨迹标识不作为权威数据。服务端重新规划和计价。即时订单忽略 scheduledTime。

POST /order/{id}/cancel 依据登录角色决定取消身份。乘客为 1、司机为 2，客户端不得指定系统身份 3；传入与登录角色不符的角色会被拒绝。系统超时处理使用内部状态机。

POST /order/{id}/pay 只允许订单本人操作。payChannel 与 tradeNo 必填（由 `RideOrderServiceImpl.payOrder` 手写判空，DTO 无声明式校验注解）。完整校验顺序为「参数非空 400 → 进入 `withOrderLock`（Redisson 看门狗）→ 非本人 403 → 状态非 `FINISHED_WAIT_PAY` 409 → `paymentVerifier == null` 时 503「支付核验尚未配置」或默认 Bean 抛 503「支付渠道尚未接入」→ 锁内条件更新为已支付」；403/409 与支付核验本身都在订单锁内执行。

**当前已实现（部分实现）**：`PaymentVerifier` 接口签名只有 `(orderId, userId, channel, tradeNo, amount)`；默认 Bean（`PaymentConfig.unavailablePaymentVerifier()`，`@ConditionalOnMissingBean`）直接抛 `BusinessException(503, "支付渠道尚未接入，无法核验支付结果")`，**不核验任何渠道数据**。因此不能通过客户端提交任意交易号完成支付，也不能把「商户 / 币种 / 支付终态核验」描述为现有能力。

**接入渠道时需实现的契约（设计中）**：核对商户、币种、金额、支付终态，保证渠道交易号与订单唯一对应；注释里提到的「商户 / 币种 / 终态」属于这份未来契约，而非当前实现。禁止提供直接返回成功的生产实现。

Agent 取消预检绑定具体订单且 5 分钟内有效，最终提示使用本次服务返回的真实取消费。改目的地与结束行程共享订单锁，并比较旧轨迹标识；预估与结算统一使用 RidePricing，重复修改不再累计倍率。

历史订单分页 `RideOrderServiceImpl.getOrderHistoryPage(page, size, statusList)`（`:991-1015`）只校验 `page`/`size` 为正数，再按登录角色（乘客只看本人、司机只看自己接的单、ADMIN/SUPPORT 可看全部）与 `statusList` 过滤后分页查询。当前**没有**按时间范围筛选的入参，也**没有**「超过 200 条提示缩小时间范围」或「扫描最近 1000 条」的上限逻辑 —— 这两种说法属于改造前的历史描述，不要当作现有能力。
