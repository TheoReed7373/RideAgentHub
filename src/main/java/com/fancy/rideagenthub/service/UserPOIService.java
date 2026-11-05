package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.domain.entity.UserPOI;
import com.fancy.rideagenthub.domain.vo.PoiOrderVO;

import java.util.List;

/**
 * 用户兴趣点（POI）服务接口
 */
public interface UserPOIService {

    /**
     * 创建兴趣点
     */
    void create(UserPOI userPOI);

    /**
     * 更新兴趣点（按主键 ID）
     */
    void update(UserPOI userPOI);

    /**
     * 删除兴趣点（按主键 ID）
     */
    void deleteById(Long id);

    /**
     * 根据主键 ID 查询兴趣点
     */
    UserPOI findById(Long id);

    /**
     * 根据 userId 查询名下所有兴趣点
     */
    List<UserPOI> listByUserId(Long userId);

    /**
     * 根据 userId + poiName 查询兴趣点
     */
    List<UserPOI> findByUserIdAndPoiName(Long userId, String poiName);

    /**
     * 根据兴趣点标签查询兴趣点
     */
    UserPOI findByPoiTag(Long userId, String tag);

    /**
     * 根据兴趣点标签精确查询兴趣点（用于唯一性校验/精确更新/删除）
     */
    UserPOI findByPoiTagExact(Long userId, String poiTag);

    /**
     * 创建当前登录用户的兴趣点（自动填充 userId）
     */
    void createForCurrentUser(UserPOI userPOI);

    /**
     * 查询当前登录用户的所有兴趣点
     */
    List<UserPOI> listCurrentUserPOIs();

    /**
     * 获取当前用户订单相关POI（家、公司、其他）
     */
    PoiOrderVO getCurrentUserPoiForOrder();
}
