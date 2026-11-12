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
    @jakarta.annotation.Resource
    private com.fancy.rideagenthub.agentbase.chatinfo.ChatManager chatManager;

    @Tool(name = "getCalling", description = "根据工具调用结果历史中的调用ID，查询调用过的工具的返回结果")
    public String getToolResponse(@ToolParam(description = "调用ID") String callId, ToolContext toolContext){
        log.info("[Tool Calling]: getToolResponse(): callId={}", callId);
        ToolNotifySupport.notifyToolListener(toolContext, "根据call_id查询调用过的工具结果 (getToolResponse)");
        String chatId = toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString();
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        chatManager.assertOwned(userId, chatId, false);
        String response = toolResponseMemory.get(callId, chatId);
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
        try {
            toolResponseMemory.save(callId, chatId, response);
        } catch (RuntimeException e) {
            // 新历史保留完整工具应答，辅助索引失败不能重放已经完成的真实工具。
            log.warn("辅助工具结果索引写入失败，结果随会话保存", e);
        }
    }

}



