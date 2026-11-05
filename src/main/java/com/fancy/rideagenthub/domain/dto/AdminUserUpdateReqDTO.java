package com.fancy.rideagenthub.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 管理员更新用户信息请求DTO
 */
@Data
public class AdminUserUpdateReqDTO {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名
     */
    @JsonAlias({"userName"})
    private String username;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 密码
     */
    private String password;

    /**
     * 角色
     */
    private String role;
}
