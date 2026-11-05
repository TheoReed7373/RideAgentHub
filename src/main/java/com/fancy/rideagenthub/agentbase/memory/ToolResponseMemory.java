package com.fancy.rideagenthub.agentbase.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.ChatToolResponse;
import com.fancy.rideagenthub.mapper.ChatToolResponseMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolResponseMemory {

    private static final Duration DEFAULT_TOOL_CACHE_TTL = Duration.ofHours(24);

    private final Map<String, String> heapCache = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ChatToolResponseMapper chatToolResponseMapper;

    public ToolResponseMemory(StringRedisTemplate redisTemplate, ChatToolResponseMapper chatToolResponseMapper) {
        this.redisTemplate = redisTemplate;
        this.chatToolResponseMapper = chatToolResponseMapper;
    }

    public void save(String callId, String chatId, String response) {
        if (callId == null || callId.isBlank() || chatId == null || chatId.isBlank() || response == null) {
            return;
        }
        heapCache.put(callId, response);

        String key = RedisKeyConstants.toolResponseKey(callId);
        redisTemplate.opsForValue().set(key, response, DEFAULT_TOOL_CACHE_TTL);

        ChatToolResponse row = ChatToolResponse.builder()
                .callId(callId)
                .chatId(chatId)
                .response(response)
                .build();
        chatToolResponseMapper.insert(row);
    }

    public String get(String callId) {
        if (callId == null || callId.isBlank()) {
            return null;
        }
        String inHeap = heapCache.get(callId);
        if (inHeap != null) {
            return inHeap;
        }
        String key = RedisKeyConstants.toolResponseKey(callId);
        String inRedis = redisTemplate.opsForValue().get(key);
        if (inRedis != null) {
            heapCache.put(callId, inRedis);
            redisTemplate.expire(key, DEFAULT_TOOL_CACHE_TTL);
            return inRedis;
        }
        ChatToolResponse row = chatToolResponseMapper.selectOne(
                new LambdaQueryWrapper<ChatToolResponse>()
                        .eq(ChatToolResponse::getCallId, callId)
                        .orderByDesc(ChatToolResponse::getId)
                        .last("limit 1")
        );
        if (row == null) {
            return null;
        }
        heapCache.put(callId, row.getResponse());
        redisTemplate.opsForValue().set(key, row.getResponse(), DEFAULT_TOOL_CACHE_TTL);
        return row.getResponse();
    }
}
