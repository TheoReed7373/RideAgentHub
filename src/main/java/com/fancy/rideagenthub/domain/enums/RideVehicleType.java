package com.fancy.rideagenthub.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 网约车车辆类型枚举
 */
public enum RideVehicleType {
    EXPRESS(1, "快车"),
    PREMIUM(2, "优享"),
    LUXURY(3, "专车");

    private final int code;
    private final String desc;

    RideVehicleType(int code, String desc) {
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
    public static RideVehicleType fromCode(int code) {
        for (RideVehicleType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid vehicle type code: " + code);
    }
}
