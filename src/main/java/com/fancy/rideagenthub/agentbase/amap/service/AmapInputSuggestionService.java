package com.fancy.rideagenthub.agentbase.amap.service;

import com.fancy.rideagenthub.agentbase.amap.config.AmapProperties;
import com.fancy.rideagenthub.agentbase.amap.pojo.suggestion.AmapInputTip;
import com.fancy.rideagenthub.agentbase.amap.pojo.suggestion.AmapInputTipsResponse;
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
public class AmapInputSuggestionService {

	private final RestClient amapRestClient;
	private final AmapProperties properties;

	public AmapInputSuggestionService(@Qualifier("amapRestClient") RestClient amapRestClient,
									  AmapProperties properties) {
		this.amapRestClient = amapRestClient;
		this.properties = properties;
	}

	/**
	 * 输入提示
	 * 文档: https://lbs.amap.com/api/webservice/guide/api/inputtips
	 *
	 * 仅支持：keywords、city、location、citylimit
	 */
	public Optional<List<AmapInputTip>> inputTips(String keywords,
												  String city,
												  String location,
												  Boolean cityLimit) {
		ensureKeyConfigured();
		if (keywords == null || keywords.isBlank()) {
			throw new BusinessException(400, "keywords 不能为空");
		}

		AmapInputTipsResponse response;
		try {
			response = amapRestClient.get()
					.uri(uriBuilder -> {
						var b = uriBuilder
								.path("/assistant/inputtips")
								.queryParam("key", properties.getKey())
								.queryParam("keywords", keywords)
								.queryParam("output", "json");
						if (city != null && !city.isBlank()) {
							b.queryParam("city", city);
						}
						if (location != null && !location.isBlank()) {
							b.queryParam("location", location);
						}
						if (cityLimit != null) {
							b.queryParam("citylimit", cityLimit);
						}
						return b.build();
					})
					.retrieve()
					.onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
						throw new BusinessException(502,
								"高德 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
					})
					.body(AmapInputTipsResponse.class);
		} catch (RestClientException e) {
			throw new BusinessException(502, "高德 API 调用异常(inputtips)", e);
		}

		if (response == null) {
			return Optional.empty();
		}
		if (!response.isSuccess()) {
			log.warn("高德 API 业务失败(inputtips): info={}, infocode={}", response.info(), response.infocode());
			return Optional.empty();
		}

		List<AmapInputTip> tips = response.tips();
		if (tips == null || tips.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(tips);
	}

	/**
	 * 输入提示（经纬度格式化）
	 */
	public Optional<List<AmapInputTip>> inputTips(String keywords,
												  String city,
												  double longitude,
												  double latitude,
												  Boolean cityLimit) {
		return inputTips(keywords, city, formatLngLat(longitude, latitude), cityLimit);
	}

    public Optional<List<AmapInputTip>> inputTips(String keywords) {
        return inputTips(keywords, null, null, null);
    }

	private void ensureKeyConfigured() {
		if (properties.getKey() == null || properties.getKey().isBlank()) {
			throw new BusinessException(500, "amap.key 未配置");
		}
	}

	private static String formatLngLat(double longitude, double latitude) {
		return String.format("%.6f,%.6f", longitude, latitude);
	}
}
