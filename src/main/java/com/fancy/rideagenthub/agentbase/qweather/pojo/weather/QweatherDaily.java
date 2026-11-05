package com.fancy.rideagenthub.agentbase.qweather.pojo.weather;

/**
 * 每日预报（daily）
 */
public record QweatherDaily(
        String fxDate,
        String sunrise,
        String sunset,
        String moonrise,
        String moonset,
        String moonPhase,
        String moonPhaseIcon,
        String tempMax,
        String tempMin,
        String iconDay,
        String textDay,
        String iconNight,
        String textNight,
        String wind360Day,
        String windDirDay,
        String windScaleDay,
        String windSpeedDay,
        String wind360Night,
        String windDirNight,
        String windScaleNight,
        String windSpeedNight,
        String humidity,
        String precip,
        String pressure,
        String vis,
        String cloud,
        String uvIndex
) {
}
