package com.fancy.rideagenthub.agentbase.memory;

import org.springframework.ai.chat.messages.*;
import java.util.*;

/** 从完整的工具批次边界取窗口；损坏的历史明确拒绝使用，避免向模型提交非法协议。 */
public final class MessageWindow {
    private MessageWindow() {}

    public static List<Message> tail(List<Message> messages, int limit) {
        if (messages == null || limit <= 0) return List.of();
        Set<String> pending = new HashSet<>();
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (pending.isEmpty() && !(message instanceof ToolResponseMessage)) boundaries.add(i);
            if (message instanceof AssistantMessage assistant) {
                if (!pending.isEmpty()) throw invalid();
                for (var call : assistant.getToolCalls()) {
                    if (!pending.add(call.id())) throw invalid();
                }
            } else if (message instanceof ToolResponseMessage tools) {
                for (var response : tools.getResponses()) {
                    if (!pending.remove(response.id())) throw invalid();
                }
            } else if (!pending.isEmpty()) {
                throw invalid();
            }
        }
        // 最后一个待人工确认的工具批次可以尚未结束，必须整体保留。
        int wanted = Math.max(0, messages.size() - limit);
        int start = 0;
        for (int boundary : boundaries) {
            if (boundary > wanted) break;
            start = boundary;
        }
        return List.copyOf(messages.subList(start, messages.size()));
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("会话中的工具记录不完整，请新建对话或联系管理员修复历史");
    }
}
