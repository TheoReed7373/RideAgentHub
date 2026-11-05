package com.fancy.rideagenthub.domain.vo;

import com.fancy.rideagenthub.domain.entity.UserPOI;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class POIInfoVO {
    private String poiTag;
    private String poiName;
    private String poiAddress;
    private BigDecimal longitude;
    private BigDecimal latitude;

    public POIInfoVO(UserPOI userPOI){
        this.poiTag = userPOI.getPoiTag();
        this.poiName = userPOI.getPoiName();
        this.poiAddress = userPOI.getPoiAddress();
        this.longitude = userPOI.getLongitude();
        this.latitude = userPOI.getLatitude();
    }
}
