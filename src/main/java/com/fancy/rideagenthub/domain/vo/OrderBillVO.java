package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBillVO {

    private String orderId;

    private BigDecimal realPrice;

    private BigDecimal priceBase;

    private BigDecimal priceTime;

    private BigDecimal priceDistance;

    private BigDecimal priceExpedited;

    private BigDecimal priceRadio;
}
