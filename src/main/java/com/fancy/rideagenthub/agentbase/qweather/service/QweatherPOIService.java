package com.fancy.rideagenthub.agentbase.qweather.service;

import com.fancy.rideagenthub.agentbase.qweather.config.QweatherProperties;
import com.fancy.rideagenthub.agentbase.qweather.pojo.poi.QweatherPoi;
import com.fancy.rideagenthub.agentbase.qweather.pojo.poi.QweatherPoiResponse;
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
public class QweatherPOIService {

	private final RestClient qweatherRestClient;
	private final QweatherProperties properties;

	public QweatherPOIService(@Qualifier("qweatherRestClient") RestClient qweatherRestClient,
							  QweatherProperties properties) {
		this.qweatherRestClient = qweatherRestClient;
		this.properties = properties;
	}

	/**
	 * POI搜索
	 * 文档: https://dev.qweather.com/docs/api/geoapi/poi-lookup/
	 */
	public Optional<List<QweatherPoi>> lookup(String location,
											  String type,
											  String city,
											  Integer number,
											  String lang) {
		validateKey();
		if (location == null || location.isBlank()) {
			throw new BusinessException(400, "location 不能为空");
		}
		if (type == null || type.isBlank()) {
			throw new BusinessException(400, "type 不能为空");
		}

		QweatherPoiResponse response;
		try {
			response = qweatherRestClient.get()
					.uri(uriBuilder -> {
						var b = uriBuilder
								.path("/geo/v2/poi/lookup")
								.queryParam("key", properties.getKey())
								.queryParam("location", location)
								.queryParam("type", type);
						if (city != null && !city.isBlank()) {
							b.queryParam("city", city);
						}
						if (number != null) {
							b.queryParam("number", number);
						}
						if (lang != null && !lang.isBlank()) {
							b.queryParam("lang", lang);
						}
						return b.build();
					})
					.retrieve()
					.onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
						throw new BusinessException(502,
								"和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
					})
					.body(QweatherPoiResponse.class);
		} catch (RestClientException e) {
			throw new BusinessException(502, "和风天气 POI搜索 调用异常", e);
		}

		return extractPoiOrEmpty("lookup", response);
	}

	/**
	 * POI范围搜索
	 * 文档: https://dev.qweather.com/docs/api/geoapi/poi-range/
	 */
	public Optional<List<QweatherPoi>> range(double longitude,
											 double latitude,
											 String type,
											 Integer radiusKm,
											 Integer number,
											 String lang) {
		validateKey();
		if (type == null || type.isBlank()) {
			throw new BusinessException(400, "type 不能为空");
		}

		// 文档建议最多保留两位小数
		String location = String.format(Locale.ROOT, "%.2f,%.2f", longitude, latitude);

		QweatherPoiResponse response;
		try {
			response = qweatherRestClient.get()
					.uri(uriBuilder -> {
						var b = uriBuilder
								.path("/geo/v2/poi/range")
								.queryParam("key", properties.getKey())
								.queryParam("location", location)
								.queryParam("type", type);
						if (radiusKm != null) {
							b.queryParam("radius", radiusKm);
						}
						if (number != null) {
							b.queryParam("number", number);
						}
						if (lang != null && !lang.isBlank()) {
							b.queryParam("lang", lang);
						}
						return b.build();
					})
					.retrieve()
					.onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
						throw new BusinessException(502,
								"和风天气 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
					})
					.body(QweatherPoiResponse.class);
		} catch (RestClientException e) {
			throw new BusinessException(502, "和风天气 POI范围搜索 调用异常", e);
		}

		return extractPoiOrEmpty("range", response);
	}

	private void validateKey() {
		if (properties.getKey() == null || properties.getKey().isBlank()) {
			throw new BusinessException(500, "qweather.key 未配置");
		}
		if (properties.getUrl() == null || properties.getUrl().isBlank()) {
			throw new BusinessException(500, "qweather.url 未配置");
		}
	}

	private Optional<List<QweatherPoi>> extractPoiOrEmpty(String apiName, QweatherPoiResponse response) {
		if (response == null) {
			return Optional.empty();
		}
		if (!response.isSuccess()) {
			log.warn("和风天气 POI API 业务失败({}): code={}", apiName, response.code());
			return Optional.empty();
		}
		List<QweatherPoi> poi = response.poi();
		if (poi == null || poi.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(poi);
	}
}
