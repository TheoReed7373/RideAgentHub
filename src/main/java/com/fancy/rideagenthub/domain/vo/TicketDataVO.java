package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工单统计数据VO
 * 用于仪表盘展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketDataVO {
    /**
     * 待分配工单数量 (status=0)
     */
    private Long pendingAssignCount;

    /**
     * 处理中工单数量 (status=1 且 handlerId不为空)
     */
    private Long processingCount;

    /**
     * 今日创建工单数量
     */
    private Long todayCreatedCount;

    /**
     * 今日完成工单数量 (今日update且status=3)
     */
    private Long todayCompletedCount;
}
