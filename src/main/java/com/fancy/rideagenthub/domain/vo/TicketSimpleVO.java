package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工单简要信息VO
 * 用于未完结工单列表展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketSimpleVO {
    /**
     * 工单对外编号
     */
    private String ticketId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 工单类型
     */
    private Integer ticketType;

    /**
     * 工单类型描述
     */
    private String ticketTypeDesc;

    /**
     * 工单标题
     */
    private String title;

    /**
     * 状态
     */
    private Integer ticketStatus;

    /**
     * 状态描述
     */
    private String ticketStatusDesc;
}
