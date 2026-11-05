package com.fancy.rideagenthub.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 工单状态枚举
 * <p>
 * 0-待分配, 1-处理中, 2-待用户确认, 3-已完成, 4-已关闭
 */
@Getter
public enum TicketStatus {
    PENDING_ASSIGN(0, "待分配"),
    PROCESSING(1, "处理中"),
    WAIT_USER_CONFIRM(2, "待用户确认"),
    COMPLETED(3, "已完成"),
    CLOSED(4, "已关闭");

    @EnumValue
    private final int code;
    private final String desc;

    TicketStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static TicketStatus fromCode(int code) {
        for (TicketStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ticket status code: " + code);
    }
}
