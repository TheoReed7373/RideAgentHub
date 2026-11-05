package com.fancy.rideagenthub.agentbase.amap.pojo.suggestion;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 输入提示 tip 对象
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapInputTip(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String id,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String name,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String district,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String address,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String typecode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String city
) {
}
