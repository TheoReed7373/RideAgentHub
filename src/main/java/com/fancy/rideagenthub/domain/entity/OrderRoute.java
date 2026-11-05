package com.fancy.rideagenthub.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "order_routes")
public class OrderRoute {
    @Id
    private String mongoTraceId;
    private String estRoute;
    private String estPolyline;
    private String realPolyline;
}
