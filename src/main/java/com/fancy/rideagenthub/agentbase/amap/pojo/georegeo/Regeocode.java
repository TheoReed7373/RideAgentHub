package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List; /**
 * 逆地理编码主体
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Regeocode(
        @JsonProperty("formatted_address")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String formattedAddress,
        @JsonProperty("addressComponent") AddressComponent addressComponent,
        List<AmapPoi> pois,
        List<AmapRoad> roads,
        @JsonProperty("roadinters") List<AmapRoadInter> roadInters,
        List<AmapAoi> aois
) {}
