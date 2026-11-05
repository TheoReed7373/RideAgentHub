package com.fancy.rideagenthub.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fancy.rideagenthub.domain.enums.TicketSenderRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单沟通记录实体
 * <p>
 * 对应表: sys_ticket_chat
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_ticket_chat")
public class TicketChat {

	private static final long serialVersionUID = 1L;

	/**
	 * 主键ID（自增）
	 */
	@TableId(value = "id", type = IdType.AUTO)
	private Long id;

	/**
	 * 关联 sys_ticket 的主键ID
	 */
	@TableField("ticket_id")
	private String ticketId;

	/**
	 * 发送者ID
	 */
	@TableField("sender_id")
	private Long senderId;

	/**
	 * 发送者角色: 1-乘客, 2-司机, 3-客服, 0-系统自动
	 */
	@TableField("sender_role")
	private Integer senderRole;

	/**
	 * 消息内容
	 */
	@TableField("content")
	private String content;

	/**
	 * 创建时间
	 */
	@TableField("created_at")
	private LocalDateTime createdAt;

}
