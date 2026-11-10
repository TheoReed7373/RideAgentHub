package com.fancy.rideagenthub.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.domain.enums.RideOrderStatus;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.util.RedisScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 待接单订单 GEO 索引。事务提交后失效、读取时完整重建，避免把单笔入池当作完整索引。
 * 发布前校验版本和锁令牌；回表后再校验版本，缓存不可用时交由调用方查询数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGeoPool {
    private static final Duration INDEX_TTL = Duration.ofHours(2);
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(10);
    private static final double SEARCH_RADIUS_KM = 3.0;
    private static final int SCAN_LIMIT = 200;

    public record GeoOrder(long orderId, double distanceKm) { }
    public record GeoSlice(String version, List<GeoOrder> orders) { }

    private final StringRedisTemplate redisTemplate;
    private final RideOrderMapper rideOrderMapper;
    private final RedisLock redisLock;

    /** 只在提交后失效，不发布事务中的坐标或旧快照。下次搜索从数据库加载完整池。 */
    public void add(String orderId, BigDecimal startLng, BigDecimal startLat) {
        remove(orderId);
    }

    public void remove(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }

    private void invalidate() {
        try {
            redisTemplate.execute(RedisScripts.INVALIDATE_ORDER_GEO_POOL,
                    List.of(RedisKeyConstants.ORDER_GEO_POOL_VERSION_KEY,
                            RedisKeyConstants.ORDER_GEO_POOL_READY_KEY, RedisKeyConstants.ORDER_GEO_POOL_KEY),
                    UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            // 固定 TTL 不因写入续期，失效失败时也不会无限延长旧索引寿命。
            log.warn("订单地理池失效失败，等待固定 TTL 校准", e);
        }
    }

    /** 返回三公里内最近的至多 200 个候选；未就绪与故障返回 Optional.empty()。 */
    public Optional<GeoSlice> search(BigDecimal lng, BigDecimal lat) {
        if (!DriverGeoIndex.validCoordinates(lng, lat)
                || TransactionSynchronizationManager.isActualTransactionActive()) {
            return Optional.empty();
        }
        try {
            Optional<GeoSlice> existing = read(lng, lat);
            if (existing.isPresent()) {
                return existing;
            }
            String lockKey = RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY;
            String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
            if (token == null) {
                return Optional.empty();
            }
            try {
                existing = read(lng, lat);
                if (existing.isPresent()) {
                    return existing;
                }
                redisTemplate.opsForValue().setIfAbsent(RedisKeyConstants.ORDER_GEO_POOL_VERSION_KEY,
                        UUID.randomUUID().toString());
                String version = redisTemplate.opsForValue().get(RedisKeyConstants.ORDER_GEO_POOL_VERSION_KEY);
                if (version == null) {
                    return Optional.empty();
                }
                List<RideOrder> orders = rideOrderMapper.selectList(new LambdaQueryWrapper<RideOrder>()
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode()));
                List<String> args = new ArrayList<>();
                args.add(version);
                args.add(token);
                args.add(String.valueOf(INDEX_TTL.toMillis()));
                for (RideOrder order : orders) {
                    if (order.getOrderId() == null || order.getOrderId() <= 0
                            || !DriverGeoIndex.validCoordinates(order.getStartLng(), order.getStartLat())) {
                        continue;
                    }
                    args.add(order.getStartLng().toPlainString());
                    args.add(order.getStartLat().toPlainString());
                    args.add(order.getOrderId().toString());
                }
                Long published = redisTemplate.execute(RedisScripts.PUBLISH_ORDER_GEO_POOL,
                        List.of(RedisKeyConstants.ORDER_GEO_POOL_VERSION_KEY, lockKey,
                                RedisKeyConstants.ORDER_GEO_POOL_READY_KEY, RedisKeyConstants.ORDER_GEO_POOL_KEY),
                        args.toArray());
                return Long.valueOf(1).equals(published) ? read(lng, lat) : Optional.empty();
            } finally {
                redisLock.unlock(lockKey, token);
            }
        } catch (RuntimeException e) {
            log.warn("读取订单地理池失败，降级为数据库时间排序", e);
            return Optional.empty();
        }
    }

    public boolean isCurrent(GeoSlice slice) {
        try {
            return slice.version().equals(redisTemplate.opsForValue().get(RedisKeyConstants.ORDER_GEO_POOL_VERSION_KEY));
        } catch (RuntimeException e) {
            log.warn("订单地理池版本校验失败", e);
            return false;
        }
    }

    private Optional<GeoSlice> read(BigDecimal lng, BigDecimal lat) {
        List<?> result = redisTemplate.execute(RedisScripts.SEARCH_ORDER_GEO_POOL,
                List.of(RedisKeyConstants.ORDER_GEO_POOL_VERSION_KEY,
                        RedisKeyConstants.ORDER_GEO_POOL_READY_KEY, RedisKeyConstants.ORDER_GEO_POOL_KEY),
                lng.toPlainString(), lat.toPlainString(), String.valueOf(SEARCH_RADIUS_KM), String.valueOf(SCAN_LIMIT));
        if (result == null || result.isEmpty() || result.size() % 2 != 1) {
            return Optional.empty();
        }
        List<GeoOrder> orders = new ArrayList<>();
        for (int i = 1; i < result.size(); i += 2) {
            long id = Long.parseLong((String) result.get(i));
            double distance = Double.parseDouble((String) result.get(i + 1));
            if (id <= 0 || !Double.isFinite(distance) || distance < 0) {
                return Optional.empty();
            }
            orders.add(new GeoOrder(id, distance));
        }
        return Optional.of(new GeoSlice((String) result.getFirst(), List.copyOf(orders)));
    }
}
