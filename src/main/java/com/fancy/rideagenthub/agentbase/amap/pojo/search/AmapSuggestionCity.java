package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapSuggestionCity(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String name,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String num,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String citycode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode
) {
}
