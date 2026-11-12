package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fancy.rideagenthub.agentbase.rag.RagService;
import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.mapper.*;
import com.fancy.rideagenthub.service.base.*;
import com.fancy.rideagenthub.service.impl.TicketServiceImpl;
import com.fancy.rideagenthub.util.SnowflakeIdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅连接专用测试端口；所有数据都位于独立临时 schema，绝不使用应用 schema。 */
@EnabledIfSystemProperty(named = "test.mysql.port", matches = "\\d+")
class RepairDatabaseIntegrationTest {
    private static JdbcTemplate admin, database;
    private static String schema;
    private static DriverManagerDataSource source;
    private static SqlSessionTemplate session;
    @BeforeAll static void setup() throws Exception {
        String url = "jdbc:mysql://127.0.0.1:" + System.getProperty("test.mysql.port")
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        schema = "ride_repair_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        source = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), "root", "");
        database = new JdbcTemplate(source);
        try (var connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/init.sql")); }
        var configuration = new MybatisConfiguration();
        for (Class<?> mapper : List.of(QaInfoMapper.class, QaElasticMapMapper.class, QaSyncJobMapper.class,
                TicketMapper.class, TicketChatMapper.class, ChatMapper.class, ChatMessageMapper.class)) configuration.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var sessionFactory = Objects.requireNonNull(factory.getObject(), "测试 SqlSessionFactory 创建失败");
        session = new SqlSessionTemplate(sessionFactory);
    }
    @AfterAll static void cleanup() {
        if (admin != null && schema != null && schema.matches("ride_repair_[0-9a-f]{32}")) admin.execute("DROP DATABASE " + schema);
    }
    @BeforeEach void emptyTables() {
        for (String table : List.of("sys_qa_info", "sys_qa_es", "sys_qa_sync_job", "sys_chat", "sys_ticket", "sys_ticket_chat", "sys_user")) {
            // 只重置本测试创建的临时 schema，整表清理是预期行为。
            //noinspection SqlWithoutWhere
            database.execute("DELETE FROM " + table);
        }
    }
    private TransactionTemplate transaction() {
        var template = new TransactionTemplate(new DataSourceTransactionManager(source));
        template.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        return template;
    }
    @Test void activeUsernamesAreUniqueButCanBeReusedAfterDeletion() {
        database.update("INSERT INTO sys_user(user_id,username,password,role) VALUES(1,'same','hash','USER')");
        assertThrows(DuplicateKeyException.class, () -> database.update("INSERT INTO sys_user(user_id,username,password,role) VALUES(2,'same','hash','USER')"));
        database.update("UPDATE sys_user SET is_deleted=1 WHERE user_id=1");
        database.update("INSERT INTO sys_user(user_id,username,password,role) VALUES(2,'same','hash','USER')");
        database.update("UPDATE sys_user SET is_deleted=1 WHERE user_id=2");
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class));
    }
    @Test void chatIdsHaveOneOwnerEvenUnderConflictingCreation() {
        database.update("INSERT INTO sys_chat(chat_id,user_id,title) VALUES('shared',1,'first')");
        assertThrows(DuplicateKeyException.class, () -> database.update("INSERT INTO sys_chat(chat_id,user_id,title) VALUES('shared',2,'second')"));
    }
    // 索引和列由 setup 中的 init.sql 动态创建；此处故意还原旧结构后验证迁移，不依赖 IDE 关联的业务库。
    @SuppressWarnings("SqlResolve")
    @Test void migrationUpgradesOldSchemaWithoutDeletingExistingAccounts() throws Exception {
        database.update("INSERT INTO sys_user(user_id,username,password,role) VALUES(1,'existing','hash','USER')");
        database.execute("ALTER TABLE sys_user DROP INDEX uk_user_id, DROP INDEX uk_active_username_role, DROP INDEX uk_active_email_role, DROP COLUMN active_username, DROP COLUMN active_email");
        database.execute("ALTER TABLE sys_chat DROP INDEX uk_chat_id, DROP COLUMN history_deleted");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/migrations/20261113_security_consistency.sql"));
        }
        assertEquals("existing", database.queryForObject("SELECT username FROM sys_user WHERE user_id=1", String.class));
        assertThrows(DuplicateKeyException.class, () -> database.update("INSERT INTO sys_user(user_id,username,password,role) VALUES(2,'existing','hash','USER')"));
    }
    @Test void failedBatchRollsBackBothQaDataAndSyncJobs() {
        var service = new RagService(mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class), session.getMapper(QaInfoMapper.class),
                session.getMapper(QaElasticMapMapper.class));
        ReflectionTestUtils.setField(service, "syncJobs", session.getMapper(QaSyncJobMapper.class));
        ReflectionTestUtils.setField(service, "snowflakeIdWorker", new SnowflakeIdWorker(1, 1));
        var valid = new RagQAAddDTO(); valid.setQuestions(List.of("question")); valid.setAnswer("answer");
        var invalid = new RagQAAddDTO();
        assertThrows(IllegalArgumentException.class, () -> transaction().executeWithoutResult(tx -> service.addQAs(List.of(valid, invalid))));
        for (String table : List.of("sys_qa_info", "sys_qa_es", "sys_qa_sync_job"))
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
    @Test void retiredHistoryCannotBeRecreatedByLaterMessageSave() {
        database.update("INSERT INTO sys_chat(chat_id,user_id,title) VALUES('retired',1,'test')");
        var memory = new com.fancy.rideagenthub.agentbase.memory.MysqlMemory(session.getMapper(ChatMessageMapper.class),
                new com.fancy.rideagenthub.agentbase.memory.MessageParser());
        ReflectionTestUtils.setField(memory, "chatMapper", session.getMapper(ChatMapper.class));
        transaction().executeWithoutResult(tx -> memory.append("retired", List.of(new org.springframework.ai.chat.messages.UserMessage("old"))));
        new ChatInfoService(session.getMapper(ChatMapper.class), mock(StringRedisTemplate.class)).retireHistory("retired");
        memory.clear("retired");
        assertThrows(com.fancy.rideagenthub.exception.BusinessException.class, () -> transaction().executeWithoutResult(tx ->
                memory.append("retired", List.of(new org.springframework.ai.chat.messages.UserMessage("late")))));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM sys_messages WHERE chat_id='retired'", Integer.class));
    }
    // sys_qa_sync_job 属于运行时创建的独立测试 schema。
    @SuppressWarnings("SqlResolve")
    @Test void newMutationAfterLockedSyncCannotLoseItsJob() throws Exception {
        QaSyncJobMapper jobs = session.getMapper(QaSyncJobMapper.class); jobs.enqueue(1L);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), writerStarted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                var reader = pool.submit(() -> transaction().executeWithoutResult(tx -> {
                    database.queryForList("SELECT * FROM sys_qa_sync_job WHERE group_id=1 FOR UPDATE");
                    locked.countDown(); await(release); jobs.deleteById(1L);
                }));
                assertTrue(locked.await(3, TimeUnit.SECONDS));
                var writer = pool.submit(() -> transaction().executeWithoutResult(tx -> { writerStarted.countDown(); jobs.enqueue(1L); }));
                assertTrue(writerStarted.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));
                release.countDown(); reader.get(3, TimeUnit.SECONDS); writer.get(3, TimeUnit.SECONDS);
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM sys_qa_sync_job WHERE group_id=1", Integer.class));
            } finally {
                // 必须先放行等待任务，再由 try-with-resources 等待线程池关闭。
                release.countDown();
                pool.shutdownNow();
            }
        }
    }
    @Test void closingTicketPreventsConcurrentConfirmationFromOverwritingClosedState() throws Exception {
        database.update("INSERT INTO sys_ticket(ticket_id,user_id,user_type,ticket_type,ticket_status,title,content) VALUES('T',1,1,1,2,'x','x')");
        var tickets = new TicketServiceImpl(session.getMapper(TicketMapper.class), session.getMapper(TicketChatMapper.class),
                mock(UserAuthMapper.class), mock(StringRedisTemplate.class), new ObjectMapper(),
                mock(DelayedCacheEvictor.class), mock(RedisLock.class), mock(TicketPoolIndex.class));
        CountDownLatch changed = new CountDownLatch(1), release = new CountDownLatch(1), confirming = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                var close = pool.submit(() -> transaction().executeWithoutResult(tx -> {
                    assertTrue(tickets.cancelTicket("T", 1L)); changed.countDown(); await(release);
                }));
                assertTrue(changed.await(3, TimeUnit.SECONDS));
                var confirm = pool.submit(() -> {
                    confirming.countDown();
                    var req = new TicketFeedbackReqDTO(); req.setTicketId("T"); req.setUserId("1");
                    return transaction().execute(tx -> {
                        assertThrows(IllegalArgumentException.class, () -> tickets.confirmAndRate(req)); return true;
                    });
                });
                assertTrue(confirming.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> confirm.get(100, TimeUnit.MILLISECONDS));
                release.countDown(); close.get(3, TimeUnit.SECONDS); assertTrue(confirm.get(3, TimeUnit.SECONDS));
                assertEquals(4, database.queryForObject("SELECT ticket_status FROM sys_ticket WHERE ticket_id='T'", Integer.class));
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(4, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
