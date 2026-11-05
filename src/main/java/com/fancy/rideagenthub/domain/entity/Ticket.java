package com.fancy.rideagenthub.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fancy.rideagenthub.domain.enums.TicketPriority;
import com.fancy.rideagenthub.domain.enums.TicketStatus;
import com.fancy.rideagenthub.domain.enums.TicketType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单主表实体
 * <p>
 * 对应表: sys_ticket
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_ticket")
public class Ticket {

	private static final long serialVersionUID = 1L;

	/**
	 * 主键ID（自增）
	 */
	@TableId(value = "id", type = IdType.AUTO)
	private Long id;

	/**
	 * 工单对外编号（业务唯一标识）：Tyyyymmdd100025
     * 年月日、工单类型码、当天第几单（Redis维护，自增获取）
	 */
	@TableField("ticket_id")
	private String ticketId;

	/**
	 * 发起人ID
	 */
	@TableField("user_id")
	private Long userId;

	/**
	 * 发起人类型: 1-乘客, 2-司机
	 */
	@TableField("user_type")
	private Integer userType;

	/**
	 * 关联的网约车订单ID
	 */
	@TableField("order_id")
	private Long orderId;

	/**
	 * 工单类型: 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他
	 */
	@TableField("ticket_type")
	private Integer ticketType;

	/**
	 * 优先级: 1-普通, 2-紧急, 3-特急
	 */
	@TableField("priority")
	private Integer priority;

	/**
	 * 状态: 0-待分配, 1-处理中, 2-待用户确认, 3-已完成, 4-已关闭
	 */
	@TableField("ticket_status")
	private Integer ticketStatus;

	/**
	 * 当前处理客服ID
	 */
	@TableField("handler_id")
	private Long handlerId;

	/**
	 * 工单标题
	 */
	@TableField("title")
	private String title;

	/**
	 * 工单详情描述
	 */
	@TableField("content")
	private String content;

	/**
	 * 处理结果摘要
	 */
	@TableField("process_result")
	private String processResult;

	/**
	 * 创建时间
	 */
	@TableField("created_at")
	private LocalDateTime createdAt;

	/**
	 * 更新时间
	 */
	@TableField("updated_at")
	private LocalDateTime updatedAt;

}
