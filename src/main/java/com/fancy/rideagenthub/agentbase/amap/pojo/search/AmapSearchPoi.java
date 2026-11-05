package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fancy.rideagenthub.agentbase.amap.util.jackson.ObjectOrEmptyArrayDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapSearchPoi(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String parent,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String id,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String name,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String type,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String typecode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonProperty("entr_location")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String entrLocation,
        @JsonProperty("exit_location")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String exitLocation,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String address,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String pcode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String pname,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String citycode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String cityname,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adcode,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String adname,
        @JsonProperty("gridcode")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String gridcode,
        @JsonProperty("business_area")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String businessArea,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String tel,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String shopinfo,
        @JsonProperty("shopid")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String shopid,
        @JsonProperty("keytag")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String keytag,
        @JsonProperty("tag")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String tag,
        @JsonProperty("alias")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String alias,
        @JsonProperty("importance")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String importance,
        @JsonProperty("poiweight")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String poiweight,
        @JsonProperty("children")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String children,
        @JsonProperty("childtype")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String childtype,
        @JsonProperty("atag")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String atag,
        @JsonProperty("event")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String event,
        @JsonProperty("email")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String email,
        @JsonProperty("website")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String website,
        @JsonProperty("timestamp")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String timestamp,
        @JsonProperty("space_num")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String spaceNum,
        @JsonProperty("postcode")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String postcode,
        @JsonProperty("match")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String match,
        @JsonProperty("recommend")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String recommend,
        @JsonProperty("discount_num")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String discountNum,
        @JsonProperty("groupbuy_num")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String groupbuyNum,
        @JsonProperty("favorite_num")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String favoriteNum,
        @JsonProperty("navi_poiid")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String naviPoiid,
        @JsonProperty("featured_reviews")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String featuredReviews,
        @JsonProperty("biz_type")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String bizType,
        @JsonProperty("parking_type")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String parkingType,
        @JsonProperty("building")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String building,
        @JsonProperty("indoor_map")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String indoorMap,
        @JsonProperty("indoor_data")
        @JsonDeserialize(using = ObjectOrEmptyArrayDeserializer.class)
        AmapIndoorData indoorData,
        @JsonProperty("biz_ext")
        @JsonDeserialize(using = ObjectOrEmptyArrayDeserializer.class)
        AmapBizExt bizExt,
        List<AmapPhoto> photos
) {
}
