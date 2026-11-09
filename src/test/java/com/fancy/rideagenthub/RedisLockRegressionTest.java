package com.fancy.rideagenthub;

import com.fancy.rideagenthub.service.base.RedisLock;
import com.fancy.rideagenthub.util.RedisScripts;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisLockRegressionTest {

    @Test
    void acquisitionUsesUniqueTokensAndAtomicExpiryAndReturnsNullWhenBusy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Duration ttl = Duration.ofSeconds(10);
        when(values.setIfAbsent(eq("key"), anyString(), eq(ttl))).thenReturn(true, true, false, null);
        RedisLock locks = new RedisLock(redis);
        String first = locks.tryLock("key", ttl);
        String second = locks.tryLock("key", ttl);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertNull(locks.tryLock("key", ttl));
        assertNull(locks.tryLock("key", ttl));
        locks.unlock("key", first);
        verify(redis).execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of("key"), first);
    }

    @Test
    void invalidExpiryIsRejectedAndMissingTokenCannotUnlock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisLock locks = new RedisLock(redis);
        for (Duration ttl : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> locks.tryLock("key", ttl));
        }
        assertThrows(IllegalArgumentException.class, () -> locks.tryLock(" ", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> locks.tryLock("key", null));
        locks.unlock("key", null);
        locks.unlock("key", " ");
        verifyNoInteractions(redis);
    }

    @Test
    void releaseFailureDoesNotMaskCriticalSectionResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(RedisScripts.RELEASE_LOCK_IF_MATCH), anyList(), anyString()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        assertDoesNotThrow(() -> new RedisLock(redis).unlock("key", "token"));
    }
}
