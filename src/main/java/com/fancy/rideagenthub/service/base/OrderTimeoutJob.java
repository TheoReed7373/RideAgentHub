package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.config.RideOrderTimeoutProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.domain.enums.RideOrderStatus;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.service.RideOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 订单超时兜底轮询
 * <p>
 * 只做三件事：认领到期任务、交给业务处理、吞掉单条异常。
 * <p>
 * <b>为什么不在循环里直接改库</b>：超时判定依赖对订单当前状态与时间戳的重读，
 * 这段逻辑属于订单状态机，放在 {@link RideOrderService#handleTimeoutOrder} 里
 * 才能与其它状态流转共用同一套口径。本类只负责"到点了叫醒它"。
 * <p>
 * <b>异常隔离</b>：单条任务处理失败（DB 抖动、并发状态变更）不应该中断整批扫描，
 * 否则一条坏数据会让整个队列停摆。失败任务重新入队，数据库补偿扫描负责恢复
 * Redis 故障、进程退出和上线前历史订单遗漏的任务。
 */
@Slf4j
@Component
public class OrderTimeoutJob {

    private final OrderDelayQueue orderDelayQueue;
    private final RideOrderService rideOrderService;
    private final RideOrderTimeoutProperties properties;
    private final RideOrderMapper rideOrderMapper;

    public OrderTimeoutJob(OrderDelayQueue orderDelayQueue,
                           RideOrderService rideOrderService,
                           RideOrderTimeoutProperties properties,
                           RideOrderMapper rideOrderMapper) {
        this.orderDelayQueue = orderDelayQueue;
        this.rideOrderService = rideOrderService;
        this.properties = properties;
        this.rideOrderMapper = rideOrderMapper;
    }

    /**
     * 扫描到期订单
     * <p>
     * {@code fixedDelay} 而非 {@code fixedRate}：本轮处理完再开始计时，
     * 避免慢轮次把后续轮次挤成背靠背执行。
     */
    @Scheduled(fixedDelayString = "${ride-order.timeout.poll-interval-ms:5000}")
    public void pollTimeoutOrders() {
        if (!properties.isEnabled()) {
            return;
        }

        List<String> dueOrders;
        try {
            dueOrders = orderDelayQueue.claimDue(properties.getBatchSize());
        } catch (Exception e) {
            // Redis 不可用：本轮跳过，等待下一轮。订单超时是兜底能力，宁可晚处理也不能把调度线程打挂
            log.warn("订单超时队列扫描失败，本轮跳过", e);
            return;
        }

        if (dueOrders.isEmpty()) {
            return;
        }
        log.debug("认领到 {} 条到期订单，开始处理", dueOrders.size());

        processOrders(dueOrders);
    }

    /** 每分钟从数据库补偿一次，覆盖入队失败、领取后进程退出和历史订单。 */
    @Scheduled(fixedDelay = 60000L)
    public void recoverOverdueOrders() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime acceptCutoff = now.minusMinutes(properties.getAcceptTimeoutMinutes());
        try {
            List<RideOrder> orders = rideOrderMapper.selectList(new LambdaQueryWrapper<RideOrder>()
                    .eq(RideOrder::getIsDeleted, 0)
                    .and(states -> states
                            .nested(created -> created.eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
                                    .and(anchor -> anchor.le(RideOrder::getUpdateTime, acceptCutoff)
                                            .or(fallback -> fallback.isNull(RideOrder::getUpdateTime)
                                                    .le(RideOrder::getCreateTime, acceptCutoff)))
                                    .and(reservation -> reservation.isNull(RideOrder::getIsReservation)
                                            .or().ne(RideOrder::getIsReservation, 1)
                                            .or().isNull(RideOrder::getScheduledTime)
                                            .or().le(RideOrder::getScheduledTime, acceptCutoff)))
                            .or(arrival -> arrival.eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                                    .le(RideOrder::getDriverAcceptTime, now.minusMinutes(properties.getArriveTimeoutMinutes())))
                            .or(payment -> payment.eq(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
                                    .le(RideOrder::getFinishTime, now.minusMinutes(properties.getPayTimeoutMinutes()))))
                    .orderByAsc(RideOrder::getUpdateTime)
                    .last("LIMIT " + properties.getBatchSize()));
            processOrders(orders.stream().map(order -> order.getOrderId().toString()).toList());
        } catch (RuntimeException e) {
            log.warn("订单超时数据库补偿扫描失败", e);
        }
    }

    private void processOrders(List<String> orderIds) {
        for (String orderId : orderIds) {
            try {
                rideOrderService.handleTimeoutOrder(orderId);
            } catch (Exception e) {
                log.error("订单超时处理失败: orderId={}", orderId, e);
                orderDelayQueue.schedule(orderId, Duration.ofMillis(properties.getPollIntervalMs()));
            }
        }
    }
}
