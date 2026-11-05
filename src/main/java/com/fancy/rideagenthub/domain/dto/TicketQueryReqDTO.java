package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 工单分页查询请求DTO
 */
@Data
public class TicketQueryReqDTO {
    /**
     * 查询某人的工单 (C端用)
     */
    private String userId;

    /**
     * 用户类型
     */
    private Integer userType;

    /**
     * 筛选状态
     */
    private Integer status;

    /**
     * 筛选类型
     */
    private Integer type;

    /**
     * 客服ID (B端用，查待办)
     */
    private String handlerId;

    /**
     * 搜索关键词(标题/编号)
     */
    private String keyword;

    /**
     * 当前页码
     */
    private long current = 1;

    /**
     * 每页大小
     */
    private long size = 10;
}
