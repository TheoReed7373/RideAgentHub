package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.config.RideOrderTimeoutProperties;
import com.fancy.rideagenthub.domain.dto.Point;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.service.base.*;
import com.fancy.rideagenthub.service.impl.DriverLocationServiceImpl;
import com.fancy.rideagenthub.service.impl.RideOrderServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeoPoolRegressionTest {
    private final RideOrderMapper orders = mock(RideOrderMapper.class);
    private final OrderGeoPool pool = mock(OrderGeoPool.class);
    private final DriverGeoIndex drivers = mock(DriverGeoIndex.class);
    private final OrderGrabService grabs = mock(OrderGrabService.class);
    private final RideOrderServiceImpl service = new RideOrderServiceImpl(orders, mock(AmapRouteService.class),
            mock(OrderRouteService.class), mock(AmapGeoRegeoService.class), grabs,
            mock(OrderDelayQueue.class), pool, drivers, new RideOrderTimeoutProperties(),
            OrderLockTestSupport.availableClient());
    private final OrderGeoPool.GeoSlice slice = new OrderGeoPool.GeoSlice("v1", List.of(
            new OrderGeoPool.GeoOrder(10, 0.111), new OrderGeoPool.GeoOrder(20, 1.235)));

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "geo-pool-tests"), RideOrder.class);
    }

    @Test
    void offlineDriverGetsEmptyPoolWithoutDatabaseRead() {
        when(drivers.location("1")).thenReturn(new DriverGeoIndex.Location(DriverGeoIndex.OnlineStatus.OFFLINE, null));
        assertEquals(0, service.getDriverOrderPool("1", 1, 10).getTotal());
        verifyNoInteractions(pool, orders);
    }

    @Test
    void redisFailureFallsBackToDatabaseRatherThanMarkingDriverOffline() {
        when(drivers.location("1")).thenReturn(new DriverGeoIndex.Location(DriverGeoIndex.OnlineStatus.UNAVAILABLE, null));
        fallback();
        var page = service.getDriverOrderPool("1", 1, 10);
        assertEquals(3, page.getTotal());
        assertEquals("99", page.getRecords().getFirst().getOrderId());
        assertNull(page.getRecords().getFirst().getDistanceKm());
        verifyNoInteractions(pool);
    }

    @Test
    void distanceOrderSurvivesUnorderedDatabaseQueryAndPagination() {
        online();
        when(pool.search(any(), any())).thenReturn(Optional.of(slice));
        when(pool.isCurrent(slice)).thenReturn(true);
        when(orders.selectList(any())).thenReturn(List.of(order(20), order(10)));
        var page = service.getDriverOrderPool("1", 2, 1);
        assertEquals(2, page.getTotal());
        assertEquals("20", page.getRecords().getFirst().getOrderId());
        assertEquals(new BigDecimal("1.24"), page.getRecords().getFirst().getDistanceKm());
        verify(orders, never()).selectCount(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "missingRow", "changedVersion", "emptyChangedVersion"})
    void unavailableOrStalePoolFallsBackAsWholePage(String reason) {
        online();
        var candidate = reason.equals("emptyChangedVersion") ? new OrderGeoPool.GeoSlice("v1", List.of()) : slice;
        when(pool.search(any(), any())).thenReturn(reason.equals("unavailable") ? Optional.empty() : Optional.of(candidate));
        when(pool.isCurrent(candidate)).thenReturn(!reason.contains("Version"));
        when(orders.selectCount(any())).thenReturn(3L);
        if (reason.equals("missingRow")) {
            when(orders.selectList(any())).thenReturn(List.of(order(10)), List.of(order(99)));
        } else if (reason.equals("changedVersion")) {
            when(orders.selectList(any())).thenReturn(List.of(order(20), order(10)), List.of(order(99)));
        } else {
            when(orders.selectList(any())).thenReturn(List.of(order(99)));
        }
        var result = service.getDriverOrderPool("1", 1, 10);
        assertEquals(3, result.getTotal());
        assertEquals("99", result.getRecords().getFirst().getOrderId());
    }

    @Test
    void stableEmptyNearbyPoolDoesNotQueryDatabase() {
        online();
        var empty = new OrderGeoPool.GeoSlice("v1", List.of());
        when(pool.search(any(), any())).thenReturn(Optional.of(empty));
        when(pool.isCurrent(empty)).thenReturn(true);
        assertEquals(0, service.getDriverOrderPool("1", null, null).getTotal());
        verifyNoInteractions(orders);
    }

    @Test
    void largeDistancePageDoesNotWrapOffset() {
        online();
        when(pool.search(any(), any())).thenReturn(Optional.of(slice));
        when(pool.isCurrent(slice)).thenReturn(true);
        when(orders.selectList(any())).thenReturn(List.of(order(20), order(10)));
        assertTrue(service.getDriverOrderPool("1", Integer.MAX_VALUE, Integer.MAX_VALUE).getRecords().isEmpty());
    }

    @Test
    void fallbackAlsoUsesLongOffsetAndStableTimeThenIdOrder() {
        when(drivers.location("1")).thenReturn(new DriverGeoIndex.Location(DriverGeoIndex.OnlineStatus.UNAVAILABLE, null));
        fallback();
        service.getDriverOrderPool("1", Integer.MAX_VALUE, 2);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RideOrder>> query =
                ArgumentCaptor.captor();
        verify(orders).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("limit 4294967292,2"));
        assertTrue(query.getValue().getSqlSegment().contains("create_time DESC,order_id DESC"));
    }

    @Test
    void invalidParametersAreRejectedBeforeAnyCacheOrDatabaseCall() {
        assertThrows(IllegalArgumentException.class, () -> service.getDriverOrderPool("1", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getDriverOrderPool("1", 1, -1));
        assertThrows(IllegalArgumentException.class, () -> service.getDriverOrderPool(" ", 1, 10));
        verifyNoInteractions(orders, pool, drivers);
    }

    @Test
    void locationValidationUsesRedisLatitudeBounds() {
        var locations = new DriverLocationServiceImpl(drivers);
        assertThrows(IllegalArgumentException.class, () -> locations.reportLocation("1", BigDecimal.ZERO, new BigDecimal("86")));
        assertThrows(IllegalArgumentException.class, () -> locations.reportLocation("1", new BigDecimal("181"), BigDecimal.ZERO));
        verifyNoInteractions(drivers);
    }

    @Test
    void statusEndpointDoesNotReportRedisOutageAsOffline() {
        when(drivers.location("1")).thenReturn(new DriverGeoIndex.Location(DriverGeoIndex.OnlineStatus.UNAVAILABLE, null));
        assertThrows(BusinessException.class, () -> new DriverLocationServiceImpl(drivers).getStatus("1"));
    }

    @Test
    void indexInvalidationWaitsForCommitAndRollbackPublishesNothing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var index = new OrderGeoPool(redis, orders, mock(RedisLock.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            index.add("1", BigDecimal.ZERO, BigDecimal.ZERO);
            index.remove("2");
            verifyNoInteractions(redis);
            TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                    callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(redis);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            index.remove("1");
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(redis).execute(any(), anyList(), any(Object[].class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void transactionSnapshotCannotPublishPublicIndex() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(new OrderGeoPool(redis, orders, mock(RedisLock.class)).search(BigDecimal.ZERO, BigDecimal.ZERO).isEmpty());
            verifyNoInteractions(redis, orders);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void onlySuccessfulAcceptanceInvalidatesPoolAndRefreshesOnlineDriver(int updated) {
        when(grabs.tryGrab("10", "1")).thenReturn(OrderGrabService.GrabResult.SUCCESS);
        when(orders.update(isNull(), any())).thenReturn(updated);
        assertEquals(updated == 1, service.driverAcceptOrder("010", "01", new BigDecimal("30"), new BigDecimal("120")));
        if (updated == 1) {
            verify(pool).remove("10");
            verify(drivers).refreshIfExist("1", new BigDecimal("120"), new BigDecimal("30"));
        } else {
            verifyNoInteractions(pool, drivers);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void timeoutCancellationAndReturnToPoolBothInvalidateGeoIndex(int status) {
        var order = order(10);
        order.setOrderStatus(status);
        order.setCreateTime(LocalDateTime.now().minusHours(1));
        order.setUpdateTime(order.getCreateTime());
        order.setDriverId(status == 20 ? 1L : null);
        order.setDriverAcceptTime(LocalDateTime.now().minusHours(1));
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.update(isNull(), any())).thenReturn(1);
        service.handleTimeoutOrder("10");
        if (status == 20) {
            verify(pool).add("10", order.getStartLng(), order.getStartLat());
            verify(grabs).releaseDriver("1", "10");
            verify(grabs).refreshOrderStatus("10");
        } else {
            verify(pool).remove("10");
        }
    }

    @Test
    void geoCacheFailureDoesNotBreakCommittedBusinessOperation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("Redis unavailable"));
        var index = new OrderGeoPool(redis, orders, mock(RedisLock.class));
        assertDoesNotThrow(() -> index.remove("1"));
        assertTrue(index.search(BigDecimal.ZERO, BigDecimal.ZERO).isEmpty());
    }

    private void online() {
        when(drivers.location("1")).thenReturn(new DriverGeoIndex.Location(DriverGeoIndex.OnlineStatus.ONLINE,
                new Point(new BigDecimal("30"), new BigDecimal("120"))));
    }

    private void fallback() {
        when(orders.selectCount(any())).thenReturn(3L);
        when(orders.selectList(any())).thenReturn(List.of(order(99)));
    }

    static RideOrder order(long id) {
        return RideOrder.builder().orderId(id).orderStatus(10).isDeleted(0)
                .startLng(new BigDecimal("120")).startLat(new BigDecimal("30")).build();
    }
}
