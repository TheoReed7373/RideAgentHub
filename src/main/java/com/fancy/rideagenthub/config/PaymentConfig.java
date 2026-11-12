package com.fancy.rideagenthub.config;

import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.service.PaymentVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {
    @Bean
    @ConditionalOnMissingBean(PaymentVerifier.class)
    PaymentVerifier unavailablePaymentVerifier() {
        // 尚未接入可信渠道时拒绝确认支付，不能把任意交易号当作支付成功。
        return (orderId, userId, channel, tradeNo, amount) -> {
            throw new BusinessException(503, "支付渠道尚未接入，无法核验支付结果");
        };
    }
}
