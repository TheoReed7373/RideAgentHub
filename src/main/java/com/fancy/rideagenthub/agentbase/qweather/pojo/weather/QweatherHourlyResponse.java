package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

import java.util.List;

public record QweatherHourlyResponse(
        String code,
        String updateTime,
        String fxLink,
        List<QweatherHourly> hourly,
        QweatherRefer refer
) {
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
