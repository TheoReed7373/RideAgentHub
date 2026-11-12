package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.security.UserToken;

/**
 * Token 管理服务
 */
public interface TokenService {
    long currentVersion(Long userId);

    /**
     * 生成无 `-` 的 UUID Token
     */
    String generateToken();

    /**
     * 保存 Token 到 Redis
     */
    void saveToken(UserToken userToken);

    /**
     * 从 Redis 获取 Token
     */
    UserToken getToken(String token);

    /**
     * 删除 Token
     */
    void deleteToken(String token);

    /**
     * 删除用户的所有 Token
     */
    void deleteAllTokensByUserId(Long userId);

    /**
     * 将 Token 添加到用户的 Token 索引集合
     */
    void addTokenToUserIndex(Long userId, String token);

    /**
     * 从用户的 Token 索引集合移除 Token
     */
    void removeTokenFromUserIndex(Long userId, String token);
}
