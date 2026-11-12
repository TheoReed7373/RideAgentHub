package com.fancy.rideagenthub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.service.UserAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户认证数据服务实现
 */
@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements UserAuthService {

    private final UserAuthMapper userAuthMapper;

    @Override
    public UserAuth findUserRoleByEmail(String email) {
        return userAuthMapper.selectOne(
                new LambdaQueryWrapper<UserAuth>()
                        .eq(UserAuth::getEmail, email.toLowerCase())
                        .eq(UserAuth::getRole, "USER")
                        .eq(UserAuth::getStatus, 1)
                        .eq(UserAuth::getIsDeleted, 0));
    }

    @Override
    public UserAuth findByUsernameAndRole(String username, String role) {
        return userAuthMapper.selectOne(
                new LambdaQueryWrapper<UserAuth>()
                        .eq(UserAuth::getUsername, username)
                        .eq(UserAuth::getRole, role.toUpperCase())
                        .eq(UserAuth::getStatus, 1)
                        .eq(UserAuth::getIsDeleted, 0));
    }

    @Override
    public UserAuth findByEmail(String email) {
        return userAuthMapper.selectOne(
                new LambdaQueryWrapper<UserAuth>()
                        .eq(UserAuth::getEmail, email.toLowerCase())
                        .eq(UserAuth::getStatus, 1)
                        .eq(UserAuth::getIsDeleted, 0));
    }

    @Override
    public boolean existsByEmailAndRole(String email, String role) {
        Long count = userAuthMapper.selectCount(
                new LambdaQueryWrapper<UserAuth>()
                        .eq(UserAuth::getEmail, email.toLowerCase())
                        .eq(UserAuth::getRole, role.toUpperCase())
                        .eq(UserAuth::getStatus, 1)
                        .eq(UserAuth::getIsDeleted, 0));
        return count != null && count > 0;
    }

    @Override
    public void createUser(UserAuth userAuth) {
        // 邮箱统一转小写
        if (userAuth.getEmail() != null) {
            userAuth.setEmail(userAuth.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
        }
        // 角色统一转大写
        if (userAuth.getRole() != null) {
            userAuth.setRole(userAuth.getRole().toUpperCase());
        }
        userAuth.setUsername(userAuth.getUsername().trim());
        userAuth.setCreateTime(LocalDateTime.now());
        userAuth.setUpdateTime(LocalDateTime.now());
        userAuthMapper.insert(userAuth);
    }

    @Override
    public void updateLastLoginTime(Long userId) {
        userAuthMapper.update(null,
                new LambdaUpdateWrapper<UserAuth>()
                        .eq(UserAuth::getUserId, userId)
                        .set(UserAuth::getLastLoginTime, LocalDateTime.now())
                        .set(UserAuth::getUpdateTime, LocalDateTime.now()));
    }

        @Override
        public void updatePasswordByUserId(Long userId, String encodedPassword) {
        userAuthMapper.update(null,
            new LambdaUpdateWrapper<UserAuth>()
                .eq(UserAuth::getUserId, userId)
                .eq(UserAuth::getStatus, 1)
                .eq(UserAuth::getIsDeleted, 0)
                .set(UserAuth::getPassword, encodedPassword)
                .set(UserAuth::getUpdateTime, LocalDateTime.now()));
        }

    @Override
    public void logicalDeleteByUserId(Long userId) {
        userAuthMapper.update(null,
                new LambdaUpdateWrapper<UserAuth>()
                        .eq(UserAuth::getUserId, userId)
                        .set(UserAuth::getIsDeleted, 1)
                        .set(UserAuth::getStatus, 0)
                        .set(UserAuth::getUpdateTime, LocalDateTime.now()));
    }
}
