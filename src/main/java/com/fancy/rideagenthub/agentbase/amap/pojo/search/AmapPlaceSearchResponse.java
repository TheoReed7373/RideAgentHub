package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 高德 POI 搜索响应（关键字搜索/周边搜索通用）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapPlaceSearchResponse(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String info,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String infocode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String count,
        AmapSuggestion suggestion,
        List<AmapSearchPoi> pois
) {
    public boolean isSuccess() {
        return "1".equals(status);
    }
}
