package com.fancy.rideagenthub.service.impl;

import com.fancy.rideagenthub.domain.dto.Point;
import com.fancy.rideagenthub.domain.vo.DriverLocationVO;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.service.DriverLocationService;
import com.fancy.rideagenthub.service.base.DriverGeoIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 司机在线位置服务实现
 * <p>
 * 本类只做参数校验与结果装配，真正的状态维护在 {@link DriverGeoIndex}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverLocationServiceImpl implements DriverLocationService {

    private final DriverGeoIndex driverGeoIndex;

    @Override
    public Boolean reportLocation(String driverId, BigDecimal lng, BigDecimal lat) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        if (lng == null || lat == null) {
            throw new IllegalArgumentException("经纬度不能为空");
        }
        if (!DriverGeoIndex.validCoordinates(lng, lat)) {
            throw new IllegalArgumentException("经度须在[-180,180]内，纬度须在[-85.05112878,85.05112878]内");
        }
        return driverGeoIndex.goOnline(driverId, lng, lat);
    }

    @Override
    public Boolean goOffline(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        return driverGeoIndex.goOffline(driverId);
    }

    @Override
    public DriverLocationVO getStatus(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        DriverGeoIndex.Location location = driverGeoIndex.location(driverId);
        if (location.status() == DriverGeoIndex.OnlineStatus.UNAVAILABLE) {
            throw new BusinessException(503, "司机位置服务暂时不可用");
        }
        if (location.status() == DriverGeoIndex.OnlineStatus.OFFLINE) {
            return DriverLocationVO.builder().online(false).build();
        }
        Point point = location.point();
        return DriverLocationVO.builder()
                .online(true)
                .lng(point.getLng())
                .lat(point.getLat())
                .build();
    }
}
