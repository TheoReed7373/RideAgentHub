package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapPhoto(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String title,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String url
) {
}
