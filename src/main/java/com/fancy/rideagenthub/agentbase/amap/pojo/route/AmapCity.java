package com.fancy.rideagenthub.agentbase.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapCity(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String name,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String citycode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode,
        List<AmapDistrict> districts
) {
}
