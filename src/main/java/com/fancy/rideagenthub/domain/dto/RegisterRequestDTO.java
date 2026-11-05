package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 邮件验证码注册请求
 */
@Data
public class RegisterRequestDTO {

    /**
     * 邮箱地址
     */
    private String email;

    /**
     * 验证码
     */
    private String code;

    /**
     * 密码
     */
    private String password;

    /**
     * 用户名（可选）
     */
    private String username;
}
