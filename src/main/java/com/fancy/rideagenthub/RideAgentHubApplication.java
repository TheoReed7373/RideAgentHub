package com.fancy.rideagenthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RideAgentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideAgentHubApplication.class, args);
    }

}
