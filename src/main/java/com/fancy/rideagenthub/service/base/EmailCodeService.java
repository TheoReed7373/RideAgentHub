package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.config.AuthProperties;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.enums.EmailScene;
import com.fancy.rideagenthub.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 邮件验证码服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;
    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    private static final Random RANDOM = new Random();

    /**
     * 发送验证码
     *
     * @param email 邮箱地址
     * @param scene 场景
     */
    public void sendCode(String email, EmailScene scene) {
        // 检查冷却
        if (isInCooldown(email, scene)) {
            throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
        }

        // 生成 6 位数字验证码
        String code = String.format("%06d", RANDOM.nextInt(1000000));

        // 存储验证码
        String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
        redisTemplate.opsForValue().set(codeKey, code, authProperties.getEmailCodeTtlSeconds(), TimeUnit.SECONDS);

        // 设置冷却
        String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
        redisTemplate.opsForValue().set(cooldownKey, "1", authProperties.getEmailCodeCooldownSeconds(),
                TimeUnit.SECONDS);

        // 发送邮件
        sendEmail(email, scene, code);

        log.info("Verification code sent to {} for scene {}", email, scene);
    }

    /**
     * 校验并消费验证码
     *
     * @param email 邮箱地址
     * @param scene 场景
     * @param code  验证码
     * @return 验证是否成功
     */
    public boolean verifyAndConsume(String email, EmailScene scene, String code) {
        String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
        String storedCode = redisTemplate.opsForValue().get(codeKey);

        if (storedCode == null) {
            return false;
        }

        if (!storedCode.equals(code)) {
            return false;
        }

        // 验证成功，删除验证码（一次性消费）
        redisTemplate.delete(codeKey);
        return true;
    }

    /**
     * 检查是否在冷却期
     */
    public boolean isInCooldown(String email, EmailScene scene) {
        String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
        return Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey));
    }

    private void sendEmail(String email, EmailScene scene, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            // 部分 SMTP 服务商（如 QQ/Foxmail）要求 MAIL FROM 必须与认证用户名一致
            message.setFrom(mailProperties.getUsername());
            message.setTo(email);
            message.setSubject(getEmailSubject(scene));
            message.setText(getEmailContent(scene, code));

            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send email to {}", email, e);
            // 发送失败时删除验证码和冷却，允许重试
            String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
            String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
            redisTemplate.delete(codeKey);
            redisTemplate.delete(cooldownKey);
            throw new BusinessException(500, "邮件发送失败，请稍后重试");
        }
    }

    private String getEmailSubject(EmailScene scene) {
        return switch (scene) {
            case REGISTER -> "【RideAgentHub】注册验证码";
            case LOGIN -> "【RideAgentHub】登录验证码";
            case RESET_PASSWORD -> "【RideAgentHub】重置密码验证码";
        };
    }

    private String getEmailContent(EmailScene scene, String code) {
        String action = switch (scene) {
            case REGISTER -> "注册";
            case LOGIN -> "登录";
            case RESET_PASSWORD -> "重置密码";
        };
        return String.format("""
                您好！

                您正在进行%s操作，验证码为：%s

                验证码有效期为 %d 分钟，请勿泄露给他人。

                如非本人操作，请忽略此邮件。

                RideAgentHub 团队
                """, action, code, authProperties.getEmailCodeTtlSeconds() / 60);
    }
}
