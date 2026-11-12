package com.fancy.rideagenthub;

import com.fancy.rideagenthub.config.AuthProperties;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.fancy.rideagenthub.domain.enums.EmailScene;
import com.fancy.rideagenthub.exception.*;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.security.*;
import com.fancy.rideagenthub.service.base.*;
import com.fancy.rideagenthub.service.impl.TokenServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")
class SecurityRedisIntegrationTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private String suffix;
    private EmailCodeService codes;
    private OrderConfirmation confirmation;
    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.redis.port")));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
    }
    @AfterAll static void close() { factory.destroy(); }
    @BeforeEach void setup() {
        suffix = UUID.randomUUID().toString();
        codes = new EmailCodeService(redis, new AuthProperties(), mock(JavaMailSender.class), new MailProperties());
        confirmation = new OrderConfirmation(redis);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys("*" + suffix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    @Test void verificationCodeCanOnlyBeConsumedOnceUnderConcurrency() throws Exception {
        String email = suffix + "@example.invalid";
        redis.opsForValue().set(RedisKeyConstants.emailCodeKey("LOGIN", email), "123456", Duration.ofMinutes(1));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 16; i++) attempts.add(() -> codes.verifyAndConsume(email, EmailScene.LOGIN, "123456"));
            long success = 0; for (var result : pool.invokeAll(attempts)) if (result.get()) success++;
            assertEquals(1, success);
        } finally { pool.shutdownNow(); }
    }
    @Test void fiveWrongGuessesInvalidateEvenTheCorrectCode() {
        String email = suffix + "@example.invalid";
        redis.opsForValue().set(RedisKeyConstants.emailCodeKey("LOGIN", email), "123456", Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) assertFalse(codes.verifyAndConsume(email, EmailScene.LOGIN, "000000"));
        assertFalse(codes.verifyAndConsume(email, EmailScene.LOGIN, "123456"));
    }
    @Test void onlyOneConcurrentSendAcquiresCooldown() throws Exception {
        var sender = mock(JavaMailSender.class);
        var service = new EmailCodeService(redis, new AuthProperties(), sender, new MailProperties());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) attempts.add(() -> {
                try { service.sendCode(suffix + "@example.invalid", EmailScene.LOGIN); return true; }
                catch (BusinessException e) { assertEquals(429, e.getCode()); return false; }
            });
            long success = 0; for (var result : pool.invokeAll(attempts)) if (result.get()) success++;
            assertEquals(1, success);
        } finally { pool.shutdownNow(); }
    }
    @Test void confirmationIsBoundToQuoteAndConsumedOnce() {
        confirmation.quoted(suffix); String id = confirmation.begin(suffix, "call");
        assertThrows(BusinessException.class, () -> confirmation.confirm(suffix, "other", true));
        confirmation.confirm(suffix, id, true);
        assertDoesNotThrow(() -> confirmation.confirm(suffix, id, true));
        assertThrows(BusinessException.class, () -> confirmation.confirm(suffix, id, false));
        assertTrue(confirmation.isConfirmed(suffix)); confirmation.consume(suffix);
        assertThrows(BusinessException.class, () -> confirmation.consume(suffix));
    }
    @Test void rejectionAndNewQuotesInvalidateCreationPermission() {
        confirmation.quoted(suffix); String id = confirmation.begin(suffix, "call");
        confirmation.confirm(suffix, id, false);
        assertThrows(BusinessException.class, () -> confirmation.consume(suffix));
        confirmation.quoted(suffix); String next = confirmation.begin(suffix, "next"); confirmation.confirm(suffix, next, true);
        confirmation.quoted(suffix);
        assertFalse(confirmation.isConfirmed(suffix));
    }
    @Test void revocationFencesLateTokenPublicationAndRemovesWholeIndex() {
        var service = new TokenServiceImpl(redis, new AuthProperties(), new ObjectMapper());
        long userId = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        String version = "auth:session-version:" + userId;
        try {
            var token = token(userId, suffix);
            service.saveToken(token);
            assertTrue(redis.opsForSet().isMember(RedisKeyConstants.userTokensKey(userId), suffix));
            service.deleteAllTokensByUserId(userId);
            assertFalse(redis.hasKey(RedisKeyConstants.tokenKey(suffix)));
            assertThrows(UnauthorizedException.class, () -> service.saveToken(token));
            assertFalse(redis.hasKey(RedisKeyConstants.userTokensKey(userId)));
        } finally { redis.delete(List.of(version, RedisKeyConstants.userTokensKey(userId), RedisKeyConstants.tokenKey(suffix))); }
    }
    @Test void changedPasswordRejectsTokenEvenIfRedisRevocationWasUnavailable() {
        long id = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        var mapper = mock(UserAuthMapper.class);
        var user = UserAuth.builder().userId(id).role("USER").password("old-hash").build();
        when(mapper.selectOne(any())).thenReturn(user);
        var service = new TokenServiceImpl(redis, new AuthProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "users", mapper);
        try {
            var token = token(id, suffix); service.saveToken(token); assertNotNull(service.getToken(suffix));
            user.setPassword("new-hash"); assertNull(service.getToken(suffix));
        } finally { redis.delete(List.of(RedisKeyConstants.userTokensKey(id), RedisKeyConstants.tokenKey(suffix))); }
    }
    @Test void transactionRollbackReleasesCodeReservationWithoutRestoringOverNewCode() {
        String email = suffix + "@example.invalid";
        redis.opsForValue().set(RedisKeyConstants.emailCodeKey("REGISTER", email), "123456", Duration.ofMinutes(1));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(codes.verifyAndConsume(email, EmailScene.REGISTER, "123456"));
            assertFalse(codes.verifyAndConsume(email, EmailScene.REGISTER, "123456"));
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
            callbacks.forEach(c -> c.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            assertTrue(codes.verifyAndConsume(email, EmailScene.REGISTER, "123456"));
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
    @Test void transactionCommitPermanentlyConsumesReservedCode() {
        String email = suffix + "@example.invalid";
        redis.opsForValue().set(RedisKeyConstants.emailCodeKey("REGISTER", email), "123456", Duration.ofMinutes(1));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(codes.verifyAndConsume(email, EmailScene.REGISTER, "123456"));
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
            callbacks.forEach(c -> c.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
            assertFalse(codes.verifyAndConsume(email, EmailScene.REGISTER, "123456"));
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
    @Test void duplicateNodeConfigurationIsRejectedAndLostLeaseStopsGeneration() throws Exception {
        var config = new org.redisson.config.Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + System.getProperty("test.redis.port"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        var client = org.redisson.Redisson.create(config);
        var generatorConfig = new com.fancy.rideagenthub.config.IdGeneratorConfig();
        try (var lease = generatorConfig.idNodeLease(client, 1, 1)) {
            var adapter = new org.redisson.spring.data.connection.RedissonConnectionFactory(client);
            adapter.afterPropertiesSet();
            var confirmationViaRedisson = new OrderConfirmation(new StringRedisTemplate(adapter));
            confirmationViaRedisson.quoted(suffix);
            String quote = confirmationViaRedisson.begin(suffix, "actual-adapter");
            confirmationViaRedisson.confirm(suffix, quote, true);
            confirmationViaRedisson.consume(suffix);
            assertThrows(IllegalStateException.class, () -> generatorConfig.idNodeLease(client, 1, 1));
            var generator = generatorConfig.snowflakeIdWorker(lease);
            assertTrue(generator.nextId() > 0);
            redis.delete("id:node:1:1");
            assertThrows(IllegalStateException.class, generator::nextId);
        } finally { client.shutdown(); }
    }
    private UserToken token(long userId, String value) {
        return UserToken.builder().userId(userId).role("USER").token(value).sessionVersion(0)
                .credentialStamp(CredentialStamp.of("old-hash")).expiresAtEpochMs(System.currentTimeMillis() + 60000).build();
    }
}
