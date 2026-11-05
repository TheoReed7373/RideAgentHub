package com.fancy.rideagenthub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 当前用户信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCurrentInfoVO {

    private String userId;

    private String username;

    private String email;

    private String role;

    private LocalDateTime lastLoginTime;

    private LocalDateTime createTime;
}
