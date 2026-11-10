package com.fancy.rideagenthub.util;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

public final class RedisScripts {

    /** 同时更换版本并移除旧状态池，阻止并发重建发布旧快照。 */
    public static final DefaultRedisScript<Long> INVALIDATE_TICKET_POOLS = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1])
            for i = 2, #KEYS do
                redis.call('DEL', KEYS[i])
            end
            return 1
            """, Long.class);

    /** 确认版本与重建锁仍有效，再原子替换完整索引及其就绪标记。 */
    public static final DefaultRedisScript<Long> PUBLISH_TICKET_POOL = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return 0
            end
            redis.call('DEL', KEYS[3], KEYS[4])
            for i = 4, #ARGV, 2 do
                redis.call('ZADD', KEYS[4], ARGV[i], ARGV[i + 1])
            end
            local count = redis.call('ZCARD', KEYS[4])
            redis.call('SET', KEYS[3], ARGV[1] .. ':' .. count, 'PX', ARGV[3])
            redis.call('PEXPIRE', KEYS[4], ARGV[3])
            return 1
            """, Long.class);

    /** 原子校验版本、成员数量并读取总数和当前页；索引缺失不能伪装成空池。 */
    public static final DefaultRedisScript<List> READ_TICKET_POOL = new DefaultRedisScript<>("""
            local version = redis.call('GET', KEYS[1])
            local count = redis.call('ZCARD', KEYS[3])
            if not version or redis.call('GET', KEYS[2]) ~= version .. ':' .. count then
                return {}
            end
            local result = {version, tostring(count)}
            local members = redis.call('ZREVRANGE', KEYS[3], ARGV[1], ARGV[2])
            for _, member in ipairs(members) do
                result[#result + 1] = member
            end
            return result
            """, List.class);

    /** 在同一次 Redis 操作中领取并移除到期任务，保护同时发生的改期。 */
    public static final DefaultRedisScript<List> CLAIM_DUE_ORDERS = new DefaultRedisScript<>("""
            local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
            for _, orderId in ipairs(orders) do
                redis.call('ZREM', KEYS[1], orderId)
            end
            return orders
            """, List.class);

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

    /** 检查订单状态与司机占位，并原子占用司机。 */
    public static final DefaultRedisScript<Long> GRAB_ORDER_ATOMIC = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return -2
            end
            redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
            return 1
            """, Long.class);

    /** 订单状态单调推进，防止先前操作的延迟同步覆盖后续状态。 */
    public static final DefaultRedisScript<Long> SYNC_ORDER_STATUS = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == false or tonumber(current) == nil or tonumber(current) <= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

    /** 原子清理过期记录、检查限额并登记本次调用。 */
    public static final DefaultRedisScript<Long> SLIDING_WINDOW_RATE_LIMIT = new DefaultRedisScript<>("""
            redis.replicate_commands()
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local window = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            local used = redis.call('ZCARD', KEYS[1])
            if used >= limit then
                return -1
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return limit - used - 1
            """, Long.class);

    private RedisScripts() {
    }
}
