package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 工单处理/回复请求DTO
 */
@Data
public class TicketHandleReqDTO {
    /**
     * 工单主键(ticketId)
     */
    private String ticketId;

    /**
     * 操作人ID (客服或用户)
     */
    private String operatorId;

    /**
     * 操作人角色: 用户/客服
     */
    private Integer operatorRole;

    /**
     * 回复内容/处理意见
     */
    private String content;

    /**
     * 动作指令
     * ACTION_REPLY (仅回复), ACTION_RESOLVE (处理完成), ACTION_TRANSFER (转交)
     */
    private String actionType;

    // 常量定义
    public static final String ACTION_REPLY = "REPLY";
    public static final String ACTION_RESOLVE = "RESOLVE";
    public static final String ACTION_TRANSFER = "TRANSFER";
    public static final String ACTION_REJECT = "REJECT";
}
