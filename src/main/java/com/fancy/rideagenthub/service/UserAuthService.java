package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.domain.entity.UserAuth;

/**
 * 用户认证数据服务接口
 */
public interface UserAuthService {

    /**
     * 根据邮箱查询 USER 角色的可用账号
     */
    UserAuth findUserRoleByEmail(String email);

    /**
     * 根据用户名和角色查询可用账号
     */
    UserAuth findByUsernameAndRole(String username, String role);

    /**
     * 根据邮箱查询可用账号（任意角色）
     */
    UserAuth findByEmail(String email);

    /**
     * 检查是否存在指定邮箱和角色的可用账号
     */
    boolean existsByEmailAndRole(String email, String role);

    /**
     * 创建用户
     */
    void createUser(UserAuth userAuth);

    /**
     * 更新最后登录时间
     */
    void updateLastLoginTime(Long userId);

    /**
     * 更新密码
     */
    void updatePasswordByUserId(Long userId, String encodedPassword);

    /**
     * 逻辑删除用户
     */
    void logicalDeleteByUserId(Long userId);
}
