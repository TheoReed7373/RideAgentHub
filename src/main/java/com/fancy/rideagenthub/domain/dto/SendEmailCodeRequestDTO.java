package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 发送邮件验证码请求
 */
@Data
public class SendEmailCodeRequestDTO {

    /**
     * 邮箱地址
     */
    private String email;

    /**
     * 场景: REGISTER / LOGIN / RESET_PASSWORD
     */
    private String scene;
}
