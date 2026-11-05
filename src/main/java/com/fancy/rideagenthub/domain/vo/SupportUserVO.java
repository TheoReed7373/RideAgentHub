package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客服用户列表VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportUserVO {

    private String userId;

    private String userName;
}
