package com.fancy.rideagenthub.agentbase.qweather.service;

import com.fancy.rideagenthub.agentbase.qweather.config.QweatherProperties;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherDaily;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherDailyResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherHourly;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherHourlyResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherMinutelyResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherNow;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherNowResponse;
import com.fancy.rideagenthub.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class QweatherWeatherService {

    private final RestClient qweatherRestClient;
    private final QweatherProperties properties;

    public QweatherWeatherService(@Qualifier("qweatherRestClient") RestClient qweatherRestClient,
                                 QweatherProperties properties) {
        this.qweatherRestClient = qweatherRestClient;
        this.properties = properties;
    }


    /**
     * 实时天气
     * 文档: https://dev.qweather.com/docs/api/weather/weather-now/
     */
    public Optional<QweatherNow> currentWeather(String location) {
        validateKeyAndLocation(location);

        QweatherNowResponse response;
        try {
            response = qweatherRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v7/weather/now")
                            .queryParam("key", properties.getKey())
                            .queryParam("location", location)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(QweatherNowResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "和风天气 实时天气 调用异常", e);
        }

        if (response == null || !response.isSuccess()) {
            log.warn("和风天气 实时天气 业务失败: code={}", response != null ? response.code() : "null");
            return Optional.empty();
        }
        return Optional.ofNullable(response.now());
    }

    /**
     * 每日天气预报 - 3d
     * @param location 以英文逗号分隔的经度,纬度坐标（十进制，最多支持小数点后两位）
     * 文档：https://dev.qweather.com/docs/api/weather/weather-daily-forecast/
     * @return
     */
    public Optional<List<QweatherDaily>> get3dayWeatherForecast(String location) {
        validateKeyAndLocation(location);

        QweatherDailyResponse response;
        try {
            response = qweatherRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v7/weather/3d")
                            .queryParam("key", properties.getKey())
                            .queryParam("location", location)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(QweatherDailyResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "和风天气 每日预报(3d) 调用异常", e);
        }

        if (response == null || !response.isSuccess()) {
            log.warn("和风天气 每日预报(3d) 业务失败: code={}", response != null ? response.code() : "null");
            return Optional.empty();
        }
        List<QweatherDaily> daily = response.daily();
        if (daily == null || daily.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(daily);
    }

    /**
     * 逐小时天气预报 - 24h
     * 文档：https://dev.qweather.com/docs/api/weather/weather-hourly-forecast/
     */
    public Optional<List<QweatherHourly>> get24hourWeatherForecast(String location) {
        validateKeyAndLocation(location);

        QweatherHourlyResponse response;
        try {
            response = qweatherRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v7/weather/24h")
                            .queryParam("key", properties.getKey())
                            .queryParam("location", location)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(QweatherHourlyResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "和风天气 逐小时预报(24h) 调用异常", e);
        }

        if (response == null || !response.isSuccess()) {
            log.warn("和风天气 逐小时预报(24h) 业务失败: code={}", response != null ? response.code() : "null");
            return Optional.empty();
        }
        List<QweatherHourly> hourly = response.hourly();
        if (hourly == null || hourly.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hourly);
    }

    /**
     * 获取2h内分钟级降雨
     * 文档：https://dev.qweather.com/docs/api/minutely/minutely-precipitation/
     * @param location 以英文逗号分隔的经度,纬度坐标（十进制，最多支持小数点后两位）
     */
    public Optional<QweatherMinutelyResponse> getRain2hForecast(String location) {
        validateKeyAndLocation(location);

        QweatherMinutelyResponse response;
        try {
            response = qweatherRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v7/minutely/5m")
                            .queryParam("key", properties.getKey())
                            .queryParam("location", location)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        throw new BusinessException(502,
                                "和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
                    })
                    .body(QweatherMinutelyResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(502, "和风天气 分钟级降水(2h) 调用异常", e);
        }

        if (response == null || !response.isSuccess()) {
            log.warn("和风天气 分钟级降水(2h) 业务失败: code={}", response != null ? response.code() : "null");
            return Optional.empty();
        }
        return Optional.of(response);
    }

    public Optional<QweatherNow> currentWeatherByLatLon(double lat, double lon){
        String lat2f = String.format(Locale.ROOT, "%.2f", lat);
        String lon2f = String.format(Locale.ROOT, "%.2f", lon);
        return currentWeather(lon2f + "," + lat2f);
    }

    public Optional<List<QweatherDaily>> get3dayForecastByLatLon(double lat, double lon) {
        String lat2f = String.format(Locale.ROOT, "%.2f", lat);
        String lon2f = String.format(Locale.ROOT, "%.2f", lon);
        return get3dayWeatherForecast(lon2f + "," + lat2f);
    }

    public Optional<List<QweatherHourly>> get24hourForecastByLatLon(double lat, double lon) {
        String lat2f = String.format(Locale.ROOT, "%.2f", lat);
        String lon2f = String.format(Locale.ROOT, "%.2f", lon);
        return get24hourWeatherForecast(lon2f + "," + lat2f);
    }

    public Optional<QweatherMinutelyResponse> getRain2hForecastByLatLon(double lat, double lon) {
        String lat2f = String.format(Locale.ROOT, "%.2f", lat);
        String lon2f = String.format(Locale.ROOT, "%.2f", lon);
        return getRain2hForecast(lon2f + "," + lat2f);
    }

    private void validateKeyAndLocation(String location) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new BusinessException(500, "qweather.key 未配置");
        }
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new BusinessException(500, "qweather.url 未配置");
        }
        if (location == null || location.isBlank()) {
            throw new BusinessException(400, "location 不能为空");
        }
    }
}
