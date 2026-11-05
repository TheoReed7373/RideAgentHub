package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapIndoorData(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String cmsid,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String cpid,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String floor,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String truefloor
) {
}
