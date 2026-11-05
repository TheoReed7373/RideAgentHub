package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty; /**
 * 道路交叉口
 */
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapRoadInter(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String direction,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonProperty("first_id")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String firstId,
        @JsonProperty("first_name")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String firstName,
        @JsonProperty("second_id")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String secondId,
        @JsonProperty("second_name")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String secondName
) {}
