package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map; /**
 * 地址组件 (包含高德API最容易报错的动态类型字段)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressComponent(
    @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String country,
    @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String province,

        Object city,

        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String citycode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String district,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String township,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String towncode,

        // 坑：无数据时返回 []，有数据时返回 Object
        Object neighborhood,
        Object building,

        @JsonProperty("streetNumber") StreetNumber streetNumber,
        @JsonProperty("businessAreas") List<BusinessArea> businessAreas
) {
    // 辅助方法：安全获取城市名称
    public String getCityName() {
        if (city instanceof String s) return s;
        return ""; // 直辖市通常这里为空，使用 province 即可
    }

    // 辅助方法：安全获取社区名称
    public String getNeighborhoodName() {
        return extractNameFromDynamicField(neighborhood);
    }

    // 辅助方法：安全获取建筑名称
    public String getBuildingName() {
        return extractNameFromDynamicField(building);
    }

    private String extractNameFromDynamicField(Object field) {
        if (field instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name != null ? name.toString() : "";
        }
        return "";
    }
}
