package com.fancy.rideagenthub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 经纬度点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Point {

    private BigDecimal lat;

    private BigDecimal lng;
}
