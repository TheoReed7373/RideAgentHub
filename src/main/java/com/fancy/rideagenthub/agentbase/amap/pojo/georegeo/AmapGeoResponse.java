package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 地理编码根响应对象
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapGeoResponse(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String info,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String infocode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String count,
        List<Geocode> geocodes
) {
    public boolean isSuccess() {
        return "1".equals(status);
    }
}
