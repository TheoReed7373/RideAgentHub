package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.service.base.ChatInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChatStatusTool {
    private final ChatManager chatManager;

    @Tool(description = "当工具调用多次出错或明显无法满足用户需求时，主动锁定对话")
    public String lockSession(ToolContext toolContext){
        chatManager.lockChat(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        return "对话已锁定，引导用户创建新对话。";
    }
}


