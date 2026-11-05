package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.agentbase.amap.pojo.search.AmapSearchPoi;
import com.fancy.rideagenthub.agentbase.amap.service.AmapSearchService;
import com.fancy.rideagenthub.domain.vo.POISearchInfoVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
@Slf4j
public class POIAroundTool {
    @Resource
    private AmapSearchService amapSearchService;

    @Resource
    private ObjectMapper objectMapper;

    @Tool(description = "根据关键词搜索某坐标附近的兴趣点")
    public String searchAroundPOI(@ToolParam(description = "经度") double longitude,
                                  @ToolParam(description = "纬度") double latitude,
                                  @ToolParam(description = "关键词") String keywords,
                                  @ToolParam(description = "半径(m)") Integer radius,
                                  ToolContext toolContext){
        log.info("[Tool Calling]: searchAroundPOI(): longitude={}, latitude={}, keywords={}, radius={}", longitude, latitude, keywords, radius);
        ToolNotifySupport.notifyToolListener(toolContext, "搜索某坐标附近的兴趣点 (searchAroundPOI)");

        // 转成 List<POISearchInfoVO> 再 toString。
        List<POISearchInfoVO> result = amapSearchService.searchAround(longitude, latitude, keywords, radius)
                .orElse(Collections.emptyList())
                .stream()
                .filter(Objects::nonNull)
                .limit(10)
                .map(this::toPoiSearchInfoVOWithDistance)
                .filter(Objects::nonNull)
                .toList();
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("POIAroundTool JSON 序列化失败，fallback toString", e);
            return result.toString();
        }
    }

    private POISearchInfoVO toPoiSearchInfoVOWithDistance(AmapSearchPoi poi) {
        if (poi == null) {
            return null;
        }
        POISearchInfoVO vo = new POISearchInfoVO();
        vo.setName(poi.name());
        vo.setType(poi.type());
        vo.setAddress(poi.address());
        vo.setDistance(poi.distance());

        BigDecimal[] lngLat = parseLngLat(poi.location());
        if (lngLat == null || lngLat.length != 2) {
            lngLat = parseLngLat(poi.entrLocation());
        }
        if (lngLat != null && lngLat.length == 2) {
            vo.setLongitude(lngLat[0]);
            vo.setLatitude(lngLat[1]);
        }
        return vo;
    }

    /**
     * 高德 location/entr_location 格式通常为 "lng,lat"。
     */
    private BigDecimal[] parseLngLat(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            BigDecimal lng = new BigDecimal(parts[0].trim());
            BigDecimal lat = new BigDecimal(parts[1].trim());
            return new BigDecimal[]{lng, lat};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}

