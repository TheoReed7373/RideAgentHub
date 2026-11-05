package com.fancy.rideagenthub.agentbase.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapPath(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String duration,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String strategy,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String tolls,
        @JsonProperty("toll_distance")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String tollDistance,
        @JsonProperty("traffic_lights")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String trafficLights,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String restriction,
        List<AmapRoad> roads,
        List<AmapStep> steps
) {
}
