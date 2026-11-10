package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.config.RideOrderTimeoutProperties;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.service.base.OrderDelayQueue;
import com.fancy.rideagenthub.service.base.OrderGeoPool;
import com.fancy.rideagenthub.service.base.DriverGeoIndex;
import com.fancy.rideagenthub.service.base.OrderGrabService;
import com.fancy.rideagenthub.service.base.OrderRouteService;
import com.fancy.rideagenthub.service.impl.RideOrderServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderLockRegressionTest {

    private final RideOrderMapper orders = mock(RideOrderMapper.class);
    private final OrderGrabService grabs = mock(OrderGrabService.class);
    private final OrderDelayQueue queue = mock(OrderDelayQueue.class);
    private final OrderRouteService routes = mock(OrderRouteService.class);
    private final RedissonClient client = OrderLockTestSupport.availableClient();
    private final RLock lock = client.getLock(RedisKeyConstants.orderLockKey("100"));
    private final RideOrderServiceImpl service = new RideOrderServiceImpl(orders, mock(AmapRouteService.class),
            routes, mock(AmapGeoRegeoService.class), grabs, queue, mock(OrderGeoPool.class), mock(DriverGeoIndex.class), new RideOrderTimeoutProperties(), client);

    @BeforeAll
    static void initializeMapperMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "order-lock-tests"), RideOrder.class);
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        Thread.interrupted();
    }

    @Test
    void acceptanceKeepsWatchdogLockUntilCommitCallbacksFinish() throws Exception {
        when(grabs.tryGrab("100", "1")).thenReturn(OrderGrabService.GrabResult.SUCCESS);
        when(orders.update(isNull(), any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        assertTrue(service.driverAcceptOrder("0100", "01", null, null));
        verify(lock).tryLock(3L, TimeUnit.SECONDS);
        verify(lock, never()).tryLock(anyLong(), anyLong(), any());
        verify(lock, never()).unlock();
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        verify(lock, never()).unlock();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(lock).unlock();
    }

    @Test
    void failedAcceptanceKeepsLockUntilRollbackCleanupCompletes() {
        when(grabs.tryGrab("100", "1")).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    verify(lock, never()).unlock();
                    grabs.rollbackGrab("1", "100");
                }
            });
            return OrderGrabService.GrabResult.SUCCESS;
        });
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(orders.update(isNull(), any())).thenThrow(failure);
        TransactionSynchronizationManager.initSynchronization();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.driverAcceptOrder("100", "1", null, null)));
        verify(lock, never()).unlock();
        TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        var ordered = inOrder(grabs, lock);
        ordered.verify(grabs).rollbackGrab("1", "100");
        ordered.verify(lock).unlock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "arrive", "start", "finish", "cancel", "pay", "timeout"})
    void allStateChangesUseCanonicalLockBeforeAnyBusinessWork(String operation) throws Exception {
        clearInvocations(client);
        when(lock.tryLock(3L, TimeUnit.SECONDS)).thenReturn(false);
        BusinessException failure = assertThrows(BusinessException.class, () -> invoke(operation));
        assertEquals(409, failure.getCode());
        verify(client).getLock(RedisKeyConstants.orderLockKey("100"));
        verifyNoInteractions(orders, grabs, queue, routes);
        verify(lock, never()).unlock();
    }

    @Test
    void interruptionPreservesThreadFlagAndSkipsBusinessWork() throws Exception {
        when(lock.tryLock(3L, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.startRide("100", "1")).getCode());
        assertTrue(Thread.currentThread().isInterrupted());
        verifyNoInteractions(orders, grabs, queue);
        verify(lock, never()).unlock();
    }

    @Test
    void unlockFailureDoesNotReplaceBusinessException() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(orders.update(isNull(), any())).thenThrow(failure);
        doThrow(new IllegalStateException("Redis unavailable")).when(lock).unlock();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.startRide("100", "1")));
    }

    @Test
    void unlockFailureDoesNotReportCommittedPaymentAsFailure() {
        when(orders.update(isNull(), any())).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable")).when(lock).unlock();
        assertTrue(service.payOrder("0100", 1, "trade"));
        verify(queue).cancel("100");
        verify(grabs).syncOrderStatus("100", 60);
    }

    @Test
    void successfulArrivalRemovesCanonicalQueueMember() {
        when(orders.update(isNull(), any())).thenReturn(1);
        assertTrue(service.driverArriveStart("0100", "01"));
        verify(queue).cancel("100");
        verify(grabs).syncOrderStatus("100", 30);
        verify(lock).unlock();
    }

    @Test
    void finishedRideSchedulesCanonicalQueueMember() {
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).driverId(1L)
                .orderStatus(40).mongoTraceId("trace").vehicleType(1).build());
        when(orders.update(isNull(), any())).thenReturn(1);
        assertEquals("100", service.finishRide("0100", "01", null, null, null,
                "0,0;0.001,0.001", null).getOrderId());
        verify(queue).schedule("100", Duration.ofMinutes(new RideOrderTimeoutProperties().getPayTimeoutMinutes()));
        verify(grabs).releaseDriver("01", "100");
    }

    private void invoke(String operation) {
        switch (operation) {
            case "accept" -> service.driverAcceptOrder("0100", "1", null, null);
            case "arrive" -> service.driverArriveStart("0100", "1");
            case "start" -> service.startRide("0100", "1");
            case "finish" -> service.finishRide("0100", "1", null, null, null, "0,0;1,1", null);
            case "cancel" -> service.cancelOrder("0100", "2", 1, "cancel");
            case "pay" -> service.payOrder("0100", 1, "trade");
            case "timeout" -> service.handleTimeoutOrder("0100");
            default -> throw new AssertionError(operation);
        }
    }
}
