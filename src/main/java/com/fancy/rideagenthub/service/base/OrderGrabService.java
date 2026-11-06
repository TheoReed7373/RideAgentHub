package com.fancy.rideagenthub.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.fancy.rideagenthub.domain.enums.RideOrderStatus;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.util.RedisScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderGrabService {

    private static final String IDLE = "__IDLE__";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final RideOrderMapper rideOrderMapper;
    private final UserAuthMapper userAuthMapper;

    public enum GrabResult {
        SUCCESS, ORDER_NOT_FOUND, ORDER_NOT_GRABBABLE, DRIVER_BUSY
    }

    /** 在接单事务内锁定司机账号，使缓存过期或丢失时仍满足一人一单。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public GrabResult tryGrab(String orderId, String driverId) {
        orderId = normalizeId(orderId);
        driverId = normalizeId(driverId);
        UserAuth driver = userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
                .eq(UserAuth::getUserId, Long.valueOf(driverId))
                .eq(UserAuth::getIsDeleted, 0)
                .eq(UserAuth::getStatus, 1)
                .eq(UserAuth::getRole, "DRIVER")
                .last("FOR UPDATE"));
        if (driver == null) {
            throw new BusinessException(403, "司机账号不可用");
        }

        // 同一司机的上一个接单事务已经结束，依据数据库修复过期或残留的占位。
        RideOrder active = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getDriverId, Long.valueOf(driverId))
                .eq(RideOrder::getIsDeleted, 0)
                .notIn(RideOrder::getOrderStatus, List.of(
                        RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                        RideOrderStatus.PAID.getCode(), RideOrderStatus.CANCELLED.getCode()))
                .last("LIMIT 1 FOR UPDATE"));
        redisTemplate.opsForValue().set(RedisKeyConstants.driverActiveKey(driverId),
                active == null ? IDLE : active.getOrderId().toString(), CACHE_TTL);

        String statusKey = RedisKeyConstants.orderStatusKey(orderId);
        if (redisTemplate.opsForValue().get(statusKey) == null) {
            RideOrder order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                    .eq(RideOrder::getOrderId, Long.valueOf(orderId))
                    .eq(RideOrder::getIsDeleted, 0));
            if (order == null) {
                return GrabResult.ORDER_NOT_FOUND;
            }
            redisTemplate.opsForValue().setIfAbsent(statusKey, String.valueOf(order.getOrderStatus()), CACHE_TTL);
        }

        Long result = redisTemplate.execute(RedisScripts.GRAB_ORDER_ATOMIC,
                List.of(statusKey, RedisKeyConstants.driverActiveKey(driverId)),
                String.valueOf(RideOrderStatus.CREATED.getCode()), IDLE, orderId,
                String.valueOf(CACHE_TTL.toSeconds()));
        if (result == null) {
            throw new IllegalStateException("抢单预检未返回结果");
        }
        if (result == 1L) {
            String claimedOrderId = orderId;
            String claimedDriverId = driverId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        rollbackGrab(claimedDriverId, claimedOrderId);
                    }
                }
            });
            return GrabResult.SUCCESS;
        }
        return result == -2L ? GrabResult.DRIVER_BUSY : GrabResult.ORDER_NOT_GRABBABLE;
    }

    /** 状态缓存提交后更新，按状态顺序推进，避免延迟回调写回旧状态。 */
    public void syncOrderStatus(String orderId, int status) {
        String key = RedisKeyConstants.orderStatusKey(normalizeId(orderId));
        afterCommit(() -> redisTemplate.execute(RedisScripts.SYNC_ORDER_STATUS,
                List.of(key), String.valueOf(status), String.valueOf(CACHE_TTL.toSeconds())));
    }

    public void refreshOrderStatus(String orderId) {
        String key = RedisKeyConstants.orderStatusKey(normalizeId(orderId));
        afterCommit(() -> redisTemplate.delete(key));
    }

    public void rollbackGrab(String driverId, String orderId) {
        String key = RedisKeyConstants.driverActiveKey(normalizeId(driverId));
        try {
            redisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(key), normalizeId(orderId));
        } catch (RuntimeException e) {
            log.warn("释放司机订单占位失败: driverId={}, orderId={}", driverId, orderId, e);
        }
    }

    /** 只释放该订单持有的占位，保护司机随后接到的新订单。 */
    public void releaseDriver(String driverId, String orderId) {
        String normalizedDriver = normalizeId(driverId);
        String normalizedOrder = normalizeId(orderId);
        afterCommit(() -> rollbackGrab(normalizedDriver, normalizedOrder));
    }

    private void afterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("订单缓存同步失败", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    private String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ID不能为空");
        }
        return Long.valueOf(id.trim()).toString();
    }
}
