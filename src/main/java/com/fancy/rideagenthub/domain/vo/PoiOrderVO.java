package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class PoiOrderVO {
    private PoiDetailVO home;
    private PoiDetailVO work;
    private List<PoiDetailVO> other;
}
