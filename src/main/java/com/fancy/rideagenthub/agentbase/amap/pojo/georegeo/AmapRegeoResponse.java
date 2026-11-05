package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 根响应对象
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapRegeoResponse(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String info,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String infocode,
        Regeocode regeocode
) {
    public boolean isSuccess() {
        return "1".equals(status);
    }
}