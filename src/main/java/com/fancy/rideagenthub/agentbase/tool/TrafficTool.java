package com.fancy.rideagenthub.agentbase.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class TrafficTool {

    @Tool(description = "根据航班号查询航班相关信息")
    public String getFlightInfo(@ToolParam(description = "航班号") String number, ToolContext toolcontext) {
        log.info("[Traffic]: getFlightInfo(): {}", number);
        ToolNotifySupport.notifyToolListener(toolcontext, "根据航班号查询航班相关信息");
        // 统一转大写处理
        String flightNum = number.toUpperCase();

        return switch (flightNum) {
            // --- 成都天府国际机场 (TFU) 进场示例 ---
            case "CA4110" -> "航班号：CA4110，北京首都到成都天府，起飞时间：08:00，降落时间：11:15，天府机场T2航站楼";
            case "3U8888" -> "航班号：3U8888，上海虹桥到成都天府，起飞时间：14:00，降落时间：17:30，天府机场T2航站楼";
            case "MU5401" -> "航班号：MU5401，上海浦东到成都天府，起飞时间：09:00，降落时间：12:35，天府机场T2航站楼";
            case "KL891" -> "航班号：KL891，阿姆斯特丹到成都天府，起飞时间：12:45，降落时间：05:55(+1)，天府机场T1航站楼";

            // --- 成都双流国际机场 (CTU) 进场示例 ---
            case "CA1405" -> "航班号：CA1405，北京首都到成都双流，起飞时间：09:30，降落时间：12:45，双流机场T2航站楼";
            case "CZ3443" -> "航班号：CZ3443，广州白云到成都双流，起飞时间：11:00，降落时间：13:20，双流机场T2航站楼";
            case "3U8922" -> "航班号：3U8922，深圳宝安到成都双流，起飞时间：19:15，降落时间：21:50，双流机场T2航站楼";
            case "HU7147" -> "航班号：HU7147，海口美兰到成都双流，起飞时间：15:20，降落时间：18:10，双流机场T2航站楼";
            default -> "抱歉，未查询到航班号为 " + flightNum + " 的进场信息，请核对后重试。";
        };
    }

}

