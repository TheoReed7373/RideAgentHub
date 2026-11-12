package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.dto.CreateOrderDTO;
import com.fancy.rideagenthub.domain.enums.OrderInfoEnum;
import com.fancy.rideagenthub.domain.enums.RideVehicleType;
import com.fancy.rideagenthub.domain.vo.EstRouteVO;
import com.fancy.rideagenthub.domain.vo.PriceEstimateVO;
import com.fancy.rideagenthub.service.RideOrderService;
import com.fancy.rideagenthub.service.base.ChatInfoService;
import com.fancy.rideagenthub.service.base.OrderRouteService;
import com.fancy.rideagenthub.util.TimeUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OrderTool {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RideOrderService rideOrderService;
    @Resource
    private AmapRouteService amapRouteService;
    @Resource
    private OrderRouteService orderRouteService;
    @Resource
    private ChatManager chatManager;
    @Resource
    private com.fancy.rideagenthub.service.base.OrderConfirmation orderConfirmation;

    @Tool(description = "保存新订单相关参数")
    public String saveNewOrderParam(@ToolParam(description = "订单相关参数Map") OrderParams params,
            ToolContext toolContext) {
        log.info("[OrderTool]: saveNewOrderParam(): params={}", params);
        ToolNotifySupport.notifyToolListener(toolContext, "保存新订单相关参数 (saveNewOrderParam)");
        // 遍历入参并填入
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        if (params == null || params.slots() == null) return "请提供有效订单参数";
        stringRedisTemplate.opsForHash().delete(chatInfoKey, "ReadyforRoute", "ReadyforConfirm", "ReadyForCreate",
                "quoteId", "confirmedQuote", "EST_TIME", "EST_DISTANCE_KM");
        for (OrderInfoEnum key : params.slots.keySet()) {
            if (key == OrderInfoEnum.EST_PRICE || key == OrderInfoEnum.EST_DISTANCE_KM || key == OrderInfoEnum.MONGO_TRACE_ID)
                continue;
            redisPut(chatInfoKey, key.name(), params.slots.get(key));
        }
        String allParams = params.slots.keySet().stream()
                .map(Enum::name)
                .collect(Collectors.joining(";"));
        return "已保存以下参数：" + allParams;
    }

    @Tool(description = "获取新订单参数是否已准备好")
    public String isNewOrderReady(ToolContext toolContext) {
        log.info("[OrderTool]: isNewOrderReady()");
        ToolNotifySupport.notifyToolListener(toolContext, "获取新订单参数是否已准备好 (isNewOrderReady)");
        List<OrderInfoEnum> lackList = new ArrayList<>();
        List<OrderInfoEnum> errorList = new ArrayList<>();
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        for (OrderInfoEnum key : OrderInfoEnum.values()) {
            if(key.equals(OrderInfoEnum.EST_PRICE) || key.equals(OrderInfoEnum.MONGO_TRACE_ID) || key.equals(OrderInfoEnum.EST_DISTANCE_KM)){
                continue;
            }
            Object o = redisGetObj(chatInfoKey, key.name());
            if (o == null) {
                if (key.equals(OrderInfoEnum.SCHEDULED_TIME)) {
                    // 如果有预约字段且为1则判定缺少，如果没有则不加入
                    Object isRev = redisGetObj(chatInfoKey, OrderInfoEnum.IS_RESERVATION.name());
                    if (isRev != null && isRev.toString().equals("1")) {
                        lackList.add(key);
                        continue;
                    }
                    continue;
                }
                lackList.add(key);
                continue;
            }
            String value = o.toString();
            switch (key) {
                case VEHICLE_TYPE -> {
                    if (!value.equals("1") && !value.equals("2") && !value.equals("3")) {
                        errorList.add(key);
                        redisDel(chatInfoKey, key.name());
                    }
                }
                case IS_RESERVATION, IS_EXPEDITED -> {
                    if (!value.equals("0") && !value.equals("1")) {
                        errorList.add(key);
                        redisDel(chatInfoKey, key.name());
                    }
                }
                case SCHEDULED_TIME -> {
                    if (TimeUtil.parse(value) == null || !TimeUtil.parse(value).isAfter(com.fancy.rideagenthub.util.BusinessTime.now())) {
                        errorList.add(key);
                        redisDel(chatInfoKey, key.name());
                    }
                }
                case START_LAT, END_LAT, START_LNG, END_LNG -> {
                    if (!validCoordinate(value, key == OrderInfoEnum.START_LAT || key == OrderInfoEnum.END_LAT)) {
                        errorList.add(key);
                        redisDel(chatInfoKey, key.name());
                    }
                }
            }
        }
        // 如果没有准备好，返回“缺哪些枚举Name”，都准备好则返回“已准备好进行路径规划”并写入参数
        if (lackList.isEmpty() && errorList.isEmpty()) {
            redisPut(chatInfoKey, "ReadyforRoute", "true");
            return "参数已准备好，使用getEstRouteAndPrice()进行算价和/或路径规划。";
        }
        List<String> parts = new ArrayList<>();
        if (!lackList.isEmpty()) {
            String lackStr = lackList.stream().map(Enum::name).collect(Collectors.joining(";"));
            parts.add("缺少的参数：" + lackStr);
        }
        if (!errorList.isEmpty()) {
            String errorStr = errorList.stream().map(Enum::name).collect(Collectors.joining(";"));
            parts.add("由于格式错误被删除的参数：" + errorStr);
        }
        return String.join("；", parts);
    }

    @Tool(description = "对准备好的新订单进行路径规划并计算预期价格")
    public String getEstRouteAndPrice(@ToolParam(description = "不进行路径规划") boolean noRoute, ToolContext toolContext) {
        log.info("[OrderTool]: getEstRouteAndPrice(), 不进行路径规划: {}", noRoute);
        ToolNotifySupport.notifyToolListener(toolContext, "对准备好的新订单进行路径规划并计算预期价格 (getEstRouteAndPrice)");
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        if(redisGetObj(chatInfoKey,OrderInfoEnum.MONGO_TRACE_ID.name()) == null){
            noRoute = false;
        }
        if (redisGetObj(chatInfoKey, "ReadyforRoute") == null) {
            return "请使用isNewOrderReady()先检查订单参数是否完备";
        }
        String oldTrace = redisGetObj(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name()) == null ? null :
                redisGet(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name());
        if (redisGetObj(chatInfoKey, "EST_TIME") == null || redisGetObj(chatInfoKey, "EST_DISTANCE_KM") == null) noRoute = false;
        String startLat = redisGet(chatInfoKey, OrderInfoEnum.START_LAT.name());
        String startLng = redisGet(chatInfoKey, OrderInfoEnum.START_LNG.name());
        String endLat = redisGet(chatInfoKey, OrderInfoEnum.END_LAT.name());
        String endLng = redisGet(chatInfoKey, OrderInfoEnum.END_LNG.name());

        // 调用后生成一个UUID作为mongoid，路径规划后估价并存mongo
        if(noRoute){
            PriceEstimateVO estPrice = getEstPrice(redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name()),
                    redisGet(chatInfoKey, "EST_TIME"),
                    redisGet(chatInfoKey, OrderInfoEnum.IS_RESERVATION.name()),
                    redisGet(chatInfoKey, OrderInfoEnum.IS_EXPEDITED.name()),
                    redisGet(chatInfoKey, OrderInfoEnum.VEHICLE_TYPE.name()));
            putPricePara(chatInfoKey, estPrice);
            redisPut(chatInfoKey, OrderInfoEnum.EST_PRICE.name(), estPrice.getEstPrice().toPlainString());
            redisDel(chatInfoKey, "ReadyforRoute");
            orderConfirmation.quoted(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
            return String.format("新订单完成算价，预计里程%sKm，预计耗时%s分钟，预计价格%s元。请使用notifyUser()通知用户确认。",
                    redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name()),
                    TimeUtil.getMinutes(redisGet(chatInfoKey, "EST_TIME")), estPrice.getEstPrice());
        }
        String mongoId = UUID.randomUUID().toString();
        EstRouteVO estRoute = amapRouteService.getDrivingRouteEst(Double.parseDouble(startLng),
                Double.parseDouble(startLat), Double.parseDouble(endLng), Double.parseDouble(endLat));
        PriceEstimateVO estPrice = getEstPrice(estRoute.getEstKm(), estRoute.getEstTime(),
                redisGet(chatInfoKey, OrderInfoEnum.IS_RESERVATION.name()),
                redisGet(chatInfoKey, OrderInfoEnum.IS_EXPEDITED.name()),
                redisGet(chatInfoKey, OrderInfoEnum.VEHICLE_TYPE.name()));
        orderRouteService.addOrder(mongoId, estRoute);
        putPricePara(chatInfoKey, estPrice);
        redisPut(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name(), mongoId);
        redisPut(chatInfoKey, OrderInfoEnum.EST_PRICE.name(), estPrice.getEstPrice().toPlainString());
        redisPut(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name(), estRoute.getEstKm());
        redisPut(chatInfoKey, "EST_TIME", estRoute.getEstTime());
        // 最后在redis中放ReadyforConfirm:true的标记供HITL用户验证。
        redisDel(chatInfoKey, "ReadyforRoute");
        orderConfirmation.quoted(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());

        if (oldTrace != null && !oldTrace.equals(mongoId)) {
            try { orderRouteService.deleteByTraceId(oldTrace); }
            catch (RuntimeException e) { log.warn("旧规划轨迹清理失败", e); }
        }
        return String.format("新订单完成路径规划和算价，预计里程%sKm，预计全程耗时%s分钟，预计价格%s元。请使用notifyUser()通知用户确认。", estRoute.getEstKm(),
                TimeUtil.getMinutes(estRoute.getEstTime()), estPrice.getEstPrice());
    }

    @Tool(description = "解释当前订单价格构成")
    public String explainPrice(ToolContext toolContext) {
        log.info("[OrderTool]: explainPrice()");
        ToolNotifySupport.notifyToolListener(toolContext, "解释当前订单价格构成 (explainPrice)");
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        if(redisGetObj(chatInfoKey, "ReadyforConfirm") == null){
            return "请使用getEstRouteAndPrice()先进行算价和/或路径规划。";
        }
        // 获取里程费、时长费、远途费、加急费、倍率，解释如何计算。
        Object estDistanceObj = redisGetObj(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name());
        Object estTimeObj = redisGetObj(chatInfoKey, "EST_TIME");
        Object vehicleTypeObj = redisGetObj(chatInfoKey, OrderInfoEnum.VEHICLE_TYPE.name());
        Object isExpeditedObj = redisGetObj(chatInfoKey, OrderInfoEnum.IS_EXPEDITED.name());
        Object isReservationObj = redisGetObj(chatInfoKey, OrderInfoEnum.IS_RESERVATION.name());

        String estDistanceKmStr = estDistanceObj == null ? null : estDistanceObj.toString();
        String estTimeSecStr = estTimeObj == null ? null : estTimeObj.toString();
        String vehicleTypeStr = vehicleTypeObj == null ? "1" : vehicleTypeObj.toString();
        String isExpeditedStr = isExpeditedObj == null ? "0" : isExpeditedObj.toString();
        String isReservationStr = isReservationObj == null ? "0" : isReservationObj.toString();

        // 兜底：如果价格拆分字段缺失，则重算一次并回填（防止 explainPrice 被单独调用时缺字段）
        boolean lackBreakdown = redisGetObj(chatInfoKey, PriceEnum.PRICE_RADIO.name()) == null
                || redisGetObj(chatInfoKey, PriceEnum.MILEAGE_FEE.name()) == null
                || redisGetObj(chatInfoKey, PriceEnum.LONG_DISTANCE_SURCHARGE.name()) == null
                || redisGetObj(chatInfoKey, PriceEnum.TIME_FEE.name()) == null
                || redisGetObj(chatInfoKey, PriceEnum.EXPEDITED_FEE.name()) == null
                || redisGetObj(chatInfoKey, OrderInfoEnum.EST_PRICE.name()) == null;
        if (lackBreakdown) {
            PriceEstimateVO rebuilt = getEstPrice(estDistanceKmStr, estTimeSecStr, isReservationStr, isExpeditedStr, vehicleTypeStr);
            putPricePara(chatInfoKey, rebuilt);
            redisPut(chatInfoKey, OrderInfoEnum.EST_PRICE.name(), rebuilt.getEstPrice().toPlainString());
        }

        BigDecimal distanceKm;
        try {
            distanceKm = estDistanceKmStr == null ? BigDecimal.ZERO : new BigDecimal(estDistanceKmStr.trim());
        } catch (Exception ignored) {
            distanceKm = BigDecimal.ZERO;
        }
        if (distanceKm.signum() < 0) {
            distanceKm = BigDecimal.ZERO;
        }

        BigDecimal durationSec;
        try {
            durationSec = estTimeSecStr == null ? BigDecimal.ZERO : new BigDecimal(estTimeSecStr.trim());
        } catch (Exception ignored) {
            durationSec = BigDecimal.ZERO;
        }
        if (durationSec.signum() < 0) {
            durationSec = BigDecimal.ZERO;
        }
        BigDecimal minutes = durationSec.divide(new BigDecimal("60"), 0, RoundingMode.CEILING);

        BigDecimal priceRadio = new BigDecimal(redisGet(chatInfoKey, PriceEnum.PRICE_RADIO.name()));
        BigDecimal mileageFee = new BigDecimal(redisGet(chatInfoKey, PriceEnum.MILEAGE_FEE.name()));
        BigDecimal longDistanceFee = new BigDecimal(redisGet(chatInfoKey, PriceEnum.LONG_DISTANCE_SURCHARGE.name()));
        BigDecimal timeFee = new BigDecimal(redisGet(chatInfoKey, PriceEnum.TIME_FEE.name()));
        BigDecimal expeditedFee = new BigDecimal(redisGet(chatInfoKey, PriceEnum.EXPEDITED_FEE.name()));
        BigDecimal estPrice = new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.EST_PRICE.name()));

        BigDecimal startPrice = new BigDecimal("8.00");
        BigDecimal startDistanceKm = new BigDecimal("2.0");
        BigDecimal perKmFee = new BigDecimal("2.20");
        BigDecimal perMinFee = new BigDecimal("0.50");
        BigDecimal longDistanceThresholdKm = new BigDecimal("20.0");
        BigDecimal longDistanceSurchargePerKm = perKmFee.multiply(new BigDecimal("0.50"));

        BigDecimal overStartKm = distanceKm.subtract(startDistanceKm);
        if (overStartKm.signum() < 0) {
            overStartKm = BigDecimal.ZERO;
        }
        BigDecimal longOverKm = distanceKm.subtract(longDistanceThresholdKm);
        if (longOverKm.signum() < 0) {
            longOverKm = BigDecimal.ZERO;
        }

        BigDecimal subTotal = mileageFee.add(timeFee).add(longDistanceFee);
        BigDecimal preMultiply = subTotal.add(expeditedFee);

        int vehicleTypeCode;
        try {
            vehicleTypeCode = Integer.parseInt(vehicleTypeStr.trim());
        } catch (Exception ignored) {
            vehicleTypeCode = 1;
        }
        String vehicleTypeName;
        try {
            vehicleTypeName = RideVehicleType.fromCode(vehicleTypeCode).getDesc();
        } catch (Exception ignored) {
            vehicleTypeName = "快车";
        }

        boolean expedited = "1".equals(isExpeditedStr.trim()) || "true".equalsIgnoreCase(isExpeditedStr.trim());

        StringBuilder sb = new StringBuilder();
        sb.append("当前订单预估费用说明（金额保留2位小数，四舍五入）\n");
        sb.append(String.format("- 预计里程：%s km；预计用时：%s 分钟（按秒换算并向上取整）\n",
                distanceKm.stripTrailingZeros().toPlainString(), minutes.toPlainString()));
        sb.append(String.format("- 车型：%s；是否加急：%s\n", vehicleTypeName, expedited ? "是" : "否"));
        sb.append("\n费用构成（先加总，再乘倍率）：\n");
        sb.append(String.format("1) 基础里程费 = 起步价%s元（含%skm） + 超里程费 max(0, D-%skm)×%s元/km\n",
                startPrice.toPlainString(), startDistanceKm.stripTrailingZeros().toPlainString(),
                startDistanceKm.stripTrailingZeros().toPlainString(), perKmFee.toPlainString()));
        sb.append(String.format("   - 本单超里程：%s km；基础里程费：%s 元\n",
                overStartKm.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                mileageFee.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("2) 时长费 = ceil(T秒/60)×%s元/分钟\n", perMinFee.toPlainString()));
        sb.append(String.format("   - 本单计费分钟：%s 分钟；时长费：%s 元\n",
                minutes.toPlainString(), timeFee.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("3) 远途/空驶费 = max(0, D-%skm)×(%s元/km×50%%)\n",
                longDistanceThresholdKm.stripTrailingZeros().toPlainString(), perKmFee.toPlainString()));
        sb.append(String.format("   - 本单远途里程：%s km；远途/空驶费：%s 元\n",
                longOverKm.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                longDistanceFee.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("4) 加急费：%s 元（加急固定调度费，未加急则为0）\n",
                expeditedFee.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("\n小计 = 基础里程费(%s) + 时长费(%s) + 远途/空驶费(%s) = %s 元\n",
                mileageFee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                timeFee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                longDistanceFee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                subTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("加急后小计 = 小计(%s) + 固定加急费(%s) = %s 元\n",
                subTotal.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                expeditedFee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                preMultiply.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("5) 乘算倍率：%s（车型倍率 + 加急动态倍率）\n", priceRadio.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        sb.append(String.format("\n最终预估一口价 = 加急后小计(%s) × 倍率(%s) = %s 元\n",
                preMultiply.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                priceRadio.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                estPrice.setScale(2, RoundingMode.HALF_UP).toPlainString()));

        // 省钱建议：根据是否加急 / 车型（2、3）给出建议；如果没有更便宜的明确指出
        sb.append("\n如果您觉得价格偏高，可以考虑：\n");
        boolean hasSuggestion = false;

        if (expedited) {
            PriceEstimateVO noExped = getEstPrice(estDistanceKmStr, estTimeSecStr, isReservationStr, "0", vehicleTypeStr);
            BigDecimal save = estPrice.subtract(noExped.getEstPrice());
            if (save.signum() > 0) {
                sb.append(String.format("- 关闭加急：预计可省 %s 元（从 %s 元降至 %s 元）\n",
                        save.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        estPrice.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        noExped.getEstPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()));
                hasSuggestion = true;
            }
        }

        if (vehicleTypeCode == 2 || vehicleTypeCode == 3) {
            // 优先建议降到快车（1），并给出可省金额
            PriceEstimateVO cheaper = getEstPrice(estDistanceKmStr, estTimeSecStr, isReservationStr, isExpeditedStr, "1");
            BigDecimal save = estPrice.subtract(cheaper.getEstPrice());
            if (save.signum() > 0) {
                sb.append(String.format("- 选择快车(车型1)：预计可省 %s 元（从 %s 元降至 %s 元）\n",
                        save.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        estPrice.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        cheaper.getEstPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()));
                hasSuggestion = true;
            }
            if (vehicleTypeCode == 3) {
                PriceEstimateVO downTo2 = getEstPrice(estDistanceKmStr, estTimeSecStr, isReservationStr, isExpeditedStr, "2");
                BigDecimal save2 = estPrice.subtract(downTo2.getEstPrice());
                if (save2.signum() > 0) {
                    String type3Name;
                    String type2Name;
                    try {
                        type3Name = RideVehicleType.fromCode(3).getDesc();
                    } catch (Exception ignored) {
                        type3Name = "车型3";
                    }
                    try {
                        type2Name = RideVehicleType.fromCode(2).getDesc();
                    } catch (Exception ignored) {
                        type2Name = "车型2";
                    }
                    sb.append(String.format("- 从%s(车型3)降到%s(车型2)：预计可省 %s 元（从 %s 元降至 %s 元）\n",
                            type3Name,
                            type2Name,
                            save2.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            estPrice.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            downTo2.getEstPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()));
                    hasSuggestion = true;
                }
            }
        }

        if (!hasSuggestion) {
            sb.append("- 当前已是最省组合（快车且未加急）。若想进一步降低费用，只能通过减少行程距离/时长（如更换上下车点、避开拥堵时段）\n");
        }

        sb.append("\n提示：以上为预估价格，实际费用可能受实时路况/路线变化等因素影响。\n");
        return sb.toString();
    }

    @Tool(description = "通知用户检查新订单参数并确认创建订单")
    public String notifyUser(ToolContext toolContext) {
        // 这个工具只是为了让大模型显式的调用这个Tool以便触发sink.tryEmitComplete()结束的。
        // 这部分逻辑要写在OrderAgent的选择工具swtich里面。
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        if (stringRedisTemplate.opsForHash().get(chatInfoKey, "ReadyforConfirm") == null) {
            return "尚未进行路径规划。";
        }
        return "";
    }

    @Tool(description = "用户确认参数无误后，标记订单为可创建态")
    public String markOrderReadyForCreate(ToolContext toolContext) {
        log.info("[OrderTool]: markOrderReadyForCreate()");
        ToolNotifySupport.notifyToolListener(toolContext, "用户确认参数无误后，标记订单为可创建态 (markOrderReadyForCreate)");
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        String chatId = toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString();
        return orderConfirmation.isConfirmed(chatId) ? "订单已取得用户确认，可以创建" : "尚未取得用户对当前报价的确认";
    }

    @Tool(description = "创建订单")
    public String createOrder(ToolContext toolContext) {
        log.info("[OrderTool]: createOrder()");
        ToolNotifySupport.notifyToolListener(toolContext, "创建订单 (createOrder)");
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        if(redisGetObj(chatInfoKey, "ReadyForCreate") == null){
            return "订单非可创建态";
        }
        CreateOrderDTO orderDTO = CreateOrderDTO.builder()
                .userId(toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString())
                .mongoTraceId(redisGet(chatInfoKey, OrderInfoEnum.MONGO_TRACE_ID.name()))
                .vehicleType(Integer.parseInt(redisGet(chatInfoKey, OrderInfoEnum.VEHICLE_TYPE.name())))
                .isReservation(Integer.parseInt(redisGet(chatInfoKey, OrderInfoEnum.IS_RESERVATION.name())))
                .isExpedited(Integer.parseInt(redisGet(chatInfoKey, OrderInfoEnum.IS_EXPEDITED.name())))
                .scheduledTime(redisGetObj(chatInfoKey, OrderInfoEnum.SCHEDULED_TIME.name()) == null ? null :
                        TimeUtil.parse(redisGet(chatInfoKey, OrderInfoEnum.SCHEDULED_TIME.name())))
                .startAddress(redisGet(chatInfoKey, OrderInfoEnum.START_ADDRESS.name()))
                .startLat(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.START_LAT.name())))
                .startLng(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.START_LNG.name())))
                .endAddress(redisGet(chatInfoKey, OrderInfoEnum.END_ADDRESS.name()))
                .endLat(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.END_LAT.name())))
                .endLng(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.END_LNG.name())))
                .estPrice(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.EST_PRICE.name())))
                .estDistance(new BigDecimal(redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name())))
                .radio(new BigDecimal(redisGet(chatInfoKey, PriceEnum.PRICE_RADIO.name())))
                .build();
        orderConfirmation.consume(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        String orderId = rideOrderService.createOrder(orderDTO);
        try { orderRouteService.deleteByTraceId(orderDTO.getMongoTraceId()); }
        catch (RuntimeException e) { log.warn("订单预览轨迹清理失败", e); }
        String estDistanceKm = redisGet(chatInfoKey, OrderInfoEnum.EST_DISTANCE_KM.name());
        String estTimeSec = redisGet(chatInfoKey, "EST_TIME");
        String estPriceStr = redisGet(chatInfoKey, OrderInfoEnum.EST_PRICE.name());
        chatManager.lockChat(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        redisPut(chatInfoKey, "OrderId", orderId);
        return String.format("订单已创建，订单编号%s，预计里程%sKm，预计全程耗时%s分钟，预计价格%s元。本轮对话已锁定，引导用户后续需求新建对话。",
                orderId,
                estDistanceKm,
                TimeUtil.getMinutes(estTimeSec),
                estPriceStr);
    }


    public record OrderParams(
            // 核心设计：使用 Map<Enum, Object> 接收多槽位
            // Key 为枚举，Value 为待填充的数据
            Map<OrderInfoEnum, String> slots) {
    }

    public enum PriceEnum{
        PRICE_RADIO("乘算倍率"),
        MILEAGE_FEE("里程费"),
        LONG_DISTANCE_SURCHARGE("远途/空驶费"),
        TIME_FEE("时长费"),
        EXPEDITED_FEE("加急费");

        private final String description;
        PriceEnum(String description) {
            this.description = description;
        }
    }

    private void putPricePara(String key, PriceEstimateVO vo){
        redisPut(key, PriceEnum.PRICE_RADIO.name(), vo.getEstPriceRadio().toPlainString());
        redisPut(key, PriceEnum.MILEAGE_FEE.name(), vo.getEstPriceBase().toPlainString());
        redisPut(key, PriceEnum.LONG_DISTANCE_SURCHARGE.name(), vo.getEstPriceDistance().toPlainString());
        redisPut(key, PriceEnum.TIME_FEE.name(), vo.getEstPriceTime().toPlainString());
        redisPut(key, PriceEnum.EXPEDITED_FEE.name(), vo.getEstPriceExpedited().toPlainString());
    }

    private void redisPut(String key, String hashKey, String content) {
        stringRedisTemplate.opsForHash().put(key, hashKey, content);
    }

    // 确定有才能用！
    private String redisGet(String key, String hashKey) {
        Object value = stringRedisTemplate.opsForHash().get(key, hashKey);
        if (value == null) throw new IllegalArgumentException("订单缺少字段：" + hashKey + "，请重新检查参数和报价");
        return value.toString();
    }

    private boolean validCoordinate(String value, boolean latitude) {
        try { return new BigDecimal(value).abs().compareTo(BigDecimal.valueOf(latitude ? 90 : 180)) <= 0; }
        catch (RuntimeException e) { return false; }
    }

    private Object redisGetObj(String key, String hashKey) {
        return stringRedisTemplate.opsForHash().get(key, hashKey);
    }

    private void redisDel(String key, String hashKey) {
        stringRedisTemplate.opsForHash().delete(key, hashKey);
    }

    private PriceEstimateVO getEstPrice(String estKm, String estTime, String isRev, String isExped,
            String vehicleType) {
        return com.fancy.rideagenthub.service.base.RidePricing.calculate(estKm, estTime,
                Integer.valueOf(vehicleType), "1".equals(isExped) ? 1 : 0);
    }
}



