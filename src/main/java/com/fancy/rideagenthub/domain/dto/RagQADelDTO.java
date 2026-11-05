package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class RagQADelDTO {
    private List<String> groupIds;
    private List<String> questionIds;
}
