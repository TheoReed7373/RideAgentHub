package com.fancy.rideagenthub.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 工单类型枚举
 * <p>
 * 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他
 */
@Getter
public enum TicketType {
    LOST_ITEM(1, "物品遗失"),
    FARE_DISPUTE(2, "费用争议"),
    SERVICE_COMPLAINT(3, "服务投诉"),
    SAFETY_ISSUE(4, "安全问题"),
    OTHER(5, "其他");

    @EnumValue
    private final int code;
    private final String desc;

    TicketType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static TicketType fromCode(int code) {
        for (TicketType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid ticket type code: " + code);
    }
}
