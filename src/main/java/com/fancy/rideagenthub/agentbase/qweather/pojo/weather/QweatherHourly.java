package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

/**
 * 逐小时预报（hourly）
 */
public record QweatherHourly(
        String fxTime,
        String temp,
        String icon,
        String text,
        String wind360,
        String windDir,
        String windScale,
        String windSpeed,
        String humidity,
        String pop,
        String precip,
        String pressure,
        String cloud,
        String dew
) {
}
