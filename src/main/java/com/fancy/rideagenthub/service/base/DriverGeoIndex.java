package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.dto.Point;
import com.fancy.rideagenthub.util.RedisScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 共享 GEO 位置池与成员级心跳；上下线、刷新、状态读取均原子执行。 */
@Slf4j
@Component
public class DriverGeoIndex {
    public enum OnlineStatus { ONLINE, OFFLINE, UNAVAILABLE }

    public record Location(OnlineStatus status, Point point) { }

    private static final Duration ONLINE_TTL = Duration.ofSeconds(60);
    private final StringRedisTemplate redisTemplate;

    public DriverGeoIndex(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Redis GEO 使用 Web Mercator 纬度范围，而非通常的正负 90 度。 */
    public static boolean validCoordinates(BigDecimal lng, BigDecimal lat) {
        return lng != null && lat != null
                && lng.abs().compareTo(new BigDecimal("180")) <= 0
                && lat.abs().compareTo(new BigDecimal("85.05112878")) <= 0;
    }

    public boolean goOnline(String driverId, BigDecimal lng, BigDecimal lat) {
        return update(driverId, lng, lat, "online");
    }

    /** 检查在线与位置刷新不可拆开，避免并发下线后被接单请求重新上线。 */
    public boolean refreshIfExist(String driverId, BigDecimal lng, BigDecimal lat) {
        return update(driverId, lng, lat, "refresh");
    }

    private boolean update(String driverId, BigDecimal lng, BigDecimal lat, String mode) {
        if (driverId == null || driverId.isBlank() || !validCoordinates(lng, lat)) {
            return false;
        }
        try {
            return Long.valueOf(1).equals(redisTemplate.execute(RedisScripts.UPDATE_DRIVER_LOCATION,
                    keys(), driverId, lng.toPlainString(), lat.toPlainString(),
                    String.valueOf(ONLINE_TTL.toMillis()), mode));
        } catch (RuntimeException e) {
            log.warn("司机位置上报失败: driverId={}", driverId, e);
            return false;
        }
    }

    public boolean goOffline(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return false;
        }
        try {
            return Long.valueOf(1).equals(redisTemplate.execute(RedisScripts.REMOVE_DRIVER_LOCATION, keys(), driverId));
        } catch (RuntimeException e) {
            log.warn("司机下线失败: driverId={}", driverId, e);
            return false;
        }
    }

    /** 一次读取在线状态与坐标，避免状态检查后又下线导致查询使用过期坐标。 */
    public Location location(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return new Location(OnlineStatus.OFFLINE, null);
        }
        try {
            List<?> result = redisTemplate.execute(RedisScripts.READ_DRIVER_LOCATION, keys(),
                    driverId, String.valueOf(ONLINE_TTL.toMillis()));
            if (result == null) {
                return new Location(OnlineStatus.UNAVAILABLE, null);
            }
            if (result.isEmpty()) {
                return new Location(OnlineStatus.OFFLINE, null);
            }
            return new Location(OnlineStatus.ONLINE, Point.builder()
                    .lng(new BigDecimal((String) result.get(0)))
                    .lat(new BigDecimal((String) result.get(1))).build());
        } catch (RuntimeException e) {
            log.warn("读取司机位置失败: driverId={}", driverId, e);
            return new Location(OnlineStatus.UNAVAILABLE, null);
        }
    }

    public OnlineStatus status(String driverId) {
        return location(driverId).status();
    }

    public Optional<Point> position(String driverId) {
        return Optional.ofNullable(location(driverId).point());
    }

    private List<String> keys() {
        return List.of(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, RedisKeyConstants.DRIVER_GEO_ONLINE_KEY);
    }
}
