package com.fancy.rideagenthub.service.base;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 提交后延迟删除缓存，清理并发读请求回填的旧快照。 */
@Component
@Slf4j
public class DelayedCacheEvictor {

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "delayed-cache-evictor");
        thread.setDaemon(true);
        return thread;
    });

    public DelayedCacheEvictor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void evictAfter(String key, Duration delay) {
        try {
            executor.schedule(() -> {
                try {
                    redisTemplate.delete(key);
                } catch (RuntimeException e) {
                    log.warn("延迟删除缓存失败: key={}", key, e);
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.warn("延迟删除任务未被接收: key={}", key, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
