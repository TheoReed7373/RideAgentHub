package com.fancy.rideagenthub.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 用户ID列表请求DTO
 */
@Data
public class UserIdListReqDTO {

    /**
     * 用户ID列表
     */
    private List<String> userIds;
}
