package com.fancy.rideagenthub.agentbase.amap.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Configuration
@Slf4j
public class AmapConfig {

    @Bean("amapRestClient")
    public RestClient amapRestClient(RestClient.Builder builder, AmapProperties properties) {
        // 配置简单的超时设置
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());

        return builder
                .baseUrl(properties.getUrl())
                .requestFactory(requestFactory)
                // 可以在这里添加全局 Header 或 拦截器
                .requestInterceptor((request, body, execution) -> {
                    long startNs = System.nanoTime();
                    URI uri = request.getURI();
                    String maskedUri = maskKey(uri != null ? uri.toString() : null);
                    try {
                        var response = execution.execute(request, body);
                        long costMs = (System.nanoTime() - startNs) / 1_000_000;
                        if (log.isDebugEnabled()) {
                            log.debug("[AMAP] {} {} -> {} ({}ms)", request.getMethod(), maskedUri,
                                    response.getStatusCode(), costMs);
                        }
                        return response;
                    } catch (Exception e) {
                        long costMs = (System.nanoTime() - startNs) / 1_000_000;
                        log.warn("[AMAP] {} {} failed ({}ms): {}: {}", request.getMethod(), maskedUri,
                                costMs, e.getClass().getSimpleName(), e.getMessage());
                        throw e;
                    }
                })
                .build();
    }

    private static String maskKey(String uri) {
        if (uri == null || uri.isBlank()) {
            return uri;
        }
        // 常见 query: key=xxxxx，做最小脱敏避免泄露
        return uri.replaceAll("([?&]key=)([^&]+)", "$1***");
    }
}
