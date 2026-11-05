package com.fancy.rideagenthub.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 订单取消方枚举
 * 1-用户, 2-司机, 3-系统
 */
public enum RideCancelRole {
    USER(1, "用户"),
    DRIVER(2, "司机"),
    SYSTEM(3, "系统");

    private final int code;
    private final String desc;

    RideCancelRole(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    @JsonCreator
    public static RideCancelRole fromCode(int code) {
        for (RideCancelRole role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new IllegalArgumentException("Invalid cancel role code: " + code);
    }
}
