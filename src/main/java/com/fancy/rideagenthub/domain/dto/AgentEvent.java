package com.fancy.rideagenthub.domain.dto;

public record AgentEvent(String type, // 事件类型: "message", "tool_start", "error", "confirm"，"notify"
        Object payload // 数据载荷: 字符串 或 JSON对象

) {
    public static AgentEvent message(String content) {
        return new AgentEvent("message", content);
    }

    public static AgentEvent tool(String toolName) {
        return new AgentEvent("tool_start", toolName);
    }

    public static AgentEvent error(String error) {
        return new AgentEvent("error", error);
    }

    public static AgentEvent comfirm(Object json) {
        return new AgentEvent("confirm", json);
    }

    public static AgentEvent notify(String note){
        return new AgentEvent("notify", note);
    }
}
