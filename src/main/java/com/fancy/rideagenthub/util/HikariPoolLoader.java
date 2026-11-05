package com.fancy.rideagenthub.util;

import com.fancy.rideagenthub.mapper.CityCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * HikariPool 预热工具类
 * 在 Spring Boot 应用启动完成后自动执行一次数据库查询，
 * 以提前初始化 HikariCP 连接池，避免首次请求时的延迟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HikariPoolLoader {

    private final CityCodeMapper cityCodeMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpHikariPool() {
        try {
            cityCodeMapper.selectCount(null);
            log.info("HikariPool 连接池预热完成");
        } catch (Exception e) {
            log.warn("HikariPool 连接池预热失败: {}", e.getMessage());
        }
    }
}
