package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.Geocode;
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
public class GeoRegeoTool {

	private final AmapGeoRegeoService amapGeoRegeoService;
	private final CityCodeUtil cityCodeUtil;

	public GeoRegeoTool(AmapGeoRegeoService amapGeoRegeoService,CityCodeUtil cityCodeUtil) {
		this.amapGeoRegeoService = amapGeoRegeoService;
		this.cityCodeUtil = cityCodeUtil;
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
		return "formatted_address=" + r.formattedAddress();
	}

	@Tool(description = "地理编码：根据地址获取经纬度坐标")
	public String geo(
			@ToolParam(description = "结构化地址，例如 北京市朝阳区阜通东大街6号；接受城市名+地点名，例如 成都市电子科技大学（沙河校区）") String address,
			@ToolParam(required = false, description = "可选：指定查询城市，例如 北京/北京市；不填则全国检索") String city,
			ToolContext toolContext) {
		log.info("[Tool Calling]: geo(): address={}, city={}", address, city);
		ToolNotifySupport.notifyToolListener(toolContext, "地理编码 (geo)");

		String cityCode = cityCodeUtil.getCityCode(city);
		Optional<Geocode> geo = amapGeoRegeoService.getGeo(address, cityCode);
		if (geo.isEmpty()) {
			return "未查询到坐标";
		}
		Geocode g = geo.get();
		return "location=" + g.location() + ", level=" + g.level() + ", formatted_address=" + g.formattedAddress();
	}

}

