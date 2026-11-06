package com.fancy.rideagenthub.util;

import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisScripts {

    /** 原子设置 Hash 字段并刷新整个 key 的过期时间，TTL 单位为秒。 */
    public static final DefaultRedisScript<Long> HASH_SET_WITH_EXPIRE = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return redis.call('EXPIRE', KEYS[1], ARGV[3])
            """, Long.class);

    /** 仅在锁令牌匹配时原子删除锁，避免误删其他持有者的锁。 */
    public static final DefaultRedisScript<Long> RELEASE_LOCK_IF_MATCH = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private RedisScripts() {
    }
}
