package com.fancy.rideagenthub;

import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.service.base.OrderDelayQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 只连接显式指定的独立测试 Redis 端口。 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")
class OrderDelayQueueIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static OrderDelayQueue queue;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.redis.port")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        queue = new OrderDelayQueue(redis);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @AfterEach
    void cleanup() {
        redis.delete(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY);
    }

    @Test
    void parallelConsumersClaimEachDueOrderOnceAndPreserveFutureTasks() throws Exception {
        for (int index = 0; index < 20; index++) {
            queue.schedule(String.valueOf(index), Duration.ZERO);
        }
        queue.schedule("future", Duration.ofHours(1));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return queue.claimDue(20);
            });
            var second = executor.submit(() -> {
                start.await();
                return queue.claimDue(20);
            });
            start.countDown();
            List<String> claimed = new ArrayList<>(first.get(5, TimeUnit.SECONDS));
            claimed.addAll(second.get(5, TimeUnit.SECONDS));
            assertEquals(20, claimed.size());
            assertEquals(20, new HashSet<>(claimed).size());
            assertNotNull(redis.opsForZSet().score(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, "future"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rescheduledOrderIsNotClaimedUntilNewDeadline() {
        queue.schedule("100", Duration.ZERO);
        queue.schedule("100", Duration.ofHours(1));
        assertTrue(queue.claimDue(100).isEmpty());
        assertEquals(1L, redis.opsForZSet().zCard(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY));
        queue.schedule("100", Duration.ZERO);
        assertEquals(List.of("100"), queue.claimDue(100));
        assertTrue(queue.claimDue(100).isEmpty());
    }

    @Test
    void existingQueueEntriesRemainReadableAndBatchLimitIsRespected() {
        redis.opsForZSet().add(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, "old", 1);
        queue.schedule("new", Duration.ZERO);
        assertEquals(List.of("old"), queue.claimDue(1));
        assertEquals(List.of("new"), queue.claimDue(1));
    }
}
