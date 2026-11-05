package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

import java.util.List;

/**
 * 分钟级降水（未来2小时，每5分钟）
 */
public record QweatherMinutelyResponse(
        String code,
        String updateTime,
        String fxLink,
        String summary,
        List<QweatherMinutely> minutely,
        QweatherRefer refer
) {
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
