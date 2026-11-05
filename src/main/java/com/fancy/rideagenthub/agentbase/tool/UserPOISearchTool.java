package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.domain.entity.UserPOI;
import com.fancy.rideagenthub.service.UserPOIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserPOISearchTool {
    private final UserPOIService userPOIService;

    @Tool(description = "获取用户保存的兴趣点标签列表")
    public String listUserPOIs(ToolContext toolContext){
        log.info("[UserPOISearch]: listUserPOIs");
        ToolNotifySupport.notifyToolListener(toolContext, "获取用户保存的兴趣点标签列表");
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
        log.info("[UserPOISearch]: getUserPOIDetailByTag(): {}", poiTag);
        ToolNotifySupport.notifyToolListener(toolContext, "根据兴趣点标签获取用户兴趣点详细信息");
        Long userId = Long.valueOf(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString());
        UserPOI result = userPOIService.findByPoiTag(userId, poiTag);
        return "兴趣点标签：" + result.getPoiTag() + "；" +
                "兴趣点名：" + result.getPoiName() + "；" +
                "兴趣点地址：" + result.getPoiAddress() + "；" +
                "经度：" + result.getLongitude() + "；" +
                "纬度：" + result.getLatitude();
    }

    @Tool(description = "根据兴趣点名称（例如瑞幸咖啡）模糊搜索获取用户兴趣点详细信息")
    public String searchUserPOIDetail(@ToolParam(description = "兴趣点名称") String poiName, ToolContext toolContext){
        log.info("[UserPOISearch]: searchUserPOIDetail(): {}", poiName);
        ToolNotifySupport.notifyToolListener(toolContext, "根据兴趣点名称模糊搜索获取用户兴趣点详细信息");
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
}



