package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 忘记密码-验证码重置密码请求
 */
@Data
public class ResetPasswordRequestDTO {

    /**
     * 邮箱地址
     */
    private String email;

    /**
     * 验证码
     */
    private String code;

    /**
     * 新密码
     */
    private String newPassword;
}
