package com.fancy.rideagenthub.agentbase.qweather.pojo.life;

import java.util.List;

public record QweatherWeatherAlertMessageType(
        String code,
        List<String> supersedes
) {
}
