package com.fancy.rideagenthub;

import com.fancy.rideagenthub.service.base.OrderRouteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Slf4j
public class MongoDbTest {
    @Resource
    private OrderRouteService orderRouteService;

    @Test
    public void test(){
        long currentTimeMillis = System.currentTimeMillis();
        orderRouteService.getByTraceId("a37c1b4d-83c8-48e0-baf4-f491e959f368");
        log.info("[1]: Time taken: {}ms", System.currentTimeMillis() - currentTimeMillis);
        long currentTimeMillis2 = System.currentTimeMillis();
        orderRouteService.getByTraceId("9f1c3738-82fc-49df-9793-1dd896ae18c0");
        log.info("[2]: Time taken: {}ms", System.currentTimeMillis() - currentTimeMillis2);
    }
}
