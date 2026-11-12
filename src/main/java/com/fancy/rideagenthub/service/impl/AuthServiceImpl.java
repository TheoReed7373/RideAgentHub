package com.fancy.rideagenthub.service.impl;

import com.fancy.rideagenthub.config.AuthProperties;
import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.domain.vo.LoginResultVO;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.fancy.rideagenthub.domain.enums.EmailScene;
import com.fancy.rideagenthub.domain.enums.UserRole;
import com.fancy.rideagenthub.exception.UnauthorizedException;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.security.UserToken;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.AuthService;
import com.fancy.rideagenthub.service.TokenService;
import com.fancy.rideagenthub.service.UserAuthService;
import com.fancy.rideagenthub.service.base.EmailCodeService;
import com.fancy.rideagenthub.service.base.UserUsernameBloomFilterService;
import com.fancy.rideagenthub.util.SnowflakeIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserAuthService userAuthService;
    private final TokenService tokenService;
    private final EmailCodeService emailCodeService;
    private final AuthProperties authProperties;
    private final UserUsernameBloomFilterService usernameBloomFilterService;

    public AuthServiceImpl(UserAuthService userAuthService,
                           TokenService tokenService,
                           EmailCodeService emailCodeService,
                           AuthProperties authProperties,
                           UserUsernameBloomFilterService usernameBloomFilterService) {
        this.userAuthService = userAuthService;
        this.tokenService = tokenService;
        this.emailCodeService = emailCodeService;
        this.authProperties = authProperties;
        this.usernameBloomFilterService = usernameBloomFilterService;
    }

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Snowflake ID generator (workerId=0, datacenterId=0)
    @jakarta.annotation.Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Override
    public void sendEmailCode(SendEmailCodeRequestDTO request) {
        if (request == null) {
            throw new BusinessException(400, "请求不能为空");
        }
        validateEmail(request.getEmail());
        EmailScene scene = EmailScene.fromString(request.getScene());

        // 重置密码场景：必须校验用户存在，避免向未注册邮箱发送重置验证码
        if (scene == EmailScene.RESET_PASSWORD) {
            UserAuth userAuth = userAuthService.findUserRoleByEmail(request.getEmail());
            if (userAuth == null) {
                throw new BusinessException(404, "账号不存在");
            }
        }

        emailCodeService.sendCode(request.getEmail(), scene);
    }

    @Override
    @Transactional
    public Long registerByEmailCode(RegisterRequestDTO request) {
        if (request == null) throw new BusinessException(400, "请求不能为空");
        validateEmail(request.getEmail());
        if (request.getPassword() == null || request.getPassword().isBlank()) throw new BusinessException(400, "密码不能为空");
        if (request.getUsername() != null && !isUsernameAvailable(request.getUsername())) throw new BusinessException(409, "用户名已存在");
        if (userAuthService.existsByEmailAndRole(request.getEmail(), UserRole.USER.name())) throw new BusinessException(409, "该邮箱已注册");

        // 校验验证码
        if (!emailCodeService.verifyAndConsume(request.getEmail(), EmailScene.REGISTER, request.getCode())) {
            throw new BusinessException(400, "验证码无效或已过期");
        }

        // 生成 userId
        Long userId = snowflakeIdWorker.nextId();

        // 创建用户
        UserAuth userAuth = UserAuth.builder()
                .userId(userId)
                .email(request.getEmail())
                .username(request.getUsername() != null ? request.getUsername() : "user_" + userId)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER.name())
                .status(1)
                .isDeleted(0)
                .build();

        userAuthService.createUser(userAuth);
        usernameBloomFilterService.addUsername(userAuth.getUsername());

        log.info("User registered: userId={}, email={}", userId, request.getEmail());
        return userId;
    }

    @Override
    public LoginResultVO loginByPassword(PasswordLoginRequestDTO request) {
        if (request == null || request.getPassword() == null || request.getPassword().isBlank()) throw new BusinessException(400, "登录参数不完整");
        // 解析登录标识
        LoginPrincipal principal = parseLoginPrincipal(request.getLogin());

        // 查询用户
        UserAuth userAuth = findUserByPrincipal(principal);
        if (userAuth == null) {
            throw new UnauthorizedException("用户名或密码错误");
        }

        long version = tokenService.currentVersion(userAuth.getUserId());
        userAuth = findUserByPrincipal(principal);
        if (userAuth == null) throw new UnauthorizedException("账号状态已变化");
        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), userAuth.getPassword())) {
            throw new UnauthorizedException("用户名或密码错误");
        }

        return issueLoginResultVO(userAuth, version);
    }

    @Override
    public LoginResultVO loginByEmailCode(EmailCodeLoginRequestDTO request) {
        if (request == null) throw new BusinessException(400, "请求不能为空");
        validateEmail(request.getEmail());

        // 校验验证码
        if (!emailCodeService.verifyAndConsume(request.getEmail(), EmailScene.LOGIN, request.getCode())) {
            throw new BusinessException(400, "验证码无效或已过期");
        }

        // 邮件验证码登录仅允许 USER 角色
        UserAuth userAuth = userAuthService.findUserRoleByEmail(request.getEmail());
        if (userAuth == null) {
            throw new UnauthorizedException("账号不存在");
        }

        return issueLoginResultVO(userAuth, tokenService.currentVersion(userAuth.getUserId()));
    }

    @Override
    @Transactional
    public void resetPasswordByEmailCode(ResetPasswordRequestDTO request) {
        if (request == null) {
            throw new BusinessException(400, "请求不能为空");
        }

        validateEmail(request.getEmail());

        String newPassword = request.getNewPassword();
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException(400, "新密码不能为空");
        }

        // 校验验证码
        if (!emailCodeService.verifyAndConsume(request.getEmail(), EmailScene.RESET_PASSWORD, request.getCode())) {
            throw new BusinessException(400, "验证码无效或已过期");
        }

        // 仅允许 USER 角色通过邮箱重置密码
        UserAuth userAuth = userAuthService.findUserRoleByEmail(request.getEmail());
        if (userAuth == null) {
            throw new UnauthorizedException("账号不存在");
        }

        userAuthService.updatePasswordByUserId(userAuth.getUserId(), passwordEncoder.encode(newPassword));

        // 重置密码后，强制失效该用户全部 token
        tokenService.deleteAllTokensByUserId(userAuth.getUserId());

        log.info("Password reset: userId={}, email={}", userAuth.getUserId(), request.getEmail());
    }

    @Override
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokenService.deleteToken(token);
        }
    }

    @Override
    @Transactional
    public void deleteAccount() {
        UserToken currentUser = UserTokenContext.getRequired();

        // 逻辑删除用户
        userAuthService.logicalDeleteByUserId(currentUser.getUserId());

        // 删除所有 token
        tokenService.deleteAllTokensByUserId(currentUser.getUserId());

        log.info("Account deleted: userId={}", currentUser.getUserId());
    }

    @Override
    public Long createAccount(AccountInfoDTO accountInfoDTO) {
        if (accountInfoDTO == null) {
            throw new BusinessException(400, "账户信息不能为空");
        }

        String username = accountInfoDTO.getUserName();
        if (username == null || username.isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (username.contains("@")) {
            throw new BusinessException(400, "用户名不能包含@符号");
        }

        String rawPassword = accountInfoDTO.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(400, "密码不能为空");
        }

        UserRole role;
        try {
            role = UserRole.fromString(accountInfoDTO.getRole());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "角色不合法: " + accountInfoDTO.getRole());
        }

        // 检查用户名+角色是否已存在
        UserAuth existing = userAuthService.findByUsernameAndRole(username, role.name());
        if (existing != null) {
            throw new BusinessException(409, "该用户名已存在");
        }

        Long userId = snowflakeIdWorker.nextId();
        UserAuth userAuth = UserAuth.builder()
                .userId(userId)
                .username(username)
                .email(null)
                .password(passwordEncoder.encode(rawPassword))
                .role(role.name())
                .status(1)
                .isDeleted(0)
                .build();

        userAuthService.createUser(userAuth);
        if (UserRole.USER.name().equals(role.name())) {
            usernameBloomFilterService.addUsername(userAuth.getUsername());
        }
        log.info("Account created: userId={}, username={}, role={}", userId, username, role.name());
        return userId;
    }

    @Override
    public boolean isUsernameAvailable(String username) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (username.contains("@")) {
            throw new BusinessException(400, "用户名不能包含@符号");
        }

        UserAuth existing = userAuthService.findByUsernameAndRole(username, UserRole.USER.name());
        return existing == null;
    }

    /**
     * 签发 Token
     */
    private LoginResultVO issueLoginResultVO(UserAuth userAuth, long version) {
        String token = tokenService.generateToken();
        long now = System.currentTimeMillis();
        long expiresAt = now + authProperties.getTokenTtlSeconds() * 1000;

        UserToken userToken = UserToken.builder()
                .userId(userAuth.getUserId())
                .authId(userAuth.getId())
                .username(userAuth.getUsername())
                .email(userAuth.getEmail())
                .role(userAuth.getRole())
                .token(token)
                .issuedAtEpochMs(now)
                .sessionVersion(version)
                .credentialStamp(com.fancy.rideagenthub.security.CredentialStamp.of(userAuth.getPassword()))
                .expiresAtEpochMs(expiresAt)
                .build();

        tokenService.saveToken(userToken);

        // 更新最后登录时间
        userAuthService.updateLastLoginTime(userAuth.getUserId());

        log.info("User logged in: userId={}, role={}", userAuth.getUserId(), userAuth.getRole());

        return LoginResultVO.builder()
                .token(token)
                .expiresInSec(authProperties.getTokenTtlSeconds())
                .role(userAuth.getRole())
                .userId(userAuth.getUserId().toString())
                .build();
    }

    /**
     * 解析登录标识
     */
    private LoginPrincipal parseLoginPrincipal(String login) {
        if (login == null || login.isBlank()) {
            throw new BusinessException(400, "登录标识不能为空");
        }

        if (login.contains("#")) {
            // username#role 形式
            String[] parts = login.split("#", 2);
            String username = parts[0].trim();
            String roleStr = parts[1].trim();

            if (username.isEmpty() || roleStr.isEmpty()) {
                throw new BusinessException(400, "登录格式错误");
            }

            // 禁止 email#role 形式
            if (username.contains("@")) {
                throw new BusinessException(400, "后台登录请使用 username#role 格式，不支持邮箱");
            }

            UserRole role = UserRole.fromString(roleStr);
            return new LoginPrincipal(username, null, role);
        } else {
            // email 或 username，默认 USER 角色
            if (login.contains("@")) {
                return new LoginPrincipal(null, login, UserRole.USER);
            } else {
                return new LoginPrincipal(login, null, UserRole.USER);
            }
        }
    }

    /**
     * 根据解析后的标识查询用户
     */
    private UserAuth findUserByPrincipal(LoginPrincipal principal) {
        if (principal.email != null) {
            return userAuthService.findUserRoleByEmail(principal.email);
        } else {
            return userAuthService.findByUsernameAndRole(principal.username, principal.role.name());
        }
    }

    /**
     * 验证邮箱格式
     */
    private void validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(400, "邮箱不能为空");
        }
        if (!email.contains("@") || !email.contains(".")) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
    }

    /**
     * 登录标识解析结果
     */
    private record LoginPrincipal(String username, String email, UserRole role) {
    }
}
