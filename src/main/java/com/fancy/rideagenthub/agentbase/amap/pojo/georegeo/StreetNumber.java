package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; /**
 * 门牌信息
 */
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
@JsonIgnoreProperties(ignoreUnknown = true)
public record StreetNumber(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String street,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String number,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String direction,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance
) {}
