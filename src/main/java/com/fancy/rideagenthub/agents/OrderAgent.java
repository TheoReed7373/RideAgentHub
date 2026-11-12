package com.fancy.rideagenthub.agents;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agentbase.tool.*;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.UserLocation;
import com.fancy.rideagenthub.domain.enums.OrderInfoEnum;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.fancy.rideagenthub.config.ChatProperties.ORDER_AGENT_SYS_PROMPT;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderAgent {
    private final ChatModel openAiChatModel;
    private final MessageMemory messageMemory;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    // 工具注入
    private final OrderTool orderTool;
    private final POISearchTool poiSearchTool;
    private final ToolRepPointerTool toolRepPointerTool;
    private final TrafficTool trafficTool;
    private final UserPOISearchTool userPOISearchTool;
    private final GeoRegeoTool geoRegeoTool;
    @jakarta.annotation.Resource
    private com.fancy.rideagenthub.service.base.OrderConfirmation orderConfirmation;

    private static final int HISTORY_SIZE = 100;

    /**
     * 正常对话入口
     */
    public void invoke(Sinks.Many<AgentEvent> sink, String chatId, String userId, String userMessage) {
        log.info("[OrderAgent] invoke: chatId={}, userId={}, userMessage={}", chatId, userId, userMessage);

        // 获取历史消息
        List<Message> history = new ArrayList<>(messageMemory.get(userId, chatId, HISTORY_SIZE));

        // 记录原始历史长度，用于后续只保存新增消息
        int originalHistorySize = history.size() + (!history.isEmpty() && !(history.getFirst() instanceof SystemMessage) ? 1 : 0);

        // 构建系统提示
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
        String location = buildLocationString(userId);
        String sysPromptString = ORDER_AGENT_SYS_PROMPT.formatted(currentTime, location);

        // 添加 SystemMessage (如果历史为空)
        if (history.isEmpty()) {
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

        // 添加用户消息
        history.add(new UserMessage(userMessage));

        // 启动工具执行循环
        runLoopStep(history, originalHistorySize, sink, chatId, userId);
    }

    /**
     * 用户确认后恢复对话
     */
    public void resume(Sinks.Many<AgentEvent> sink, String chatId, String userId, String feedback) {
        log.info("[OrderAgent] resume: chatId={}, userId={}, feedback={}", chatId, userId, feedback);

        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);

        // 检查 break 字段
        Object breakFlag = stringRedisTemplate.opsForHash().get(chatInfoKey, "break");
        if (breakFlag == null || !"resuming".equals(breakFlag.toString())) {
            sink.tryEmitNext(AgentEvent.error("非法的恢复请求：对话不在等待确认状态"));
            sink.tryEmitComplete();
            return;
        }

        // 获取历史消息
        List<Message> history = new ArrayList<>(messageMemory.getPersisted(userId, chatId, HISTORY_SIZE));

        // 记录原始历史长度，用于后续只保存新增消息
        int originalHistorySize = history.size();

        // 获取最后一个 AssistantMessage 的 notifyUser 工具调用 ID
        Object pending = stringRedisTemplate.opsForHash().get(chatInfoKey, "pendingToolCall");
        String lastToolCallId = pending == null ? null : pending.toString();
        if (lastToolCallId == null) {
            sink.tryEmitNext(AgentEvent.error("无法找到待确认的工具调用"));
            sink.tryEmitComplete();
            return;
        }

        // 将用户反馈作为 ToolResponseMessage 追加
        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                lastToolCallId,
                "notifyUser",
                feedback);
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .build();
        // 先持久化工具应答，再调用模型；模型失败后历史仍保持配对，可继续正常聊天。
        boolean answered = history.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream())
                .anyMatch(r -> lastToolCallId.equals(r.id()));
        if (!answered) {
            messageMemory.save(userId, chatId, List.of(toolResponseMessage));
            history.add(toolResponseMessage);
        }
        originalHistorySize = history.size();
        stringRedisTemplate.opsForHash().delete(chatInfoKey, "break", "pendingToolCall", "confirmationAccepted");

        // 继续执行循环
        runLoopStep(history, originalHistorySize, sink, chatId, userId);
    }

    private String getLastNotifyUserToolCallId(List<Message> history) {
        // 从后往前查找最后一个包含 notifyUser 调用的 AssistantMessage
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage assistantMsg) {
                for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
                    if ("notifyUser".equals(toolCall.name())) {
                        return toolCall.id();
                    }
                }
            }
        }
        return null;
    }

    private void runLoopStep(List<Message> history, int originalHistorySize, Sinks.Many<AgentEvent> sink, String chatId,
            String userId) {
        if (history.size() - originalHistorySize > 200) {
            sink.tryEmitNext(AgentEvent.error("本轮工具调用次数过多，请重新发起请求"));
            sink.tryEmitComplete();
            return;
        }
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(
                orderTool, poiSearchTool, toolRepPointerTool, trafficTool, userPOISearchTool, geoRegeoTool));
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

        AgentStreams.subscribe(this.openAiChatModel.stream(prompt), sink,
                        chatResponse -> {
                            Generation generation = chatResponse.getResult();
                            if (generation == null)
                                return;

                            AssistantMessage msg = generation.getOutput();
                            String content = msg.getText();
                            if (content != null && !content.isEmpty()) {
                                sink.tryEmitNext(AgentEvent.message(content));
                                contentBuffer.append(content);
                            }
                            assistantMessageRef.set(msg);
                        },
                        error -> {
                            log.error("[OrderAgent] Chat Error", error);
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

            // 特殊处理 notifyUser 工具
            if ("notifyUser".equals(toolName)) {
                // 同批后续调用没有执行，显式应答；确认调用的响应留到真实用户反馈后追加。
                int index = toolCalls.indexOf(toolCall);
                for (int i = index + 1; i < toolCalls.size(); i++) {
                    var deferred = toolCalls.get(i);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(deferred.id(), deferred.name(),
                            "尚未执行：请等待用户确认后按需重新调用。"));
                }
                if (!toolResponses.isEmpty()) history.add(ToolResponseMessage.builder().responses(toolResponses).build());
                handleNotifyUser(toolCall, history, originalHistorySize, sink, chatId, userId, toolContext);
                return; // 中断循环，等待用户确认
            }

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

    private void handleNotifyUser(AssistantMessage.ToolCall toolCall, List<Message> history,
            int originalHistorySize, Sinks.Many<AgentEvent> sink, String chatId, String userId,
            ToolContext toolContext) {
        log.info("[OrderAgent] notifyUser triggered, preparing confirmation");

        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);

        // 构建订单参数 JSON
        Map<String, String> orderParams = new HashMap<>();
        for (OrderInfoEnum key : OrderInfoEnum.values()) {
            if(key.equals(OrderInfoEnum.MONGO_TRACE_ID)) {
                continue;
            }
            Object value = stringRedisTemplate.opsForHash().get(chatInfoKey, key.name());
            if (value != null) {
                orderParams.put(key.name(), value.toString());
            }
        }
        Object time = stringRedisTemplate.opsForHash().get(chatInfoKey, "EST_TIME");
        if (time != null) {
            orderParams.put("EST_TIME", time.toString());
        }

        try {
            String confirmationId = orderConfirmation.begin(chatId, toolCall.id());
            orderParams.put("confirmationId", confirmationId);
            String orderJson = objectMapper.writeValueAsString(orderParams);

            // 1. 发出 AgentEvent.confirm()

            // 2. 保存新增的消息（从原始历史长度开始的子列表）
            saveNewMessages(userId, chatId, history, originalHistorySize);

            // 3. 设置 Redis break 标记
            stringRedisTemplate.opsForHash().put(chatInfoKey, "break", "yes");
            sink.tryEmitNext(AgentEvent.comfirm(orderJson));

            // 4. 结束 sink
            sink.tryEmitComplete();

        } catch (Exception e) {
            log.error("[OrderAgent] Failed to serialize order params", e);
            sink.tryEmitNext(AgentEvent.error("序列化订单参数失败: " + e.getMessage()));
            sink.tryEmitComplete();
        }
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
                // ====== OrderTool ======
                case "saveNewOrderParam" -> {
                    Map<OrderInfoEnum, String> slots = new HashMap<>();
                    JsonNode slotsNode = null;
                    // 支持两种格式: { "params": { "slots": {...} } } 或 { "slots": {...} }
                    if (rootNode.has("params") && rootNode.get("params").has("slots")) {
                        slotsNode = rootNode.get("params").get("slots");
                    } else if (rootNode.has("slots")) {
                        slotsNode = rootNode.get("slots");
                    }
                    if (slotsNode != null) {
                        JsonNode finalSlotsNode = slotsNode;
                        slotsNode.fieldNames().forEachRemaining(field -> {
                            try {
                                OrderInfoEnum key = OrderInfoEnum.valueOf(field);
                                slots.put(key, finalSlotsNode.get(field).asText());
                            } catch (IllegalArgumentException ignored) {
                            }
                        });
                    }
                    return orderTool.saveNewOrderParam(new OrderTool.OrderParams(slots), toolContext);
                }
                case "isNewOrderReady" -> {
                    return orderTool.isNewOrderReady(toolContext);
                }
                case "getEstRouteAndPrice" -> {
                    // 支持两种格式: {"noRoute":true} 或 {"params":{"noRoute":true}}
                    boolean noRoute = false;
                    JsonNode noRouteNode = null;
                    if (rootNode.has("params") && rootNode.get("params").has("noRoute")) {
                        noRouteNode = rootNode.get("params").get("noRoute");
                    } else if (rootNode.has("noRoute")) {
                        noRouteNode = rootNode.get("noRoute");
                    }
                    if (noRouteNode != null && !noRouteNode.isNull()) {
                        // 允许 bool / 字符串
                        if (noRouteNode.isBoolean()) {
                            noRoute = noRouteNode.asBoolean(false);
                        } else {
                            String raw = noRouteNode.asText("").trim();
                            noRoute = "1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw)
                                    || "y".equalsIgnoreCase(raw);
                        }
                    }
                    return orderTool.getEstRouteAndPrice(noRoute, toolContext);
                }
                case "markOrderReadyForCreate" -> {
                    return orderTool.markOrderReadyForCreate(toolContext);
                }
                case "createOrder" -> {
                    return orderTool.createOrder(toolContext);
                }
                case "explainPrice" -> {
                    return orderTool.explainPrice(toolContext);
                }

                // ====== POISearchTool ======
                case "searchPOIsByKeyword" -> {
                    String keyword = rootNode.has("keyword") ? rootNode.get("keyword").asText() : null;
                    String city = rootNode.has("city") ? rootNode.get("city").asText() : null;
                    return poiSearchTool.searchPOIsByKeyword(keyword, city, toolContext);
                }

                // ====== ToolRepPointerTool ======
                case "getCalling" -> {
                    String callId = rootNode.get("callId").asText();
                    return toolRepPointerTool.getToolResponse(callId, toolContext);
                }

                // ====== TrafficTool ======
                case "getFlightInfo" -> {
                    String number = rootNode.get("number").asText();
                    return trafficTool.getFlightInfo(number, toolContext);
                }

                // ====== UserPOISearchTool ======
                case "listUserPOIs" -> {
                    return userPOISearchTool.listUserPOIs(toolContext);
                }
                case "getUserPOIDetailByTag" -> {
                    String poiTag = rootNode.get("poiTag").asText();
                    return userPOISearchTool.getUserPOIDetailByTag(poiTag, toolContext);
                }
                case "searchUserPOIDetail" -> {
                    String poiName = rootNode.get("poiName").asText();
                    return userPOISearchTool.searchUserPOIDetail(poiName, toolContext);
                }

                // ====== GeoRegeoTool ======
                case "regeo" -> {
                    String longtitude = rootNode.get("longitude").asText();
                    String latitude = rootNode.get("latitude").asText();
                    return geoRegeoTool.regeo(Double.parseDouble(longtitude), Double.parseDouble(latitude), toolContext);
                }

                case "geo" -> {
                    String address = rootNode.get("address").asText();
                    String city = rootNode.has("city") ? rootNode.get("city").asText() : "";
                    return geoRegeoTool.geo(address, city, toolContext);
                }

                default -> {
                    return "Unknown tool: " + toolName;
                }
            }
        } catch (Exception e) {
            log.error("[OrderAgent] Tool execution error", e);
            return "Tool execution failed: " + e.getMessage();
        }
    }
}


