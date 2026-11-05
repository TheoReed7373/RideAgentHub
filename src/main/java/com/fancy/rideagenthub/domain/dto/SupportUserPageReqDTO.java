package com.fancy.rideagenthub.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 客服用户分页查询请求DTO
 */
@Data
public class SupportUserPageReqDTO {

    /**
     * 搜索关键词：按 username 模糊匹配
     *
     * 兼容前端误拼字段：keyward
     */
    @JsonAlias({"keyward"})
    private String keyword;

    /**
     * 当前页码
     */
    private long current = 1;

    /**
     * 每页大小
     */
    private long size = 10;
}
