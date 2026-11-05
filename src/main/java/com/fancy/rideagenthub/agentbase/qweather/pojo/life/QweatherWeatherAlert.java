package com.fancy.rideagenthub.agentbase.qweather.pojo.life;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QweatherWeatherAlert(
        String id,
        String senderName,
        String issuedTime,
        QweatherWeatherAlertMessageType messageType,
        QweatherWeatherAlertEventType eventType,
        String urgency,
        String severity,
        String certainty,
        String icon,
        QweatherRgbaColor color,
        String effectiveTime,
        String onsetTime,
        @JsonProperty("expireTime") String expireTime,
        @JsonProperty("expiredTime") String expiredTime,
        String headline,
        String description,
        String criteria,
        String instruction,
        List<String> responseTypes
) {
}
