package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.config.RideOrderTimeoutProperties;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.service.RideOrderService;
import com.fancy.rideagenthub.service.base.OrderDelayQueue;
import com.fancy.rideagenthub.service.base.OrderGeoPool;
import com.fancy.rideagenthub.service.base.DriverGeoIndex;
import com.fancy.rideagenthub.service.base.OrderGrabService;
import com.fancy.rideagenthub.service.base.OrderRouteService;
import com.fancy.rideagenthub.service.base.OrderTimeoutJob;
import com.fancy.rideagenthub.service.impl.RideOrderServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTimeoutRegressionTest {

    @BeforeAll
    static void initializeMapperMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "timeout-tests"), RideOrder.class);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void queueChangesWaitForCommitAndRollbackPublishesNothing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> values = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(values);
        OrderDelayQueue queue = new OrderDelayQueue(redis);
        TransactionSynchronizationManager.initSynchronization();
        queue.schedule("100", Duration.ofMinutes(1));
        queue.cancel("200");
        verifyNoInteractions(redis);
        TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(redis);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        queue.schedule("100", Duration.ofMinutes(1));
        queue.cancel("200");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(values).add(anyString(), eq("100"), anyDouble());
        verify(values).remove(anyString(), eq("200"));
    }

    @Test
    void redisQueueFailureDoesNotFailCommittedBusinessOperation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForZSet()).thenThrow(new IllegalStateException("Redis unavailable"));
        OrderDelayQueue queue = new OrderDelayQueue(redis);
        assertDoesNotThrow(() -> queue.schedule("100", Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> queue.cancel("100"));
    }

    @Test
    void failedTimeoutIsRequeuedWithoutStoppingTheBatch() {
        OrderDelayQueue queue = mock(OrderDelayQueue.class);
        when(queue.claimDue(100)).thenReturn(List.of("100", "200"));
        RideOrderService service = mock(RideOrderService.class);
        doThrow(new IllegalStateException("DB unavailable")).when(service).handleTimeoutOrder("100");
        new OrderTimeoutJob(queue, service, new RideOrderTimeoutProperties(), mock(RideOrderMapper.class)).pollTimeoutOrders();
        verify(queue).schedule("100", Duration.ofMillis(5000));
        verify(service).handleTimeoutOrder("200");
    }

    @Test
    void databaseRecoveryProcessesOrdersMissingFromRedis() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectList(any())).thenReturn(List.of(RideOrder.builder().orderId(100L).build()));
        RideOrderService service = mock(RideOrderService.class);
        OrderDelayQueue queue = mock(OrderDelayQueue.class);
        new OrderTimeoutJob(queue, service, new RideOrderTimeoutProperties(), orders).recoverOverdueOrders();
        verify(service).handleTimeoutOrder("100");
        verifyNoInteractions(queue);
    }

    @Test
    void disabledTimeoutsDoNotPollOrRecover() {
        RideOrderTimeoutProperties properties = new RideOrderTimeoutProperties();
        properties.setEnabled(false);
        OrderDelayQueue queue = mock(OrderDelayQueue.class);
        RideOrderService service = mock(RideOrderService.class);
        RideOrderMapper orders = mock(RideOrderMapper.class);
        OrderTimeoutJob job = new OrderTimeoutJob(queue, service, properties, orders);
        job.pollTimeoutOrders();
        job.recoverOverdueOrders();
        verifyNoInteractions(queue, service, orders);
    }

    @Test
    void arrivalTimeoutReleasesOnlyItsDriverClaimAndInvalidatesRegressedStatus() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        RideOrder order = RideOrder.builder().orderId(100L).driverId(1L).orderStatus(20)
                .driverAcceptTime(LocalDateTime.now().minusMinutes(11)).build();
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.update(isNull(), any())).thenReturn(1);
        OrderGrabService grabs = mock(OrderGrabService.class);
        OrderDelayQueue queue = mock(OrderDelayQueue.class);
        service(orders, grabs, queue).handleTimeoutOrder("100");
        verify(grabs).releaseDriver("1", "100");
        verify(grabs).refreshOrderStatus("100");
        verify(grabs, never()).syncOrderStatus(anyString(), eq(10));
        verify(queue).schedule("100", Duration.ofMinutes(15));
        ArgumentCaptor<LambdaUpdateWrapper<RideOrder>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orders).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSegment().contains("driver_accept_time"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(order.getDriverAcceptTime()));
    }

    @Test
    void losingTimeoutUpdateDoesNotReleaseDriverOrChangeQueue() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).driverId(1L)
                .orderStatus(20).driverAcceptTime(LocalDateTime.now().minusMinutes(11)).build());
        OrderGrabService grabs = mock(OrderGrabService.class);
        OrderDelayQueue queue = mock(OrderDelayQueue.class);
        service(orders, grabs, queue).handleTimeoutOrder("100");
        verifyNoInteractions(grabs, queue);
    }

    @Test
    void futureReservationIsRescheduledWithoutCancellation() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).orderStatus(10)
                .createTime(LocalDateTime.now().minusHours(1)).isReservation(1)
                .scheduledTime(LocalDateTime.now().plusHours(1)).build());
        OrderDelayQueue queue = mock(OrderDelayQueue.class);
        service(orders, mock(OrderGrabService.class), queue).handleTimeoutOrder("100");
        verify(orders, never()).update(isNull(), any());
        verify(queue).schedule(eq("100"), argThat(delay -> delay.compareTo(Duration.ofHours(1)) > 0));
    }

    @Test
    void timeoutCancellationChecksSnapshotAndPreservesBill() {
        RideOrderMapper orders = mock(RideOrderMapper.class);
        LocalDateTime snapshotTime = LocalDateTime.now().minusHours(1);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).driverId(1L).orderStatus(50)
                .finishTime(snapshotTime).updateTime(snapshotTime).build());
        when(orders.update(isNull(), any())).thenReturn(1);
        OrderGrabService grabs = mock(OrderGrabService.class);
        service(orders, grabs, mock(OrderDelayQueue.class)).handleTimeoutOrder("100");
        ArgumentCaptor<LambdaUpdateWrapper<RideOrder>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orders).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSegment().contains("update_time"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(snapshotTime));
        assertFalse(update.getValue().getSqlSet().contains("real_price"));
        verify(grabs).releaseDriver("1", "100");
    }

    @Test
    void invalidTimeoutConfigurationFailsEarly() {
        RideOrderTimeoutProperties properties = new RideOrderTimeoutProperties();
        assertDoesNotThrow(properties::validate);
        properties.setBatchSize(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    private RideOrderServiceImpl service(RideOrderMapper orders, OrderGrabService grabs, OrderDelayQueue queue) {
        return new RideOrderServiceImpl(orders, mock(AmapRouteService.class), mock(OrderRouteService.class),
                mock(AmapGeoRegeoService.class), grabs, queue, mock(OrderGeoPool.class), mock(DriverGeoIndex.class), new RideOrderTimeoutProperties(),
                OrderLockTestSupport.availableClient());
    }
}
