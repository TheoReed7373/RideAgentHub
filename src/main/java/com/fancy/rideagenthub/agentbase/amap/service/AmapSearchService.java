package com.fancy.rideagenthub.agentbase.amap.service;

import com.fancy.rideagenthub.agentbase.amap.config.AmapProperties;
import com.fancy.rideagenthub.agentbase.amap.pojo.search.AmapPlaceSearchResponse;
import com.fancy.rideagenthub.agentbase.amap.pojo.search.AmapSearchPoi;
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
public class AmapSearchService {

	private final RestClient amapRestClient;
	private final AmapProperties properties;

	public AmapSearchService(@Qualifier("amapRestClient") RestClient amapRestClient,
							 AmapProperties properties) {
		this.amapRestClient = amapRestClient;
		this.properties = properties;
	}

	/**
	 * 关键字搜索（POI 文本搜索）
	 * 文档: https://lbs.amap.com/api/webservice/guide/api/search
	 */
	public Optional<List<AmapSearchPoi>> searchByKeyword(String keywords, String city) {
		return searchByKeyword(keywords, null, city, null, null, null);
	}

	/**
	 * 关键字搜索（keywords/types 二选一）
	 *
	 * @param keywords   查询关键字，与 types 二选一必填
	 * @param types      POI 类型，与 keywords 二选一必填（多个用 "|" 分隔）
	 * @param city       查询城市（中文/citycode/adcode）
	 * @param cityLimit  仅返回指定城市数据
	 * @param offset     每页记录（建议 <= 25，默认 20）
	 * @param page       页码（默认 1）
	 */
	public Optional<List<AmapSearchPoi>> searchByKeyword(String keywords,
														String types,
														String city,
														Boolean cityLimit,
														Integer offset,
														Integer page) {
		ensureKeyConfigured();
		boolean keywordsBlank = keywords == null || keywords.isBlank();
		boolean typesBlank = types == null || types.isBlank();
		if (keywordsBlank && typesBlank) {
			throw new BusinessException(400, "keywords 与 types 至少填写一个");
		}

		AmapPlaceSearchResponse response;
		try {
			response = amapRestClient.get()
					.uri(uriBuilder -> {
						var b = uriBuilder
								.path("/place/text")
								.queryParam("key", properties.getKey())
								.queryParam("output", "json")
								.queryParam("extensions", "base");
						if (!keywordsBlank) {
							b.queryParam("keywords", keywords);
						}
						if (!typesBlank) {
							b.queryParam("types", types);
						}
						if (city != null && !city.isBlank()) {
							b.queryParam("city", city);
						}
						if (cityLimit != null) {
							b.queryParam("citylimit", cityLimit);
						}
						b.queryParam("offset", normalizeOffset(offset));
						b.queryParam("page", normalizePage(page));
						return b.build();
					})
					.retrieve()
					.onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
						throw new BusinessException(502,
								"高德 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
					})
					.body(AmapPlaceSearchResponse.class);
		} catch (RestClientException e) {
			String diag = buildDiagnosticMessage(e);
			log.warn("高德 API 调用异常(KeywordPOI): {}", diag, e);
			throw new BusinessException(502, "高德 API 调用异常", e);
		}

		if (response == null) {
			return Optional.empty();
		}
		if (!response.isSuccess()) {
			log.warn("高德 API 业务失败(place/text): info={}, infocode={}", response.info(), response.infocode());
			return Optional.empty();
		}
		List<AmapSearchPoi> pois = response.pois();
		if (pois == null || pois.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(pois);
	}

	/**
	 * 周边搜索
	 * 文档: https://lbs.amap.com/api/webservice/guide/api/search
	 */
	public Optional<List<AmapSearchPoi>> searchAround(double longitude,
													  double latitude,
													  String keywords,
													  Integer radius) {
		return searchAround(formatLngLat(longitude, latitude), keywords, null, null, radius, null, null, null);
	}

	/**
	 * 周边搜索（支持常用可选参数）
	 *
	 * @param location   中心点，经纬度: lng,lat
	 * @param keywords   查询关键字（可选）
	 * @param types      POI 类型（可选，多个用 "|" 分隔）
	 * @param city       查询城市（可选）
	 * @param radius     半径 0-50000（默认 5000）
	 * @param sortRule   distance/weight（默认 distance）
	 * @param offset     每页记录（默认 20）
	 * @param page       页码（默认 1）
	 */
	public Optional<List<AmapSearchPoi>> searchAround(String location,
													  String keywords,
													  String types,
													  String city,
													  Integer radius,
													  String sortRule,
													  Integer offset,
													  Integer page) {
		ensureKeyConfigured();
		if (location == null || location.isBlank()) {
			throw new BusinessException(400, "location 不能为空");
		}

		AmapPlaceSearchResponse response;
		try {
			response = amapRestClient.get()
					.uri(uriBuilder -> {
						var b = uriBuilder
								.path("/place/around")
								.queryParam("key", properties.getKey())
								.queryParam("location", location)
								.queryParam("radius", normalizeRadius(radius))
								.queryParam("sortrule", normalizeSortRule(sortRule))
								.queryParam("extensions", "base");
						if (keywords != null && !keywords.isBlank()) {
							b.queryParam("keywords", keywords);
						}
						if (types != null && !types.isBlank()) {
							b.queryParam("types", types);
						}
						if (city != null && !city.isBlank()) {
							b.queryParam("city", city);
						}
						b.queryParam("offset", normalizeOffset(offset));
						b.queryParam("page", normalizePage(page));
						return b.build();
					})
					.retrieve()
					.onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
						throw new BusinessException(502,
								"高德 API HTTP 请求失败: " + resp.getStatusCode() + " " + resp.getStatusText());
					})
					.body(AmapPlaceSearchResponse.class);
		} catch (RestClientException e) {
			String diag = buildDiagnosticMessage(e);
			log.warn("高德 API 调用异常(AroundPOI): {}", diag, e);
			throw new BusinessException(502, "高德 API 调用异常", e);
		}

		if (response == null) {
			return Optional.empty();
		}
		if (!response.isSuccess()) {
			log.warn("高德 API 业务失败(place/around): info={}, infocode={}", response.info(), response.infocode());
			return Optional.empty();
		}
		List<AmapSearchPoi> pois = response.pois();
		if (pois == null || pois.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(pois);
	}

	private void ensureKeyConfigured() {
		if (properties.getKey() == null || properties.getKey().isBlank()) {
			throw new BusinessException(500, "amap.key 未配置");
		}
	}

	private static String formatLngLat(double longitude, double latitude) {
		return String.format("%.6f,%.6f", longitude, latitude);
	}

	private static int normalizeOffset(Integer offset) {
		if (offset == null) {
			return 20;
		}
		if (offset < 1) {
			return 1;
		}
		return Math.min(offset, 25);
	}

	private static int normalizePage(Integer page) {
		if (page == null || page < 1) {
			return 1;
		}
		return page;
	}

	private static int normalizeRadius(Integer radius) {
		if (radius == null) {
			return 5000;
		}
		if (radius < 0) {
			return 0;
		}
		return Math.min(radius, 50000);
	}

	private static String normalizeSortRule(String sortRule) {
		if (sortRule == null || sortRule.isBlank()) {
			return "distance";
		}
		String v = sortRule.trim();
		if ("weight".equalsIgnoreCase(v)) {
			return "weight";
		}
		return "distance";
	}

	private static String buildDiagnosticMessage(Throwable e) {
		if (e == null) {
			return "unknown";
		}
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String top = e.getClass().getSimpleName() + (e.getMessage() != null ? (": " + e.getMessage()) : "");
		String bottom = root == e ? null : (root.getClass().getSimpleName() + (root.getMessage() != null ? (": " + root.getMessage()) : ""));
		if (bottom == null || bottom.isBlank()) {
			return top;
		}
		return top + " | root=" + bottom;
	}
}
