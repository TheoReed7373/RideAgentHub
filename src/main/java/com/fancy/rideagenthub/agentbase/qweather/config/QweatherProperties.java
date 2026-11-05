package com.fancy.rideagenthub.agentbase.qweather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 和风天气 API 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "qweather")
public class QweatherProperties {

    /**
     * API base url
     */
    private String url;

    /**
     * API Key（作为 query param key 传入即可鉴权）
     */
    private String key;

    /**
     * 连接/读取超时（毫秒）
     */
    private int timeout = 5000;
}
