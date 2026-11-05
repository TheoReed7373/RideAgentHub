package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工单列表展示VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketVO {
    /**
     * 主键ID
     */
    private String id;

    /**
     * 工单对外编号
     */
    private String ticketId;

    /**
     * 发起人ID
     */
    private String userId;

    /**
     * 发起人类型: 1-乘客, 2-司机
     */
    private Integer userType;

    /**
     * 发起人类型描述
     */
    private String userTypeDesc;

    /**
     * 关联的网约车订单ID
     */
    private String orderId;

    /**
     * 工单类型
     */
    private Integer ticketType;

    /**
     * 工单类型描述
     */
    private String ticketTypeDesc;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 优先级描述
     */
    private String priorityDesc;

    /**
     * 状态
     */
    private Integer ticketStatus;

    /**
     * 状态描述
     */
    private String ticketStatusDesc;

    /**
     * 当前处理客服ID
     */
    private String handlerId;

    /**
     * 工单标题
     */
    private String title;

    /**
     * 工单内容摘要
     */
    private String contentSummary;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
