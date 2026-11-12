package com.fancy.rideagenthub.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncWorkConfig {
    @Bean("ragExecutor")
    public ThreadPoolTaskExecutor ragExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("rag-search-");
        return executor;
    }
    @Bean("chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-request-");
        return executor;
    }
}
