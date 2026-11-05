package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; /**
 * AOI (兴趣面/区域)
 */
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapAoi(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String id,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String name,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String area,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String type
) {}
