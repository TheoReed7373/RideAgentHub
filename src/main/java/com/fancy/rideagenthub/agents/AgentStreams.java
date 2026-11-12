package com.fancy.rideagenthub.agents;

import com.fancy.rideagenthub.domain.dto.AgentEvent;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.function.Consumer;

/** 所有模型回调共享同一异常出口，完成回调抛异常也必须结束对外响应。 */
public final class AgentStreams {
    private AgentStreams() {}

    public static void subscribe(Flux<ChatResponse> source, Sinks.Many<AgentEvent> sink,
            Consumer<ChatResponse> next, Consumer<Throwable> error, Runnable complete) {
        source.takeUntilOther(reactor.core.publisher.Mono.delay(java.time.Duration.ofMinutes(3))
                .flatMap(ignored -> reactor.core.publisher.Mono.error(new java.util.concurrent.TimeoutException("模型响应超时"))))
            .doOnNext(next)
            .doOnComplete(complete)
            .subscribe(ignored -> {}, failure -> {
                guard(sink, () -> error.accept(failure));
                sink.tryEmitError(failure);
            });
    }

    public static void guard(Sinks.Many<AgentEvent> sink, Runnable action) {
        try {
            action.run();
        } catch (Exception failure) {
            sink.tryEmitNext(AgentEvent.error("处理失败，请稍后重试"));
            sink.tryEmitError(failure);
        }
    }
}
