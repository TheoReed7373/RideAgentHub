package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

public record QweatherNowResponse(
        String code,
        String updateTime,
        String fxLink,
        QweatherNow now,
        QweatherRefer refer
) {
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
