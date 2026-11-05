package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import java.util.function.Consumer;

public final class ToolNotifySupport {
    private static final Logger log = LoggerFactory.getLogger(ToolNotifySupport.class);

    private ToolNotifySupport() {
    }

    public static void notifyToolListener(ToolContext toolContext, String message) {
        if (toolContext == null || toolContext.getContext() == null) {
            return;
        }
        Object listenerObj = toolContext.getContext().get(ToolContextKeyConstants.TOOL_LISTENER);
        if (listenerObj instanceof Consumer<?> consumer) {
            try {
                @SuppressWarnings("unchecked")
                Consumer<String> listener = (Consumer<String>) consumer;
                listener.accept(message);
            } catch (Exception e) {
                // Keep tool execution resilient, but record callback failures for observability.
                Object chatId = toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID);
                Object userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID);
                log.warn("toolListener callback failed, message={}, chatId={}, userId={}",
                        message, chatId, userId, e);
            }
        }
    }
}


