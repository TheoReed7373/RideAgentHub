package com.fancy.rideagenthub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserPoiDTO {
    private String id;
    private String poiTag;
    private String poiName;
    private String poiAddress;
    private BigDecimal longitude;
    private BigDecimal latitude;
}
