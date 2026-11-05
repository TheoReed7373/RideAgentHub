package com.fancy.rideagenthub.agentbase.amap.util.citycode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.CityCode;
import com.fancy.rideagenthub.mapper.CityCodeMapper;
import jakarta.annotation.Nullable;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@Component
public class CityCodeUtil {

    private final CityCodeMapper cityCodeMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String NULL_SENTINEL = "__NULL__";
    private static final long CACHE_TTL_DAYS = 7;


    public CityCodeUtil(CityCodeMapper cityCodeMapper, StringRedisTemplate redisTemplate) {
        this.cityCodeMapper = cityCodeMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 根据城市名获取编码
     * @param cityName XX或XX市
     * @return 城市编码
     */
    @Nullable
    public String getCityCode(String cityName){
        if (cityName == null || cityName.isBlank()) {
            return null;
        }

        String key = RedisKeyConstants.amapCityCodeKey(cityName);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return NULL_SENTINEL.equals(cached) ? null : cached;
        }

        CityCode cityCode = cityCodeMapper.selectOne(
                new LambdaQueryWrapper<CityCode>()
                        .eq(CityCode::getName, cityName)
                        .or()
                        .eq(CityCode::getSimpleName, cityName)
        );

        String code = cityCode == null ? null : cityCode.getCityCode();
        redisTemplate.opsForValue().set(
                key,
                code == null ? NULL_SENTINEL : code,
                CACHE_TTL_DAYS,
                TimeUnit.DAYS
        );
        return code;
    }

}
