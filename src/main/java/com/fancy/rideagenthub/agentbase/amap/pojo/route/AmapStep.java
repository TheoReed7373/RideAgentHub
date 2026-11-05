package com.fancy.rideagenthub.agentbase.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrArrayToStringListDeserializer;
import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapStep(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String instruction,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String orientation,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String road,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String duration,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String polyline,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String action,
        @JsonProperty("assistant_action")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String assistantAction,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String tolls,
        @JsonProperty("toll_distance")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String tollDistance,
        @JsonProperty("toll_road")
        @JsonDeserialize(using = StringOrArrayToStringListDeserializer.class)
        List<String> tollRoad,
        List<AmapTmc> tmcs,
        List<AmapCity> cities
) {
}
