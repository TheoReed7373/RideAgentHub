package com.fancy.rideagenthub.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class POISearchInfoVO {
    private String name;
    private String type;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    //离中心点的距离，仅当周边搜索时有值
    private String distance;
}
