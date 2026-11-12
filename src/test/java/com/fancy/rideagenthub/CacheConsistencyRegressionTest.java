package com.fancy.rideagenthub;

import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.agentbase.memory.HeapMemory;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agentbase.memory.MysqlMemory;
import com.fancy.rideagenthub.agentbase.memory.RedisMemory;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.dto.TicketCreateReqDTO;
import com.fancy.rideagenthub.mapper.TicketChatMapper;
import com.fancy.rideagenthub.mapper.TicketMapper;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.service.base.DelayedCacheEvictor;
import com.fancy.rideagenthub.service.base.RedisLock;
import com.fancy.rideagenthub.service.base.TicketPoolIndex;
import com.fancy.rideagenthub.service.impl.TicketServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheConsistencyRegressionTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void clearingHeapInvalidatesOldLease() {
        HeapMemory heap = new HeapMemory();
        long lease = heap.currentVersion("chat");
        heap.clear("chat");
        assertFalse(heap.overwriteIfVersionMatch("chat", messages("old"), lease));
        assertTrue(heap.getAll("chat").isEmpty());
        assertTrue(heap.overwriteIfVersionMatch("chat", messages("new"), heap.currentVersion("chat")));
    }

    @Test
    void heapAppendDoesNotCreatePartialHistoryOrDuplicateRefilledMessages() {
        HeapMemory heap = new HeapMemory();
        heap.appendIfVersionMatch("chat", messages("new"), 0);
        assertTrue(heap.getAll("chat").isEmpty());
        long beforeSave = heap.currentVersion("chat");
        assertTrue(heap.overwriteIfVersionMatch("chat", messages("old", "new"), beforeSave));
        heap.appendIfVersionMatch("chat", messages("new"), beforeSave);
        assertTrue(heap.getAll("chat").isEmpty());
    }

    @Test
    void rejectedHeapRefillPreservesConcurrentAppend() {
        HeapMemory heap = new HeapMemory();
        RedisMemory redis = mock(RedisMemory.class);
        MysqlMemory mysql = mock(MysqlMemory.class);
        when(redis.getAll("chat")).thenAnswer(invocation -> {
            heap.append("chat", messages("new"));
            return messages("old");
        });
        MessageMemory memory = new MessageMemory(heap, redis, mysql, mock(ChatManager.class));
        assertEquals("old", memory.get("user", "chat", 1).getFirst().getText());
        assertEquals("new", heap.getAll("chat").getFirst().getText());
    }

    @Test
    void rejectedRedisRefillDoesNotPopulateHeap() {
        HeapMemory heap = new HeapMemory();
        RedisMemory redis = mock(RedisMemory.class);
        MysqlMemory mysql = mock(MysqlMemory.class);
        when(redis.getAll("chat")).thenReturn(List.of());
        when(mysql.getAll("chat")).thenReturn(messages("old"));
        when(redis.overwriteIfLeaseValid(eq("chat"), anyList(), anyLong())).thenReturn(false);
        MessageMemory memory = new MessageMemory(heap, redis, mysql, mock(ChatManager.class));
        memory.get("user", "chat", 10);
        assertTrue(heap.getAll("chat").isEmpty());
        memory.getUserMessage("user", "chat");
        assertTrue(heap.getAll("chat").isEmpty());
    }

    @Test
    void limitedReadCachesFullHistoryForSubsequentReads() {
        HeapMemory heap = new HeapMemory();
        RedisMemory redis = mock(RedisMemory.class);
        MysqlMemory mysql = mock(MysqlMemory.class);
        when(redis.getAll("chat")).thenReturn(List.of());
        when(mysql.getAll("chat")).thenReturn(messages("one", "two", "three"));
        when(redis.overwriteIfLeaseValid(eq("chat"), anyList(), anyLong())).thenReturn(true);
        MessageMemory memory = new MessageMemory(heap, redis, mysql, mock(ChatManager.class));
        assertEquals(List.of("three"), texts(memory.get("user", "chat", 1)));
        assertEquals(3, memory.getUserMessage("user", "chat").size());
        assertEquals(3, memory.get("user", "chat", 100).size());
    }

    @Test
    void failedPersistenceDoesNotPublishNewMessages() {
        HeapMemory heap = new HeapMemory();
        heap.append("chat", messages("old"));
        RedisMemory redis = mock(RedisMemory.class);
        MysqlMemory mysql = mock(MysqlMemory.class);
        doThrow(new IllegalStateException("DB failure")).when(mysql).append(eq("chat"), anyList());
        MessageMemory memory = new MessageMemory(heap, redis, mysql, mock(ChatManager.class));
        assertThrows(IllegalStateException.class, () -> memory.save("user", "chat", messages("new")));
        assertEquals(List.of("old"), texts(heap.getAll("chat")));
        verify(redis, never()).appendIfVersionMatch(anyString(), anyList(), any(), anyLong());
    }

    @Test
    void successfulSaveAppendsToWarmCacheAndColdSaveDoesNotHideOlderMessages() {
        HeapMemory heap = new HeapMemory();
        heap.append("chat", messages("old"));
        RedisMemory redis = mock(RedisMemory.class);
        MysqlMemory mysql = mock(MysqlMemory.class);
        MessageMemory memory = new MessageMemory(heap, redis, mysql, mock(ChatManager.class));
        memory.save("user", "chat", messages("new"));
        assertEquals(List.of("old", "new"), texts(heap.getAll("chat")));
        memory.save("another-user", "cold", messages("new"));
        assertTrue(heap.getAll("cold").isEmpty());
    }

    @Test
    void clearingChatInvalidatesBothCacheLeasesAfterPersistence() {
        HeapMemory heap = new HeapMemory();
        RedisMemory redis = mock(RedisMemory.class);
        MysqlMemory mysql = mock(MysqlMemory.class);
        MessageMemory memory = new MessageMemory(heap, redis, mysql, mock(ChatManager.class));
        doAnswer(invocation -> {
            assertTrue(heap.overwriteIfVersionMatch("chat", messages("old"), 0));
            return null;
        }).when(mysql).clear("chat");
        memory.clearChat("chat");
        assertTrue(heap.getAll("chat").isEmpty());
        var ordered = inOrder(mysql, redis);
        ordered.verify(mysql).clear("chat");
        ordered.verify(redis).clear("chat");
    }

    @Test
    void ticketInvalidationRunsOnlyAfterCommitAndRetriesWhenFirstDeleteFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList())).thenReturn(1L);
        DelayedCacheEvictor delayed = mock(DelayedCacheEvictor.class);
        TicketPoolIndex pool = mock(TicketPoolIndex.class);
        TicketServiceImpl service = new TicketServiceImpl(mock(TicketMapper.class), mock(TicketChatMapper.class),
                mock(UserAuthMapper.class), redis, new ObjectMapper(), delayed,
                new RedisLock(redis), pool);
        TransactionSynchronizationManager.initSynchronization();
        service.submitTicket(TicketCreateReqDTO.builder().userId("1").ticketType(1)
                .title("test").content("test").build());
        String key = RedisKeyConstants.ticketStatisticsKey(LocalDate.now());
        verify(redis, never()).delete(key);
        verifyNoInteractions(delayed);
        verifyNoInteractions(pool);
        when(redis.delete(key)).thenThrow(new IllegalStateException("Redis failure"));
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(2, callbacks.size());
        callbacks.forEach(callback -> assertDoesNotThrow(callback::afterCommit));
        verify(pool).syncByTicketId(anyString());
        verify(delayed).evictAfter(key, Duration.ofMillis(500));
    }

    @Test
    void rolledBackTicketDoesNotInvalidateStatistics() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList())).thenReturn(1L);
        DelayedCacheEvictor delayed = mock(DelayedCacheEvictor.class);
        TicketPoolIndex pool = mock(TicketPoolIndex.class);
        TicketServiceImpl service = new TicketServiceImpl(mock(TicketMapper.class), mock(TicketChatMapper.class),
                mock(UserAuthMapper.class), redis, new ObjectMapper(), delayed,
                new RedisLock(redis), pool);
        TransactionSynchronizationManager.initSynchronization();
        service.submitTicket(TicketCreateReqDTO.builder().userId("1").ticketType(1)
                .title("test").content("test").build());
        for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) {
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(redis, never()).delete(anyString());
        verifyNoInteractions(delayed);
        verifyNoInteractions(pool);
    }

    @Test
    void delayedEvictorSurvivesDeleteFailuresAndShutsDown() throws InterruptedException {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CountDownLatch finished = new CountDownLatch(1);
        when(redis.delete("first")).thenThrow(new IllegalStateException("temporary failure"));
        when(redis.delete("second")).thenAnswer(invocation -> {
            finished.countDown();
            return true;
        });
        DelayedCacheEvictor evictor = new DelayedCacheEvictor(redis);
        try {
            evictor.evictAfter("first", Duration.ZERO);
            evictor.evictAfter("second", Duration.ofMillis(20));
            assertTrue(finished.await(3, TimeUnit.SECONDS));
        } finally {
            evictor.shutdown();
        }
        assertDoesNotThrow(() -> evictor.evictAfter("closed", Duration.ZERO));
    }

    private static List<Message> messages(String... texts) {
        return java.util.Arrays.stream(texts).<Message>map(UserMessage::new).toList();
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }
}
