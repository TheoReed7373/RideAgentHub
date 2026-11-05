package com.fancy.rideagenthub.agentbase.amap.pojo.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapSuggestion(
        List<String> keywords,
        List<AmapSuggestionCity> cities
) {
}
