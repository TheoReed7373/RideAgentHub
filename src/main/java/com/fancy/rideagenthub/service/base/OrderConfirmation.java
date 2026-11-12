package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderConfirmation {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> CONFIRM = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'break')
            if redis.call('HGET', KEYS[1], 'quoteId') ~= ARGV[1] then return 0 end
            if ARGV[2] == 'yes' and tonumber(ARGV[3]) - tonumber(redis.call('HGET', KEYS[1], 'quoteCreatedAt') or '0') > 900000 then return 0 end
            if state == 'resuming' then
                if redis.call('HGET', KEYS[1], 'confirmationAccepted') == ARGV[2] then return 1 end
                return 0
            end
            if state ~= 'yes' then return 0 end
            redis.call('HSET', KEYS[1], 'break', 'resuming')
            redis.call('HSET', KEYS[1], 'confirmationAccepted', ARGV[2])
            if ARGV[2] == 'yes' then
                redis.call('HSET', KEYS[1], 'confirmedQuote', ARGV[1], 'ReadyForCreate', ARGV[1])
            else redis.call('HDEL', KEYS[1], 'confirmedQuote', 'ReadyForCreate') end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local quote = redis.call('HGET', KEYS[1], 'quoteId')
            if tonumber(ARGV[1]) - tonumber(redis.call('HGET', KEYS[1], 'quoteCreatedAt') or '0') > 900000 then return 0 end
            if not quote or redis.call('HGET', KEYS[1], 'confirmedQuote') ~= quote or
               redis.call('HGET', KEYS[1], 'ReadyForCreate') ~= quote then return 0 end
            redis.call('HDEL', KEYS[1], 'ReadyForCreate', 'confirmedQuote')
            return 1
            """, Long.class);

    public void quoted(String chatId) {
        var hash = redis.opsForHash();
        String key = RedisKeyConstants.chatInfoKey(chatId);
        hash.delete(key, "ReadyForCreate", "confirmedQuote");
        hash.putAll(key, java.util.Map.of("quoteId", UUID.randomUUID().toString(), "ReadyforConfirm", "true",
                "quoteCreatedAt", String.valueOf(System.currentTimeMillis())));
        redis.expire(key, java.time.Duration.ofHours(24));
    }

    public String begin(String chatId, String toolCallId) {
        String key = RedisKeyConstants.chatInfoKey(chatId);
        Object quote = redis.opsForHash().get(key, "quoteId");
        if (quote == null || redis.opsForHash().get(key, "ReadyforConfirm") == null) {
            throw new BusinessException(409, "请先完成有效报价");
        }
        redis.opsForHash().putAll(key, java.util.Map.of("pendingToolCall", toolCallId, "break", "yes"));
        return quote.toString();
    }

    public void confirm(String chatId, String confirmationId, Boolean accepted) {
        if (confirmationId == null || accepted == null) throw new BusinessException(400, "请提供确认编号和明确的确认结果");
        Long result = redis.execute(CONFIRM, List.of(RedisKeyConstants.chatInfoKey(chatId)),
                confirmationId, accepted ? "yes" : "no", String.valueOf(System.currentTimeMillis()));
        if (!Long.valueOf(1).equals(result)) throw new BusinessException(409, "确认已失效或已被使用，请重新核对订单");
    }

    public boolean isConfirmed(String chatId) {
        String key = RedisKeyConstants.chatInfoKey(chatId);
        Object quote = redis.opsForHash().get(key, "quoteId");
        return quote != null && quote.equals(redis.opsForHash().get(key, "confirmedQuote"))
                && quote.equals(redis.opsForHash().get(key, "ReadyForCreate"));
    }

    public void consume(String chatId) {
        if (!Long.valueOf(1).equals(redis.execute(CONSUME, List.of(RedisKeyConstants.chatInfoKey(chatId)), String.valueOf(System.currentTimeMillis())))) {
            throw new BusinessException(409, "尚未取得有效的用户确认，或本次确认已使用");
        }
    }
}
