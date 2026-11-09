package com.fancy.rideagenthub;

import com.fancy.rideagenthub.agentbase.amap.util.citycode.CityCodeUtil;
import com.fancy.rideagenthub.agentbase.memory.MessageParser;
import com.fancy.rideagenthub.agentbase.memory.RedisMemory;
import com.fancy.rideagenthub.agentbase.memory.HeapMemory;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agentbase.memory.MysqlMemory;
import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.CityCode;
import com.fancy.rideagenthub.mapper.CityCodeMapper;
import com.fancy.rideagenthub.service.base.RedisLock;
import com.fancy.rideagenthub.util.RedisScripts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用独立的本地 Redis 端口运行，例如 -Dtest.redis.port=16379。 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")
class RedisCacheRegressionTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private RedisMemory memory;
    private String chatId;
    private final List<String> keys = new ArrayList<>();

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.redis.port")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void setup() {
        chatId = "regression-" + UUID.randomUUID();
        memory = new RedisMemory(redis, new MessageParser());
        keys.add(RedisKeyConstants.chatHistoryKey(chatId));
        keys.add(RedisKeyConstants.chatHistoryVersionKey(chatId));
    }

    @AfterEach
    void cleanup() {
        redis.delete(keys);
    }

    @Test
    void appendSetsDataExpiryAtomicallyAndRejectsOlderLease() {
        long lease = memory.currentVersion(chatId);
        memory.append(chatId, messages("new"), Duration.ofMinutes(5));
        assertTrue(redis.getExpire(RedisKeyConstants.chatHistoryKey(chatId)) > 0);
        assertEquals(-1L, redis.getExpire(RedisKeyConstants.chatHistoryVersionKey(chatId)));
        assertFalse(memory.overwriteIfLeaseValid(chatId, messages("old"), lease));
        assertEquals(List.of("new"), texts(memory.getAll(chatId)));
    }

    @Test
    void refillConsumesLeaseAndUsesHistoryTtl() {
        long lease = memory.currentVersion(chatId);
        assertTrue(memory.overwriteIfLeaseValid(chatId, messages("one", "two"), lease));
        assertTrue(redis.getExpire(RedisKeyConstants.chatHistoryKey(chatId)) > 3600);
        assertFalse(memory.overwriteIfLeaseValid(chatId, messages("older"), lease));
        assertEquals(List.of("two"), texts(memory.getLastN(chatId, 1)));
        assertEquals(List.of("one", "two"), texts(memory.getLastN(chatId, 100)));
        assertTrue(memory.getLastN(chatId, 0).isEmpty());
    }

    @Test
    void clearInvalidatesZeroAndNonzeroLeases() {
        long emptyLease = memory.currentVersion(chatId);
        memory.clear(chatId);
        assertFalse(memory.overwriteIfLeaseValid(chatId, messages("deleted"), emptyLease));
        memory.append(chatId, messages("new"), Duration.ofMinutes(5));
        long lease = memory.currentVersion(chatId);
        memory.clear(chatId);
        assertFalse(memory.overwriteIfLeaseValid(chatId, messages("deleted"), lease));
        assertTrue(memory.getAll(chatId).isEmpty());
    }

    @Test
    void dataExpirationDoesNotResetVersionOrReviveLease() {
        long beforeWrite = memory.currentVersion(chatId);
        memory.append(chatId, messages("new"), Duration.ofMillis(50));
        redis.expire(RedisKeyConstants.chatHistoryKey(chatId), Duration.ZERO);
        assertFalse(memory.overwriteIfLeaseValid(chatId, messages("old"), beforeWrite));
        assertTrue(memory.currentVersion(chatId) > beforeWrite);
    }

    @Test
    void readingLegacyVersionRemovesItsExpiryAndInvalidatesPartialHistory() {
        String versionKey = RedisKeyConstants.chatHistoryVersionKey(chatId);
        redis.opsForValue().set(versionKey, "17", Duration.ofSeconds(5));
        redis.opsForList().rightPush(RedisKeyConstants.chatHistoryKey(chatId), new MessageParser().parse(new UserMessage("partial")));
        assertEquals(18L, memory.currentVersion(chatId));
        assertTrue(memory.getAll(chatId).isEmpty());
        assertEquals(-1L, redis.getExpire(versionKey));
        memory.clear(chatId);
        assertEquals(19L, memory.currentVersion(chatId));
    }

    @Test
    void legacyHistoryWithoutVersionIsRebuiltFromPersistence() {
        redis.opsForList().rightPush(RedisKeyConstants.chatHistoryKey(chatId), new MessageParser().parse(new UserMessage("partial")));
        assertEquals(0L, memory.currentVersion(chatId));
        assertTrue(memory.getAll(chatId).isEmpty());
    }

    @Test
    void coldSaveDoesNotCreatePartialHistory() {
        long lease = memory.currentVersion(chatId);
        memory.appendIfVersionMatch(chatId, messages("new"), Duration.ofMinutes(5), lease);
        assertTrue(memory.getAll(chatId).isEmpty());
        assertFalse(memory.overwriteIfLeaseValid(chatId, messages("old"), lease));
    }

    @Test
    void saveAppendsToWarmCacheAndInvalidatesConcurrentRefill() {
        assertTrue(memory.overwriteIfLeaseValid(chatId, messages("old"), memory.currentVersion(chatId)));
        long lease = memory.currentVersion(chatId);
        memory.appendIfVersionMatch(chatId, messages("new"), Duration.ofMinutes(5), lease);
        assertEquals(List.of("old", "new"), texts(memory.getAll(chatId)));
        lease = memory.currentVersion(chatId);
        assertTrue(memory.overwriteIfLeaseValid(chatId, messages("old", "new", "last"), lease));
        memory.appendIfVersionMatch(chatId, messages("last"), Duration.ofMinutes(5), lease);
        assertTrue(memory.getAll(chatId).isEmpty());
    }

    @Test
    void concurrentSavesAndRefillsPreserveEveryPersistedMessage() throws Exception {
        MysqlMemory mysql = mock(MysqlMemory.class);
        List<Message> persisted = new ArrayList<>();
        when(mysql.getAll(chatId)).thenAnswer(invocation -> {
            synchronized (persisted) {
                return new ArrayList<>(persisted);
            }
        });
        doAnswer(invocation -> {
            synchronized (persisted) {
                persisted.addAll(invocation.<List<Message>>getArgument(1));
            }
            return null;
        }).when(mysql).append(any(), any());
        MessageMemory messages = new MessageMemory(new HeapMemory(), memory, mysql, mock(ChatManager.class));
        List<Callable<Void>> writers = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            int writer = worker;
            writers.add(() -> {
                for (int i = 0; i < 10; i++) {
                    messages.save("user", chatId, messages(writer + "-" + i));
                    messages.get("user", chatId, 3);
                }
                return null;
            });
        }
        var executor = Executors.newFixedThreadPool(4);
        try {
            for (var result : executor.invokeAll(writers, 15, TimeUnit.SECONDS)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
        List<String> actual = texts(messages.get("user", chatId, 100));
        assertEquals(texts(persisted), actual);
        assertEquals(40, actual.size());
        assertEquals(40, actual.stream().distinct().count());
    }

    @Test
    void cityMissCannotOverwriteTombstoneOrRefillAfterTombstoneExpires() {
        String city = "city-" + UUID.randomUUID();
        String key = RedisKeyConstants.amapCityCodeKey(city);
        keys.add(key);
        keys.add(RedisKeyConstants.amapCityCodeVersionKey(city));
        CityCodeMapper mapper = mock(CityCodeMapper.class);
        CityCodeUtil cities = new CityCodeUtil(mapper, redis);
        CityCode old = new CityCode();
        old.setCityCode("old");
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            cities.invalidate(city);
            return old;
        });
        assertEquals("old", cities.getCityCode(city));
        assertEquals("__TOMBSTONE__", redis.opsForValue().get(key));
        redis.delete(key);
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            cities.invalidate(city);
            redis.delete(key); // 模拟查询耗时超过墓碑 TTL。
            return old;
        });
        assertEquals("old", cities.getCityCode(city));
        assertNull(redis.opsForValue().get(key));
    }

    @Test
    void cityCachePreservesNullSentinelAndIsIndependentOfDefaultLocale() {
        String city = "CITY-I-" + UUID.randomUUID();
        String key = RedisKeyConstants.amapCityCodeKey(city);
        keys.add(key);
        keys.add(RedisKeyConstants.amapCityCodeVersionKey(city));
        CityCodeMapper mapper = mock(CityCodeMapper.class);
        CityCodeUtil cities = new CityCodeUtil(mapper, redis);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(key, RedisKeyConstants.amapCityCodeKey(city));
            assertNull(cities.getCityCode(" " + city + " "));
            assertNull(cities.getCityCode(city.toLowerCase(Locale.ROOT)));
            verify(mapper, times(1)).selectOne(any());
            assertEquals("__NULL__", redis.opsForValue().get(key));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void existingHashAndLockScriptsStillProtectData() {
        String hash = "hash-" + chatId;
        String lock = "lock-" + chatId;
        keys.addAll(List.of(hash, lock));
        redis.opsForHash().put(hash, "classification", "ORDER");
        assertEquals(1L, redis.execute(RedisScripts.HASH_SET_WITH_EXPIRE, List.of(hash), "locked", "true", "60"));
        assertEquals("ORDER", redis.opsForHash().get(hash, "classification"));
        assertTrue(redis.getExpire(hash) > 0);
        redis.opsForValue().set(lock, "new-owner");
        assertEquals(0L, redis.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(lock), "old-owner"));
        assertEquals("new-owner", redis.opsForValue().get(lock));
        assertEquals(1L, redis.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(lock), "new-owner"));
        assertFalse(redis.hasKey(lock));
    }

    @Test
    void redisLockHelperRejectsContentionAndProtectsNewOwnerAfterExpiry() throws Exception {
        String key = "helper-lock-" + chatId;
        keys.add(key);
        var locks = new RedisLock(redis);
        String oldToken = locks.tryLock(key, Duration.ofSeconds(30));
        assertNotNull(oldToken);
        assertTrue(redis.getExpire(key) > 0);
        assertNull(locks.tryLock(key, Duration.ofSeconds(30)));
        // 缩短有效期，验证真实过期后旧令牌不会删除新持有者。
        redis.expire(key, Duration.ofMillis(20));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        String newToken = locks.tryLock(key, Duration.ofSeconds(30));
        assertNotNull(newToken);
        assertNotEquals(oldToken, newToken);
        locks.unlock(key, oldToken);
        assertEquals(newToken, redis.opsForValue().get(key));
        locks.unlock(key, newToken);
        assertFalse(redis.hasKey(key));
    }

    private static List<Message> messages(String... texts) {
        return java.util.Arrays.stream(texts).<Message>map(UserMessage::new).toList();
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }
}
