package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

/**
 * 工单升级优先级请求DTO
 */
@Data
public class TicketEscalateReqDTO {

    /**
     * 工单编号（ticketId）
     */
    private String ticketId;

    /**
     * 目标优先级（2-紧急，3-特急）
     */
    private Integer targetLevel;

    /**
     * 升级原因
     */
    private String reason;
}
