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

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[5]) == 1 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[3])
            redis.call('SET', KEYS[4], ARGV[4], 'EX', ARGV[2])
            redis.call('DEL', KEYS[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return 0 end
            if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
            if tonumber(redis.call('GET', KEYS[2]) or '0') >= 5 then return 0 end
            if stored ~= ARGV[1] then
                local attempts = redis.call('INCR', KEYS[2])
                redis.call('PEXPIRE', KEYS[2], math.max(1, redis.call('PTTL', KEYS[1])))
                if attempts >= 5 then redis.call('DEL', KEYS[1]) end
                return 0
            end
            if ARGV[3] == 'reserve' then
                redis.call('SET', KEYS[3], ARGV[2], 'PX', math.max(1, redis.call('PTTL', KEYS[1])))
            else redis.call('DEL', KEYS[1], KEYS[2], KEYS[4]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CLEANUP = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[3]) == ARGV[1] then redis.call('DEL', KEYS[1], KEYS[3]) end
            if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[3]) ~= ARGV[1] then return 0 end
            if ARGV[2] == 'commit' then redis.call('DEL', KEYS[1], KEYS[2], KEYS[4]) end
            redis.call('DEL', KEYS[3])
            return 1
            """, Long.class);

    /**
     * 发送验证码
     *
     * @param email 邮箱地址
     * @param scene 场景
     */
    public void sendCode(String email, EmailScene scene) {
        email = email.trim().toLowerCase(Locale.ROOT);

        // 生成 6 位数字验证码
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        String issueId = java.util.UUID.randomUUID().toString();

        // 存储验证码
        String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);

        // 设置冷却
        String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
        Long issued = redisTemplate.execute(ISSUE, List.of(codeKey, cooldownKey, codeKey + ":attempts", codeKey + ":owner", codeKey + ":claim"), code,
                String.valueOf(authProperties.getEmailCodeTtlSeconds()), String.valueOf(authProperties.getEmailCodeCooldownSeconds()), issueId);
        if (!Long.valueOf(1).equals(issued)) throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");

        // 发送邮件
        sendEmail(email, scene, code, issueId);

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
        if (email == null || code == null || !code.matches("[0-9]{6}")) return false;
        email = email.trim().toLowerCase(Locale.ROOT);
        String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
        List<String> keys = List.of(codeKey, codeKey + ":attempts", codeKey + ":claim", codeKey + ":owner");
        String claimId = java.util.UUID.randomUUID().toString();
        boolean transactional = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        boolean verified = Long.valueOf(1).equals(redisTemplate.execute(VERIFY, keys, code, claimId, transactional ? "reserve" : "consume"));
        if (verified && transactional) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            try { redisTemplate.execute(COMPLETE, keys, claimId, status == STATUS_COMMITTED ? "commit" : "rollback"); }
                            catch (RuntimeException e) { log.warn("验证码预留状态清理失败，将按原有效期失效", e); }
                        }
                    });
        }
        return verified;
    }

    /**
     * 检查是否在冷却期
     */
    public boolean isInCooldown(String email, EmailScene scene) {
        email = email.trim().toLowerCase(Locale.ROOT);
        String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
        return Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey));
    }

    private void sendEmail(String email, EmailScene scene, String code, String issueId) {
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
            redisTemplate.execute(CLEANUP, List.of(codeKey, cooldownKey, codeKey + ":owner"), issueId);
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
