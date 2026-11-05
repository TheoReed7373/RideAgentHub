package com.fancy.rideagenthub.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 工单消息发送者角色枚举
 * <p>
 * 1-乘客, 2-司机, 3-客服, 0-系统自动
 */
@Getter
public enum TicketSenderRole {
    SYSTEM(0, "系统自动"),
    PASSENGER(1, "乘客"),
    DRIVER(2, "司机"),
    CUSTOMER_SERVICE(3, "客服");

    @EnumValue
    private final int code;
    private final String desc;

    TicketSenderRole(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static TicketSenderRole fromCode(int code) {
        for (TicketSenderRole role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new IllegalArgumentException("Invalid ticket sender role code: " + code);
    }
}
