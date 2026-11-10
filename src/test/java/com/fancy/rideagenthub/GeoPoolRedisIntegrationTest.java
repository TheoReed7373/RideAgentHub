package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.service.base.DriverGeoIndex;
import com.fancy.rideagenthub.service.base.OrderGeoPool;
import com.fancy.rideagenthub.service.base.RedisLock;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 只在显式指定的独立 Redis 实例运行，验证真实 GEO、Lua、TTL 与发布竞态。 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "\\d+")
class GeoPoolRedisIntegrationTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final RideOrderMapper orders = mock(RideOrderMapper.class);
    private OrderGeoPool pool;
    private DriverGeoIndex drivers;
    private static final BigDecimal LNG = new BigDecimal("120");
    private static final BigDecimal LAT = new BigDecimal("30");

    @BeforeAll
    static void connect() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "order-geo-redis"), RideOrder.class);
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
        pool = new OrderGeoPool(redis, orders, new RedisLock(redis));
        drivers = new DriverGeoIndex(redis);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys("order:geo:pool*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(List.of(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY));
    }

    @Test
    void coldMutationCannotHideExistingOrdersAndSearchUsesDistanceAndRadius() {
        RideOrder near = order(1, "120.001");
        RideOrder far = order(2, "120.01");
        RideOrder outside = order(3, "121");
        when(orders.selectList(any())).thenReturn(List.of(far, outside, near));
        pool.add("2", far.getStartLng(), far.getStartLat());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY)));
        var result = search();
        assertEquals(List.of(1L, 2L), ids(result));
        assertTrue(result.orders().getFirst().distanceKm() > 0);
        assertTrue(result.orders().getFirst().distanceKm() < result.orders().getLast().distanceKm());
        search();
        verify(orders, times(1)).selectList(any());
    }

    @Test
    void emptyWarmPoolDoesNotRepeatedlyRebuild() {
        when(orders.selectList(any())).thenReturn(List.of());
        assertTrue(search().orders().isEmpty());
        assertTrue(search().orders().isEmpty());
        verify(orders, times(1)).selectList(any());
    }

    @Test
    void membershipChangesInvalidateOldVersionAndRebuildWholePool() {
        when(orders.selectList(any())).thenReturn(List.of(order(1, "120")), List.of(order(2, "120")));
        var previous = search();
        pool.remove("1");
        assertFalse(pool.isCurrent(previous));
        assertEquals(List.of(2L), ids(search()));
    }

    @Test
    void missingGeoKeyWithSurvivingReadyMarkerTriggersRebuild() {
        when(orders.selectList(any())).thenReturn(List.of(order(1, "120")));
        search();
        redis.delete(RedisKeyConstants.ORDER_GEO_POOL_KEY);
        assertEquals(List.of(1L), ids(search()));
        verify(orders, times(2)).selectList(any());
    }

    @Test
    void corruptPartialPoolIsReplacedRatherThanExtended() {
        when(orders.selectList(any())).thenReturn(List.of(order(1, "120"), order(2, "120.01")));
        search();
        redis.opsForZSet().remove(RedisKeyConstants.ORDER_GEO_POOL_KEY, "1");
        assertEquals(List.of(1L, 2L), ids(search()));
        verify(orders, times(2)).selectList(any());
    }

    @Test
    void databaseMutationDuringRebuildRejectsOldSnapshot() {
        when(orders.selectList(any())).thenAnswer(invocation -> {
            pool.remove("1");
            return List.of(order(1, "120"));
        });
        assertTrue(pool.search(LNG, LAT).isEmpty());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY)));
    }

    @Test
    void expiredLockOwnerCannotPublishOrDeleteNewOwnersLock() {
        when(orders.selectList(any())).thenAnswer(invocation -> {
            redis.opsForValue().set(RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY, "new-owner", Duration.ofSeconds(30));
            return List.of(order(1, "120"));
        });
        assertTrue(pool.search(LNG, LAT).isEmpty());
        assertEquals("new-owner", redis.opsForValue().get(RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY)));
    }

    @Test
    void rebuildContentionFallsBackWithoutDatabaseStampede() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(orders.selectList(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(order(1, "120"));
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> pool.search(LNG, LAT));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(pool.search(LNG, LAT).isEmpty());
            } finally {
                release.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS).isPresent());
        }
        verify(orders, times(1)).selectList(any());
    }

    @Test
    void dataAndMarkerHaveFixedAlignedExpiry() {
        when(orders.selectList(any())).thenReturn(List.of(order(1, "120")));
        search();
        long dataTtl = redis.getExpire(RedisKeyConstants.ORDER_GEO_POOL_KEY, TimeUnit.MILLISECONDS);
        long readyTtl = redis.getExpire(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY, TimeUnit.MILLISECONDS);
        assertTrue(dataTtl > Duration.ofMinutes(119).toMillis());
        assertTrue(Math.abs(dataTtl - readyTtl) < 100);
        redis.expire(RedisKeyConstants.ORDER_GEO_POOL_KEY, Duration.ofSeconds(30));
        redis.expire(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY, Duration.ofSeconds(30));
        search();
        assertTrue(redis.getExpire(RedisKeyConstants.ORDER_GEO_POOL_KEY) <= 30);
    }

    @Test
    void invalidOrderCoordinatesCannotBreakValidNearbyOrders() {
        var invalid = order(2, "120");
        invalid.setStartLat(new BigDecimal("90"));
        when(orders.selectList(any())).thenReturn(List.of(invalid, order(1, "120")));
        assertEquals(List.of(1L), ids(search()));
    }

    @Test
    void driverHeartbeatAndPositionAreUpdatedAndRemovedTogether() {
        assertTrue(drivers.goOnline("1", LNG, LAT));
        var location = drivers.location("1");
        assertEquals(DriverGeoIndex.OnlineStatus.ONLINE, location.status());
        assertEquals(120, location.point().getLng().doubleValue(), 0.00001);
        assertEquals(30, location.point().getLat().doubleValue(), 0.00001);
        assertEquals(-1, redis.getExpire(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY));
        assertTrue(drivers.goOffline("1"));
        assertEquals(DriverGeoIndex.OnlineStatus.OFFLINE, drivers.status("1"));
        assertFalse(redis.opsForHash().hasKey(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, "1"));
        assertNull(redis.opsForZSet().score(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, "1"));
    }

    @Test
    void acceptRefreshCannotBringOfflineOrExpiredDriverBackOnline() {
        assertFalse(drivers.refreshIfExist("1", LNG, LAT));
        drivers.goOnline("1", LNG, LAT);
        drivers.goOffline("1");
        assertFalse(drivers.refreshIfExist("1", LNG, LAT));
        drivers.goOnline("1", LNG, LAT);
        redis.opsForHash().put(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, "1", "0");
        assertFalse(drivers.refreshIfExist("1", LNG, LAT));
        assertNull(redis.opsForZSet().score(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, "1"));
    }

    @Test
    void onlineRefreshUpdatesPositionWithoutCreatingOtherDrivers() {
        drivers.goOnline("1", LNG, LAT);
        assertTrue(drivers.refreshIfExist("1", new BigDecimal("121"), LAT));
        assertEquals(121, drivers.position("1").orElseThrow().getLng().doubleValue(), 0.00001);
        assertFalse(drivers.refreshIfExist("2", LNG, LAT));
        assertEquals(DriverGeoIndex.OnlineStatus.ONLINE, drivers.status("1"));
    }

    @Test
    void expiredDriverIsCleanedWithoutAffectingAnotherDriversHeartbeat() {
        drivers.goOnline("1", LNG, LAT);
        drivers.goOnline("2", LNG, LAT);
        redis.opsForHash().put(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, "1", "0");
        assertEquals(DriverGeoIndex.OnlineStatus.OFFLINE, drivers.status("1"));
        assertNull(redis.opsForZSet().score(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, "1"));
        assertEquals(DriverGeoIndex.OnlineStatus.ONLINE, drivers.status("2"));
    }

    @Test
    void orphanPositionMissingPositionAndInvalidHeartbeatAreCleaned() {
        redis.opsForGeo().add(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, new Point(120, 30), "1");
        assertEquals(DriverGeoIndex.OnlineStatus.OFFLINE, drivers.status("1"));
        drivers.goOnline("1", LNG, LAT);
        redis.delete(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY);
        assertEquals(DriverGeoIndex.OnlineStatus.OFFLINE, drivers.status("1"));
        assertFalse(redis.opsForHash().hasKey(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, "1"));
        drivers.goOnline("1", LNG, LAT);
        redis.opsForHash().put(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, "1", "invalid");
        assertEquals(DriverGeoIndex.OnlineStatus.OFFLINE, drivers.status("1"));
    }

    @Test
    void invalidCoordinatesDoNotLeaveHeartbeatBehind() {
        assertFalse(drivers.goOnline("1", LNG, new BigDecimal("90")));
        assertFalse(redis.opsForHash().hasKey(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, "1"));
        assertEquals(DriverGeoIndex.OnlineStatus.OFFLINE, drivers.status("1"));
    }

    private OrderGeoPool.GeoSlice search() {
        return pool.search(LNG, LAT).orElseThrow();
    }

    private List<Long> ids(OrderGeoPool.GeoSlice slice) {
        return slice.orders().stream().map(OrderGeoPool.GeoOrder::orderId).toList();
    }

    private RideOrder order(long id, String lng) {
        var order = GeoPoolRegressionTest.order(id);
        order.setStartLng(new BigDecimal(lng));
        return order;
    }
}
