package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 邮件验证码登录请求
 */
@Data
public class EmailCodeLoginRequestDTO {

    /**
     * 邮箱地址
     */
    private String email;

    /**
     * 验证码
     */
    private String code;
}
