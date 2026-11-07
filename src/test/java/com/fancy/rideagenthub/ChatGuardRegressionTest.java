package com.fancy.rideagenthub;

import com.fancy.rideagenthub.config.ChatGuardProperties;
import com.fancy.rideagenthub.service.base.ChatRateLimiter;
import com.fancy.rideagenthub.service.base.ClassificationCache;
import com.fancy.rideagenthub.util.RedisScripts;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatGuardRegressionTest {

    @Test
    void embeddedNullCharactersCannotMergeContextAndPrompt() {
        ClassificationCache cache = cache(new ChatGuardProperties());
        cache.put("DAILY", "A\0B", "C", "ORDER");
        assertNull(cache.get("DAILY", "A", "B\0C"));
        assertEquals("ORDER", cache.get("DAILY", "A\0B", "C"));
    }

    @Test
    void nullAndEmptyContextUseSeparateKeys() {
        ClassificationCache cache = cache(new ChatGuardProperties());
        cache.put("DAILY", null, "prompt", "ORDER");
        assertNull(cache.get("DAILY", "", "prompt"));
    }

    @Test
    void cacheSeparatesPreviousRoutesContextAndPrompts() {
        ClassificationCache cache = cache(new ChatGuardProperties());
        cache.put("DAILY", "context", "prompt", "ORDER");
        assertEquals("ORDER", cache.get("DAILY", "context", "prompt"));
        assertNull(cache.get("ORDER", "context", "prompt"));
        assertNull(cache.get("DAILY", "other", "prompt"));
        assertNull(cache.get("DAILY", "context", "other"));
    }

    @Test
    void disabledCacheDoesNotReadOrWriteRedis() {
        ChatGuardProperties properties = new ChatGuardProperties();
        properties.setClassifyCacheTtlSeconds(0);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ClassificationCache cache = new ClassificationCache(redis, properties);
        assertNull(cache.get("DAILY", "context", "prompt"));
        cache.put("DAILY", "context", "prompt", "ORDER");
        verifyNoInteractions(redis);
    }

    @Test
    void emptyClassificationDoesNotPopulateCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ClassificationCache cache = new ClassificationCache(redis, new ChatGuardProperties());
        cache.put("DAILY", "context", "prompt", null);
        cache.put("DAILY", "context", "prompt", " \n");
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureDegradesCacheAndLimiter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis unavailable"));
        when(redis.execute(eq(RedisScripts.SLIDING_WINDOW_RATE_LIMIT), anyList(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        ChatGuardProperties properties = new ChatGuardProperties();
        ClassificationCache cache = new ClassificationCache(redis, properties);
        assertNull(cache.get("DAILY", "context", "prompt"));
        assertDoesNotThrow(() -> cache.put("DAILY", "context", "prompt", "ORDER"));
        assertTrue(new ChatRateLimiter(redis, properties).tryAcquire("1"));
    }

    @Test
    void disabledLimiterAndAnonymousInputDoNotAccessRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatGuardProperties properties = new ChatGuardProperties();
        ChatRateLimiter limiter = new ChatRateLimiter(redis, properties);
        assertTrue(limiter.tryAcquire(null));
        properties.setRateLimitMax(0);
        assertTrue(limiter.tryAcquire("1"));
        verifyNoInteractions(redis);
    }

    private ClassificationCache cache(ChatGuardProperties properties) {
        Map<String, String> entries = new HashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        return new ClassificationCache(redis, properties);
    }
}
