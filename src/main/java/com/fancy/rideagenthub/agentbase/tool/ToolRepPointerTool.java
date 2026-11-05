package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.agentbase.memory.ToolResponseMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@RequiredArgsConstructor
public class ToolRepPointerTool {

    private final ToolResponseMemory toolResponseMemory;

    @Tool(description = "根据工具调用结果历史中的调用ID，查询调用过的工具的返回结果")
    public String getToolResponse(@ToolParam(description = "调用ID") String callId, ToolContext toolContext){
        log.info("[Tool Calling]: getToolResponse(): callId={}", callId);
        ToolNotifySupport.notifyToolListener(toolContext, "根据call_id查询调用过的工具结果 (getToolResponse)");
        String response = toolResponseMemory.get(callId);
        log.info("[Tool Result-response]: {}", response);
        return response;
    }

    /**
     * 将真实工具结果持久化（用于 ToolResponse 指针）。
     */
    public void recordCalling(String callId, String response, ToolContext toolContext) {
        if (callId == null || callId.isBlank() || response == null) {
            return;
        }
        String chatId = null;
        if (toolContext != null && toolContext.getContext() != null) {
            Object chatIdObj = toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID);
            chatId = chatIdObj == null ? null : chatIdObj.toString();
        }
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        toolResponseMemory.save(callId, chatId, response);
    }

}



