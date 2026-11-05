package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultVO {

    /**
     * 认证 Token
     */
    private String token;

    /**
     * Token 有效期（秒）
     */
    private long expiresInSec;

    /**
     * 用户角色
     */
    private String role;

    /**
     * 用户 ID
     */
    private String userId;
}
