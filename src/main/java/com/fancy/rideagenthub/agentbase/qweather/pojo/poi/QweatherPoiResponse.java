package com.fancy.rideagenthub.agentbase.qweather.pojo.poi;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QweatherPoiResponse(
        String code,
        @JsonProperty("poi") List<QweatherPoi> poi,
        QweatherRefer refer
) {
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
