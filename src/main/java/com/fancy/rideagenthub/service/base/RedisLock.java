package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.util.RedisScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 用唯一令牌标识持有者的不可重入 Redis 锁。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLock {

    private final StringRedisTemplate redisTemplate;

    /** 加锁和设置有效期原子执行；竞争失败返回 null。 */
    public String tryLock(String key, Duration ttl) {
        if (key == null || key.isBlank() || ttl == null || ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("锁 key 不能为空，有效期至少为 1 毫秒");
        }
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl)) ? token : null;
    }

    /** 原子校验令牌后删除，防止旧持有者误删过期后重新获得的锁。 */
    public void unlock(String key, String token) {
        if (key == null || key.isBlank() || token == null || token.isBlank()) {
            return;
        }
        try {
            redisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(key), token);
        } catch (RuntimeException e) {
            // 不覆盖临界区的返回值或异常，剩余锁由有效期兜底释放。
            log.warn("Redis 锁释放失败: key={}", key, e);
        }
    }
}
