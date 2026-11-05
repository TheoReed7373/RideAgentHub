package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapPath;
import com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class DailyTool {
    private final AmapRouteService amapRouteService;

    @Tool(description = "估算前往某目的地需要预留的时间")
    public String estimateBufferTime(@ToolParam(description = "起点经度")double startLng,
                                     @ToolParam(description = "起点纬度")double startLat,
                                     @ToolParam(description = "终点经度")double endLng,
                                     @ToolParam(description = "终点纬度")double endLat,
                                     ToolContext toolContext){
        log.info("[Tool Calling]: estimateBufferTime(): startLng={}, startLat={}, endLng={}, endLat={}", startLng, startLat, endLng, endLat);
        ToolNotifySupport.notifyToolListener(toolContext, "估算前往某目的地需要预留的时间 (estimateBufferTime)");

        Optional<AmapRoute> routeOpt = amapRouteService.getDrivingRoute(
                startLng, startLat,
                endLng, endLat,
                10,
                AmapRouteService.Extension.ALL,
                false
        );

        if (routeOpt.isEmpty() || routeOpt.get().paths() == null || routeOpt.get().paths().isEmpty()) {
            return "暂时无法获取路径规划结果，无法估算需要预留的时间。";
        }

        AmapPath path = routeOpt.get().paths().get(0);
        long driveMinutes = ceilMinutesSafe(path == null ? null : path.duration());
        String kmText = calcKmSafe(path == null ? null : path.distance());

        LinkedHashSet<String> congestedRoads = new LinkedHashSet<>();
        if (path != null && path.steps() != null) {
            for (var step : path.steps()) {
                if (step == null) {
                    continue;
                }
                String road = normalizeRoad(step.road());
                if (road == null) {
                    continue;
                }

                if (containsCongestion(step.tmcs())) {
                    congestedRoads.add(road);
                    if (congestedRoads.size() > 5) {
                        String roadsText = formatManyRoads(new ArrayList<>(congestedRoads));
                        long suggested = driveMinutes + 30;
                        return "全程预计" + kmText + "km，约" + driveMinutes + "分钟。"
                                + roadsText + "等多个路段有堵车，建议预留" + suggested + "分钟（含25分钟机动+5分钟等车）。";
                    }
                }
            }
        }

        long suggested = driveMinutes + 20;
        if (congestedRoads.isEmpty()) {
            return "全程预计" + kmText + "km，约" + driveMinutes + "分钟。路况总体畅通，建议预留" + suggested + "分钟（含15分钟机动+5分钟等车）。";
        }

        String roadsText = String.join("、", congestedRoads);
        return "全程预计" + kmText + "km，约" + driveMinutes + "分钟。"
                + roadsText + "路段有堵车，建议预留" + suggested + "分钟（含15分钟机动+5分钟等车）。";
    }

    @Tool(description = "简单估算前往某目的地价格")
    public String estimatePrice(@ToolParam(description = "起点经度")double startLng,
                                @ToolParam(description = "起点纬度")double startLat,
                                @ToolParam(description = "终点经度")double endLng,
                                @ToolParam(description = "终点纬度")double endLat,
                                ToolContext toolContext){
        log.info("[Tool Calling]: estimatePrice(): startLng={}, startLat={}, endLng={}, endLat={}", startLng, startLat, endLng, endLat);
        ToolNotifySupport.notifyToolListener(toolContext, "简单估算前往某目的地价格 (estimatePrice)");

        Optional<AmapRoute> routeOpt = amapRouteService.getDrivingRoute(
                startLng, startLat,
                endLng, endLat,
                10,
                AmapRouteService.Extension.ALL,
                false
        );

        if (routeOpt.isEmpty() || routeOpt.get().paths() == null || routeOpt.get().paths().isEmpty()) {
            return "暂时无法获取路径规划结果，无法估算价格。";
        }

        AmapPath path = routeOpt.get().paths().get(0);
        String durationSeconds = path == null ? null : path.duration();
        String distanceMeters = path == null ? null : path.distance();

        long minutesLong = ceilMinutesSafe(durationSeconds);
        BigDecimal minutes = new BigDecimal(minutesLong);

        BigDecimal distanceKm = parseKmFromMeters(distanceMeters);
        String kmText = distanceKm.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();

        // 计价规则与 OrderTool#getEstPrice 保持一致：快车(倍率1.0)、不加急。
        final int MONEY_SCALE = 2;
        BigDecimal startPrice = new BigDecimal("8.00");
        BigDecimal startDistanceKm = new BigDecimal("2.0");
        BigDecimal perKmFee = new BigDecimal("2.20");
        BigDecimal perMinFee = new BigDecimal("0.50");
        BigDecimal longDistanceThresholdKm = new BigDecimal("20.0");

        BigDecimal overStartKm = distanceKm.subtract(startDistanceKm);
        if (overStartKm.signum() < 0) {
            overStartKm = BigDecimal.ZERO;
        }
        BigDecimal mileageFee = overStartKm.multiply(perKmFee);
        BigDecimal priceBase = startPrice.add(mileageFee);

        BigDecimal longOverKm = distanceKm.subtract(longDistanceThresholdKm);
        if (longOverKm.signum() < 0) {
            longOverKm = BigDecimal.ZERO;
        }
        BigDecimal longDistanceSurchargePerKm = perKmFee.multiply(new BigDecimal("0.50"));
        BigDecimal priceDistance = longOverKm.multiply(longDistanceSurchargePerKm);

        BigDecimal priceTime = minutes.multiply(perMinFee);

        BigDecimal estPrice = priceBase.add(priceTime).add(priceDistance)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return "全程预计" + kmText + "km，约" + minutes.toPlainString() + "分钟，预估费用约" + estPrice.toPlainString()
                + "元（按快车、不加急规则简单估算；实际费用以平台计价为准）。如果用户需要下单，请引导用户明确表示下单。";
    }

    private static BigDecimal parseKmFromMeters(String distanceMeters) {
        if (distanceMeters == null || distanceMeters.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal meters = new BigDecimal(distanceMeters.trim());
            if (meters.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            // 保留更高精度参与计算，展示时再四舍五入到2位。
            return meters.divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean containsCongestion(List<com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapTmc> tmcs) {
        if (tmcs == null || tmcs.isEmpty()) {
            return false;
        }
        for (var tmc : tmcs) {
            if (tmc == null) {
                continue;
            }
            String status = tmc.status();
            if (status == null || status.isBlank()) {
                continue;
            }
            String s = status.trim();
            if ("拥堵".equals(s) || "严重拥堵".equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeRoad(String road) {
        if (road == null) {
            return null;
        }
        String r = road.trim();
        return r.isEmpty() ? null : r;
    }

    private static String formatManyRoads(List<String> roads) {
        if (roads == null || roads.isEmpty()) {
            return "";
        }
        if (roads.size() == 1) {
            return roads.get(0) + "……";
        }
        String first = roads.get(0);
        String second = roads.get(1);
        String last = roads.get(roads.size() - 1);
        if (roads.size() == 2) {
            return first + "、" + second + "……";
        }
        return first + "、" + second + "……" + last;
    }

    private static long ceilMinutesSafe(String durationSeconds) {
        if (durationSeconds == null || durationSeconds.isBlank()) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(durationSeconds.trim());
            if (seconds <= 0) {
                return 0;
            }
            return (seconds + 59) / 60;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String calcKmSafe(String distanceMeters) {
        if (distanceMeters == null || distanceMeters.isBlank()) {
            return "0";
        }
        try {
            BigDecimal meters = new BigDecimal(distanceMeters.trim());
            BigDecimal km = meters.divide(new BigDecimal("1000"), 2, RoundingMode.HALF_UP);
            return km.toPlainString();
        } catch (NumberFormatException ignored) {
            return "0";
        }
    }



}

