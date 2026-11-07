package com.fancy.rideagenthub;

import com.fancy.rideagenthub.config.ChatGuardProperties;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.service.base.ChatRateLimiter;
import com.fancy.rideagenthub.service.base.ClassificationCache;
import com.fancy.rideagenthub.util.RedisScripts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 连接独立测试端口，验证实际 Redis 脚本和分类缓存。 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")
class ChatGuardRedisIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final List<String> keys = new ArrayList<>();

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.redis.port")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @AfterEach
    void cleanup() {
        redis.delete(keys);
    }

    @Test
    void parallelCallsRespectSingleUserQuota() throws Exception {
        String user = UUID.randomUUID().toString();
        String key = RedisKeyConstants.chatRateLimitKey(user);
        keys.add(key);
        ChatGuardProperties properties = new ChatGuardProperties();
        properties.setRateLimitMax(7);
        ChatRateLimiter limiter = new ChatRateLimiter(redis, properties);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                calls.add(() -> limiter.tryAcquire(user));
            }
            int accepted = 0;
            for (var result : executor.invokeAll(calls)) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(7, accepted);
            assertEquals(7L, redis.opsForZSet().zCard(key));
            assertTrue(redis.getExpire(key) > 0);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void windowRemovesExpiredCallsAndRejectsOnlyAfterLimit() {
        String key = RedisKeyConstants.chatRateLimitKey(UUID.randomUUID().toString());
        keys.add(key);
        redis.opsForZSet().add(key, "expired", 1);
        assertEquals(1L, redis.execute(RedisScripts.SLIDING_WINDOW_RATE_LIMIT, List.of(key), "10000", "2", "first"));
        assertEquals(0L, redis.execute(RedisScripts.SLIDING_WINDOW_RATE_LIMIT, List.of(key), "10000", "2", "second"));
        assertEquals(-1L, redis.execute(RedisScripts.SLIDING_WINDOW_RATE_LIMIT, List.of(key), "10000", "2", "third"));
        assertNull(redis.opsForZSet().score(key, "expired"));
        assertEquals(2L, redis.opsForZSet().zCard(key));
    }

    @Test
    void classificationCacheUsesTtlAndUnambiguousContextKeys() {
        ClassificationCache cache = new ClassificationCache(redis, new ChatGuardProperties());
        String prompt = UUID.randomUUID().toString();
        cache.put("DAILY", "A\0B", prompt, "ORDER");
        assertEquals("ORDER", cache.get("DAILY", "A\0B", prompt));
        assertNull(cache.get("DAILY", "A", "B\0" + prompt));
        assertNull(cache.get("ORDER", "A\0B", prompt));
        var cacheKeys = redis.keys(RedisKeyConstants.CHAT_CLASSIFY_CACHE_PREFIX + "*");
        assertNotNull(cacheKeys);
        keys.addAll(cacheKeys);
        assertEquals(1, cacheKeys.size());
        long ttl = redis.getExpire(cacheKeys.iterator().next());
        assertTrue(ttl > 21500 && ttl <= 21600);
    }
}
