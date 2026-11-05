package com.fancy.rideagenthub.controller;

import com.fancy.rideagenthub.annotation.RequirePermission;
import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.domain.vo.LoginResultVO;
import com.fancy.rideagenthub.domain.response.Result;
import com.fancy.rideagenthub.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";
    private static final String TOKEN_HEADER = "X-Auth-Token";

    /**
     * 发送邮件验证码
     * POST /auth/email-code
     */
    @PostMapping("/email-code")
    public Result sendEmailCode(@RequestBody SendEmailCodeRequestDTO request) {
        authService.sendEmailCode(request);
        return Result.ok();
    }

    /**
     * 邮件验证码注册
     * POST /auth/register
     */
    @PostMapping("/register")
    public Result register(@RequestBody RegisterRequestDTO request) {
        Long userId = authService.registerByEmailCode(request);
        return Result.ok(userId.toString());
    }

    /**
     * 密码登录
     * POST /auth/login/password
     */
    @PostMapping("/login/password")
    public Result loginByPassword(@RequestBody PasswordLoginRequestDTO request) {
        LoginResultVO response = authService.loginByPassword(request);
        return Result.ok(response);
    }

    /**
     * 邮件验证码登录
     * POST /auth/login/email-code
     */
    @PostMapping("/login/email-code")
    public Result loginByEmailCode(@RequestBody EmailCodeLoginRequestDTO request) {
        LoginResultVO response = authService.loginByEmailCode(request);
        return Result.ok(response);
    }

    /**
     * 忘记密码-邮件验证码重置密码
     * POST /auth/password/reset
     */
    @PostMapping("/password/reset")
    public Result resetPasswordByEmailCode(@RequestBody ResetPasswordRequestDTO request) {
        authService.resetPasswordByEmailCode(request);
        return Result.ok();
    }

    /**
     * 检测用户名是否可用（仅 USER 角色）
     * GET /auth/username/available
     */
    @GetMapping("/username/available")
    public Result checkUsernameAvailable(@RequestParam("username") String username) {
        boolean available = authService.isUsernameAvailable(username);
        return Result.ok(available);
    }

    /**
     * 登出
     * POST /auth/logout
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        String token = extractToken(request);
        authService.logout(token);
        return Result.ok();
    }

    /**
     * 注销账户
     * DELETE /auth/account
     */
    @DeleteMapping("/account")
    @RequirePermission
    public Result deleteAccount() {
        authService.deleteAccount();
        return Result.ok();
    }

    /**
     * 从请求头提取 Token
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        String tokenHeader = request.getHeader(TOKEN_HEADER);
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return tokenHeader.trim();
        }
        return null;
    }
}
