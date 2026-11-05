package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 密码登录请求
 */
@Data
public class PasswordLoginRequestDTO {

    /**
     * 登录标识：email/username 或 username#role
     */
    private String login;

    /**
     * 密码
     */
    private String password;
}
