package com.fancy.rideagenthub.agentbase.amap.service;

import com.fancy.rideagenthub.agentbase.amap.config.AmapProperties;
import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.AmapGeoResponse;
import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.AmapRegeoResponse;
import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.Geocode;
import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.Regeocode;
import com.fancy.rideagenthub.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AmapGeoRegeoService {

    private final RestClient amapRestClient;
    private final AmapProperties properties;

    public AmapGeoRegeoService(@Qualifier("amapRestClient") RestClient amapRestClient,
                               AmapProperties properties) {
        this.amapRestClient = amapRestClient;
        this.properties = properties;
    }

    /**
     * 逆地理编码查询
     * 文档: https://lbs.amap.com/api/webservice/guide/api/georegeo
     */
    public Optional<Regeocode> getRegeo(double longitude, double latitude) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new BusinessException(500, "amap.key 未配置");
        }
        // 经纬度格式：经度在前，纬度在后，逗号分隔
        String locationParam = String.format("%.6f,%.6f", longitude, latitude);

        log.debug("Calling Amap Regeo for location: {}", locationParam);

        AmapRegeoResponse response;
        try {
            response = amapRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/geocode/regeo")
                            .queryParam("location", locationParam)
                            .queryParam("key", properties.getKey())
                            .queryParam("radius", 1000)
                            .queryParam("extensions", "base")
                            .queryParam("output", "json")
                            .queryParam("roadlevel", 0)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "高德 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(AmapRegeoResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "高德 API 调用异常", e);
        }

        if (response != null && response.isSuccess()) {
            return Optional.ofNullable(response.regeocode());
        } else {
            log.warn("高德 API 业务失败: info={}, infocode={}",
                    response != null ? response.info() : "null",
                    response != null ? response.infocode() : "null");
            return Optional.empty();
        }
    }

    /**
     * 地理编码查询（地址 -> 经纬度）
     * 文档: https://lbs.amap.com/api/webservice/guide/api/georegeo
     */
    public Optional<Geocode> getGeo(String address, String city) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new BusinessException(500, "amap.key 未配置");
        }
        if (address == null || address.isBlank()) {
            throw new BusinessException(400, "address 不能为空");
        }

        AmapGeoResponse response;
        try {
            response = amapRestClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder
                                .path("/geocode/geo")
                                .queryParam("key", properties.getKey())
                                .queryParam("address", address)
                                .queryParam("output", "json");
                        if (city != null && !city.isBlank()) {
                            b.queryParam("city", city);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "高德 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(AmapGeoResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "高德 API 调用异常", e);
        }

        if (response == null) {
            return Optional.empty();
        }
        if (!response.isSuccess()) {
            log.warn("高德 API 业务失败(geo): info={}, infocode={}", response.info(), response.infocode());
            return Optional.empty();
        }

        List<Geocode> geocodes = response.geocodes();
        if (geocodes == null || geocodes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(geocodes.getFirst());
    }
}
