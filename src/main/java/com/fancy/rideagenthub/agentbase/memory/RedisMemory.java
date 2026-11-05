package com.fancy.rideagenthub.agentbase.memory;

import com.fancy.rideagenthub.constant.RedisKeyConstants;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class RedisMemory {

    private final StringRedisTemplate redisTemplate;
    private final MessageParser messageParser;

    public RedisMemory(StringRedisTemplate redisTemplate, MessageParser messageParser) {
        this.redisTemplate = redisTemplate;
        this.messageParser = messageParser;
    }

    public List<Message> getLastN(String chatId, int lastN) {
        if (chatId == null || lastN <= 0) {
            return List.of();
        }
        String key = RedisKeyConstants.chatHistoryKey(chatId);
        Long sizeObj = redisTemplate.opsForList().size(key);
        long size = sizeObj == null ? 0 : sizeObj;
        if (size <= 0) {
            return List.of();
        }
        long start = Math.max(0, size - (long) lastN);
        long end = size - 1;
        List<String> jsonList = redisTemplate.opsForList().range(key, start, end);
        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            if (json == null || json.isBlank()) {
                continue;
            }
            Message msg = messageParser.unparse(json);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    public List<Message> getAll(String chatId) {
        if (chatId == null) {
            return List.of();
        }
        String key = RedisKeyConstants.chatHistoryKey(chatId);
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            if (json == null || json.isBlank()) {
                continue;
            }
            Message msg = messageParser.unparse(json);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    public void append(String chatId, List<Message> messages) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return;
        }
        String key = RedisKeyConstants.chatHistoryKey(chatId);
        List<String> jsonList = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            String json = messageParser.parse(message);
            if (json != null && !json.isBlank()) {
                jsonList.add(json);
            }
        }
        if (!jsonList.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(key, jsonList);
        }
    }

    public void overwrite(String chatId, List<Message> messages) {
        if (chatId == null) {
            return;
        }
        clear(chatId);
        append(chatId, messages);
    }

    public void expire(String chatId, Duration duration) {
        if (chatId == null || duration == null) {
            return;
        }
        redisTemplate.expire(RedisKeyConstants.chatHistoryKey(chatId), duration);
    }

    public void clear(String chatId) {
        if (chatId == null) {
            return;
        }
        redisTemplate.delete(RedisKeyConstants.chatHistoryKey(chatId));
    }
}
