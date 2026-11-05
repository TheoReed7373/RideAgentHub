package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 工单反馈评价请求DTO
 */
@Data
public class TicketFeedbackReqDTO {
    /**
     * 工单ID
     */
    private String ticketId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 是否满意: true-满意, false-不满意
     */
    private Boolean satisfied;

    /**
     * 评分: 1-5
     */
    private Integer rating;

    /**
     * 评价内容
     */
    private String feedbackContent;
}
