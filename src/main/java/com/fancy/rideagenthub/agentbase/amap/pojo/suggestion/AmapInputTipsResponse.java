package com.fancy.rideagenthub.agentbase.amap.pojo.suggestion;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 高德输入提示 API 响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapInputTipsResponse(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String info,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String infocode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String count,
        List<AmapInputTip> tips
) {
    public boolean isSuccess() {
        return "1".equals(status);
    }
}
