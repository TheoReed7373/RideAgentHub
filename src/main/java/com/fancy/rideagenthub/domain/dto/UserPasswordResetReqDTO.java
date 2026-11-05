package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 当前用户重置密码请求
 */
@Data
public class UserPasswordResetReqDTO {

    /**
     * 新密码
     */
    private String password;
}
