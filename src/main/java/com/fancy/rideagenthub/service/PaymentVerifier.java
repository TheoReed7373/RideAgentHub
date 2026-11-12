package com.fancy.rideagenthub.service;

import java.math.BigDecimal;

/** 由支付渠道适配器查询交易，核对商户、订单、币种、金额和支付终态；客户端参数不是支付凭据。 */
public interface PaymentVerifier {
    void verifyPaid(String orderId, String userId, Integer channel, String tradeNo, BigDecimal amount);
}
