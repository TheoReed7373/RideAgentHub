package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 地理编码结果对象（地址 -> 经纬度）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Geocode(
    @JsonProperty("formatted_address")
    @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
    String formattedAddress,
    @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String country,
    @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String province,

        /**
         * 坑：部分字段在无数据时可能返回 []，有数据时返回字符串
         */
        Object city,

        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String citycode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String district,
        Object street,
        Object number,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String level
) {
    public String getCityName() {
        if (city instanceof String s) {
            return s;
        }
        return "";
    }

    public String getStreetString() {
        if (street instanceof String s) {
            return s;
        }
        return "";
    }

    public String getNumberString() {
        if (number instanceof String s) {
            return s;
        }
        return "";
    }
}
