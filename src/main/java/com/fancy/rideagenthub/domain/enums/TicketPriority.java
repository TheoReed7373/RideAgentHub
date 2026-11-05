package com.fancy.rideagenthub.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 工单优先级枚举
 * <p>
 * 1-普通, 2-紧急, 3-特急
 */
@Getter
public enum TicketPriority {
    NORMAL(1, "普通"),
    URGENT(2, "紧急"),
    CRITICAL(3, "特急");

    @EnumValue
    private final int code;
    private final String desc;

    TicketPriority(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static TicketPriority fromCode(int code) {
        for (TicketPriority priority : values()) {
            if (priority.code == code) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Invalid ticket priority code: " + code);
    }
}
