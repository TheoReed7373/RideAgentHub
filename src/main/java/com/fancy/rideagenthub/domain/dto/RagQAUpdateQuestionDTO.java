package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

@Data
public class RagQAUpdateQuestionDTO {
    /**
     * ES 文档 id（问题 id），建议用字符串避免前端精度丢失
     */
    private String questionId;

    private String question;
}
