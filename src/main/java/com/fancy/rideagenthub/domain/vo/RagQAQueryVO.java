package com.fancy.rideagenthub.domain.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RagQAQueryVO {
    private String groupId;
    private Map<String, String> questionMap; //questionId: questionText
    private String answer;
}
