package com.fancy.rideagenthub.domain.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 创建工单请求DTO
 */
@Data
@Builder
public class TicketCreateReqDTO {
    /**
     * 当前登录用户ID (Controller层注入)
     */
    private String userId;

    /**
     * 用户类型: 1-乘客, 2-司机
     */
    private Integer userType;

    /**
     * 关联订单ID (可选)
     */
    private Long orderId;

    /**
     * 工单类型: 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他
     */
    private Integer ticketType;

    /**
     * 优先级: 1-普通, 2-紧急, 3-特急
     */
    private Integer priority;

    /**
     * 工单标题
     */
    private String title;

    /**
     * 详细描述
     */
    private String content;
}
