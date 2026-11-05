package com.fancy.rideagenthub.controller;

import com.fancy.rideagenthub.agents.DailyAgent;
import com.fancy.rideagenthub.agents.OrderAgent;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.ChatParamDTO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CompletableFuture;

/**
 * 订单Agent测试控制器
 * 用于测试 OrderAgent，无需登录，硬编码 userId
 */
@RestController
@RequestMapping("/order")
public class Chat2Controller {

    private static final String TEST_USER_ID = "1877239021464317952";

    @Resource
    private OrderAgent orderAgent;

    @Resource
    private DailyAgent dailyAgent;

    /**
     * 正常对话接口
     * 
     * @param chatId    对话ID
     * @param chatParam 对话参数
     * @return 流式响应
     */
    @PostMapping("/chat/{chatId}")
    public Flux<AgentEvent> chat(@PathVariable String chatId, @RequestBody ChatParamDTO chatParam) {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        CompletableFuture.runAsync(() -> orderAgent.invoke(sink, chatId, TEST_USER_ID, chatParam.getPrompt()));

        return sink.asFlux();
    }

    /**
     * 用户确认后恢复对话接口
     * 
     * @param chatId   对话ID
     * @param feedback 用户反馈内容
     * @return 流式响应
     */
    @PostMapping("/resume/{chatId}")
    public Flux<AgentEvent> resume(@PathVariable String chatId, @RequestBody String feedback) {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        CompletableFuture.runAsync(() -> orderAgent.resume(sink, chatId, TEST_USER_ID, feedback));

        return sink.asFlux();
    }

    /**
     * 日常咨询Agent测试接口
     *
     * @param chatId    对话ID
     * @param chatParam 对话参数
     * @return 流式响应
     */
    @PostMapping("/daily/chat/{chatId}")
    public Flux<AgentEvent> dailyChat(@PathVariable String chatId, @RequestBody ChatParamDTO chatParam) {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        CompletableFuture.runAsync(() -> dailyAgent.invoke(sink, chatId, TEST_USER_ID, chatParam.getPrompt()));

        return sink.asFlux();
    }
}
