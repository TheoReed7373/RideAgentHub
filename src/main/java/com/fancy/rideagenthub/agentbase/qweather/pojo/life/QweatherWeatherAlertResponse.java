package com.fancy.rideagenthub.agentbase.qweather.pojo.life;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QweatherWeatherAlertResponse(
        QweatherMetadata metadata,
        @JsonProperty("alerts") List<QweatherWeatherAlert> alerts
) {
}
