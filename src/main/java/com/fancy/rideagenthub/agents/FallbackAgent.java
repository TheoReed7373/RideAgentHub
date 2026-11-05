package com.fancy.rideagenthub.agents;

import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.fancy.rideagenthub.config.ChatProperties.OTHER_CLIENT_SYS_PROMPT;

@Service
@Slf4j
@RequiredArgsConstructor
public class FallbackAgent {
    private final ChatModel openAiChatModel;
    private final MessageMemory messageMemory;

    private static final int HISTORY_SIZE = 100;

    public void invoke(Sinks.Many<AgentEvent> sink, String chatId, String userId, String userMessage) {
        log.info("[OtherAgent] invoke: chatId={}, userId={}, userMessage={}", chatId, userId, userMessage);
        String sysPromptString = OTHER_CLIENT_SYS_PROMPT.formatted(TimeUtil.getDetailCurrentTime());

        // 获取历史消息
        List<Message> history = new ArrayList<>(messageMemory.get(userId, chatId, HISTORY_SIZE));
        int originalHistorySize = history.size();

        // System prompt（仅在历史为空时补齐）
        if (history.isEmpty()) {
            String currentDate = TimeUtil.getDetailCurrentTime();
            history.add(new SystemMessage(sysPromptString));
        }else {
            Message firstMessage = history.getFirst();
            if(firstMessage instanceof SystemMessage){
                history.set(0, new SystemMessage(sysPromptString));
            }else{
                //sys不存在，需要提示一下长度过长已经被截断了
                history.addFirst(new SystemMessage(sysPromptString));
                sink.tryEmitNext(AgentEvent.notify("信息轮次过长，智能体性能将退化，建议您创建新对话。"));
            }
        }

        // 用户消息
        history.add(new UserMessage(userMessage));

        Prompt prompt = new Prompt(history, OpenAiChatOptions.builder()
                .model("deepseek-chat")
                .topP(0.7)
                .temperature(0.7)
                .internalToolExecutionEnabled(false)
                .build());

        StringBuilder contentBuffer = new StringBuilder();
        AtomicReference<AssistantMessage> assistantMessageRef = new AtomicReference<>();

        this.openAiChatModel.stream(prompt)
                .subscribe(
                        chatResponse -> {
                            Generation generation = chatResponse.getResult();
                            if (generation == null) {
                                return;
                            }

                            AssistantMessage msg = generation.getOutput();
                            String content = msg == null ? null : msg.getText();
                            if (content != null && !content.isEmpty()) {
                                sink.tryEmitNext(AgentEvent.message(content));
                                contentBuffer.append(content);
                            }
                            assistantMessageRef.set(msg);
                        },
                        error -> {
                            log.error("[OtherAgent] Chat Error", error);
                            sink.tryEmitNext(AgentEvent.error(error.getMessage()));
                            sink.tryEmitError(error);
                        },
                        () -> {
                            AssistantMessage finalMsg = assistantMessageRef.get();
                            if (finalMsg == null) {
                                saveNewMessages(userId, chatId, history, originalHistorySize);
                                sink.tryEmitComplete();
                                return;
                            }

                            AssistantMessage build = AssistantMessage.builder()
                                    .content(contentBuffer.toString())
                                    .properties(finalMsg.getMetadata())
                                    .toolCalls(finalMsg.getToolCalls())
                                    .build();
                            history.add(build);

                            saveNewMessages(userId, chatId, history, originalHistorySize);
                            sink.tryEmitComplete();
                        });
    }

    private void saveNewMessages(String userId, String chatId, List<Message> history, int originalHistorySize) {
        if (history.size() > originalHistorySize) {
            List<Message> newMessages = history.subList(originalHistorySize, history.size());
            messageMemory.save(userId, chatId, newMessages);
        }
    }
}
