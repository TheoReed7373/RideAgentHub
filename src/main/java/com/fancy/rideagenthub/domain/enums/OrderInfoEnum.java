package com.fancy.rideagenthub.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OrderInfoEnum {
    // 订单偏好
    VEHICLE_TYPE("车型类别(1:快车, 2:优享, 3:专车)"),
    IS_RESERVATION("是否预约单(0:实时, 1:预约)"),
    IS_EXPEDITED("是否加急(0:否, 1:是)"),
    SCHEDULED_TIME("预约时间(格式: yyyy-MM-dd HH:mm:ss)"),

    // 起终点信息
    START_ADDRESS("起点地址名称"),
    START_LAT("起点纬度"),
    START_LNG("起点经度"),
    END_ADDRESS("终点地址名称"),
    END_LAT("终点纬度"),
    END_LNG("终点经度"),

    // 价格
    EST_PRICE("预估价格"),
    MONGO_TRACE_ID("路径规划Id"),
    EST_DISTANCE_KM("预估距离");

    private final String description;

    OrderInfoEnum(String description) {
        this.description = description;
    }

    // 这一步很重要，告诉Jackson序列化时使用枚举名还是描述，通常建议用名字
    @JsonValue
    public String getField() {
        return this.name();
    }
}
