package com.fancy.rideagenthub.agentbase.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapTmc(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String polyline
) {
}
