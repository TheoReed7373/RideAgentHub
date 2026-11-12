package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.domain.entity.UserPOI;
import com.fancy.rideagenthub.domain.enums.POIInfoEnum;
import com.fancy.rideagenthub.service.UserPOIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserPOITool {
    //ToolContext需要信息：UserId、ChatId用来获取当前对话相关信息

    private final UserPOIService userPOIService;

    @Tool(description = "获取用户保存的兴趣点标签列表")
    public String listUserPOIs(ToolContext toolContext){
        log.info("[UserPOI]: listUserPOIs");
        ToolNotifySupport.notifyToolListener(toolContext, "获取用户保存的兴趣点标签列表 (listUserPOIs)");
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        List<UserPOI> userPOIS = userPOIService.listByUserId(Long.valueOf(userId));
        StringBuilder sb = new StringBuilder("用户的兴趣点标签列表为");
        if (userPOIS.isEmpty()) {
            sb.append("空");
        } else {
            sb.append("：");
            userPOIS.forEach(poi -> sb.append(poi.getPoiTag()).append("、"));
        }
        return sb.toString();
    }

    @Tool(description = "根据兴趣点标签（例如家、学校）获取用户兴趣点详细信息")
    public String getUserPOIDetailByTag(@ToolParam(description = "兴趣点标签") String poiTag, ToolContext toolContext){
        log.info("[UserPOI]: getUserPOIDetailByTag(): {}", poiTag);
        ToolNotifySupport.notifyToolListener(toolContext, "根据兴趣点标签获取用户兴趣点详细信息 (getUserPOIDetailByTag)");
        Long userId = Long.valueOf(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString());
        UserPOI result = userPOIService.findByPoiTag(userId, poiTag);
        if (result == null) return "未找到该标签，请先保存兴趣点或提供具体地址";
        return "兴趣点标签：" + result.getPoiTag() + "；" +
                "兴趣点名：" + result.getPoiName() + "；" +
                "兴趣点地址：" + result.getPoiAddress() + "；" +
                "经度：" + result.getLongitude() + "；" +
                "纬度：" + result.getLatitude();
    }

    @Tool(description = "根据兴趣点名称（例如瑞幸咖啡）模糊搜索获取用户兴趣点详细信息")
    public String searchUserPOIDetail(@ToolParam(description = "兴趣点名称") String poiName, ToolContext toolContext){
        log.info("[UserPOI]: searchUserPOIDetail(): {}", poiName);
        ToolNotifySupport.notifyToolListener(toolContext, "根据兴趣点名称模糊搜索获取用户兴趣点详细信息 (searchUserPOIDetail)");
        Long userId = Long.valueOf(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString());
        List<UserPOI> result = userPOIService.findByUserIdAndPoiName(userId, poiName);
        StringBuilder sb = new StringBuilder();
        for (UserPOI poi : result) {
            sb.append("兴趣点标签：").append(poi.getPoiTag()).append("；")
                    .append("兴趣点名：").append(poi.getPoiName()).append("；")
                    .append("兴趣点地址：").append(poi.getPoiAddress()).append("；")
                    .append("经度：").append(poi.getLongitude()).append("；")
                    .append("纬度：").append(poi.getLatitude()).append("；");
        }
        return sb.toString();
    }

    @Tool(description = "创建新用户兴趣点（需要提供标签、名称、地址、经纬度）")
    public String createNewPOI(
            @ToolParam(description = "兴趣点标签（例如家、公司、学校）") String poiTag,
            @ToolParam(description = "兴趣点名称（例如XX小区/瑞幸咖啡）") String poiName,
            @ToolParam(description = "兴趣点地址（尽量完整）") String poiAddress,
            @ToolParam(description = "经度，十进制度数，例如116.403874") BigDecimal longitude,
            @ToolParam(description = "纬度，十进制度数，例如39.914888") BigDecimal latitude,
            ToolContext toolContext){
        log.info("[UserPOI]: createNewPOI(): poiTag={}, poiName={}", poiTag, poiName);
        ToolNotifySupport.notifyToolListener(toolContext, "创建新用户兴趣点 (createNewPOI)");

        StringBuilder error = new StringBuilder();
        if (poiTag == null || poiTag.isBlank()) {
            error.append("缺少兴趣点标签信息");
        }
        if (poiName == null || poiName.isBlank()) {
            if (!error.isEmpty()) {
                error.append("；");
            }
            error.append("缺少兴趣点名信息");
        }
        if (poiAddress == null || poiAddress.isBlank()) {
            if (!error.isEmpty()) {
                error.append("；");
            }
            error.append("缺少兴趣点地址信息");
        }
        if (longitude == null) {
            if (!error.isEmpty()) {
                error.append("；");
            }
            error.append("缺少经度信息");
        }
        if (latitude == null) {
            if (!error.isEmpty()) {
                error.append("；");
            }
            error.append("缺少纬度信息");
        }
        if (!error.isEmpty()) {
            return "创建失败，" + error;
        }

        Long userId = Long.valueOf(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString());
        UserPOI exists = userPOIService.findByPoiTagExact(userId, poiTag);
        if (exists != null) {
            return "创建失败，兴趣点标签(poiTag)已存在：" + poiTag;
        }

        userPOIService.create(UserPOI.builder()
                .userId(userId)
                .poiTag(poiTag)
                .poiName(poiName)
                .poiAddress(poiAddress)
                .longitude(longitude)
                .latitude(latitude)
                .build());
        return "创建成功";
    }

    @Tool(description = "修改用户兴趣点信息。使用 poiTag 精确定位要修改的兴趣点；经纬度为十进制度数")
    public String updateUserPOIInfo(
            @ToolParam(description = "要修改的兴趣点标签") String poiTag,
            @ToolParam(description = "要修改的字段枚举：poiTag/poiName/poiAddress/poiLongitude/poiLatitude") POIInfoEnum infoEnum,
            @ToolParam(description = "新值") String value,
            ToolContext toolContext) {
        log.info("[UserPOI]: updateUserPOIInfo(): infoEnum={}, poiTag={}, value={}", infoEnum, poiTag, value);
        ToolNotifySupport.notifyToolListener(toolContext, "修改用户兴趣点信息 (updateUserPOIInfo)");

        if (infoEnum == null) {
            return "修改失败，缺少字段枚举 infoEnum";
        }
        if (poiTag == null || poiTag.isBlank()) {
            return "修改失败，缺少 poiTag";
        }
        if (value == null || value.isBlank()) {
            return "修改失败，缺少 value（新值）";
        }

        String targetPoiTag = poiTag.trim();
        String newValue = value.trim();

        Long userId = Long.valueOf(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString());
        UserPOI exists = userPOIService.findByPoiTagExact(userId, targetPoiTag);
        if (exists == null) {
            return "修改失败，未找到目标兴趣点：" + targetPoiTag;
        }

        UserPOI update = UserPOI.builder()
                .id(exists.getId())
                .userId(userId)
                .poiTag(exists.getPoiTag())
                .poiName(exists.getPoiName())
                .poiAddress(exists.getPoiAddress())
                .longitude(exists.getLongitude())
                .latitude(exists.getLatitude())
                .build();

        try {
            switch (infoEnum) {
                case poiTag -> update.setPoiTag(newValue);
                case poiName -> update.setPoiName(newValue);
                case poiAddress -> update.setPoiAddress(newValue);
                case poiLongitude -> update.setLongitude(new BigDecimal(newValue));
                case poiLatitude -> update.setLatitude(new BigDecimal(newValue));
                default -> {
                    return "修改失败，不支持的字段：" + infoEnum;
                }
            }
        } catch (NumberFormatException e) {
            return "修改失败，经纬度格式错误";
        }

        if (infoEnum == POIInfoEnum.poiTag) {
            UserPOI conflict = userPOIService.findByPoiTagExact(userId, update.getPoiTag());
            if (conflict != null && !conflict.getId().equals(update.getId())) {
                return "修改失败，poiTag 已存在：" + update.getPoiTag();
            }
        }

        userPOIService.update(update);
        return "修改成功";
    }

    @Tool(description = "删除用户兴趣点（按 poiTag 精确删除）")
    public String deleteUserPOI(
            @ToolParam(description = "要删除的兴趣点标签") String poiTag,
            ToolContext toolContext) {
        log.info("[UserPOI]: deleteUserPOI(): poiTag={}", poiTag);
        ToolNotifySupport.notifyToolListener(toolContext, "删除用户兴趣点 (deleteUserPOI)");

        if (poiTag == null || poiTag.isBlank()) {
            return "删除失败，缺少 poiTag";
        }
        Long userId = Long.valueOf(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString());
        UserPOI exists = userPOIService.findByPoiTagExact(userId, poiTag);
        if (exists == null) {
            return "删除失败，未找到目标兴趣点：" + poiTag;
        }
        userPOIService.deleteById(exists.getId());
        return "删除成功";
    }


}



