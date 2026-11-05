package com.fancy.rideagenthub.agentbase.qweather.pojo.life;

public record QweatherAirIndex(
        String code,
        String name,
        Double aqi,
        String aqiDisplay,
        String level,
        String category,
        QweatherRgbaColor color,
        QweatherAirPrimaryPollutant primaryPollutant,
        QweatherAirHealth health
) {
}
