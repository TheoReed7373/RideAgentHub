package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工单沟通记录VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketChatVO {
    /**
     * 记录ID
     */
    private String id;

    /**
     * 关联工单ID
     */
    private String ticketId;

    /**
     * 发送者ID
     */
    private String senderId;

    /**
     * 发送者角色: 0-系统, 1-乘客, 2-司机, 3-客服
     */
    private Integer senderRole;

    /**
     * 发送者角色描述
     */
    private String senderRoleDesc;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
