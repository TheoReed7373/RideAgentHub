package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.domain.dto.TicketQueryReqDTO;
import com.fancy.rideagenthub.domain.entity.Ticket;
import com.fancy.rideagenthub.mapper.TicketChatMapper;
import com.fancy.rideagenthub.mapper.TicketMapper;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.service.base.DelayedCacheEvictor;
import com.fancy.rideagenthub.service.base.RedisLock;
import com.fancy.rideagenthub.service.base.TicketPoolIndex;
import com.fancy.rideagenthub.service.impl.TicketServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketPoolRegressionTest {

    private final TicketMapper tickets = mock(TicketMapper.class);
    private final TicketPoolIndex index = mock(TicketPoolIndex.class);
    private final TicketServiceImpl service = new TicketServiceImpl(tickets, mock(TicketChatMapper.class),
            mock(UserAuthMapper.class), mock(StringRedisTemplate.class), new ObjectMapper(),
            mock(DelayedCacheEvictor.class), mock(RedisLock.class), index);

    @BeforeAll
    static void initializeMapperMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "ticket-pool-tests"), Ticket.class);
    }

    @Test
    void indexedPagePreservesRedisOrderRatherThanDatabaseBatchOrder() {
        var slice = new TicketPoolIndex.PoolSlice("v1", 2, List.of(10L, 9L));
        when(index.slice(0, 0L, 10)).thenReturn(Optional.of(slice));
        when(index.isCurrent(slice)).thenReturn(true);
        when(tickets.selectByIds(slice.ids())).thenReturn(List.of(ticket(9, 0), ticket(10, 0)));
        var result = service.getAdminTicketPage(request());
        assertEquals(2, result.getTotal());
        assertEquals(List.of("10", "9"), result.getRecords().stream().map(row -> row.getId()).toList());
        verify(tickets, never()).selectList(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "userType", "handler", "keyword", "allStatuses"})
    void additionalFiltersAndMissingStatusAlwaysUseDatabase(String filter) {
        var req = request();
        switch (filter) {
            case "type" -> req.setType(1);
            case "userType" -> req.setUserType(1);
            case "handler" -> req.setHandlerId("7");
            case "keyword" -> req.setKeyword("lost");
            case "allStatuses" -> req.setStatus(null);
            default -> throw new AssertionError(filter);
        }
        databaseFallback();
        assertEquals("99", service.getAdminTicketPage(req).getRecords().getFirst().getId());
        verifyNoInteractions(index);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "missingRow", "wrongStatus", "changedVersion", "emptyChangedVersion"})
    void staleOrUnavailableIndexFallsBackForWholePage(String reason) {
        var slice = new TicketPoolIndex.PoolSlice("v1", 1,
                reason.equals("emptyChangedVersion") ? List.of() : List.of(10L));
        when(index.slice(0, 0L, 10)).thenReturn(reason.equals("unavailable") ? Optional.empty() : Optional.of(slice));
        when(index.isCurrent(slice)).thenReturn(!reason.contains("Version"));
        when(tickets.selectByIds(slice.ids())).thenReturn(reason.equals("missingRow") ? List.of()
                : List.of(ticket(10, reason.equals("wrongStatus") ? 1 : 0)));
        databaseFallback();
        var result = service.getAdminTicketPage(request());
        assertEquals(3, result.getTotal());
        assertEquals("99", result.getRecords().getFirst().getId());
        verify(tickets).selectList(any());
    }

    @Test
    void emptyWarmPoolDoesNotQueryDatabase() {
        var slice = new TicketPoolIndex.PoolSlice("v1", 0, List.of());
        when(index.slice(0, 0L, 10)).thenReturn(Optional.of(slice));
        when(index.isCurrent(slice)).thenReturn(true);
        var result = service.getAdminTicketPage(request());
        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(tickets);
    }

    @Test
    void largeOffsetDoesNotWrapToNegativeInteger() {
        var req = request();
        req.setCurrent(Integer.MAX_VALUE);
        req.setSize(2);
        var slice = new TicketPoolIndex.PoolSlice("v1", 1, List.of());
        when(index.slice(0, 4294967292L, 2)).thenReturn(Optional.of(slice));
        when(index.isCurrent(slice)).thenReturn(true);
        assertTrue(service.getAdminTicketPage(req).getRecords().isEmpty());
        verify(index).slice(0, 4294967292L, 2);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 2147483648L, Long.MAX_VALUE})
    void invalidPaginationIsRejectedBeforeQuery(long value) {
        var req = request();
        req.setCurrent(value);
        assertThrows(IllegalArgumentException.class, () -> service.getAdminTicketPage(req));
        req.setCurrent(1);
        req.setSize(value);
        assertThrows(IllegalArgumentException.class, () -> service.getAdminTicketPage(req));
        verifyNoInteractions(tickets, index);
    }

    @Test
    void transactionSnapshotCannotPopulateSharedIndex() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisLock lock = mock(RedisLock.class);
        TicketPoolIndex realIndex = new TicketPoolIndex(redis, tickets, lock);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(realIndex.slice(0, 0, 10).isEmpty());
            verifyNoInteractions(redis, tickets, lock);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void redisReadFailureReturnsUnavailableIndex() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("Redis unavailable"));
        assertTrue(new TicketPoolIndex(redis, tickets, mock(RedisLock.class)).slice(0, 0, 10).isEmpty());
        verifyNoInteractions(tickets);
    }

    private void databaseFallback() {
        when(tickets.selectCount(any())).thenReturn(3L);
        when(tickets.selectList(any())).thenReturn(List.of(ticket(99, 0)));
    }

    private TicketQueryReqDTO request() {
        var req = new TicketQueryReqDTO();
        req.setStatus(0);
        return req;
    }

    static Ticket ticket(long id, int status) {
        return Ticket.builder().id(id).ticketId("T" + id).userType(1).ticketType(1).priority(1)
                .ticketStatus(status).updatedAt(LocalDateTime.of(2026, 11, 10, 12, 0)).build();
    }
}
