package com.fancy.rideagenthub;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fancy.rideagenthub.agentbase.amap.service.*;
import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.agentbase.memory.*;
import com.fancy.rideagenthub.agentbase.tool.*;
import com.fancy.rideagenthub.agents.*;
import com.fancy.rideagenthub.config.*;
import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.domain.entity.*;
import com.fancy.rideagenthub.domain.enums.UserRole;
import com.fancy.rideagenthub.domain.vo.*;
import com.fancy.rideagenthub.exception.*;
import com.fancy.rideagenthub.mapper.*;
import com.fancy.rideagenthub.security.*;
import com.fancy.rideagenthub.service.*;
import com.fancy.rideagenthub.service.base.*;
import com.fancy.rideagenthub.service.impl.*;
import com.fancy.rideagenthub.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.redisson.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecurityRepairRegressionTest {
    private final RideOrderMapper orders = mock(RideOrderMapper.class);
    private final AmapRouteService amap = mock(AmapRouteService.class);
    private final OrderRouteService routes = mock(OrderRouteService.class);
    private final PaymentVerifier payments = mock(PaymentVerifier.class);
    private final RideOrderServiceImpl service = new RideOrderServiceImpl(orders, amap, routes,
            mock(AmapGeoRegeoService.class), mock(OrderGrabService.class), mock(OrderDelayQueue.class),
            mock(OrderGeoPool.class), mock(DriverGeoIndex.class), new RideOrderTimeoutProperties(), OrderLockTestSupport.availableClient());

    @BeforeAll static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "security-repair");
        for (Class<?> type : List.of(RideOrder.class, Ticket.class, Chat.class, ChatToolResponse.class))
            TableInfoHelper.initTableInfo(assistant, type);
    }
    @BeforeEach void setup() {
        UserTokenContext.set(UserToken.builder().userId(1L).role("USER").build());
        ReflectionTestUtils.setField(service, "paymentVerifier", payments);
        ReflectionTestUtils.setField(service, "snowflakeIdWorker", new SnowflakeIdWorker(1, 1));
    }
    @AfterEach void clear() { UserTokenContext.clear(); }

    @Test void systemCancellationCannotBeSelectedExternally() {
        assertEquals(403, assertThrows(BusinessException.class, () -> service.cancelOrder("100", "1", 3, "x")).getCode());
        verifyNoInteractions(orders);
    }
    @Test void foreignPaymentIsRejectedBeforeChannelVerification() {
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().userId(2L).orderStatus(50).build());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.payOrder("100", 1, "fake")).getCode());
        verifyNoInteractions(payments);
        verify(orders, never()).update(isNull(), any());
    }
    @Test void unverifiablePaymentCannotBecomePaid() {
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().userId(1L).orderStatus(50).realPrice(BigDecimal.TEN).build());
        doThrow(new BusinessException(503, "unavailable")).when(payments).verifyPaid("100", "1", 1, "fake", BigDecimal.TEN);
        assertThrows(BusinessException.class, () -> service.payOrder("100", 1, "fake"));
        verify(orders, never()).update(isNull(), any());
    }
    @Test void loggedOutPaymentNeverUsesMapper() {
        UserTokenContext.clear();
        assertThrows(UnauthorizedException.class, () -> service.payOrder("100", 1, "fake"));
        verifyNoInteractions(orders);
    }
    @Test void creationOverwritesClientIdentityPriceAndTrace() {
        EstRouteVO planned = new EstRouteVO(); planned.setEstKm("10"); planned.setEstTime("600");
        when(amap.getDrivingRouteEst(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(planned);
        CreateOrderDTO dto = CreateOrderDTO.builder().userId("2").startLat(BigDecimal.ONE).startLng(BigDecimal.ONE)
                .endLat(BigDecimal.TEN).endLng(BigDecimal.TEN).startAddress("a").endAddress("b")
                .estPrice(new BigDecimal("0.01")).radio(new BigDecimal("0.01")).mongoTraceId("untrusted").build();
        service.createOrder(dto);
        var captured = ArgumentCaptor.forClass(RideOrder.class);
        verify(orders).insert(captured.capture());
        RideOrder saved = captured.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals(RidePricing.calculate("10", "600", 1, 0).getEstPrice(), saved.getEstPrice());
        assertNotEquals("untrusted", saved.getMongoTraceId());
        verify(routes).addOrder(eq(saved.getMongoTraceId()), same(planned));
    }
    @Test void missingRouteDoesNotBreakOrderDetail() {
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).userId(1L).mongoTraceId("missing").build());
        assertNull(service.getOrderDetail("100", "1", UserRole.USER).getEstRoute());
    }
    @Test void invalidPricingDataIsNotSilentlyChargedAsZero() {
        assertThrows(BusinessException.class, () -> RidePricing.calculate("bad", "600", 1, 0));
        assertThrows(BusinessException.class, () -> RidePricing.calculate("10", "-1", 1, 0));
    }
    @Test void missingPoiIsNormalBusinessResult() {
        var tool = new UserPOITool(mock(UserPOIService.class));
        assertTrue(tool.getUserPOIDetailByTag("missing", context()).contains("未找到"));
    }
    @Test void actualToolSchemaUsesDispatchName() {
        var tool = new ToolRepPointerTool(mock(ToolResponseMemory.class));
        assertEquals("getCalling", org.springframework.ai.support.ToolCallbacks.from(tool)[0].getToolDefinition().name());
    }
    @Test void failedToolPersistenceDoesNotPublishCache() {
        var redis = mock(StringRedisTemplate.class); var mapper = mock(ChatToolResponseMapper.class);
        doThrow(new IllegalStateException("db")).when(mapper).insert(any(ChatToolResponse.class));
        assertThrows(IllegalStateException.class, () -> new ToolResponseMemory(redis, mapper).save("call", "chat", "result"));
        verifyNoInteractions(redis);
    }
    @Test void historyWindowKeepsWholeToolBatch() {
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
        history.add(new UserMessage("question"));
        history.add(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("a", "function", "tool", "{}"))).build());
        history.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("a", "tool", "answer"))).build());
        assertEquals(2, MessageWindow.tail(history, 1).size());
        assertInstanceOf(AssistantMessage.class, MessageWindow.tail(history, 1).getFirst());
    }
    @Test void corruptToolPairingIsRejectedWithReadableError() {
        var orphan = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("missing", "tool", "x"))).build();
        assertThrows(IllegalStateException.class, () -> MessageWindow.tail(List.of(orphan), 100));
    }
    @Test void completionCallbackFailureTerminatesSink() {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AgentStreams.subscribe(Flux.empty(), sink, ignored -> {}, ignored -> {}, () -> { throw new IllegalStateException("db"); });
        assertThrows(IllegalStateException.class, () -> sink.asFlux().collectList().block(Duration.ofSeconds(1)));
    }
    @Test void foreignChatIsRejectedBeforeAnyCachedHistoryRead() {
        var mapper = mock(ChatMapper.class);
        when(mapper.selectOne(any())).thenReturn(Chat.builder().chatId("chat").userId(2L).build());
        var info = new ChatInfoService(mapper, mock(StringRedisTemplate.class));
        assertThrows(ForbiddenException.class, () -> info.assertOwned("1", "chat", true));
    }
    @Test void orphanedCachedHistoryIsNeverReadAsANewChat() {
        var chats = mock(ChatManager.class); when(chats.isNewOwnedChat("1", "unknown")).thenReturn(true);
        var cached = mock(RedisMemory.class); var stored = mock(MysqlMemory.class);
        var memory = new MessageMemory(new HeapMemory(), cached, stored, chats);
        assertTrue(memory.get("1", "unknown", 100).isEmpty());
        verifyNoInteractions(cached, stored);
    }
    @Test void closedTicketsCannotReenterProcessing() {
        var tickets = mock(TicketMapper.class);
        when(tickets.selectOne(any())).thenReturn(Ticket.builder().ticketId("T").handlerId(1L).ticketStatus(4).build());
        var ticketService = ticketService(tickets);
        var req = new TicketHandleReqDTO(); req.setTicketId("T"); req.setOperatorId("1"); req.setActionType("RESOLVE");
        assertEquals(409, assertThrows(BusinessException.class, () -> ticketService.processTicket(req)).getCode());
        verify(tickets, never()).update(isNull(), any());
    }
    @Test void reassignmentHandlesUnassignedTicket() {
        var tickets = mock(TicketMapper.class);
        when(tickets.selectOne(any())).thenReturn(Ticket.builder().ticketId("T").ticketStatus(0).build());
        when(tickets.update(isNull(), any())).thenReturn(1);
        assertTrue(ticketService(tickets).reassignTicket("T", 2L));
    }
    @Test void foreignOrderCannotBeAttachedToTicket() {
        var tickets = mock(TicketMapper.class); var ticketService = ticketService(tickets);
        when(orders.selectOne(any())).thenReturn(RideOrder.builder().orderId(100L).userId(2L).build());
        ReflectionTestUtils.setField(ticketService, "rideOrderMapper", orders);
        var req = TicketCreateReqDTO.builder().userId("1").orderId(100L).build();
        assertEquals(403, assertThrows(BusinessException.class, () -> ticketService.submitTicket(req)).getCode());
        verify(tickets, never()).insert(any(Ticket.class));
    }
    @Test void streamingRunnerDoesNotLoseSynchronousFirstEventAndReleasesLock() throws Exception {
        var client = mock(RedissonClient.class); var lock = mock(RLock.class); when(client.getLock(anyString())).thenReturn(lock);
        RFuture<Boolean> future = mock(RFuture.class); when(future.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(true));
        when(lock.tryLockAsync(eq(0L), eq(-1L), any(), anyLong())).thenReturn(future);
        var chats = mock(ChatManager.class); var chatService = mock(ChatService.class);
        doAnswer(call -> { Sinks.Many<AgentEvent> sink = call.getArgument(2); sink.tryEmitNext(AgentEvent.message("first")); sink.tryEmitComplete(); return null; })
                .when(chatService).chat(anyString(), any(), any(), eq("1"));
        var runner = new ChatRequestRunner(chatService, chats, client, Runnable::run);
        var events = runner.run("1", UUID.randomUUID().toString(), new ChatParamDTO("hello"), false)
                .collectList().block(Duration.ofSeconds(1));
        assertEquals(1, events.size());
        verify(lock).unlockAsync(anyLong());
    }
    @Test void realHttpStatusesAreReturned() {
        assertEquals(403, new GlobalExceptionHandler().handleForbidden(new ForbiddenException("denied")).getStatusCode().value());
    }
    @Test void timeParsingTrimsAndRejectsNonexistentDates() {
        assertNotNull(TimeUtil.parse(" 2026-11-13 12:00:00 "));
        assertNull(TimeUtil.parse("2026-13-99 12:00:00"));
    }
    @Test void corruptHistoryResumesAtNextUserTurnAndDropsDanglingTools() {
        var parser = new MessageParser();
        var orphan = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("old", "tool", "x"))).build();
        var recovered = parser.unparseHistory(List.of(parser.parse(new UserMessage("old")), "{bad json",
                parser.parse(orphan), parser.parse(new UserMessage("new")), parser.parse(new AssistantMessage("answer"))));
        assertEquals(List.of("new", "answer"), recovered.stream().map(org.springframework.ai.chat.messages.Message::getText).toList());
        assertDoesNotThrow(() -> MessageWindow.tail(recovered, 100));
    }
    @Test void deletedChatRejectsEvenWarmCacheAccess() {
        var mapper = mock(ChatMapper.class);
        when(mapper.selectOne(any())).thenReturn(Chat.builder().chatId("chat").userId(1L).historyDeleted(true).build());
        var info = new ChatInfoService(mapper, mock(StringRedisTemplate.class));
        assertEquals(410, assertThrows(BusinessException.class, () -> info.assertOwned("1", "chat", false)).getCode());
    }
    @Test void notifyBatchPreservesEveryNonConfirmationResponse() throws Exception {
        var memory = mock(MessageMemory.class);
        var redis = mock(StringRedisTemplate.class);
        var hash = mock(HashOperations.class); when(redis.opsForHash()).thenReturn(hash);
        var pointer = mock(ToolRepPointerTool.class);
        var constructor = OrderAgent.class.getDeclaredConstructors()[0];
        var overrides = Map.<Class<?>, Object>of(MessageMemory.class, memory, StringRedisTemplate.class, redis,
                ObjectMapper.class, new ObjectMapper(), ToolRepPointerTool.class, pointer);
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(t -> overrides.containsKey(t) ? overrides.get(t) : mock(t)).toArray();
        var agent = (OrderAgent) constructor.newInstance(dependencies);
        var confirmations = mock(OrderConfirmation.class); when(confirmations.begin("chat", "confirm")).thenReturn("quote");
        ReflectionTestUtils.setField(agent, "orderConfirmation", confirmations);
        var calls = List.of(new AssistantMessage.ToolCall("before", "function", "unknown", "{}"),
                new AssistantMessage.ToolCall("confirm", "function", "notifyUser", "{}"),
                new AssistantMessage.ToolCall("after", "function", "unknown", "{}"));
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>(List.of(AssistantMessage.builder().content("").toolCalls(calls).build()));
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        ReflectionTestUtils.invokeMethod(agent, "handleToolCalls", calls, history, 0, sink, "chat", "1");
        var response = (ToolResponseMessage) history.getLast();
        assertEquals(Set.of("before", "after"), response.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).collect(java.util.stream.Collectors.toSet()));
        verify(pointer, never()).recordCalling(eq("after"), anyString(), any());
        history.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("confirm", "notifyUser", "yes"))).build());
        assertDoesNotThrow(() -> MessageWindow.tail(history, 100));
    }
    @Test void modelCannotGrantItselfCreationPermission() {
        var redis = mock(StringRedisTemplate.class); var confirmations = mock(OrderConfirmation.class);
        var tool = new OrderTool(); ReflectionTestUtils.setField(tool, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(tool, "orderConfirmation", confirmations);
        assertTrue(tool.markOrderReadyForCreate(context()).contains("尚未"));
        verifyNoInteractions(redis);
    }
    private ToolContext context() { return new ToolContext(Map.of("userId", "1", "chatId", "chat")); }
    private TicketServiceImpl ticketService(TicketMapper tickets) {
        return new TicketServiceImpl(tickets, mock(TicketChatMapper.class), mock(UserAuthMapper.class),
                mock(StringRedisTemplate.class), new ObjectMapper(), mock(DelayedCacheEvictor.class),
                mock(RedisLock.class), mock(TicketPoolIndex.class));
    }
}
