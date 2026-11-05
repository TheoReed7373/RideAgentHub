package com.fancy.rideagenthub.agentbase.qweather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class QweatherConfig {

    @Bean("qweatherRestClient")
    public RestClient qweatherRestClient(RestClient.Builder builder, QweatherProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());

        return builder
                .baseUrl(properties.getUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(new GzipDecompressingInterceptor())
                .build();
    }
}
