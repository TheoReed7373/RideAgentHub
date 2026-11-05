package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 管理员用户分页查询请求DTO
 */
@Data
public class AdminUserPageReqDTO {

    /**
     * 用户名模糊搜索
     */
    private String username;

    /**
     * 是否查询已删除用户
     */
    private Boolean deleted;

    /**
     * 角色筛选
     */
    private String role;

    /**
     * 当前页码
     */
    private long current = 1;

    /**
     * 每页大小
     */
    private long size = 10;
}
