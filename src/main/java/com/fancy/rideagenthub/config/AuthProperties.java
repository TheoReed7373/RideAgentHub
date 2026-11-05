package com.fancy.rideagenthub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证模块配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * Token 有效期（秒），默认 7 天
     */
    private long tokenTtlSeconds = 604800;

    /**
     * 邮件验证码有效期（秒），默认 5 分钟
     */
    private long emailCodeTtlSeconds = 300;

    /**
     * 邮件验证码发送冷却时间（秒），默认 60 秒
     */
    private long emailCodeCooldownSeconds = 60;
}
