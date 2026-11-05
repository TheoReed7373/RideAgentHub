package com.fancy.rideagenthub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户认证 Mapper
 */
@Mapper
public interface UserAuthMapper extends BaseMapper<UserAuth> {

    @Select("SELECT username FROM sys_user WHERE role = 'USER' AND status = 1 AND is_deleted = 0 AND username IS NOT NULL")
    List<String> selectUsernamesForBloom();
}
