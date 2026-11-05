package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.domain.vo.LoginResultVO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 发送邮件验证码
     */
    void sendEmailCode(SendEmailCodeRequestDTO request);

    /**
     * 邮件验证码注册
     */
    Long registerByEmailCode(RegisterRequestDTO request);

    /**
     * 密码登录
     */
    LoginResultVO loginByPassword(PasswordLoginRequestDTO request);

    /**
     * 邮件验证码登录
     */
    LoginResultVO loginByEmailCode(EmailCodeLoginRequestDTO request);

    /**
     * 忘记密码-邮件验证码重置密码（仅 USER 角色）
     */
    void resetPasswordByEmailCode(ResetPasswordRequestDTO request);

    /**
     * 登出
     */
    void logout(String token);

    /**
     * 注销账户
     */
    void deleteAccount();

    /**
     * 创建账户
     * @param accountInfoDTO 账户信息
     * @return 账户ID
     */
    Long createAccount(AccountInfoDTO accountInfoDTO);

    /**
     * 检查用户名是否可用（仅 USER 角色）
     */
    boolean isUsernameAvailable(String username);
}
