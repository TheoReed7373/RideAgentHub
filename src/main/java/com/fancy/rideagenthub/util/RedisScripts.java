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

    /** 城市编码失效时同步递增版本号，阻止墓碑过期后旧快照重新回填。 */
    public static final DefaultRedisScript<Long> INVALIDATE_CITY_CODE = new DefaultRedisScript<>("""
            local version = redis.call('INCR', KEYS[2])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return version
            """, Long.class);

    /** 城市编码仅在租约有效且缓存仍为空时回填。 */
    public static final DefaultRedisScript<Long> CACHE_CITY_CODE_IF_VERSION_MATCH = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[2]) or '0'
            if current ~= ARGV[1] then
                return 0
            end
            if redis.call('SET', KEYS[1], ARGV[2], 'NX', 'EX', ARGV[3]) then
                return 1
            end
            return 0
            """, Long.class);

    /** 版本号独立保留，避免数据过期或清空后出现版本归零。 */
    public static final DefaultRedisScript<Long> CURRENT_HISTORY_VERSION = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[2])
                redis.call('SET', KEYS[1], '0')
            elseif redis.call('PTTL', KEYS[1]) >= 0 then
                redis.call('INCR', KEYS[1])
                redis.call('DEL', KEYS[2])
            end
            redis.call('PERSIST', KEYS[1])
            return tonumber(redis.call('GET', KEYS[1]))
            """, Long.class);

    /** 追加消息、递增版本并设置数据 TTL，全部在同一次原子操作内完成。 */
    public static final DefaultRedisScript<Long> APPEND_WITH_VERSION_BUMP = new DefaultRedisScript<>("""
            local version = redis.call('INCR', KEYS[1])
            redis.call('PERSIST', KEYS[1])
            for i = 2, #ARGV do
                redis.call('RPUSH', KEYS[2], ARGV[i])
            end
            if tonumber(ARGV[1]) > 0 then
                redis.call('PEXPIRE', KEYS[2], ARGV[1])
            else
                redis.call('PERSIST', KEYS[2])
            end
            return version
            """, Long.class);

    /** 写库期间有回填或其他写入时使缓存失效，防止重复追加或创建残缺历史。 */
    public static final DefaultRedisScript<Long> APPEND_HISTORY_IF_VERSION_MATCH = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1]) or '0'
            local version = redis.call('INCR', KEYS[1])
            redis.call('PERSIST', KEYS[1])
            if current ~= ARGV[1] then
                redis.call('DEL', KEYS[2])
            elseif redis.call('EXISTS', KEYS[2]) == 1 then
                for i = 3, #ARGV do
                    redis.call('RPUSH', KEYS[2], ARGV[i])
                end
                redis.call('PEXPIRE', KEYS[2], ARGV[2])
            end
            return version
            """, Long.class);

    /** 回填成功后消耗租约，并原子设置数据 TTL，避免继承暂存 key 的过期时间。 */
    public static final DefaultRedisScript<Long> OVERWRITE_IF_VERSION_MATCH = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return -1
            end
            local current = redis.call('GET', KEYS[3]) or '0'
            if current ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 0
            end
            redis.call('INCR', KEYS[3])
            redis.call('PERSIST', KEYS[3])
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /** 清空数据同时递增版本，使清空前的在途回填失效。 */
    public static final DefaultRedisScript<Long> CLEAR_HISTORY_WITH_VERSION_BUMP = new DefaultRedisScript<>("""
            local version = redis.call('INCR', KEYS[2])
            redis.call('PERSIST', KEYS[2])
            redis.call('DEL', KEYS[1])
            return version
            """, Long.class);

    private RedisScripts() {
    }
}
