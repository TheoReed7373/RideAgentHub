package com.fancy.rideagenthub.agentbase.memory;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class MessageParser {
    /** 坏记录后只从下一条用户消息重新建立完整上下文；不删除数据库原始记录。 */
    public List<Message> unparseHistory(List<String> rows) {
        List<Message> result = new ArrayList<>();
        boolean recovering = false;
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            if (row == null || row.isBlank()) continue;
            Message message;
            try { message = unparse(row); }
            catch (RuntimeException e) {
                log.warn("会话历史存在损坏记录，从后续完整轮次恢复，记录偏移={}", i);
                result.clear(); recovering = true; continue;
            }
            if (message == null) { result.clear(); recovering = true; continue; }
            if (recovering && !(message instanceof UserMessage)) continue;
            recovering = false;
            result.add(message);
        }
        return result;
    }
    public String parse(Message message){
        MessageType type = message.getMessageType();
        switch (type){
            case USER: {
                UserMessage userMessage = (UserMessage) message;
                JSONObject json = new JSONObject()
                        .fluentPut("type", "USER")
                        .fluentPut("content", userMessage.getText())
                        .fluentPut("metadata", userMessage.getMetadata() == null ? new HashMap<>() : userMessage.getMetadata());
                return json.toJSONString();
            }
            case ASSISTANT: {
                AssistantMessage assistantMessage = (AssistantMessage) message;
                JSONObject json = new JSONObject()
                        .fluentPut("type", "ASSISTANT")
                        .fluentPut("tool_calls", assistantMessage.getToolCalls() == null ? List.of() : assistantMessage.getToolCalls())
                        .fluentPut("content", message.getText())
                        .fluentPut("metadata", message.getMetadata() == null ? new HashMap<>() : message.getMetadata());
                return json.toJSONString();
            }
            case TOOL: {
                ToolResponseMessage toolResponseMessage = (ToolResponseMessage) message;
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                if (toolResponseMessage.getResponses() != null) {
                    toolResponseMessage.getResponses().forEach(response -> {
                        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                                response.id(),
                                response.name(),
                                response.responseData()
                        );
                        responses.add(toolResponse);
                    });
                }
                JSONObject json = new JSONObject()
                        .fluentPut("type", "TOOL")
                        //普及tool指针
                        .fluentPut("responses", responses)
                        .fluentPut("metadata", message.getMetadata() == null ? new HashMap<>() : message.getMetadata());
                return json.toJSONString();
            }
            case SYSTEM: {
                SystemMessage systemMessage = (SystemMessage) message;
                JSONObject json = new JSONObject()
                        .fluentPut("type", "SYSTEM")
                        .fluentPut("content", systemMessage.getText());
                return json.toJSONString();
            }
            default: {
                log.error("unknown message type: {}", type);
                return null;
            }
        }
    }

    public Message unparse(String jsonStr){
        JSONObject json = JSONObject.parseObject(jsonStr);
        MessageType type = MessageType.valueOf(json.getString("type"));
        switch (type){
            case USER: {
                Map<String, Object> metadata = parseMetadata(json);
                return UserMessage.builder()
                        .text(json.getString("content"))
                        .metadata(metadata)
                        .build();
            }
            case ASSISTANT: {
                Map<String, Object> metadata = parseMetadata(json);
                return AssistantMessage.builder()
                        .content(json.getString("content"))
                        .toolCalls(json.getJSONArray("tool_calls") == null ? List.of() : json.getJSONArray("tool_calls").toJavaList(AssistantMessage.ToolCall.class))
                        .properties(metadata)
                        .build();
            }
            case TOOL: {
                Map<String, Object> metadata = parseMetadata(json);
                return ToolResponseMessage.builder()
                        .responses(json.getJSONArray("responses") == null ? List.of() : json.getJSONArray("responses").toJavaList(ToolResponseMessage.ToolResponse.class))
                        .metadata(metadata)
                        .build();
            }
            case SYSTEM: {
                return SystemMessage.builder()
                        .text(json.getString("content"))
                        .build();
            }
            default:{
                log.error("unknown message type: {}", type);
                return null;
            }
        }
    }

    private Map<String, Object> parseMetadata(JSONObject json) {
        JSONObject metadataJson = json.getJSONObject("metadata");
        return metadataJson == null ? new HashMap<>() : new HashMap<>(metadataJson);
    }

    public List<SimpleMessage> simpleParse(List<Message> messages){
        List<SimpleMessage> resultList = new ArrayList<>();
        for(Message message : messages){
            switch (message.getMessageType()){
                case USER -> resultList.add(SimpleMessage.user(message.getText()));
                case ASSISTANT -> resultList.add(SimpleMessage.assistant(message.getText()));
            }
        }
        return resultList;
    }
}
