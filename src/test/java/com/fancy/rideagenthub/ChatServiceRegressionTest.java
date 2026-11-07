package com.fancy.rideagenthub;

import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agents.*;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.ChatParamDTO;
import com.fancy.rideagenthub.service.base.ChatRateLimiter;
import com.fancy.rideagenthub.service.base.ClassificationCache;
import com.fancy.rideagenthub.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceRegressionTest {

    private ChatModel model;
    private ClassificationCache cache;
    private ChatRateLimiter limiter;
    private OrderAgent orders;
    private DailyAgent daily;
    private MessageMemory memory;
    private ChatManager manager;
    private HashOperations<String, Object, Object> hash;
    private ChatServiceImpl service;
    private Sinks.Many<AgentEvent> sink;

    @BeforeEach
    void setup() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        model = mock(ChatModel.class);
        cache = mock(ClassificationCache.class);
        limiter = mock(ChatRateLimiter.class);
        when(limiter.tryAcquire("1")).thenReturn(true);
        orders = mock(OrderAgent.class);
        daily = mock(DailyAgent.class);
        memory = mock(MessageMemory.class);
        when(memory.get("1", "chat", 1)).thenReturn(List.of());
        manager = mock(ChatManager.class);
        service = new ChatServiceImpl(redis, memory, orders, mock(FallbackAgent.class), daily,
                mock(SupportAgent.class), manager, cache, limiter, model);
        sink = Sinks.many().unicast().onBackpressureBuffer();
    }

    @Test
    void limitedChatStopsBeforeMemoryClassifierAndAgents() {
        when(limiter.tryAcquire("1")).thenReturn(false);
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verifyNoInteractions(memory, cache, orders, daily);
        verify(model, never()).call(any(Prompt.class));
        assertEquals("notify", events().getFirst().type());
    }

    @Test
    void cachedClassificationSkipsModel() {
        when(cache.get("SINGLE_TURN", null, "prompt")).thenReturn("DAILY");
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verify(daily).invoke(sink, "chat", "1", "prompt");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void modelFailureProducesCompletedResponse() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("Upstream unavailable"));
        assertDoesNotThrow(() -> service.chat("chat", new ChatParamDTO("prompt"), sink, "1"));
        assertEquals("message", events().getLast().type());
        verify(cache, never()).put(anyString(), any(), anyString(), anyString());
        verify(manager, never()).initChat(anyString(), anyString(), anyString());
    }

    @Test
    void blankOrInvalidModelResultsAreNotCachedOrPersisted() {
        when(model.call(any(Prompt.class))).thenReturn(response(" \n"));
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verify(cache, never()).put(anyString(), any(), anyString(), anyString());
        verify(hash, never()).put(anyString(), eq("classification"), any());
        verify(manager, never()).initChat(anyString(), anyString(), anyString());
        assertEquals("message", events().getLast().type());
    }

    @Test
    void modelLabelsAreNormalizedBeforeCachingAndRouting() {
        when(model.call(any(Prompt.class))).thenReturn(response(" daily\n"));
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verify(daily).invoke(sink, "chat", "1", "prompt");
        verify(cache).put("SINGLE_TURN", null, "prompt", "DAILY");
    }

    @Test
    void invalidCachedResultIsReplacedUsingClassifier() {
        when(cache.get("SINGLE_TURN", null, "prompt")).thenReturn("invalid");
        when(model.call(any(Prompt.class))).thenReturn(response("DAILY"));
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verify(model).call(any(Prompt.class));
        verify(daily).invoke(sink, "chat", "1", "prompt");
    }

    @Test
    void dangerCannotBypassGuardDuringActiveOrder() {
        when(hash.get(anyString(), eq("classification"))).thenReturn("ORDER");
        when(cache.get(anyString(), any(), anyString())).thenReturn("DANGER");
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verifyNoInteractions(orders);
        assertEquals("message", events().getLast().type());
    }

    @Test
    void resumeCannotBypassUserRateLimit() {
        when(limiter.tryAcquire("1")).thenReturn(false);
        service.resume("chat", new ChatParamDTO("confirm"), sink, "1");
        verifyNoInteractions(orders);
        assertEquals("notify", events().getFirst().type());
    }

    @Test
    void emptyPromptCompletesWithoutSpendingQuota() {
        service.chat("chat", new ChatParamDTO(" \n"), sink, "1");
        verifyNoInteractions(limiter, memory, cache, orders, daily);
        assertEquals("error", events().getFirst().type());
    }

    @Test
    void nullResumeRequestCompletesWithoutSpendingQuota() {
        service.resume("chat", null, sink, "1");
        verifyNoInteractions(limiter, orders);
        assertEquals("error", events().getFirst().type());
    }

    @Test
    void nullHistoricalTextStillUsesMultiTurnClassifierPrompt() {
        Message history = mock(Message.class);
        when(memory.get("1", "chat", 1)).thenReturn(List.of(history));
        when(hash.get(anyString(), eq("classification"))).thenReturn("DAILY");
        when(model.call(any(Prompt.class))).thenReturn(response("DAILY"));
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertTrue(prompt.getValue().getUserMessage().getText().contains("上一次路由给DAILY"));
    }

    @Test
    void activeOrderKeepsItsAgentForNormalTopicChanges() {
        when(hash.get(anyString(), eq("classification"))).thenReturn("ORDER");
        when(cache.get(anyString(), any(), anyString())).thenReturn("DAILY");
        service.chat("chat", new ChatParamDTO("prompt"), sink, "1");
        verify(orders).invoke(sink, "chat", "1", "prompt");
        verifyNoInteractions(daily);
    }

    private ChatResponse response(String label) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(label))));
    }

    private List<AgentEvent> events() {
        return sink.asFlux().collectList().block(Duration.ofSeconds(1));
    }
}
