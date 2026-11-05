package com.fancy.rideagenthub.agentbase.qweather.pojo.poi;

import java.util.List;

public record QweatherRefer(
        List<String> sources,
        List<String> license
) {
}
