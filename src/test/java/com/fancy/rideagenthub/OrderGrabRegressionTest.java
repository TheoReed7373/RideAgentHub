package com.fancy.rideagenthub;

import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.config.RideOrderTimeoutProperties;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.service.base.OrderGrabService;
import com.fancy.rideagenthub.service.base.OrderDelayQueue;
import com.fancy.rideagenthub.service.base.OrderRouteService;
import com.fancy.rideagenthub.service.impl.RideOrderServiceImpl;
import com.fancy.rideagenthub.util.RedisScripts;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderGrabRegressionTest {

    @BeforeAll
    static void initializeMapperMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "order-grab-tests");
        TableInfoHelper.initTableInfo(assistant, RideOrder.class);
        TableInfoHelper.initTableInfo(assistant, UserAuth.class);
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackAcceptanceReleasesItsClaim() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RideOrderMapper orders = mock(RideOrderMapper.class);
        UserAuthMapper users = mock(UserAuthMapper.class);
        when(users.selectOne(any())).thenReturn(new UserAuth());
        when(orders.selectOne(any())).thenReturn(null, RideOrder.builder().orderId(100L).orderStatus(10).build());
        when(redis.execute(eq(RedisScripts.GRAB_ORDER_ATOMIC), anyList(), eq("10"), eq("__IDLE__"), eq("100"), eq("300")))
                .thenReturn(1L);
        OrderGrabService grabs = new OrderGrabService(redis, orders, users);
        TransactionSynchronizationManager.initSynchronization();
        assertEquals(OrderGrabService.GrabResult.SUCCESS, grabs.tryGrab("0100", "01"));
        verify(values).set(RedisKeyConstants.driverActiveKey("1"), "__IDLE__", Duration.ofMinutes(5));
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(redis).execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(RedisKeyConstants.driverActiveKey("1")), "100");
    }

    @Test
    void statusAndDriverReleaseWaitForCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OrderGrabService grabs = new OrderGrabService(redis, mock(RideOrderMapper.class), mock(UserAuthMapper.class));
        TransactionSynchronizationManager.initSynchronization();
        grabs.syncOrderStatus("0100", 90);
        grabs.releaseDriver("01", "0100");
        verifyNoInteractions(redis);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redis).execute(RedisScripts.SYNC_ORDER_STATUS, List.of(RedisKeyConstants.orderStatusKey("100")), "90", "300");
        verify(redis).execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(RedisKeyConstants.driverActiveKey("1")), "100");
    }

    @Test
    void rollbackDoesNotPublishStatusOrReleaseCommittedDriver() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OrderGrabService grabs = new OrderGrabService(redis, mock(RideOrderMapper.class), mock(UserAuthMapper.class));
        TransactionSynchronizationManager.initSynchronization();
        grabs.syncOrderStatus("100", 90);
        grabs.releaseDriver("1", "100");
        TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(redis);
    }

    @Test
    void cacheSynchronizationFailureDoesNotFailCommittedBusinessOperation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(RedisScripts.SYNC_ORDER_STATUS), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("cache unavailable"));
        OrderGrabService grabs = new OrderGrabService(redis, mock(RideOrderMapper.class), mock(UserAuthMapper.class));
        assertDoesNotThrow(() -> grabs.syncOrderStatus("100", 20));
    }

    @Test
    void existingDatabaseAssignmentReplacesStaleIdleSentinel() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeyConstants.orderStatusKey("100"))).thenReturn("10");
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(99L).driverId(1L).orderStatus(20).build());
        UserAuthMapper users = mock(UserAuthMapper.class);
        when(users.selectOne(any())).thenReturn(new UserAuth());
        when(redis.execute(eq(RedisScripts.GRAB_ORDER_ATOMIC), anyList(), eq("10"), eq("__IDLE__"), eq("100"), eq("300")))
                .thenReturn(-2L);
        OrderGrabService grabs = new OrderGrabService(redis, orders, users);
        assertEquals(OrderGrabService.GrabResult.DRIVER_BUSY, grabs.tryGrab("100", "1"));
        verify(values).set(RedisKeyConstants.driverActiveKey("1"), "99", Duration.ofMinutes(5));
    }

    @Test
    void missingOrderIsReportedWithoutClaimingDriver() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        UserAuthMapper users = mock(UserAuthMapper.class);
        when(users.selectOne(any())).thenReturn(new UserAuth());
        OrderGrabService grabs = new OrderGrabService(redis, mock(RideOrderMapper.class), users);
        assertEquals(OrderGrabService.GrabResult.ORDER_NOT_FOUND, grabs.tryGrab("100", "1"));
        verify(redis, never()).execute(eq(RedisScripts.GRAB_ORDER_ATOMIC), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedDatabaseCompareAndSetReleasesDriverAndInvalidatesStatus() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        OrderGrabService grabs = mock(OrderGrabService.class);
        when(grabs.tryGrab("100", "1")).thenReturn(OrderGrabService.GrabResult.SUCCESS);
        assertFalse(service(orders, grabs).driverAcceptOrder("0100", "01", null, null));
        verify(grabs).rollbackGrab("1", "100");
        verify(grabs).refreshOrderStatus("100");
        verify(grabs, never()).syncOrderStatus(anyString(), anyInt());
    }

    @Test
    void cancellationLosingConcurrentUpdateReportsConflict() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).userId(2L)
                .driverId(1L).orderStatus(20).build());
        OrderGrabService grabs = mock(OrderGrabService.class);
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service(orders, grabs).cancelOrder("100", "2", 1, "cancel"));
        assertEquals(409, failure.getCode());
        verifyNoInteractions(grabs);
    }

    @Test
    void successfulCancellationReleasesOnlyCancelledOrder() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).userId(2L)
                .driverId(1L).orderStatus(20).build());
        when(orders.update(isNull(), any())).thenReturn(1);
        OrderGrabService grabs = mock(OrderGrabService.class);
        service(orders, grabs).cancelOrder("100", "2", 1, "cancel");
        verify(grabs).releaseDriver("1", "100");
        verify(grabs).syncOrderStatus("100", 90);
    }

    @Test
    void finishingRideReleasesOnlyFinishedOrder() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).userId(2L)
                .driverId(1L).orderStatus(40).mongoTraceId("trace").vehicleType(1).build());
        when(orders.update(isNull(), any())).thenReturn(1);
        OrderGrabService grabs = mock(OrderGrabService.class);
        service(orders, grabs).finishRide("100", "1", null, null, null, "0,0;0.001,0.001", null);
        verify(grabs).releaseDriver("1", "100");
        verify(grabs).syncOrderStatus("100", 50);
    }

    private RideOrderServiceImpl service(RideOrderMapper orders, OrderGrabService grabs) {
        return new RideOrderServiceImpl(orders, mock(AmapRouteService.class), mock(OrderRouteService.class),
                mock(AmapGeoRegeoService.class), grabs,
                mock(OrderDelayQueue.class), new RideOrderTimeoutProperties());
    }
}
