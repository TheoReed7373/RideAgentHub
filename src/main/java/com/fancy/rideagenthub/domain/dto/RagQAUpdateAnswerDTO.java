package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

@Data
public class RagQAUpdateAnswerDTO {
    /**
     * 与 ES 文档中的 groupId 一致（雪花 id），建议用字符串避免前端精度丢失
     */
    private String groupId;

    private String answer;
}
