package com.fancy.rideagenthub.agents;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agentbase.tool.*;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.UserLocation;
import com.fancy.rideagenthub.domain.enums.POIInfoEnum;
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

import java.math.BigDecimal;

import static com.fancy.rideagenthub.config.ChatProperties.DAILY_AGENT_SYS_PROMPT;

@Service
@Slf4j
@RequiredArgsConstructor
public class DailyAgent {
    private final ChatModel openAiChatModel;
    private final MessageMemory messageMemory;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    // 工具注入
    private final CurWeatherTool curWeatherTool;
    private final GeoRegeoTool geoRegeoTool;
    private final POISearchTool poiSearchTool;
    private final UserPOITool userPOITool;
    private final DailyTool dailyTool;
    private final ToolRepPointerTool toolRepPointerTool;
    private final POIAroundTool poiAroundTool;

    private static final int HISTORY_SIZE = 100;

    /**
     * 正常对话入口
     */
    public void invoke(Sinks.Many<AgentEvent> sink, String chatId, String userId, String userMessage) {
        log.info("[DailyAgent] invoke: chatId={}, userId={}, userMessage={}", chatId, userId, userMessage);

        // 获取历史消息
        List<Message> history = new ArrayList<>(messageMemory.get(userId, chatId, HISTORY_SIZE));

        // 记录原始历史长度，用于后续只保存新增消息
        int originalHistorySize = history.size();

        // 构建系统提示
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
        String location = buildLocationString(userId);
        String sysPromptString = DAILY_AGENT_SYS_PROMPT.formatted(currentTime, location);

        // 添加 SystemMessage (如果历史为空)
        if (history.isEmpty()) {
            history.add(new SystemMessage(sysPromptString));
        } else {
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

    private void runLoopStep(List<Message> history, int originalHistorySize, Sinks.Many<AgentEvent> sink, String chatId,
            String userId) {
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(
            curWeatherTool, geoRegeoTool, poiSearchTool, userPOITool, dailyTool, toolRepPointerTool, poiAroundTool));
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
                            log.error("[DailyAgent] Chat Error", error);
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
                // ====== CurWeatherTool ======
                case "getWeatherByLatLon" -> {
                    double lat = rootNode.get("lat").asDouble();
                    double lon = rootNode.get("lon").asDouble();
                    return curWeatherTool.getWeatherByLatLon(lat, lon, toolContext);
                }
                case "get3dayWeatherForecastByLatLon" -> {
                    double lat = rootNode.get("lat").asDouble();
                    double lon = rootNode.get("lon").asDouble();
                    return curWeatherTool.get3dayWeatherForecastByLatLon(lat, lon, toolContext);
                }
                case "getRain2hForecastByLatLon" -> {
                    double lat = rootNode.get("lat").asDouble();
                    double lon = rootNode.get("lon").asDouble();
                    return curWeatherTool.getRain2hForecastByLatLon(lat, lon, toolContext);
                }
                case "getWeatherAlertsByLatLon" -> {
                    double lat = rootNode.get("lat").asDouble();
                    double lon = rootNode.get("lon").asDouble();
                    return curWeatherTool.getWeatherAlertsByLatLon(lat, lon, toolContext);
                }
                case "getAirQualityByLatLon" -> {
                    double lat = rootNode.get("lat").asDouble();
                    double lon = rootNode.get("lon").asDouble();
                    return curWeatherTool.getAirQualityByLatLon(lat, lon, toolContext);
                }

                // ====== GeoRegeoTool ======
                case "regeo" -> {
                    double longitude = rootNode.get("longitude").asDouble();
                    double latitude = rootNode.get("latitude").asDouble();
                    return geoRegeoTool.regeo(longitude, latitude, toolContext);
                }
                case "geo" -> {
                    String address = rootNode.get("address").asText();
                    String city = rootNode.has("city") ? rootNode.get("city").asText() : "";
                    return geoRegeoTool.geo(address, city, toolContext);
                }

                // ====== POISearchTool ======
                case "searchPOIsByKeyword" -> {
                    String keyword = rootNode.has("keyword") ? rootNode.get("keyword").asText() : null;
                    String city = rootNode.has("city") ? rootNode.get("city").asText() : null;
                    return poiSearchTool.searchPOIsByKeyword(keyword, city, toolContext);
                }

                // ====== POIAroundTool ======
                case "searchAroundPOI" -> {
                    double longitude = rootNode.get("longitude").asDouble();
                    double latitude = rootNode.get("latitude").asDouble();
                    String keywords = rootNode.get("keywords").asText();
                    int radius = rootNode.get("radius").asInt();
                    return poiAroundTool.searchAroundPOI(longitude, latitude, keywords, radius, toolContext);
                }

                // ====== UserPOISearchTool ======
                case "listUserPOIs" -> {
                    return userPOITool.listUserPOIs(toolContext);
                }
                case "getUserPOIDetailByTag" -> {
                    String poiTag = rootNode.get("poiTag").asText();
                    return userPOITool.getUserPOIDetailByTag(poiTag, toolContext);
                }
                case "searchUserPOIDetail" -> {
                    String poiName = rootNode.get("poiName").asText();
                    return userPOITool.searchUserPOIDetail(poiName, toolContext);
                }

                // ====== UserPOITool (write ops) ======
                case "createNewPOI" -> {
                    String poiTag = rootNode.has("poiTag") ? rootNode.get("poiTag").asText(null) : null;
                    String poiName = rootNode.has("poiName") ? rootNode.get("poiName").asText(null) : null;
                    String poiAddress = rootNode.has("poiAddress") ? rootNode.get("poiAddress").asText(null) : null;
                    BigDecimal longitude = rootNode.has("longitude") && !rootNode.get("longitude").isNull()
                            ? new BigDecimal(rootNode.get("longitude").asText())
                            : null;
                    BigDecimal latitude = rootNode.has("latitude") && !rootNode.get("latitude").isNull()
                            ? new BigDecimal(rootNode.get("latitude").asText())
                            : null;
                    return userPOITool.createNewPOI(poiTag, poiName, poiAddress, longitude, latitude, toolContext);
                }
                case "updateUserPOIInfo" -> {
                    String poiTag = rootNode.has("poiTag") ? rootNode.get("poiTag").asText(null) : null;
                    String infoEnumText = rootNode.has("infoEnum") ? rootNode.get("infoEnum").asText(null) : null;
                    String value = rootNode.has("value") ? rootNode.get("value").asText(null) : null;

                    POIInfoEnum infoEnum = null;
                    if (infoEnumText != null && !infoEnumText.isBlank()) {
                        try {
                            infoEnum = POIInfoEnum.valueOf(infoEnumText.trim());
                        } catch (IllegalArgumentException ignored) {
                            // let tool return a readable error
                        }
                    }
                    return userPOITool.updateUserPOIInfo(poiTag, infoEnum, value, toolContext);
                }
                case "deleteUserPOI" -> {
                    String poiTag = rootNode.has("poiTag") ? rootNode.get("poiTag").asText(null) : null;
                    return userPOITool.deleteUserPOI(poiTag, toolContext);
                }

                // ====== DailyTool ======
                case "estimateBufferTime" -> {
                    double startLng = rootNode.get("startLng").asDouble();
                    double startLat = rootNode.get("startLat").asDouble();
                    double endLng = rootNode.get("endLng").asDouble();
                    double endLat = rootNode.get("endLat").asDouble();
                    return dailyTool.estimateBufferTime(startLng, startLat, endLng, endLat, toolContext);
                }
                case "estimatePrice" -> {
                    double startLng = rootNode.get("startLng").asDouble();
                    double startLat = rootNode.get("startLat").asDouble();
                    double endLng = rootNode.get("endLng").asDouble();
                    double endLat = rootNode.get("endLat").asDouble();
                    return dailyTool.estimatePrice(startLng, startLat, endLng, endLat, toolContext);
                }

                // ====== ToolRepPointerTool ======
                case "getCalling" -> {
                    String callId = rootNode.get("callId").asText();
                    return toolRepPointerTool.getToolResponse(callId, toolContext);
                }

                default -> {
                    return "Unknown tool: " + toolName;
                }
            }
        } catch (Exception e) {
            log.error("[DailyAgent] Tool execution error", e);
            return "Tool execution failed: " + e.getMessage();
        }
    }
}


