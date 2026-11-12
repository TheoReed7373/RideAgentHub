package com.fancy.rideagenthub.agentbase.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.domain.entity.ChatToolResponse;
import com.fancy.rideagenthub.mapper.ChatToolResponseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@Slf4j
public class ToolResponseMemory {
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redis;
    private final ChatToolResponseMapper mapper;
    public ToolResponseMemory(StringRedisTemplate redis, ChatToolResponseMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }
    public void save(String callId, String chatId, String response) {
        if (callId == null || callId.isBlank() || chatId == null || chatId.isBlank() || response == null) return;
        mapper.insert(ChatToolResponse.builder().callId(callId).chatId(chatId).response(response).build());
        cache(chatId, callId, response);
    }
    public String get(String callId, String chatId) {
        if (callId == null || callId.isBlank() || chatId == null || chatId.isBlank()) return null;
        try {
            String cached = redis.opsForValue().get(key(chatId, callId));
            if (cached != null) return cached;
        } catch (RuntimeException e) { log.warn("工具结果缓存读取失败", e); }
        ChatToolResponse row = mapper.selectOne(new LambdaQueryWrapper<ChatToolResponse>()
                .eq(ChatToolResponse::getCallId, callId).eq(ChatToolResponse::getChatId, chatId)
                .orderByDesc(ChatToolResponse::getId).last("LIMIT 1"));
        if (row == null) return null;
        cache(chatId, callId, row.getResponse());
        return row.getResponse();
    }
    private String key(String chatId, String callId) { return "chat:tool:v2:" + chatId + ":" + callId; }
    private void cache(String chatId, String callId, String response) {
        try { redis.opsForValue().set(key(chatId, callId), response, TTL); }
        catch (RuntimeException e) { log.warn("工具结果缓存写入失败，已持久化", e); }
    }
}
