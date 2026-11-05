package com.fancy.rideagenthub.domain.vo;

import com.fancy.rideagenthub.domain.entity.UserPOI;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PoiDetailVO {
    private Long id;
    private String poiTag;
    private String poiName;
    private String poiAddress;
    private BigDecimal longitude;
    private BigDecimal latitude;

    public PoiDetailVO(UserPOI userPOI){
        this.id = userPOI.getId();
        this.poiTag = userPOI.getPoiTag();
        this.poiName = userPOI.getPoiName();
        this.poiAddress = userPOI.getPoiAddress();
        this.longitude = userPOI.getLongitude();
        this.latitude = userPOI.getLatitude();
    }
}
