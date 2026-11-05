package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

/**
 * 实况天气（now）
 */
public record QweatherNow(
        String obsTime,
        String temp,
        String feelsLike,
        String icon,
        String text,
        String wind360,
        String windDir,
        String windScale,
        String windSpeed,
        String humidity,
        String precip,
        String pressure,
        String vis,
        String cloud,
        String dew
) {
}
