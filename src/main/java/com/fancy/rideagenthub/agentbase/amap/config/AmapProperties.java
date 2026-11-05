package com.fancy.rideagenthub.agentbase.amap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 高德 WebService API 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "amap")
public class AmapProperties {

    /**
     * WebService API base url
     */
    private String url = "https://restapi.amap.com/v3";

    /**
     * WebService Key
     */
    private String key;

    /**
     * 连接/读取超时（毫秒）
     */
    private int timeout = 5000;
}
