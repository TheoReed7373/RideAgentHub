package com.fancy.rideagenthub.agentbase.qweather.service;
/**
 * 内含预警、空气质量API
 */

import com.fancy.rideagenthub.agentbase.qweather.config.QweatherProperties;
import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherAirQualityResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherWeatherAlertResponse;
import com.fancy.rideagenthub.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class QWeatherLifeService {

    private final RestClient qweatherRestClient;
    private final QweatherProperties properties;

    public QWeatherLifeService(@Qualifier("qweatherRestClient") RestClient qweatherRestClient,
                              QweatherProperties properties) {
        this.qweatherRestClient = qweatherRestClient;
        this.properties = properties;
    }

    /**
     * 实时天气预警
     * 文档：https://dev.qweather.com/docs/api/warning/weather-alert/
     */
    public Optional<QweatherWeatherAlertResponse> currentWeatherAlerts(double latitude,
                                                                       double longitude) {
        validateToken();

        String lat = String.format(Locale.ROOT, "%.2f", latitude);
        String lon = String.format(Locale.ROOT, "%.2f", longitude);

        QweatherWeatherAlertResponse response;
        try {
            response = qweatherRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/weatheralert/v1/current/{latitude}/{longitude}")
                            .queryParam("key", properties.getKey())
                            .queryParam("localTime", true)
                            .build(lat, lon))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(QweatherWeatherAlertResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "和风天气 预警信息 调用异常", e);
        }

        if (response == null) {
            return Optional.empty();
        }
        return Optional.of(response);
    }

    /**
     * 实时空气质量
     * 文档：https://dev.qweather.com/docs/api/air-quality/air-current/
     */
    public Optional<QweatherAirQualityResponse> currentAirQuality(double latitude,
                                                                  double longitude) {
        validateToken();

        String lat = String.format(Locale.ROOT, "%.2f", latitude);
        String lon = String.format(Locale.ROOT, "%.2f", longitude);

        QweatherAirQualityResponse response;
        try {
            response = qweatherRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/airquality/v1/current/{latitude}/{longitude}")
                            .queryParam("key", properties.getKey())
                            .build(lat, lon))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(QweatherAirQualityResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "和风天气 实时空气质量 调用异常", e);
        }

        if (response == null) {
            return Optional.empty();
        }
        return Optional.of(response);
    }

    private void validateToken() {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new BusinessException(500, "qweather.url 未配置");
        }
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new BusinessException(500, "qweather.key 未配置");
        }
    }
}
