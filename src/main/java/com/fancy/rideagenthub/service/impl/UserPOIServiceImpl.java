package com.fancy.rideagenthub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.domain.entity.UserPOI;
import com.fancy.rideagenthub.domain.vo.POIInfoVO;
import com.fancy.rideagenthub.domain.vo.PoiDetailVO;
import com.fancy.rideagenthub.domain.vo.PoiOrderVO;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.UserPOIMapper;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.UserPOIService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户兴趣点（POI）服务实现
 */
@Service
@RequiredArgsConstructor
public class UserPOIServiceImpl implements UserPOIService {

    private final UserPOIMapper userPOIMapper;

    @Override
    public void create(UserPOI userPOI) {
        if (userPOI == null) {
            throw new IllegalArgumentException("userPOI不能为空");
        }
        if (userPOI.getUserId() == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        normalize(userPOI);
        userPOIMapper.insert(userPOI);
    }

    @Override
    public void update(UserPOI userPOI) {
        if (userPOI == null) {
            throw new IllegalArgumentException("userPOI不能为空");
        }
        if (userPOI.getId() == null) {
            throw new IllegalArgumentException("id不能为空");
        }
        // 若携带 userId，则做一次归属校验，避免越权更新
        if (userPOI.getUserId() != null) {
            UserPOI exists = getRequiredByIdAndUserId(userPOI.getId(), userPOI.getUserId());
            userPOI.setUserId(exists.getUserId());
        } else {
            UserPOI exists = userPOIMapper.selectById(userPOI.getId());
            if (exists == null) {
                throw new BusinessException(404, "兴趣点不存在");
            }
            userPOI.setUserId(exists.getUserId());
        }

        normalize(userPOI);
        userPOIMapper.updateById(userPOI);
    }

    @Override
    public void deleteById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id不能为空");
        }
        userPOIMapper.deleteById(id);
    }

    @Override
    public UserPOI findById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id不能为空");
        }
        return userPOIMapper.selectById(id);
    }

    @Override
    public List<UserPOI> listByUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        return userPOIMapper.selectList(
                new LambdaQueryWrapper<UserPOI>()
                        .eq(UserPOI::getUserId, userId)
                        .orderByDesc(UserPOI::getId));
    }

    @Override
    public List<UserPOI> findByUserIdAndPoiName(Long userId, String poiName) {
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        String normalizedPoiName = normalizeText(poiName);
        if (normalizedPoiName == null) {
            throw new IllegalArgumentException("poiName不能为空");
        }
        return userPOIMapper.selectList(
                new LambdaQueryWrapper<UserPOI>()
                        .eq(UserPOI::getUserId, userId)
                        .like(UserPOI::getPoiName, normalizedPoiName));
    }

    @Override
    public UserPOI findByPoiTag(Long userId, String poiTag) {
        //%tag% 这样查询
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        poiTag = normalizeText(poiTag);
        return userPOIMapper.selectOne(
                new LambdaQueryWrapper<UserPOI>()
                        .eq(UserPOI::getUserId, userId)
                        .like(UserPOI::getPoiTag, poiTag)
                        .last("limit 1"));
    }

    @Override
    public UserPOI findByPoiTagExact(Long userId, String poiTag) {
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        poiTag = normalizeText(poiTag);
        if (poiTag == null) {
            return null;
        }
        return userPOIMapper.selectOne(
                new LambdaQueryWrapper<UserPOI>()
                        .eq(UserPOI::getUserId, userId)
                        .eq(UserPOI::getPoiTag, poiTag)
                        .last("limit 1"));
    }

    @Override
    public void createForCurrentUser(UserPOI userPOI) {
        if (userPOI == null) {
            throw new IllegalArgumentException("userPOI不能为空");
        }
        Long userId = UserTokenContext.getUserIdInLong();
        userPOI.setUserId(userId);
        create(userPOI);
    }

    @Override
    public List<UserPOI> listCurrentUserPOIs() {
        Long userId = UserTokenContext.getUserIdInLong();
        return listByUserId(userId);
    }

    @Override
    public PoiOrderVO getCurrentUserPoiForOrder() {
        Long userId = UserTokenContext.getUserIdInLong();
        List<UserPOI> poiList = listByUserId(userId);

        PoiDetailVO home = null;
        PoiDetailVO work = null;
        List<PoiDetailVO> other = new ArrayList<>();

        for (UserPOI poi : poiList) {
            String tag = poi.getPoiTag();
            PoiDetailVO infoVO = new PoiDetailVO(poi);

            if ("家".equals(tag)) {
                home = infoVO;
            } else if ("公司".equals(tag)) {
                work = infoVO;
            } else {
                other.add(infoVO);
            }
        }

        return PoiOrderVO.builder()
                .home(home)
                .work(work)
                .other(other)
                .build();
    }

    private POIInfoVO convertToPOIInfoVO(UserPOI poi) {
        POIInfoVO vo = new POIInfoVO();
        vo.setPoiTag(poi.getPoiTag());
        vo.setPoiName(poi.getPoiName());
        vo.setPoiAddress(poi.getPoiAddress());
        vo.setLongitude(poi.getLongitude());
        vo.setLatitude(poi.getLatitude());
        return vo;
    }

    private UserPOI getRequiredByIdAndUserId(Long id, Long userId) {
        UserPOI userPOI = userPOIMapper.selectOne(
                new LambdaQueryWrapper<UserPOI>()
                        .eq(UserPOI::getId, id)
                        .eq(UserPOI::getUserId, userId)
                        .last("limit 1"));
        if (userPOI == null) {
            throw new BusinessException(404, "兴趣点不存在或无权限");
        }
        return userPOI;
    }

    private void normalize(UserPOI userPOI) {
        userPOI.setPoiTag(normalizeText(userPOI.getPoiTag()));
        userPOI.setPoiName(normalizeText(userPOI.getPoiName()));
        userPOI.setPoiAddress(normalizeText(userPOI.getPoiAddress()));
        if (userPOI.getPoiName() == null) {
            throw new IllegalArgumentException("poiName不能为空");
        }
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
