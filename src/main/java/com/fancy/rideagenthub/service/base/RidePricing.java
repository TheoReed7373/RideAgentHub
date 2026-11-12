package com.fancy.rideagenthub.service.base;
import com.fancy.rideagenthub.domain.vo.PriceEstimateVO;
import com.fancy.rideagenthub.exception.BusinessException;
import java.math.*;
/** 预估、修改目的地和结算共享相同费率。 */
public final class RidePricing {
    private RidePricing() {}
    public static PriceEstimateVO calculate(String estKm, String estTime, Integer vehicleType, Integer isExpedited) {
        if (vehicleType != null && (vehicleType < 1 || vehicleType > 3)) throw new BusinessException(400, "车型无效");
        if (isExpedited != null && isExpedited != 0 && isExpedited != 1) throw new BusinessException(400, "加急标记无效");
        // 计价规则：金额 2 位小数 HALF_UP
        final int MONEY_SCALE = 2;

        BigDecimal distanceKm = requiredNonnegative(estKm, "里程");
        BigDecimal durationSec = requiredNonnegative(estTime, "时长");

        boolean expedited = isExpedited != null && isExpedited == 1;

        int vehicleTypeCode = vehicleType == null ? 1 : vehicleType;

        // 1) 计算基础里程费（起步价 + 里程费）
        // - 起步价 8.00，含 2.0km
        // - 里程费 2.20/km（仅超出 2km 的部分）
        BigDecimal startPrice = new BigDecimal("8.00");
        BigDecimal startDistanceKm = new BigDecimal("2.0");
        BigDecimal perKmFee = new BigDecimal("2.20");

        BigDecimal overStartKm = distanceKm.subtract(startDistanceKm);
        if (overStartKm.signum() < 0) {
            overStartKm = BigDecimal.ZERO;
        }
        BigDecimal mileageFee = overStartKm.multiply(perKmFee);
        BigDecimal priceBase = startPrice.add(mileageFee);

        // 2) 计算远途/空驶费：超出 20km 的部分，加收"里程费的 50%"
        BigDecimal longDistanceThresholdKm = new BigDecimal("20.0");
        BigDecimal longOverKm = distanceKm.subtract(longDistanceThresholdKm);
        if (longOverKm.signum() < 0) {
            longOverKm = BigDecimal.ZERO;
        }
        BigDecimal longDistanceSurchargePerKm = perKmFee.multiply(new BigDecimal("0.50"));
        BigDecimal priceDistance = longOverKm.multiply(longDistanceSurchargePerKm);

        // 3) 计算时长费：0.50/min。秒 -> 分钟，向上取整
        BigDecimal minutes = durationSec.divide(new BigDecimal("60"), 0, RoundingMode.CEILING);
        BigDecimal perMinFee = new BigDecimal("0.50");
        BigDecimal priceTime = minutes.multiply(perMinFee);

        // 4) 加急：固定调度费 5.00 + 动态倍率 +0.2
        BigDecimal priceExpedited = expedited ? new BigDecimal("5.00") : BigDecimal.ZERO;

        // 5) 车型倍率：按文档：1->1.0, 2->1.6, 3->2.5
        BigDecimal vehicleMultiplier = switch (vehicleTypeCode) {
            case 2 -> new BigDecimal("1.60");
            case 3 -> new BigDecimal("2.50");
            default -> BigDecimal.ONE;
        };
        BigDecimal expediteMultiplier = expedited ? new BigDecimal("0.20") : BigDecimal.ZERO;
        BigDecimal finalMultiplier = vehicleMultiplier.add(expediteMultiplier);

        // 6) 最终价：(小计价格 + 固定加急费) * (车型倍率 + 加急动态倍率)
        BigDecimal subTotal = priceBase.add(priceTime).add(priceDistance);
        BigDecimal estPrice = subTotal.add(priceExpedited).multiply(finalMultiplier);

        return PriceEstimateVO.builder()
                .estPrice(estPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceRadio(finalMultiplier.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceBase(priceBase.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceTime(priceTime.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceDistance(priceDistance.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceExpedited(priceExpedited.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .build();
    }
    private static BigDecimal requiredNonnegative(String value, String field) {
        try {
            BigDecimal number = new BigDecimal(value.trim());
            if (number.signum() < 0) throw new IllegalArgumentException();
            return number;
        } catch (RuntimeException e) { throw new BusinessException(502, "路径规划返回的" + field + "无效", e); }
    }
}
