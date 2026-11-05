package com.fancy.rideagenthub.agentbase.qweather.pojo.life;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QweatherMetadata(
        String tag,
        Boolean zeroResult,
        @JsonProperty("attributions") List<String> attributions
) {
}
