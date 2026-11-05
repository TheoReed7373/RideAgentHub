package com.fancy.rideagenthub.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceEstimateVO {

    /**
     * 预估一口价
     */
    private BigDecimal estPrice;

    /**
     * 乘算倍率
     */
    private BigDecimal estPriceRadio;

    /**
     * 预估基础里程费（起步价+里程费）
     */
    private BigDecimal estPriceBase;

    /**
     * 预估时长费
     */
    private BigDecimal estPriceTime;

    /**
     * 预估远途/空驶费
     */
    private BigDecimal estPriceDistance;

    /**
     * 预估加急费
     */
    private BigDecimal estPriceExpedited;

}
