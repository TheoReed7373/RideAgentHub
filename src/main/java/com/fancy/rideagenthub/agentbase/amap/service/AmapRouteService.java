package com.fancy.rideagenthub.agentbase.amap.service;

import com.fancy.rideagenthub.agentbase.amap.config.AmapProperties;
import com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapDrivingRouteResponse;
import com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapRoute;
import com.fancy.rideagenthub.domain.vo.EstRouteVO;
import com.fancy.rideagenthub.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

@Service
@Slf4j
public class AmapRouteService {

    public enum Extension {
        BASE("base"),
        ALL("all");

        private final String paramValue;

        Extension(String paramValue) {
            this.paramValue = paramValue;
        }

        public String paramValue() {
            return paramValue;
        }
    }

    private final RestClient amapRestClient;
    private final AmapProperties properties;

    public AmapRouteService(@Qualifier("amapRestClient") RestClient amapRestClient,
                            AmapProperties properties) {
        this.amapRestClient = amapRestClient;
        this.properties = properties;
    }

    /**
     * 驾车路径规划
     * 文档: https://lbs.amap.com/api/webservice/guide/api/direction
     */
    public Optional<AmapRoute> getDrivingRoute(double originLon,
                                               double originLat,
                                               double destLon,
                                               double destLat) {
        String origin = formatLngLat(originLon, originLat);
        String destination = formatLngLat(destLon, destLat);
        return getDrivingRoute(origin, destination, 10);
    }

    public Optional<AmapRoute> getDrivingRoute(double originLon,
                                               double originLat,
                                               double destLon,
                                               double destLat,
                                               Integer strategy,
                                               Extension extensions,
                                               boolean roadAggregation) {
        String origin = formatLngLat(originLon, originLat);
        String destination = formatLngLat(destLon, destLat);
        return getDrivingRoute(origin, destination, strategy, extensions, roadAggregation);
    }

    /**
     * 驾车路径规划（支持常用可选参数）
     *
     * @param origin      出发点，经纬度: lng,lat
     * @param destination 目的地，经纬度: lng,lat
     * @param strategy    驾车选择策略
     */
    public Optional<AmapRoute> getDrivingRoute(String origin,
                                               String destination,
                                               Integer strategy) {
        // 兼容旧行为：extensions=base + roadaggregation=true
        return getDrivingRoute(origin, destination, strategy, Extension.BASE, true);
    }

    /**
     * 驾车路径规划（四种组合：extensions=base/all × roadaggregation=true/false）
     */
    public Optional<AmapRoute> getDrivingRoute(String origin,
                                               String destination,
                                               Integer strategy,
                                               Extension extensions,
                                               boolean roadAggregation) {
        AmapDrivingRouteResponse response = fetchDrivingRouteResponse(origin, destination, strategy, extensions, roadAggregation);
        if (response == null || !response.isSuccess()) {
            if (response != null) {
                log.warn("高德 API 业务失败(route planning): info={}, infocode={}", response.info(), response.infocode());
            }
            return Optional.empty();
        }
        return Optional.ofNullable(response.route());
    }

    /**
     * 驾车路径规划封装（返回 EstRouteVO）
     * - 默认 extensions=all
     * - 不使用路径聚合 roadaggregation=false
     * - 入参仅起终点经纬度
     * - 失败重试 3 次，仍失败抛异常
     *
     * 约定：
     * - estRoute = AmapDrivingRouteResponse#toString()（整个返回对象字符串）
     * - estPolyline = 从返回实体中收集所有 polyline 字段后用 ";" 拼接
     * - estKm = distance/1000
     */
    public EstRouteVO getDrivingRouteEst(double originLon,
                                        double originLat,
                                        double destLon,
                                        double destLat) {
        String origin = formatLngLat(originLon, originLat);
        String destination = formatLngLat(destLon, destLat);

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                AmapDrivingRouteResponse response = fetchDrivingRouteResponse(origin, destination, 10, Extension.ALL, false);
                if (response == null) {
                    throw new BusinessException(502, "高德 API 响应为空");
                }
                if (!response.isSuccess()) {
                    throw new BusinessException(502,
                            "高德 API 业务失败: info=" + response.info() + ", infocode=" + response.infocode());
                }

                AmapRoute route = response.route();
                if (route == null || route.paths() == null || route.paths().isEmpty()) {
                    throw new BusinessException(502, "高德 API route 为空");
                }

                // 只使用第一条路线的结果
                var firstPath = route.paths().get(0);

                String polyline = joinPathPolylines(firstPath);
                if (polyline.isBlank()) {
                    throw new BusinessException(502, "高德 API polyline 为空");
                }

                String km = calcKm(firstPath);
                String time = firstPath.duration();

                // 构建只包含第一条路径的 route，用于 estRoute
                AmapRoute firstOnlyRoute = new AmapRoute(
                        route.origin(),
                        route.destination(),
                        route.taxiCost(),
                        List.of(firstPath)
                );

                EstRouteVO vo = new EstRouteVO();
                vo.setEstRoute(firstOnlyRoute.toString());
                vo.setEstPolyline(polyline);
                vo.setEstKm(km);
                vo.setEstTime(time);
                return vo;
            } catch (BusinessException e) {
                last = e;
                if (attempt < 3 && e.getCode() >= 500) {
                    log.warn("高德路径规划失败，准备重试({}/3): {}", attempt, e.getMessage());
                    continue;
                }
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < 3) {
                    log.warn("高德路径规划异常，准备重试({}/3): {}", attempt, e.getMessage());
                    continue;
                }
                throw new BusinessException(502, "高德路径规划失败(重试3次仍失败)", e);
            }
        }

        throw new BusinessException(502, "高德路径规划失败(重试3次仍失败)", last);
    }

    public Optional<AmapRoute> getDrivingRouteBaseNoAggregation(String origin, String destination, Integer strategy) {
        return getDrivingRoute(origin, destination, strategy, Extension.BASE, false);
    }

    public Optional<AmapRoute> getDrivingRouteAllNoAggregation(String origin, String destination, Integer strategy) {
        return getDrivingRoute(origin, destination, strategy, Extension.ALL, false);
    }

    public Optional<AmapRoute> getDrivingRouteBaseAggregated(String origin, String destination, Integer strategy) {
        return getDrivingRoute(origin, destination, strategy, Extension.BASE, true);
    }

    public Optional<AmapRoute> getDrivingRouteAllAggregated(String origin, String destination, Integer strategy) {
        return getDrivingRoute(origin, destination, strategy, Extension.ALL, true);
    }

    private AmapDrivingRouteResponse fetchDrivingRouteResponse(String origin,
                                                              String destination,
                                                              Integer strategy,
                                                              Extension extensions,
                                                              boolean roadAggregation) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new BusinessException(500, "amap.key 未配置");
        }
        if (origin == null || origin.isBlank()) {
            throw new BusinessException(400, "origin 不能为空");
        }
        if (destination == null || destination.isBlank()) {
            throw new BusinessException(400, "destination 不能为空");
        }
        if (extensions == null) {
            throw new BusinessException(400, "extensions 不能为空");
        }

        log.info("amap driving route: origin={}, destination={}, strategy={}, extensions={}, roadaggregation={}",
                origin, destination, strategy, extensions.paramValue(), roadAggregation);

        try {
            return amapRestClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder
                                .path("/direction/driving")
                                .queryParam("key", properties.getKey())
                                .queryParam("origin", origin)
                                .queryParam("destination", destination)
                                .queryParam("extensions", extensions.paramValue())
                                .queryParam("output", "json");
                        if (roadAggregation) {
                            b.queryParam("roadaggregation", true);
                        }
                        if (strategy != null) {
                            b.queryParam("strategy", strategy);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "高德 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(AmapDrivingRouteResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "高德 API 调用异常", e);
        }
    }

    /**
     * 收集单条路径中所有 step 的 polyline，用 ";" 拼接。
     * <p>
     * 注意：step.polyline 与 tmcs[].polyline 是同一段路的不同表示，
     * step.polyline 是完整路径，tmcs 是按交通状态细分的片段，二者不应同时收集。
     * 这里只收集 step.polyline，避免重复。
     * <p>
     * 兼容两种返回结构：
     * - 不使用路径聚合：path.steps[].polyline
     * - 使用路径聚合：path.roads[].steps[].polyline
     */
    private static String joinPathPolylines(com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapPath path) {
        List<String> polylines = new ArrayList<>();

        if (path == null) {
            return "";
        }

        // 不使用路径聚合时，直接从 path.steps() 获取
        if (path.steps() != null) {
            for (var step : path.steps()) {
                if (step == null) {
                    continue;
                }
                addIfNotBlank(polylines, step.polyline());
            }
        }

        // 使用路径聚合时，从 path.roads()[].steps() 获取
        if (path.roads() != null) {
            for (var road : path.roads()) {
                if (road == null || road.steps() == null) {
                    continue;
                }
                for (var step : road.steps()) {
                    if (step == null) {
                        continue;
                    }
                    addIfNotBlank(polylines, step.polyline());
                }
            }
        }

        StringJoiner joiner = new StringJoiner(";");
        for (String p : polylines) {
            joiner.add(p);
        }
        return joiner.toString();
    }

    private static void addIfNotBlank(List<String> target, String value) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (!v.isEmpty()) {
            target.add(v);
        }
    }

    private static String calcKm(com.fancy.rideagenthub.agentbase.amap.pojo.route.AmapPath path) {
        String distanceMetersStr = path.distance();
        if (distanceMetersStr == null || distanceMetersStr.isBlank()) {
            throw new BusinessException(502, "高德 API distance 为空");
        }
        BigDecimal meters;
        try {
            meters = new BigDecimal(distanceMetersStr.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(502, "高德 API distance 非数字: " + distanceMetersStr, e);
        }

        BigDecimal km = meters.divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP);
        return km.toPlainString();
    }

    private static String formatLngLat(double longitude, double latitude) {
        return String.format("%.6f,%.6f", longitude, latitude);
    }
}
