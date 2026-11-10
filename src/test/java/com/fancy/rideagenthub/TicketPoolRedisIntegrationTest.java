package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.Ticket;
import com.fancy.rideagenthub.mapper.TicketMapper;
import com.fancy.rideagenthub.service.base.RedisLock;
import com.fancy.rideagenthub.service.base.TicketPoolIndex;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 只在显式指定的独立 Redis 测试实例运行。数据库快照由 Mapper 替身控制。 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")
class TicketPoolRedisIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final TicketMapper tickets = mock(TicketMapper.class);
    private TicketPoolIndex index;

    @BeforeAll
    static void connect() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "ticket-pool-redis"), Ticket.class);
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
        cleanup();
        index = new TicketPoolIndex(redis, tickets, new RedisLock(redis));
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys("ticket:pool:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void mutationOnColdCacheCannotMarkSingleTicketAsCompletePool() {
        Ticket olderUrgent = ticket(2, 0);
        olderUrgent.setPriority(2);
        olderUrgent.setUpdatedAt(olderUrgent.getUpdatedAt().minusYears(1));
        when(tickets.selectList(any())).thenReturn(List.of(ticket(9, 0), olderUrgent, ticket(10, 0)));
        index.syncByTicketId("T10");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ticketPoolReadyKey(0))));
        var first = index.slice(0, 0, 2).orElseThrow();
        assertEquals(3, first.total());
        assertEquals(List.of(2L, 10L), first.ids());
        assertEquals(List.of(9L), index.slice(0, 2, 2).orElseThrow().ids());
        assertTrue(index.slice(0, 99, 2).orElseThrow().ids().isEmpty());
        verify(tickets, times(1)).selectList(any());
    }

    @Test
    void samePriorityUsesNewerTimeBeforeIdAndNumericIdForTies() {
        Ticket newer = ticket(1, 0);
        newer.setUpdatedAt(newer.getUpdatedAt().plusSeconds(1));
        when(tickets.selectList(any())).thenReturn(List.of(ticket(9, 0), ticket(10, 0), newer));
        assertEquals(List.of(1L, 10L, 9L), index.slice(0, 0, 10).orElseThrow().ids());
    }

    @Test
    void emptyPoolIsWarmWithoutZsetAndDoesNotRepeatedlyRebuild() {
        when(tickets.selectList(any())).thenReturn(List.of());
        assertEquals(0, index.slice(0, 0, 10).orElseThrow().total());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ticketPoolKey(0))));
        assertTrue(index.slice(0, 0, 10).orElseThrow().ids().isEmpty());
        verify(tickets, times(1)).selectList(any());
    }

    @Test
    void statusChangeInvalidatesOldAndNewPoolTogether() {
        when(tickets.selectList(any())).thenReturn(List.of(ticket(1, 0)), List.of(ticket(2, 1)),
                List.of(), List.of(ticket(1, 1), ticket(2, 1)));
        var oldSlice = index.slice(0, 0, 10).orElseThrow();
        index.slice(1, 0, 10).orElseThrow();
        index.syncByTicketId("T1");
        assertFalse(index.isCurrent(oldSlice));
        for (int status = 0; status <= 4; status++) {
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ticketPoolReadyKey(status))));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ticketPoolKey(status))));
        }
        assertEquals(0, index.slice(0, 0, 10).orElseThrow().total());
        assertEquals(List.of(2L, 1L), index.slice(1, 0, 10).orElseThrow().ids());
    }

    @Test
    void rebuildReplacesStaleMembersRatherThanAppendingToOldPool() {
        redis.opsForZSet().add(RedisKeyConstants.ticketPoolKey(0), member(99), 1e15);
        when(tickets.selectList(any())).thenReturn(List.of(ticket(1, 0)));
        var slice = index.slice(0, 0, 10).orElseThrow();
        assertEquals(1, slice.total());
        assertEquals(List.of(1L), slice.ids());
    }

    @Test
    void missingZsetWithSurvivingReadyMarkerRebuildsInsteadOfReturningEmpty() {
        when(tickets.selectList(any())).thenReturn(List.of(ticket(1, 0)));
        index.slice(0, 0, 10).orElseThrow();
        redis.delete(RedisKeyConstants.ticketPoolKey(0));
        assertEquals(List.of(1L), index.slice(0, 0, 10).orElseThrow().ids());
        verify(tickets, times(2)).selectList(any());
    }

    @Test
    void removedMemberWithUnchangedMarkerCannotProducePartialPool() {
        when(tickets.selectList(any())).thenReturn(List.of(ticket(1, 0), ticket(2, 0)));
        index.slice(0, 0, 10).orElseThrow();
        redis.opsForZSet().remove(RedisKeyConstants.ticketPoolKey(0), member(2));
        assertEquals(List.of(2L, 1L), index.slice(0, 0, 10).orElseThrow().ids());
        verify(tickets, times(2)).selectList(any());
    }

    @Test
    void mutationDuringDatabaseReadRejectsOldSnapshotPublication() {
        when(tickets.selectList(any())).thenAnswer(invocation -> {
            index.syncByTicketId("T1");
            return List.of(ticket(1, 0));
        });
        assertTrue(index.slice(0, 0, 10).isEmpty());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ticketPoolReadyKey(0))));
        when(tickets.selectList(any())).thenReturn(List.of(ticket(2, 0)));
        assertEquals(List.of(2L), index.slice(0, 0, 10).orElseThrow().ids());
    }

    @Test
    void expiredLockOwnerCannotPublishOrReleaseNewOwnersLock() {
        String key = RedisKeyConstants.ticketPoolRebuildLockKey(0);
        when(tickets.selectList(any())).thenAnswer(invocation -> {
            redis.opsForValue().set(key, "new-owner", Duration.ofSeconds(10));
            return List.of(ticket(1, 0));
        });
        assertTrue(index.slice(0, 0, 10).isEmpty());
        assertEquals("new-owner", redis.opsForValue().get(key));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ticketPoolReadyKey(0))));
    }

    @Test
    void lostVersionGetsNewTokenAndRejectsInFlightOldSnapshot() {
        when(tickets.selectList(any())).thenAnswer(invocation -> {
            redis.delete(RedisKeyConstants.TICKET_POOL_VERSION_KEY);
            return List.of(ticket(1, 0));
        });
        assertTrue(index.slice(0, 0, 10).isEmpty());
        when(tickets.selectList(any())).thenReturn(List.of(ticket(2, 0)));
        assertEquals(List.of(2L), index.slice(0, 0, 10).orElseThrow().ids());
    }

    @Test
    void concurrentColdReadFallsBackWhileSingleRebuilderOwnsLock() throws Exception {
        var reading = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(tickets.selectList(any())).thenAnswer(invocation -> {
            reading.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return List.of(ticket(1, 0));
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> index.slice(0, 0, 10));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertTrue(index.slice(0, 0, 10).isEmpty());
            finish.countDown();
            assertEquals(List.of(1L), first.get(5, TimeUnit.SECONDS).orElseThrow().ids());
            verify(tickets, times(1)).selectList(any());
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidMemberMakesWholeIndexUnavailableRatherThanSkippingRow() {
        redis.opsForValue().set(RedisKeyConstants.TICKET_POOL_VERSION_KEY, "v1");
        redis.opsForValue().set(RedisKeyConstants.ticketPoolReadyKey(0), "v1:1");
        redis.opsForZSet().add(RedisKeyConstants.ticketPoolKey(0), "invalid", 1);
        assertTrue(index.slice(0, 0, 10).isEmpty());
        verifyNoInteractions(tickets);
    }

    @Test
    void dataAndReadyMarkerHaveBoundedMatchingExpiry() {
        when(tickets.selectList(any())).thenReturn(List.of(ticket(1, 0)));
        index.slice(0, 0, 10).orElseThrow();
        long dataTtl = redis.getExpire(RedisKeyConstants.ticketPoolKey(0), TimeUnit.MILLISECONDS);
        long readyTtl = redis.getExpire(RedisKeyConstants.ticketPoolReadyKey(0), TimeUnit.MILLISECONDS);
        assertTrue(dataTtl > 0 && dataTtl <= Duration.ofHours(2).toMillis());
        assertTrue(Math.abs(dataTtl - readyTtl) < 1000);
        assertEquals(-1L, redis.getExpire(RedisKeyConstants.TICKET_POOL_VERSION_KEY));
        index.slice(0, 0, 10).orElseThrow();
        assertTrue(redis.getExpire(RedisKeyConstants.ticketPoolKey(0), TimeUnit.MILLISECONDS) <= dataTtl);
    }

    private Ticket ticket(long id, int status) {
        return TicketPoolRegressionTest.ticket(id, status);
    }

    private String member(long id) {
        return String.format(Locale.ROOT, "%019d", id);
    }
}
