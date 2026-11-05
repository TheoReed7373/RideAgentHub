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
public class POISearchTool {
    @Resource
    private AmapSearchService amapSearchService;

    @Resource
    private ObjectMapper objectMapper;

    @Tool(description = "根据关键词搜索兴趣点")
    public String searchPOIsByKeyword(@ToolParam(description = "关键词") String keyword,
                                      @ToolParam(required = false, description = "城市名") String city,
                                      ToolContext toolContext){
        log.info("[Tool Calling]: searchPOIsByKeyword(keyword={}, city={})", keyword, city);
        ToolNotifySupport.notifyToolListener(toolContext, "正在调用工具：[searchPOIsByKeyword]");
        // 转成 List<POISearchInfoVO> 再 toString，注意把 distance 字段忽略掉。这里返回值改为 String。
        List<POISearchInfoVO> result = amapSearchService.searchByKeyword(keyword, city)
                .orElse(Collections.emptyList())
                .stream()
                .filter(Objects::nonNull)
                .map(this::toPoiSearchInfoVOWithoutDistance)
                .filter(Objects::nonNull)
                .limit(10)
                .toList();
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("POISearchTool JSON 序列化失败，fallback toString", e);
            return result.toString();
        }
    }

    private POISearchInfoVO toPoiSearchInfoVOWithoutDistance(AmapSearchPoi poi) {
        if (poi == null) {
            return null;
        }
        POISearchInfoVO vo = new POISearchInfoVO();
        vo.setName(poi.name());
        vo.setType(poi.type());
        vo.setAddress(poi.address());

        BigDecimal[] lngLat = parseLngLat(poi.location());
        if (lngLat == null || lngLat.length != 2) {
            lngLat = parseLngLat(poi.entrLocation());
        }
        if (lngLat != null && lngLat.length == 2) {
            vo.setLongitude(lngLat[0]);
            vo.setLatitude(lngLat[1]);
        }
        // distance 字段刻意不设置
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

