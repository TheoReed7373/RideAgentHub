package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.agentbase.amap.service.AmapRouteService;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.RideOrder;
import com.fancy.rideagenthub.domain.enums.RideOrderStatus;
import com.fancy.rideagenthub.domain.enums.UserRole;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.vo.EstRouteVO;
import com.fancy.rideagenthub.domain.vo.RideOrderVO;
import com.fancy.rideagenthub.domain.vo.PriceEstimateVO;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.RideOrderMapper;
import com.fancy.rideagenthub.service.RideOrderService;
import com.fancy.rideagenthub.service.base.OrderRouteService;
import com.fancy.rideagenthub.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderSearchTool {
    private final RideOrderService rideOrderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RideOrderMapper rideOrderMapper;
    private final AmapRouteService amapRouteService;
    private final OrderRouteService orderRouteService;
    @jakarta.annotation.Resource private org.redisson.api.RedissonClient redisson;
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> CANCEL =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                if redis.call('HGET', KEYS[1], 'readyForCancel') ~= ARGV[1] then return 0 end
                local verified = tonumber(redis.call('HGET', KEYS[1], 'cancelVerifiedAt') or '0')
                if tonumber(ARGV[2]) - verified > 300000 then return 0 end
                redis.call('HDEL', KEYS[1], 'readyForCancel', 'cancelVerifiedAt')
                return 1
                """, Long.class);

    private static final String REDIS_READY_FOR_CANCEL = "readyForCancel";
    private static final String REDIS_CANCEL_FEE = "cancelFee";

    @Tool(description = "根据订单号查询订单信息")
    public String searchOrderById(@ToolParam(description = "订单号")String orderId,
                                  ToolContext toolContext){
        log.info("[OrderSearchTool]: searchOrderById(orderId={})", orderId);
        ToolNotifySupport.notifyToolListener(toolContext, "正在查询订单信息 (searchOrderById)");
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        return rideOrderService.getOrderDetail(orderId, userId, UserRole.USER).toString();
    }

    @Tool(description = "根据时段查询用户订单信息")
    public String searchOrdersByTime(@ToolParam(description = "开始时间，格式yyyy-MM-dd HH:mm:ss")String startTime,
                                     @ToolParam(description = "结束时间，格式yyyy-MM-dd HH:mm:ss")String endTime,
                                     ToolContext toolContext){
        log.info("[OrderSearchTool]: searchOrdersByTime(startTime={}, endTime={})", startTime, endTime);
        ToolNotifySupport.notifyToolListener(toolContext, "正在查询用户订单信息(按时间) (searchOrdersByTime)");
        String userIdStr = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();

        LocalDateTime start = TimeUtil.parse(startTime);
        LocalDateTime end = TimeUtil.parse(endTime);
        if (start == null || end == null) {
            return "开始时间或结束时间为空/格式不正确，请使用格式 yyyy-MM-dd HH:mm:ss";
        }
        if (end.isBefore(start)) {
            return "结束时间不能早于开始时间";
        }

        String userId;
        try {
            userId = userIdStr.trim();
            if (userId.isEmpty()) {
                return "用户ID不合法";
            }
        } catch (Exception e) {
            return "用户ID不合法";
        }

        List<RideOrderVO> found = rideOrderService.getPassengerOrdersByTime(userId, start, end, 201);
        boolean truncated = found.size() > 200;
        List<RideOrderVO> matched = truncated ? found.subList(0, 200) : found;

        if (matched.isEmpty()) {
            return String.format("在 %s ~ %s 未查询到订单。", startTime, endTime);
        }

        boolean useSimple = matched.size() > 2;
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format("共查询到%d笔订单（%s~%s）：\n", matched.size(), startTime, endTime));
        for (RideOrderVO vo : matched) {
            if (vo == null) {
                continue;
            }
            sb.append("- ");
            sb.append(useSimple ? vo.toSimpleString() : vo.toString());
            sb.append("\n");
        }
        if (useSimple) {
            sb.append("提示：如需某笔订单的详细信息请调用searchOrderById()。\n");
        }
        if (truncated) sb.append("结果超过200笔，仅展示前200笔，请缩小查询时间范围。\n");
        return sb.toString();
    }

    @Tool(description = "查询用户最近的一笔或几笔订单")
    public String searchOrdersByCount(@ToolParam(required = false, description = "查询数量，默认1") Integer limit,
                                      ToolContext toolContext){
        int finalLimit = (limit == null || limit <= 0) ? 1 : limit;
        finalLimit = Math.min(finalLimit, 20);
        log.info("[OrderSearchTool]: searchOrdersByCount(limit={})", finalLimit);
        ToolNotifySupport.notifyToolListener(toolContext, "正在查询用户最近订单 (searchOrdersByCount)");

        String userIdStr = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        String userId;
        try {
            userId = userIdStr.trim();
            if (userId.isEmpty()) {
                return "用户ID不合法";
            }
        } catch (Exception e) {
            return "用户ID不合法";
        }

        PageResult<RideOrderVO> pr = rideOrderService.getPassengerOrderHistory(userId, 1, finalLimit, null);
        List<RideOrderVO> records = pr == null ? null : pr.getRecords();
        if (records == null || records.isEmpty()) {
            return "未查询到订单。";
        }

        boolean useSimple = records.size() > 2;
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format("查询到最近%d笔订单：\n", records.size()));
        for (RideOrderVO vo : records) {
            if (vo == null) {
                continue;
            }
            sb.append("- ");
            sb.append(useSimple ? vo.toSimpleString() : vo.toString());
            sb.append("\n");
        }
        if (useSimple) {
            sb.append("提示：如需某笔订单的详细信息请调用searchOrderById()。\n");
        }
        return sb.toString();
    }

    @Tool(description = "检查取消订单所需条件与费用")
    public String verifyCancelConditions(@ToolParam(description = "订单号")String orderId,
                              ToolContext toolContext){
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        log.info("[OrderSearchTool]: verifyCancelConditions(orderId={})", orderId);
        ToolNotifySupport.notifyToolListener(toolContext, "检查取消订单条件 (verifyCancelConditions)");
        String userIdStr = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();

        String oid;
        try {
            oid = orderId.trim();
            if (oid.isEmpty()) {
                return "订单号不合法";
            }
        } catch (Exception e) {
            return "订单号不合法";
        }

        RideOrderVO vo;
        try {
            vo = rideOrderService.getOrderDetail(oid, userIdStr, UserRole.USER);
        } catch (Exception e) {
            return "订单不存在或查询失败";
        }

        // 权限兜底：只允许操作自己的订单
        try {
            String userId = userIdStr.trim();
            if (!userId.isEmpty() && vo.getUserId() != null && !vo.getUserId().equals(userId)) {
                return "无权限操作该订单";
            }
        } catch (Exception invalidIdentity) {
            return "用户身份无效";
        }

        Integer status = vo.getOrderStatus();
        if (status == null) {
            return "订单状态异常";
        }
        if (status == RideOrderStatus.CANCELLED.getCode()) {
            return "订单已经是取消状态，无需重复操作。";
        }
        if (status >= RideOrderStatus.IN_TRIP.getCode() && status < RideOrderStatus.FINISHED_WAIT_PAY.getCode()) {
            return "行程已开始，无法直接取消，请创建工单联系人工协助结束行程。";
        }
        if (status >= RideOrderStatus.FINISHED_WAIT_PAY.getCode()) {
            return "订单已进入结算/支付阶段，无法直接取消，请创建工单联系人工协助。";
        }

        // 司机是否接单 + 接单时长判断
        LocalDateTime acceptTime = vo.getDriverAcceptTime();
        if (acceptTime != null) {
            long minutes = Math.max(0, Duration.between(acceptTime, com.fancy.rideagenthub.util.BusinessTime.now()).toMinutes());
            if (minutes > 5) {
                BigDecimal cancelFee = calcCancelFee(minutes);
                stringRedisTemplate.opsForHash().put(chatInfoKey, REDIS_CANCEL_FEE, cancelFee.toPlainString());
                stringRedisTemplate.opsForHash().put(chatInfoKey, REDIS_READY_FOR_CANCEL, Long.valueOf(oid).toString());
                stringRedisTemplate.opsForHash().put(chatInfoKey, "cancelVerifiedAt", String.valueOf(System.currentTimeMillis()));
                return String.format("司机已接单超过5分钟(已接单%d分钟)。取消需支付取消费 %s 元给司机作为空驶补偿。请确认用户是否想取消。",
                        minutes, cancelFee.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        }

        // 免费取消
        stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_CANCEL_FEE);
        stringRedisTemplate.opsForHash().put(chatInfoKey, REDIS_READY_FOR_CANCEL, Long.valueOf(oid).toString());
                stringRedisTemplate.opsForHash().put(chatInfoKey, "cancelVerifiedAt", String.valueOf(System.currentTimeMillis()));
        return "当前可以无责免费取消订单。";
    }

    @Tool(description = "取消订单")
    public String cancelOrder(@ToolParam(description = "订单号")String orderId,
                              @ToolParam(description = "取消原因")String reason,
                              ToolContext toolContext){
        String chatInfoKey = RedisKeyConstants.chatInfoKey(toolContext.getContext().get(ToolContextKeyConstants.CHAT_ID).toString());
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        log.info("[OrderSearchTool]: cancelOrder(orderId={}, reason={})", orderId, reason);
        ToolNotifySupport.notifyToolListener(toolContext, "取消订单 (cancelOrder)");
        if(!stringRedisTemplate.opsForHash().hasKey(chatInfoKey, REDIS_READY_FOR_CANCEL)){
            return "先使用verifyCancelConditions()检查取消订单条件";
        }

        String oid;
        try {
            oid = orderId.trim();
            if (oid.isEmpty()) {
                return "订单号不合法";
            }
        } catch (Exception e) {
            return "订单号不合法";
        }

        RideOrderVO vo;
        try {
            vo = rideOrderService.getOrderDetail(oid, userId, UserRole.USER);
        } catch (Exception e) {
            return "订单不存在或查询失败";
        }
        try {
            String uid = userId.trim();
            if (!uid.isEmpty() && vo.getUserId() != null && !vo.getUserId().equals(uid)) {
                return "无权限操作该订单";
            }
        } catch (Exception invalidIdentity) {
            return "用户身份无效";
        }

        if (!Long.valueOf(1).equals(stringRedisTemplate.execute(CANCEL, List.of(chatInfoKey),
                Long.valueOf(oid).toString(), String.valueOf(System.currentTimeMillis())))) {
            return "取消确认对象不匹配、已过期或已使用，请重新检查此订单";
        }
        String cancelFeeStr;
        try {
            cancelFeeStr = rideOrderService.cancelOrder(oid, userId, 1, reason);
        } catch (BusinessException be) {
            return be.getMessage();
        } catch (Exception e) {
            return "取消失败，请稍后重试";
        } finally {
            // 一次性令牌：不管成功失败都清理，避免误用
            stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_READY_FOR_CANCEL);
        }
        // 清理取消费缓存
        stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_CANCEL_FEE);

        if (cancelFeeStr != null && !cancelFeeStr.isBlank()) {
            return String.format("取消成功，需要支付取消费%s元。", cancelFeeStr);
        }
        return "取消成功。";
    }

    @Tool(description = "修改订单目的地")
    public String modifyOrderDestination(@ToolParam(description = "订单号")String orderId,
                                         @ToolParam(description = "新目的地地址")String newDestAddress,
                                         @ToolParam(description = "目的地经度")String newDestLng,
                                         @ToolParam(description = "目的地纬度")String newDestLat,
                                         ToolContext toolContext){
        String canonical;
        try { canonical = Long.valueOf(orderId).toString(); } catch (RuntimeException e) { return "订单号不合法"; }
        var lock = redisson.getLock(RedisKeyConstants.orderLockKey(canonical));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) return "订单正在处理中，请稍后重试";
            return modifyDestinationUnderLock(canonical, newDestAddress, newDestLng, newDestLat, toolContext);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "操作中断，请稍后重试";
        } finally {
            if (acquired) {
                try { lock.unlock(); } catch (RuntimeException e) { log.warn("订单锁释放失败", e); }
            }
        }
    }

    private String modifyDestinationUnderLock(String orderId, String newDestAddress, String newDestLng,
            String newDestLat, ToolContext toolContext) {
        log.info("[OrderSearchTool]: modifyOrderDestination(orderId={}, newDestAddress={}, lng={}, lat={})",
                orderId, newDestAddress, newDestLng, newDestLat);
        ToolNotifySupport.notifyToolListener(toolContext, "修改订单目的地 (modifyOrderDestination)");

        String userIdStr = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();

        Long oid;
        try {
            oid = Long.parseLong(orderId);
        } catch (Exception e) {
            return "订单号不合法";
        }

        BigDecimal lng;
        BigDecimal lat;
        try {
            lng = new BigDecimal(newDestLng);
            lat = new BigDecimal(newDestLat);
        } catch (Exception e) {
            return "目的地经纬度不合法";
        }

        RideOrder order = rideOrderMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getOrderId, oid)
                .eq(RideOrder::getIsDeleted, 0));
        if (order == null) {
            return "订单不存在";
        }

        try {
            Long uid = Long.parseLong(userIdStr);
            if (order.getUserId() == null || !order.getUserId().equals(uid)) {
                return "无权限操作该订单";
            }
        } catch (Exception invalidIdentity) {
            return "用户身份无效";
        }

        if (lng.abs().compareTo(BigDecimal.valueOf(180)) > 0 || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0) return "目的地经纬度超出范围";
        Integer status = order.getOrderStatus();
        if (status == null) {
            return "订单状态异常";
        }
        if (status == RideOrderStatus.CANCELLED.getCode()) {
            return "订单已取消，无法修改目的地";
        }
        if (status >= RideOrderStatus.FINISHED_WAIT_PAY.getCode()) {
            return "订单已进入结算/支付阶段，无法修改目的地";
        }

        // 重新路径规划：用高德返回的 estKm / estTime 作为重算依据（与 OrderTool 一致）
        if (order.getStartLat() == null || order.getStartLng() == null) {
            return "订单起点经纬度缺失，无法重新路径规划";
        }

        EstRouteVO route;
        try {
            route = amapRouteService.getDrivingRouteEst(
                    order.getStartLng().doubleValue(),
                    order.getStartLat().doubleValue(),
                    lng.doubleValue(),
                    lat.doubleValue());
        } catch (BusinessException be) {
            return be.getMessage();
        } catch (Exception e) {
            return "路径规划失败，请稍后重试";
        }
        if (route == null || route.getEstKm() == null || route.getEstTime() == null) {
            return "路径规划失败：返回里程/时间为空";
        }

        String oldTraceId = order.getMongoTraceId();
        String newTraceId = UUID.randomUUID().toString();

        BigDecimal distanceKm;
        try {
            distanceKm = new BigDecimal(route.getEstKm()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return "路径规划失败：里程格式异常";
        }

        boolean alreadyDeparted = status >= RideOrderStatus.IN_TRIP.getCode()
                && status < RideOrderStatus.FINISHED_WAIT_PAY.getCode();

        PriceEstimateVO est = calcEstPriceByOrderToolRule(
                distanceKm,
                route.getEstTime(),
                order.getIsExpedited(),
                order.getVehicleType(),
                order.getPriceRadio(),
                alreadyDeparted);
        LocalDateTime now = com.fancy.rideagenthub.util.BusinessTime.now();

        orderRouteService.addOrder(newTraceId, route);
        int updated;
        try {
            updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
                .eq(RideOrder::getOrderId, oid)
                .eq(RideOrder::getUserId, Long.valueOf(userIdStr))
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getOrderStatus, status)
                .eq(oldTraceId != null, RideOrder::getMongoTraceId, oldTraceId)
                .isNull(oldTraceId == null, RideOrder::getMongoTraceId)
                .set(RideOrder::getEndAddress, normalizeText(newDestAddress))
                .set(RideOrder::getEndLat, lat)
                .set(RideOrder::getEndLng, lng)
                .set(RideOrder::getMongoTraceId, newTraceId)
                .set(RideOrder::getEstDistance, distanceKm)
                .set(RideOrder::getEstPrice, est.getEstPrice())
                .set(RideOrder::getPriceBase, est.getEstPriceBase())
                .set(RideOrder::getPriceTime, est.getEstPriceTime())
                .set(RideOrder::getPriceDistance, est.getEstPriceDistance())
                .set(RideOrder::getPriceExpedited, est.getEstPriceExpedited())
                .set(RideOrder::getPriceRadio, est.getEstPriceRadio())
                .set(RideOrder::getUpdateTime, now));
        } catch (RuntimeException failure) {
            try { orderRouteService.deleteByTraceId(newTraceId); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }

        if (updated <= 0) {
            // 回滚：订单未更新成功，删除新写入的 Mongo 路径记录
            try {
                orderRouteService.deleteByTraceId(newTraceId);
            } catch (Exception ignored) {
                // ignore
            }
            return "修改失败（订单状态可能已变化），请刷新后重试";
        }

        // 删除旧 Mongo 路径记录（若存在）
        if (oldTraceId != null && !oldTraceId.isBlank() && !oldTraceId.equals(newTraceId)) {
            try {
                orderRouteService.deleteByTraceId(oldTraceId);
            } catch (Exception ignored) {
                // ignore
            }
        }

        if (alreadyDeparted) {
            return String.format("已修改目的地并更新预估：预计里程%sKm，预计价格%s元（按新路线和统一计价规则计算）。",
                    distanceKm.stripTrailingZeros().toPlainString(), est.getEstPrice().toPlainString());
        }
        return String.format("已修改目的地并更新预估：预计里程%sKm，预计价格%s元。",
                distanceKm.stripTrailingZeros().toPlainString(), est.getEstPrice().toPlainString());
    }

    private BigDecimal calcCancelFee(long acceptedMinutes) {
        // 规则兜底：超过5分钟后，按“基础5元 + 超出分钟×0.5元”计算，最高20元
        long over = Math.max(0, acceptedMinutes - 5);
        BigDecimal fee = new BigDecimal("5.00").add(new BigDecimal(over).multiply(new BigDecimal("0.50")));
        if (fee.compareTo(new BigDecimal("20.00")) > 0) {
            fee = new BigDecimal("20.00");
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private PriceEstimateVO calcEstPriceByOrderToolRule(BigDecimal distanceKm,
                                                        String durationSecStr,
                                                        Integer isExpedited,
                                                        Integer vehicleType,
                                                        BigDecimal existingRadio,
                                                        boolean alreadyDeparted) {
        return com.fancy.rideagenthub.service.base.RidePricing.calculate(distanceKm.toPlainString(),
                durationSecStr, vehicleType, isExpedited);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }



}



