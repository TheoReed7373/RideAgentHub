package com.fancy.rideagenthub.agentbase.qweather.pojo.poi;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POI（兴趣点）
 */
public record QweatherPoi(
        String name,
        String id,
        String lat,
        String lon,
        String adm2,
        String adm1,
        String country,
        String tz,
        @JsonProperty("utcOffset") String utcOffset,
        @JsonProperty("isDst") String isDst,
        String type,
        String rank,
        String fxLink
) {
}
