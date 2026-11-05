package com.fancy.rideagenthub.agentbase.qweather.pojo.life;

import java.util.List;

public record QweatherAirQualityResponse(
        QweatherMetadata metadata,
        List<QweatherAirIndex> indexes,
        List<QweatherAirPollutant> pollutants,
        List<QweatherAirRelatedStation> stations
) {
}
