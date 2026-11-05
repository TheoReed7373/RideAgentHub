package com.fancy.rideagenthub.service.impl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agents.DailyAgent;
import com.fancy.rideagenthub.agents.OrderAgent;
import com.fancy.rideagenthub.agents.FallbackAgent;
import com.fancy.rideagenthub.agents.SupportAgent;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.ChatParamDTO;
import com.fancy.rideagenthub.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

import static com.fancy.rideagenthub.config.ChatProperties.CLASSIFIER_SYS_PROMPT;
import static com.fancy.rideagenthub.config.ChatProperties.CLASSIFIER_USER_PROMPT;

@Service
@Slf4j
public class ChatServiceImpl implements ChatService {
    private static final String ROUTER_DEFAULT_MODEL = "qwen-plus-2025-12-01";
    private static final String CLASSIFIER_MODEL = "qwen3-max-preview";

    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final MessageMemory memory;
    private final OrderAgent orderAgent;
    private final FallbackAgent fallbackAgent;
    private final DailyAgent dailyAgent;
    private final SupportAgent supportAgent;
    private final ChatManager chatManager;

    public ChatServiceImpl(StringRedisTemplate stringRedisTemplate,
            MessageMemory messageMemory,
            OrderAgent orderAgent,
            FallbackAgent fallbackAgent,
            DailyAgent dailyAgent,
            SupportAgent supportAgent,
            ChatManager chatManager,
            @Qualifier("dashScopeChatModel") ChatModel chatModel) {
        this.redisTemplate = stringRedisTemplate;
        this.memory = messageMemory;
        this.orderAgent = orderAgent;
        this.fallbackAgent = fallbackAgent;
        this.dailyAgent = dailyAgent;
        this.supportAgent = supportAgent;
        this.chatManager = chatManager;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(DashScopeChatOptions.builder().model(ROUTER_DEFAULT_MODEL).topP(0.7).build())
                .build();
    }

    @Override
    public void chat(String id, ChatParamDTO param, Sinks.Many<AgentEvent> sink, String userId) {
        log.info("[ChatServiceImpl] chat invoke!");
        if(chatManager.isLocked(id)){
            sink.tryEmitNext(AgentEvent.notify("为了更好地帮你处理新的需求，这个对话先到这里，开启新对话继续吧～"));
            sink.tryEmitComplete();
            return;
        }
        String classification = null;
        List<Message> messages = memory.get(userId, id, 1);
        Object classRedis = redisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(id),
                "classification");
        if (messages.isEmpty()) {
            // 对话为空，创建新对话
            chatManager.initChat(userId, id, param.getPrompt());
            long currentMillis = System.currentTimeMillis();
            classification = chatClient.prompt()
                    .system(CLASSIFIER_SYS_PROMPT)
                    .user(param.getPrompt())
                    .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL).temperature(0.5).build())
                    .call().content();
            log.info("分类耗时：{}ms，分类结果：{}", System.currentTimeMillis() - currentMillis, classification);
        } else {
            // 对话不为空
            long currentMillis = System.currentTimeMillis();
            classification = chatClient.prompt()
                    .system(CLASSIFIER_SYS_PROMPT)
                    .user(CLASSIFIER_USER_PROMPT.formatted(classRedis, messages.getFirst().getText(),
                            param.getPrompt()))
                    .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL).temperature(0.5).build())
                    .call().content();
            log.info("分类耗时：{}ms，分类结果：{}", System.currentTimeMillis() - currentMillis, classification);
        }
        if (classification == null) {
            String failFtr = "路由失败，请换种方式问问题。";
            sink.tryEmitNext(AgentEvent.message(failFtr));
            sink.tryEmitComplete();
            return;
        }
        sink.tryEmitNext(AgentEvent.notify(classification));
        if (classRedis != null && classRedis.equals("ORDER")) {
            if(!classification.equals("ORDER")){
                sink.tryEmitNext(AgentEvent.notify("当前处于下单流程，如果您想换个话题，请新建对话。"));
            }
            orderAgent.invoke(sink, id, userId, param.getPrompt());
            return;
        }
        redisTemplate.opsForHash().put(RedisKeyConstants.chatInfoKey(id), "classification", classification);
        switch (classification) {
            case "DANGER" -> {
                sink.tryEmitNext(AgentEvent.message("您的命令不被支持，请换个内容继续吧。"));
                sink.tryEmitComplete();
            }
            case "ORDER" -> orderAgent.invoke(sink, id, userId, param.getPrompt());
            case "DAILY" -> dailyAgent.invoke(sink, id, userId, param.getPrompt());
            case "SUPPORT" -> supportAgent.invoke(sink, id, userId, param.getPrompt());
            case "OTHER" -> fallbackAgent.invoke(sink, id, userId, param.getPrompt());
            default -> {
                // 未知分类，默认转给OtherAgent处理
                log.warn("未知分类: {}", classification);
                sink.tryEmitNext(AgentEvent.message("未知分类，请换种方式问问题。"));
                sink.tryEmitComplete();
            }
        }
    }

    @Override
    public void resume(String id, ChatParamDTO chatParam, Sinks.Many<AgentEvent> sink, String userId) {
        // 只有ORDER Agent才会需要HITL，这里直接转即可。
        orderAgent.resume(sink, id, userId, chatParam.getPrompt());
    }

}
