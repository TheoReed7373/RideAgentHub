package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.Regeocode;
import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.rideagenthub.agentbase.amap.util.citycode.CityCodeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
public class RegeoTool {

    private final AmapGeoRegeoService amapGeoRegeoService;

    public RegeoTool(AmapGeoRegeoService amapGeoRegeoService,CityCodeUtil cityCodeUtil) {
        this.amapGeoRegeoService = amapGeoRegeoService;
    }

    @Tool(description = "逆地理编码：根据经纬度获取结构化地址")
    public String regeo(
            @ToolParam(description = "经度，例如 116.397499") double longitude,
            @ToolParam(description = "纬度，例如 39.908722") double latitude,
            ToolContext toolContext) {
        log.info("[Tool Calling]: regeo(): lon={}, lat={}", longitude, latitude);
        ToolNotifySupport.notifyToolListener(toolContext, "逆地理编码 (regeo)");
        Optional<Regeocode> regeo = amapGeoRegeoService.getRegeo(longitude, latitude);
        if (regeo.isEmpty()) {
            return "未查询到地址";
        }
        Regeocode r = regeo.get();
        return "结构化地址：" + r.formattedAddress();
    }

}

