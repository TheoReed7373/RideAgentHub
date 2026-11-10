package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fancy.rideagenthub.domain.dto.TicketQueryReqDTO;
import com.fancy.rideagenthub.domain.vo.TicketVO;
import com.fancy.rideagenthub.mapper.TicketChatMapper;
import com.fancy.rideagenthub.mapper.TicketMapper;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.service.base.DelayedCacheEvictor;
import com.fancy.rideagenthub.service.base.RedisLock;
import com.fancy.rideagenthub.service.base.TicketPoolIndex;
import com.fancy.rideagenthub.service.impl.TicketServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 只在独立测试端口中新建临时 schema，不使用应用数据库。 */
@EnabledIfSystemProperty(named = "test.mysql.port", matches = "\\d+")
class TicketPoolDatabaseIntegrationTest {

    private static JdbcTemplate admin;
    private static JdbcTemplate database;
    private static String schema;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static TicketPoolIndex index;
    private static TicketServiceImpl service;

    @BeforeAll
    static void setupServices() throws Exception {
        String serverUrl = "jdbc:mysql://127.0.0.1:" + System.getProperty("test.mysql.port")
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(serverUrl, "root", ""));
        schema = "ticket_pool_regression_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        var source = new DriverManagerDataSource(serverUrl.replace("/?", "/" + schema + "?"), "root", "");
        database = new JdbcTemplate(source);
        try (var input = new ClassPathResource("sql/init.sql").getInputStream()) {
            String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var table = Pattern.compile("(?s)CREATE TABLE `sys_ticket` \\(.*?;").matcher(ddl);
            assertTrue(table.find());
            database.execute(table.group());
        }
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TicketMapper.class);
        var sessionFactory = new MybatisSqlSessionFactoryBean();
        sessionFactory.setDataSource(source);
        sessionFactory.setConfiguration(configuration);
        var tickets = new SqlSessionTemplate(sessionFactory.getObject()).getMapper(TicketMapper.class);
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.redis.port")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        var locks = new RedisLock(redis);
        index = new TicketPoolIndex(redis, tickets, locks);
        service = new TicketServiceImpl(tickets, mock(TicketChatMapper.class), mock(UserAuthMapper.class),
                redis, new ObjectMapper(), mock(DelayedCacheEvictor.class), locks, index);
    }

    @AfterAll
    static void shutdown() {
        if (factory != null) {
            factory.destroy();
        }
        if (admin != null && schema != null) {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    @BeforeEach
    void seed() {
        cleanup();
        database.update("DELETE FROM sys_ticket");
        for (long id : List.of(1L, 9L, 10L, 11L)) {
            database.update("INSERT INTO sys_ticket(id,ticket_id,user_id,user_type,ticket_type,priority,ticket_status,handler_id,title,content,updated_at)"
                            + " VALUES(?,?,1,1,1,?,0,7,'ticket','test',?)", id, "T" + id,
                    id == 1 ? 2 : 1, id == 1 ? "2025-11-10 12:00:00" : id == 11 ? "2026-11-09 12:00:00" : "2026-11-10 12:00:00");
        }
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys("ticket:pool:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void indexAndDatabaseReturnIdenticalSortedPagesAndTotals() {
        var req = request();
        var indexed = service.getAdminTicketPage(req);
        assertEquals(4, indexed.getTotal());
        assertEquals(List.of("9", "11"), ids(indexed.getRecords()));
        req.setKeyword("ticket");
        var fromDb = service.getAdminTicketPage(req);
        assertEquals(indexed.getTotal(), fromDb.getTotal());
        assertEquals(ids(indexed.getRecords()), ids(fromDb.getRecords()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "userType", "handler", "keyword", "allStatuses"})
    void databaseFallbackUsesLimitAndCountWithoutPaginationPlugin(String filter) {
        var req = request();
        switch (filter) {
            case "type" -> req.setType(1);
            case "userType" -> req.setUserType(1);
            case "handler" -> req.setHandlerId("7");
            case "keyword" -> req.setKeyword("ticket");
            case "allStatuses" -> req.setStatus(null);
            default -> throw new AssertionError(filter);
        }
        var result = service.getAdminTicketPage(req);
        assertEquals(4, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(List.of("9", "11"), ids(result.getRecords()));
    }

    @Test
    void deletedIndexedRowTriggersFullDatabasePageWithCorrectTotal() {
        index.slice(0, 0, 2).orElseThrow();
        database.update("DELETE FROM sys_ticket WHERE id=1");
        var req = request();
        req.setCurrent(1);
        var result = service.getAdminTicketPage(req);
        assertEquals(3, result.getTotal());
        assertEquals(List.of("10", "9"), ids(result.getRecords()));
    }

    @Test
    void movedIndexedRowCannotLeakFromOldStatusPool() {
        index.slice(0, 0, 2).orElseThrow();
        database.update("UPDATE sys_ticket SET ticket_status=1 WHERE id=1");
        var req = request();
        req.setCurrent(1);
        var result = service.getAdminTicketPage(req);
        assertEquals(3, result.getTotal());
        assertEquals(List.of("10", "9"), ids(result.getRecords()));
        assertTrue(result.getRecords().stream().allMatch(row -> row.getTicketStatus() == 0));
    }

    @Test
    void committedPriorityChangeRebuildsOrdering() {
        index.slice(0, 0, 10).orElseThrow();
        database.update("UPDATE sys_ticket SET priority=3 WHERE id=11");
        index.syncByTicketId("T11");
        var req = request();
        req.setCurrent(1);
        assertEquals(List.of("11", "1"), ids(service.getAdminTicketPage(req).getRecords()));
    }

    @Test
    void databaseFallbackSupportsOffsetBeyondIntegerRange() {
        var req = request();
        req.setCurrent(Integer.MAX_VALUE);
        req.setKeyword("ticket");
        var result = service.getAdminTicketPage(req);
        assertEquals(4, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    private TicketQueryReqDTO request() {
        var req = new TicketQueryReqDTO();
        req.setStatus(0);
        req.setCurrent(2);
        req.setSize(2);
        return req;
    }

    private List<String> ids(List<TicketVO> rows) {
        return rows.stream().map(TicketVO::getId).toList();
    }
}
