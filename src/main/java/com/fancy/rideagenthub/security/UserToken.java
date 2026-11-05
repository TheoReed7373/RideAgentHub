package com.fancy.rideagenthub.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户令牌信息
 * 存储在 Redis 中，用于鉴权上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户业务 ID（来自 UserAuth.userId）
     */
    private Long userId;

    /**
     * 认证记录 ID（来自 UserAuth.id）
     */
    private Long authId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 角色（大写）
     */
    private String role;

    /**
     * Token 字符串
     */
    private String token;

    /**
     * 签发时间（毫秒时间戳）
     */
    private long issuedAtEpochMs;

    /**
     * 过期时间（毫秒时间戳）
     */
    private long expiresAtEpochMs;
}
