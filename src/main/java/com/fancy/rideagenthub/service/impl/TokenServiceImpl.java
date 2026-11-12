package com.fancy.rideagenthub.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.rideagenthub.config.AuthProperties;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.security.*;
import com.fancy.rideagenthub.service.TokenService;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {
    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    @jakarta.annotation.Resource private UserAuthMapper users;
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            if (redis.call('GET', KEYS[3]) or '0') ~= ARGV[4] then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]) * 2)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[2])
            local tokens = redis.call('SMEMBERS', KEYS[1])
            for _, token in ipairs(tokens) do redis.call('DEL', ARGV[1] .. token) end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private String versionKey(Long userId) { return "auth:session-version:" + userId; }
    @Override public String generateToken() { return UUID.randomUUID().toString().replace("-", ""); }
    @Override public long currentVersion(Long userId) {
        String value = redisTemplate.opsForValue().get(versionKey(userId));
        return value == null ? 0L : Long.parseLong(value);
    }
    @Override public void saveToken(UserToken token) {
        if (token.getUserId() == null || token.getRole() == null || token.getCredentialStamp() == null) {
            throw new UnauthorizedException("无法签发缺少身份信息的令牌");
        }
        try {
            Long saved = redisTemplate.execute(SAVE, List.of(RedisKeyConstants.tokenKey(token.getToken()),
                    RedisKeyConstants.userTokensKey(token.getUserId()), versionKey(token.getUserId())),
                    objectMapper.writeValueAsString(token), String.valueOf(authProperties.getTokenTtlSeconds()),
                    token.getToken(), String.valueOf(token.getSessionVersion()));
            if (!Long.valueOf(1).equals(saved)) throw new UnauthorizedException("登录状态已变化，请重新登录");
        } catch (JsonProcessingException e) { throw new IllegalStateException("Token序列化失败", e); }
    }
    @Override public UserToken getToken(String token) {
        String json = redisTemplate.opsForValue().get(RedisKeyConstants.tokenKey(token));
        if (json == null) return null;
        try {
            UserToken parsed = objectMapper.readValue(json, UserToken.class);
            if (parsed.getUserId() == null || parsed.getRole() == null || parsed.getCredentialStamp() == null
                    || parsed.getSessionVersion() != currentVersion(parsed.getUserId())) return null;
            // 密码与账号状态以数据库为准，撤销期间旧密码验证生成的令牌也不能复活。
            UserAuth user = users.selectOne(new LambdaQueryWrapper<UserAuth>()
                    .eq(UserAuth::getUserId, parsed.getUserId()).eq(UserAuth::getIsDeleted, 0).eq(UserAuth::getStatus, 1));
            if (user == null || !Objects.equals(user.getRole(), parsed.getRole())
                    || !Objects.equals(CredentialStamp.of(user.getPassword()), parsed.getCredentialStamp())) return null;
            return parsed;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("拒绝格式无效的登录令牌");
            return null;
        }
    }
    @Override public void deleteToken(String token) {
        String json = redisTemplate.opsForValue().get(RedisKeyConstants.tokenKey(token));
        if (json == null) return;
        try {
            UserToken parsed = objectMapper.readValue(json, UserToken.class);
            redisTemplate.execute(REMOVE, List.of(RedisKeyConstants.tokenKey(token),
                    RedisKeyConstants.userTokensKey(parsed.getUserId())), token);
        } catch (JsonProcessingException e) { redisTemplate.delete(RedisKeyConstants.tokenKey(token)); }
    }
    @Override public void deleteAllTokensByUserId(Long userId) {
        Runnable revoke = () -> redisTemplate.execute(REVOKE,
                List.of(RedisKeyConstants.userTokensKey(userId), versionKey(userId)), RedisKeyConstants.tokenKey(""));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { revoke.run(); }
            });
        } else revoke.run();
    }
    @Override public void addTokenToUserIndex(Long userId, String token) {
        throw new UnsupportedOperationException("令牌与索引必须由saveToken原子写入");
    }
    @Override public void removeTokenFromUserIndex(Long userId, String token) {
        deleteToken(token);
    }
}
