package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapBizExt(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String rating,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String cost,
        @JsonProperty("opentime2")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String opentime2,
        @JsonProperty("open_time")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String openTime,
        @JsonProperty("meal_ordering")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String mealOrdering
) {
}
