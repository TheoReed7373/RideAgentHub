package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

/**
 * 分钟级降水预报（minutely）
 */
public record QweatherMinutely(
        String fxTime,
        String precip,
        String type
) {
}
