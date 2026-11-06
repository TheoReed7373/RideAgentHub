package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.config.RideOrderTimeoutProperties;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.service.RideOrderService;
import com.fancy.rideagenthub.service.base.OrderGrabService;
import com.fancy.rideagenthub.service.base.OrderDelayQueue;
import com.fancy.rideagenthub.service.base.OrderRouteService;
import com.fancy.rideagenthub.service.impl.RideOrderServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅连接显式指定的本地测试端口，在独立的新建数据库内验证抢单。 */
@EnabledIfSystemProperty(named = "test.mysql.port", matches = "\\d+")
class OrderGrabIntegrationTest {

    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static JdbcTemplate admin;
    private static JdbcTemplate database;
    private static String schema;
    private static RideOrderMapper orders;
    private static OrderGrabService grabs;
    private static RideOrderService rides;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void setupServices() throws Exception {
        String serverUrl = "jdbc:mysql://127.0.0.1:" + System.getProperty("test.mysql.port")
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(serverUrl, "root", ""));
        schema = "order_grab_regression_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        var source = new DriverManagerDataSource(serverUrl.replace("/?", "/" + schema + "?"), "root", "");
        database = new JdbcTemplate(source);
        try (var input = new ClassPathResource("sql/init.sql").getInputStream()) {
            String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var tables = Pattern.compile("(?s)CREATE TABLE `(?:sys_user|sys_ride_order)` \\(.*?;").matcher(ddl);
            int created = 0;
            while (tables.find()) {
                database.execute(tables.group());
                created++;
            }
            assertEquals(2, created);
        }
        transactionManager = new DataSourceTransactionManager(source);
        var configuration = new MybatisConfiguration();
        configuration.addMapper(RideOrderMapper.class);
        configuration.addMapper(UserAuthMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        orders = session.getMapper(RideOrderMapper.class);
        redisFactory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.redis.port")));
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
        grabs = transactional(new OrderGrabService(redis, orders, session.getMapper(UserAuthMapper.class)));
        rides = rideService(orders);
    }

    @AfterAll
    static void shutdown() {
        if (redisFactory != null) {
            redisFactory.destroy();
        }
        if (admin != null && schema != null) {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    @BeforeEach
    void seed() {
        database.update("DELETE FROM sys_ride_order");
        database.update("DELETE FROM sys_user");
        for (int driver = 1; driver <= 2; driver++) {
            database.update("INSERT INTO sys_user(user_id,username,password,role) VALUES(?,?,?,'DRIVER')", driver, "driver-" + driver, "test");
        }
        for (int order = 100; order <= 101; order++) {
            database.update("INSERT INTO sys_ride_order(order_id,user_id,start_address,start_lat,start_lng,end_address,end_lat,end_lng) VALUES(?,9,'start',0,0,'end',1,1)", order);
        }
        clearCache();
    }

    @AfterEach
    void clearCache() {
        redis.delete(List.of(RedisKeyConstants.orderStatusKey("100"), RedisKeyConstants.orderStatusKey("101"),
                RedisKeyConstants.driverActiveKey("1"), RedisKeyConstants.driverActiveKey("2")));
    }

    @Test
    void coldCacheAcceptanceCommitsAndSecondOrderIsRejected() {
        assertTrue(rides.driverAcceptOrder("0100", "01", null, null));
        assertEquals(1L, database.queryForObject("SELECT driver_id FROM sys_ride_order WHERE order_id=100", Long.class));
        assertEquals("20", redis.opsForValue().get(RedisKeyConstants.orderStatusKey("100")));
        redis.delete(RedisKeyConstants.driverActiveKey("1"));
        BusinessException busy = assertThrows(BusinessException.class, () -> rides.driverAcceptOrder("101", "1", null, null));
        assertEquals(409, busy.getCode());
        assertEquals(1, activeCount());
    }

    @RepeatedTest(5)
    void sameDriverConcurrentRequestsAcceptOnlyOneOrder() throws Exception {
        assertEquals(1, race(() -> attempt("100", "1"), () -> attempt("101", "1")));
        assertEquals(1, activeCount());
    }

    @RepeatedTest(5)
    void sameOrderConcurrentDriversProduceOnlyOneWinner() throws Exception {
        assertEquals(1, race(() -> attempt("100", "1"), () -> attempt("100", "2")));
        assertEquals(1, activeCount());
        long winner = database.queryForObject("SELECT driver_id FROM sys_ride_order WHERE order_id=100", Long.class);
        String loserClaim = redis.opsForValue().get(RedisKeyConstants.driverActiveKey(winner == 1 ? "2" : "1"));
        assertNotEquals("100", loserClaim);
    }

    @Test
    void rollbackAfterSuccessfulUpdateDoesNotPublishAcceptance() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.execute(status -> {
            assertTrue(rides.driverAcceptOrder("100", "1", null, null));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(0, activeCount());
        assertNull(redis.opsForValue().get(RedisKeyConstants.driverActiveKey("1")));
        assertEquals("10", redis.opsForValue().get(RedisKeyConstants.orderStatusKey("100")));
        assertTrue(rides.driverAcceptOrder("100", "1", null, null));
    }

    @Test
    void databaseExceptionReleasesClaimAndAllowsRetry() {
        RideOrderMapper failing = mock(RideOrderMapper.class);
        when(failing.update(isNull(), any())).thenThrow(new IllegalStateException("database failure"));
        RideOrderService broken = rideService(failing);
        assertThrows(IllegalStateException.class, () -> broken.driverAcceptOrder("100", "1", null, null));
        assertNull(redis.opsForValue().get(RedisKeyConstants.driverActiveKey("1")));
        assertEquals(0, activeCount());
        assertTrue(rides.driverAcceptOrder("101", "1", null, null));
    }

    @Test
    void staleIdleSentinelCannotBypassDatabaseAssignment() {
        assertTrue(rides.driverAcceptOrder("100", "1", null, null));
        redis.opsForValue().set(RedisKeyConstants.driverActiveKey("1"), "__IDLE__");
        assertFalse(attempt("101", "1"));
        assertEquals(1, activeCount());
    }

    @Test
    void staleCreatedStatusIsRejectedByDatabaseAndClaimIsReleased() {
        assertTrue(rides.driverAcceptOrder("100", "1", null, null));
        redis.opsForValue().set(RedisKeyConstants.orderStatusKey("100"), "10");
        assertFalse(rides.driverAcceptOrder("100", "2", null, null));
        assertNull(redis.opsForValue().get(RedisKeyConstants.driverActiveKey("2")));
        assertNull(redis.opsForValue().get(RedisKeyConstants.orderStatusKey("100")));
        assertEquals(1, activeCount());
    }

    @Test
    void lateReleaseDoesNotDeleteNewOrderAndLateSyncDoesNotRegressStatus() {
        redis.opsForValue().set(RedisKeyConstants.driverActiveKey("1"), "101");
        grabs.releaseDriver("1", "100");
        grabs.rollbackGrab("1", "100");
        assertEquals("101", redis.opsForValue().get(RedisKeyConstants.driverActiveKey("1")));
        grabs.syncOrderStatus("100", 90);
        grabs.syncOrderStatus("100", 20);
        assertEquals("90", redis.opsForValue().get(RedisKeyConstants.orderStatusKey("100")));
    }

    private static int activeCount() {
        return database.queryForObject("SELECT COUNT(*) FROM sys_ride_order WHERE driver_id IS NOT NULL AND order_status NOT IN (50,60,90)", Integer.class);
    }

    private static boolean attempt(String order, String driver) {
        try {
            return rides.driverAcceptOrder(order, driver, null, null);
        } catch (BusinessException failure) {
            assertEquals(409, failure.getCode());
            return false;
        }
    }

    private int race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> { start.await(); return first.call(); });
            var two = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return (one.get(10, TimeUnit.SECONDS) ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static RideOrderService rideService(RideOrderMapper mapper) {
        return transactional(new RideOrderServiceImpl(mapper, mock(AmapRouteService.class),
                mock(OrderRouteService.class), mock(AmapGeoRegeoService.class), grabs,
                mock(OrderDelayQueue.class), new RideOrderTimeoutProperties()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
