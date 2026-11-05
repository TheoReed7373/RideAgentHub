package com.fancy.rideagenthub.agentbase.amap.pojo.route;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * roadaggregation=true 时，paths 下的道路聚合信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapRoad(
        @JsonProperty("road_name")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String roadName,
        @JsonProperty("road_distance")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String roadDistance,
        @JsonProperty("traffic_lights")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String trafficLights,
        List<AmapStep> steps
) {
}
