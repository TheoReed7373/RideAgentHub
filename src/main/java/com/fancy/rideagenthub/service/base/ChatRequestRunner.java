package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.agents.AgentStreams;
import com.fancy.rideagenthub.domain.dto.*;
import com.fancy.rideagenthub.service.ChatService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class ChatRequestRunner {
    private final ChatService service;
    private final ChatManager chats;
    private final RedissonClient redis;
    private final Executor executor;

    public ChatRequestRunner(ChatService service, ChatManager chats, RedissonClient redis,
            @Qualifier("chatExecutor") Executor executor) {
        this.service = service;
        this.chats = chats;
        this.redis = redis;
        this.executor = executor;
    }

    public Flux<AgentEvent> run(String userId, String chatId, ChatParamDTO request, boolean resume) {
        java.util.UUID.fromString(chatId);
        return Flux.defer(() -> {
            Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
            var lock = redis.getLock("chat:execution:" + chatId);
            long owner = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            try {
                var acquisition = lock.tryLockAsync(0, -1, TimeUnit.SECONDS, owner).toCompletableFuture();
                boolean acquired;
                try { acquired = acquisition.get(3, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException | InterruptedException timeout) {
                    acquisition.thenAccept(late -> { if (late) lock.unlockAsync(owner); });
                    if (timeout instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw timeout;
                }
                if (!acquired) {
                    return Flux.just(AgentEvent.error("会话正在处理中，请等待本轮结束"));
                }
                chats.assertOwned(userId, chatId, !resume);
            } catch (Exception e) {
                if (lock.isHeldByThread(owner)) lock.unlockAsync(owner);
                return Flux.error(e);
            }
            // 保持后台订阅至终态，客户端断开不能提前释放正在执行工具的锁。
            Flux<AgentEvent> events = sink.asFlux().doOnSubscribe(subscription -> {
                try {
                    executor.execute(() -> AgentStreams.guard(sink, () -> {
                        if (resume) service.resume(chatId, request, sink, userId);
                        else service.chat(chatId, request, sink, userId);
                    }));
                } catch (RuntimeException failure) { sink.tryEmitError(failure); }
            }).doFinally(signal -> lock.unlockAsync(owner)).publish().autoConnect(2);
            events.subscribe(ignored -> {}, ignored -> {});
            return events;
        });
    }
}
