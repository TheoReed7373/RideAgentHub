package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 用户补充工单信息请求DTO
 */
@Data
public class TicketAppendMessageReqDTO {

    /**
     * 工单编号（ticketId）
     */
    private String ticketId;

    /**
     * 追加内容
     */
    private String content;
}
