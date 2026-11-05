package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

import java.util.List;

public record QweatherDailyResponse(
        String code,
        String updateTime,
        String fxLink,
        List<QweatherDaily> daily,
        QweatherRefer refer
) {
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
