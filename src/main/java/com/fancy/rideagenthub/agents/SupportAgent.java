package com.fancy.rideagenthub.agents;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agentbase.tool.OrderSearchTool;
import com.fancy.rideagenthub.agentbase.tool.RagTool;
import com.fancy.rideagenthub.agentbase.tool.TicketTool;
import com.fancy.rideagenthub.agentbase.tool.ToolRepPointerTool;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.UserLocation;
import com.fancy.rideagenthub.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.fancy.rideagenthub.config.ChatProperties.SUPPORT_AGENT_SYS_PROMPT;

@Service
@Slf4j
@RequiredArgsConstructor
public class SupportAgent {
    private final ChatModel openAiChatModel;
    private final MessageMemory messageMemory;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    // 工具注入
    private final OrderSearchTool orderSearchTool;
    private final TicketTool ticketTool;
    private final ToolRepPointerTool toolRepPointerTool;
    private final RagTool ragTool;

    private static final int HISTORY_SIZE = 100;

    /**
     * 正常对话入口
     */
    public void invoke(Sinks.Many<AgentEvent> sink, String chatId, String userId, String userMessage) {
        log.info("[SupportAgent] invoke: chatId={}, userId={}, userMessage={}", chatId, userId, userMessage);

        // 获取历史消息
        List<Message> history = new ArrayList<>(messageMemory.get(userId, chatId, HISTORY_SIZE));

        // 记录原始历史长度，用于后续只保存新增消息
        int originalHistorySize = history.size();

        // 构建系统提示
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
        String location = buildLocationString(userId);
        String sysPromptString = SUPPORT_AGENT_SYS_PROMPT.formatted(currentTime, location);

        // 添加 SystemMessage (如果历史为空)
        if (history.isEmpty()) {
            history.add(new SystemMessage(sysPromptString));
        } else {
            Message firstMessage = history.getFirst();
            if (firstMessage instanceof SystemMessage) {
                history.set(0, new SystemMessage(sysPromptString));
            } else {
                // sys不存在，需要提示一下长度过长已经被截断了
                history.addFirst(new SystemMessage(sysPromptString));
                sink.tryEmitNext(AgentEvent.notify("信息轮次过长，智能体性能将退化，建议您创建新对话。"));
            }
        }

        // 添加用户消息
        history.add(new UserMessage(userMessage));

        // 启动工具执行循环
        runLoopStep(history, originalHistorySize, sink, chatId, userId);
    }

    private void runLoopStep(List<Message> history, int originalHistorySize, Sinks.Many<AgentEvent> sink, String chatId,
            String userId) {
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(
            orderSearchTool, ticketTool, toolRepPointerTool, ragTool));
        Consumer<String> toolListener = (msg) -> sink.tryEmitNext(AgentEvent.tool(msg));

        Prompt prompt = new Prompt(history, OpenAiChatOptions.builder()
                .model("deepseek-chat")
                .toolCallbacks(callbacks)
                .toolContext(Map.of(
                        ToolContextKeyConstants.TOOL_LISTENER, toolListener,
                        ToolContextKeyConstants.USER_ID, userId,
                        ToolContextKeyConstants.CHAT_ID, chatId))
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
                            String content = msg.getText();
                            if (content != null && !content.isEmpty()) {
                                sink.tryEmitNext(AgentEvent.message(content));
                                contentBuffer.append(content);
                            }
                            assistantMessageRef.set(msg);
                        },
                        error -> {
                            log.error("[SupportAgent] Chat Error", error);
                            sink.tryEmitNext(AgentEvent.error(error.getMessage()));
                            sink.tryEmitError(error);
                        },
                        () -> {
                            AssistantMessage finalMsg = assistantMessageRef.get();
                            if (finalMsg == null) {
                                // 保存新增的消息（从原始历史长度开始的子列表）
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

                            List<AssistantMessage.ToolCall> toolCalls = finalMsg.getToolCalls();

                            if (!toolCalls.isEmpty()) {
                                handleToolCalls(toolCalls, history, originalHistorySize, sink, chatId, userId);
                            } else {
                                // 纯文本回复，保存新增的消息并结束
                                saveNewMessages(userId, chatId, history, originalHistorySize);
                                sink.tryEmitComplete();
                            }
                        });
    }

    private void handleToolCalls(List<AssistantMessage.ToolCall> toolCalls, List<Message> history,
            int originalHistorySize, Sinks.Many<AgentEvent> sink, String chatId, String userId) {
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        Consumer<String> toolListener = (msg) -> sink.tryEmitNext(AgentEvent.tool(msg));
        ToolContext toolContext = new ToolContext(Map.of(
                ToolContextKeyConstants.TOOL_LISTENER, toolListener,
                ToolContextKeyConstants.USER_ID, userId,
                ToolContextKeyConstants.CHAT_ID, chatId));

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            String args = toolCall.arguments();

            String result = executeTool(toolName, args, toolContext, sink);
            toolRepPointerTool.recordCalling(toolCall.id(), result, toolContext);
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(),
                    toolName,
                    result));
        }

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(toolResponses)
                .build();
        history.add(toolResponseMessage);

        // 递归调用
        runLoopStep(history, originalHistorySize, sink, chatId, userId);
    }

    /**
     * 只保存新增的消息（从 originalHistorySize 开始的子列表）
     */
    private void saveNewMessages(String userId, String chatId, List<Message> history, int originalHistorySize) {
        if (history.size() > originalHistorySize) {
            List<Message> newMessages = history.subList(originalHistorySize, history.size());
            messageMemory.save(userId, chatId, newMessages);
        }
    }

    private String buildLocationString(String userId) {
        UserLocation userLocation = userService.getUserLocation(userId);
        if (userLocation == null) {
            return "具体位置未知";
        }
        return String.format("%s,%s,%s",
                userLocation.getAddress() != null ? userLocation.getAddress() : "具体位置未知",
                userLocation.getLongitude() != null ? userLocation.getLongitude() : "",
                userLocation.getLatitude() != null ? userLocation.getLatitude() : "");
    }

    private String executeTool(String toolName, String jsonArgs, ToolContext toolContext, Sinks.Many<AgentEvent> sink) {
        try {
            JsonNode rootNode = (jsonArgs == null || jsonArgs.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(jsonArgs);

            switch (toolName) {
                // ====== OrderSearchTool ======
                case "searchOrderById" -> {
                    String orderId = rootNode.get("orderId").asText();
                    return orderSearchTool.searchOrderById(orderId, toolContext);
                }
                case "searchOrdersByTime" -> {
                    String startTime = rootNode.get("startTime").asText();
                    String endTime = rootNode.get("endTime").asText();
                    return orderSearchTool.searchOrdersByTime(startTime, endTime, toolContext);
                }
                case "searchOrdersByCount" -> {
                    Integer limit = rootNode.has("limit") ? rootNode.get("limit").asInt() : null;
                    return orderSearchTool.searchOrdersByCount(limit, toolContext);
                }
                case "verifyCancelConditions" -> {
                    String orderId = rootNode.get("orderId").asText();
                    return orderSearchTool.verifyCancelConditions(orderId, toolContext);
                }
                case "cancelOrder" -> {
                    String orderId = rootNode.get("orderId").asText();
                    String reason = rootNode.get("reason").asText();
                    return orderSearchTool.cancelOrder(orderId, reason, toolContext);
                }
                case "modifyOrderDestination" -> {
                    String orderId = rootNode.get("orderId").asText();
                    String newDestAddress = rootNode.get("newDestAddress").asText();
                    String newDestLng = rootNode.get("newDestLng").asText();
                    String newDestLat = rootNode.get("newDestLat").asText();
                    return orderSearchTool.modifyOrderDestination(orderId, newDestAddress, newDestLng, newDestLat,
                            toolContext);
                }

                // ====== TicketTool ======
                case "createTicket" -> {
                    String orderId = rootNode.has("orderId") ? rootNode.get("orderId").asText(null) : null;
                    String title = rootNode.get("title").asText();
                    String content = rootNode.get("content").asText();
                    Integer priority = rootNode.has("priority") ? rootNode.get("priority").asInt() : null;
                    Integer type = rootNode.get("type").asInt();
                    return ticketTool.createTicket(orderId, title, content, priority, type, toolContext);
                }
                case "queryTicketProgress" -> {
                    String ticketId = rootNode.has("ticketId") ? rootNode.get("ticketId").asText(null) : null;
                    return ticketTool.queryTicketProgress(ticketId, toolContext);
                }
                case "queryUnfinishedTickets" -> {
                    Integer limit = rootNode.has("limit") ? rootNode.get("limit").asInt() : null;
                    return ticketTool.queryUnfinishedTickets(limit, toolContext);
                }
                case "escalateTicket" -> {
                    String ticketId = rootNode.get("ticketId").asText();
                    String reason = rootNode.get("reason").asText();
                    Integer targetLevel = rootNode.get("targetLevel").asInt();
                    return ticketTool.escalateTicket(ticketId, reason, targetLevel, toolContext);
                }
                case "appendUserMessage" -> {
                    String content = rootNode.get("content").asText();
                    String ticketId = rootNode.get("ticketId").asText();
                    return ticketTool.appendUserMessage(content, ticketId, toolContext);
                }

                // ====== ToolRepPointerTool ======
                case "getCalling" -> {
                    String callId = rootNode.get("callId").asText();
                    return toolRepPointerTool.getToolResponse(callId, toolContext);
                }

                // ====== RagTool (Agentic RAG) ======
                case "searchKnowledgeBase" -> {
                    String queryStr = null;
                    if (rootNode.hasNonNull("queryStr")) {
                        queryStr = rootNode.get("queryStr").asText();
                    } else if (rootNode.hasNonNull("query")) {
                        queryStr = rootNode.get("query").asText();
                    } else if (rootNode.hasNonNull("question")) {
                        queryStr = rootNode.get("question").asText();
                    }

                    if (queryStr == null || queryStr.isBlank()) {
                        return "Missing required argument: queryStr";
                    }
                    return ragTool.searchKnowledgeBase(queryStr, toolContext);
                }

                default -> {
                    return "Unknown tool: " + toolName;
                }
            }
        } catch (Exception e) {
            log.error("[SupportAgent] Tool execution error", e);
            return "Tool execution failed: " + e.getMessage();
        }
    }
}


