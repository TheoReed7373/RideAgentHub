package com.fancy.rideagenthub.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.rideagenthub.config.AuthProperties;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.security.UserToken;
import com.fancy.rideagenthub.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Token 管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void saveToken(UserToken userToken) {
        String key = RedisKeyConstants.tokenKey(userToken.getToken());
        try {
            String json = objectMapper.writeValueAsString(userToken);
            redisTemplate.opsForValue().set(key, json, authProperties.getTokenTtlSeconds(), TimeUnit.SECONDS);
            // 添加到用户 Token 索引
            addTokenToUserIndex(userToken.getUserId(), userToken.getToken());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize UserToken", e);
            throw new RuntimeException("Token 序列化失败", e);
        }
    }

    @Override
    public UserToken getToken(String token) {
        String key = RedisKeyConstants.tokenKey(token);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, UserToken.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize UserToken", e);
            return null;
        }
    }

    @Override
    public void deleteToken(String token) {
        // 先获取 token 以便移除索引
        UserToken userToken = getToken(token);
        if (userToken != null) {
            removeTokenFromUserIndex(userToken.getUserId(), token);
        }
        String key = RedisKeyConstants.tokenKey(token);
        redisTemplate.delete(key);
    }

    @Override
    public void deleteAllTokensByUserId(Long userId) {
        String indexKey = RedisKeyConstants.userTokensKey(userId);
        Set<String> tokens = redisTemplate.opsForSet().members(indexKey);
        if (tokens != null && !tokens.isEmpty()) {
            for (String token : tokens) {
                String tokenKey = RedisKeyConstants.tokenKey(token);
                redisTemplate.delete(tokenKey);
            }
        }
        redisTemplate.delete(indexKey);
    }

    @Override
    public void addTokenToUserIndex(Long userId, String token) {
        String indexKey = RedisKeyConstants.userTokensKey(userId);
        redisTemplate.opsForSet().add(indexKey, token);
        // 设置索引过期时间为 token TTL 的 2 倍（防止索引提前过期）
        redisTemplate.expire(indexKey, authProperties.getTokenTtlSeconds() * 2, TimeUnit.SECONDS);
    }

    @Override
    public void removeTokenFromUserIndex(Long userId, String token) {
        String indexKey = RedisKeyConstants.userTokensKey(userId);
        redisTemplate.opsForSet().remove(indexKey, token);
    }
}
