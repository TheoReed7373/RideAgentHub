package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherAirIndex;
import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherAirPollutant;
import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherAirQualityResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherWeatherAlert;
import com.fancy.rideagenthub.agentbase.qweather.pojo.life.QweatherWeatherAlertResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherDaily;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherMinutely;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherMinutelyResponse;
import com.fancy.rideagenthub.agentbase.qweather.pojo.weather.QweatherNow;
import com.fancy.rideagenthub.agentbase.qweather.service.QWeatherLifeService;
import com.fancy.rideagenthub.agentbase.qweather.service.QweatherWeatherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
public class CurWeatherTool {
    @Resource
    private QweatherWeatherService qweatherWeatherService;
    @Resource
    private QWeatherLifeService qWeatherLifeService;

    @Tool(description = "根据经纬度获取当前天气信息")
    public String getWeatherByLatLon(
            @ToolParam(description = "纬度") double lat,
            @ToolParam(description = "经度") double lon,
            ToolContext toolContext) {
        log.info("[Tool Calling]: getWeatherByLatLon(): lat={}, lon={}", lat, lon);
        ToolNotifySupport.notifyToolListener(toolContext, "当前天气 (getWeatherByLatLon)");
        Optional<QweatherNow> qweatherNow = qweatherWeatherService.currentWeatherByLatLon(lat, lon);
        String result = """
                    当前位置气温：%s℃，体感温度%s℃，天气%s，%s级风。
                    """;
        QweatherNow now = qweatherNow.orElse(null);
        if(now == null){
            // 失败后重试一次
            Optional<QweatherNow> retry = qweatherWeatherService.currentWeatherByLatLon(lat, lon);
            QweatherNow nowRetry = retry.orElse(null);
            if (nowRetry == null) {
                return "工具未能获取天气数据";
            }
            return result.formatted(nowRetry.temp(), nowRetry.feelsLike(), nowRetry.text(), nowRetry.windScale());
        }else{
            return result.formatted(now.temp(), now.feelsLike(), now.text(), now.windScale());
        }
    }

    @Tool(description = "根据经纬度获取未来三天天气信息")
    public String get3dayWeatherForecastByLatLon(
            @ToolParam(description = "纬度") double lat,
            @ToolParam(description = "经度") double lon,
            ToolContext toolContext) {
        log.info("[Tool Calling]: get3dayWeatherForecastByLatLon(): lat={}, lon={}", lat, lon);
        ToolNotifySupport.notifyToolListener(toolContext, "未来三天预报 (get3dayWeatherForecastByLatLon)");
        Optional<List<QweatherDaily>> qweatherDailyOpt = qweatherWeatherService.get3dayForecastByLatLon(lat, lon);
        List<QweatherDaily> dailyList = qweatherDailyOpt.orElse(null);
        if (dailyList == null || dailyList.isEmpty()) {
            return "工具未能获取未来三天天气数据";
        }
        //2025-11-15，11℃~15℃，白天多云，夜间晴，预计降水0.5mm
        String toolResponse = """
                未来三天天气情况：
                %s
                """;
        String singleRep = "%s日，%s℃~%s℃，白天%s，夜间%s，预计降水量%smm。";
        StringBuilder sb = new StringBuilder();
        for (QweatherDaily daily : dailyList) {
            if (daily == null) {
                continue;
            }
            sb.append(singleRep.formatted(
                    daily.fxDate(),
                    daily.tempMin(),
                    daily.tempMax(),
                    daily.textDay(),
                    daily.textNight(),
                    daily.precip()
            )).append(System.lineSeparator());
        }
        if (sb.isEmpty()) {
            return "工具未能获取未来三天天气数据";
        }
        return toolResponse.formatted(sb.toString());
    }

    @Tool(description = "根据经纬度获取未来2小时降水信息")
    public String getRain2hForecastByLatLon(
            @ToolParam(description = "纬度") double lat,
            @ToolParam(description = "经度") double lon,
            ToolContext toolContext) {
        log.info("[Tool Calling]: getRain2hForecastByLatLon(): lat={}, lon={}", lat, lon);
        ToolNotifySupport.notifyToolListener(toolContext, "未来2小时降水 (getRain2hForecastByLatLon)");
        Optional<QweatherMinutelyResponse> respOpt = qweatherWeatherService.getRain2hForecastByLatLon(lat, lon);
        QweatherMinutelyResponse resp = respOpt.orElse(null);
        if (resp == null) {
            return "工具未能获取降水预报数据";
        }

        String toolResponse = "未来两小时降水信息：%s";
        
        // 优先使用官方summary
        if (resp.summary() != null && !resp.summary().isBlank()) {
            return toolResponse.formatted(resp.summary());
        }

        // 如果summary为null，自己总结降水情况
        List<QweatherMinutely> minutelyList = resp.minutely();
        if (minutelyList == null || minutelyList.isEmpty()) {
            return "未来两小时暂无降水预报数据";
        }

        // 分析降水数据变化趋势
        String summary = analyzePrecipitation(minutelyList);
        return toolResponse.formatted(summary);
    }

    /**
     * 分析降水数据，生成描述性文本
     */
    private String analyzePrecipitation(List<QweatherMinutely> minutelyList) {
        // 检查第一个时段是否有降水
        boolean isRainingNow = false;
        if (minutelyList.get(0) != null && minutelyList.get(0).precip() != null) {
            double firstPrecip = Double.parseDouble(minutelyList.get(0).precip());
            isRainingNow = firstPrecip > 0.0;
        }

        // 查找降水状态变化点
        String changeType = null; // "stop"表示停止，"start"表示开始
        int changeMinutes = -1;
        String precipType = "降水"; // 默认降水类型

        for (int i = 0; i < minutelyList.size(); i++) {
            QweatherMinutely minutely = minutelyList.get(i);
            if (minutely == null || minutely.precip() == null) {
                continue;
            }

            double precip = Double.parseDouble(minutely.precip());
            boolean hasRain = precip > 0.0;

            // 记录降水类型
            if (hasRain && minutely.type() != null) {
                precipType = "rain".equals(minutely.type()) ? "降雨" : 
                            "snow".equals(minutely.type()) ? "降雪" : "降水";
            }

            // 检测状态变化
            if (isRainingNow && !hasRain) {
                // 从有降水变为无降水
                changeType = "stop";
                changeMinutes = i * 5;
                break;
            } else if (!isRainingNow && hasRain) {
                // 从无降水变为有降水
                changeType = "start";
                changeMinutes = i * 5;
                break;
            }
        }

        // 生成描述文本
        if (changeType == null) {
            // 整个时段内状态不变
            if (isRainingNow) {
                return "未来两小时内将持续" + precipType;
            } else {
                return "未来两小时内无降水";
            }
        } else if ("stop".equals(changeType)) {
            return "未来" + changeMinutes + "分钟后" + precipType + "将停止";
        } else {
            return "未来" + changeMinutes + "分钟后将开始" + precipType;
        }
    }

    @Tool(description = "根据经纬度获取天气预警信息")
    public String getWeatherAlertsByLatLon(
            @ToolParam(description = "纬度") double lat,
            @ToolParam(description = "经度") double lon,
            ToolContext toolContext) {
        log.info("[Tool Calling]: getWeatherAlertsByLatLon(): lat={}, lon={}", lat, lon);
        ToolNotifySupport.notifyToolListener(toolContext, "天气预警 (getWeatherAlertsByLatLon)");
        Optional<QweatherWeatherAlertResponse> respOpt = qWeatherLifeService.currentWeatherAlerts(lat, lon);
        QweatherWeatherAlertResponse resp = respOpt.orElse(null);
        if (resp == null || resp.alerts() == null || resp.alerts().isEmpty()) {
            return "当前无天气预警";
        }

        List<QweatherWeatherAlert> alerts = resp.alerts();
        String toolResponse = """
                天气预警共%s条，以下是预警信息：
                %s
                """;
        StringBuilder sb = new StringBuilder();
        for(QweatherWeatherAlert alert : alerts){
            sb.append(alert.description()).append(System.lineSeparator());
        }
        return toolResponse.formatted(alerts.size(), sb.toString());
    }

    @Tool(description = "根据经纬度获取空气质量信息")
    public String getAirQualityByLatLon(
            @ToolParam(description = "纬度") double lat,
            @ToolParam(description = "经度") double lon,
            ToolContext toolContext) {
        log.info("[Tool Calling]: getAirQualityByLatLon(): lat={}, lon={}", lat, lon);
        ToolNotifySupport.notifyToolListener(toolContext, "空气质量 (getAirQualityByLatLon)");
        Optional<QweatherAirQualityResponse> respOpt = qWeatherLifeService.currentAirQuality(lat, lon);
        QweatherAirQualityResponse resp = respOpt.orElse(null);
        if (resp == null) {
            return "工具未能获取空气质量数据";
        }
        String toolResponse = """
                空气质量：%s，空气质量指数：%s，首要污染物：%s。
                """;
        // 取index的第一个AQI数据；primaryPollutant为null的情况下取pollutants列表下最高浓度的污染物
        List<QweatherAirIndex> indexes = resp.indexes();
        if (indexes == null || indexes.isEmpty() || indexes.get(0) == null) {
            return "工具未能获取空气质量数据";
        }

        QweatherAirIndex index = indexes.get(0);
        String category = index.category();
        String aqiDisplay = index.aqiDisplay();

        String primaryPollutantName = null;
        if (index.primaryPollutant() != null) {
            primaryPollutantName = index.primaryPollutant().fullName();
            if (primaryPollutantName == null || primaryPollutantName.isBlank()) {
                primaryPollutantName = index.primaryPollutant().name();
            }
        }

        if (primaryPollutantName == null || primaryPollutantName.isBlank()) {
            List<QweatherAirPollutant> pollutants = resp.pollutants();
            if (pollutants != null && !pollutants.isEmpty()) {
                QweatherAirPollutant maxPollutant = pollutants.stream()
                        .filter(Objects::nonNull)
                        .filter(p -> p.concentration() != null && p.concentration().value() != null)
                        .max((a, b) -> Double.compare(a.concentration().value(), b.concentration().value()))
                        .orElse(null);
                if (maxPollutant != null) {
                    primaryPollutantName = maxPollutant.fullName();
                    if (primaryPollutantName == null || primaryPollutantName.isBlank()) {
                        primaryPollutantName = maxPollutant.name();
                    }
                }
            }
        }

        if (primaryPollutantName == null || primaryPollutantName.isBlank()) {
            primaryPollutantName = "未知";
        }

        return toolResponse.formatted(category, aqiDisplay, primaryPollutantName);
    }




}

