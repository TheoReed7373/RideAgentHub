package com.fancy.rideagenthub.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 当前用户信息修改请求
 */
@Data
public class UserCurrentUpdateReqDTO {

    /**
     * 用户名
     */
    @JsonAlias({"userName"})
    private String username;

    /**
     * 邮箱
     */
    private String email;
}
