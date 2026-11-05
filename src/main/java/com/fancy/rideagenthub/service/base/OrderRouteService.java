package com.fancy.rideagenthub.service.base;

import com.mongodb.client.result.DeleteResult;
import com.fancy.rideagenthub.domain.entity.OrderRoute;
import com.fancy.rideagenthub.domain.vo.EstRouteVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OrderRouteService {
    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 创建订单轨迹记录
     *
     * @param traceId 业务唯一ID
     * @param estRouteVO 预估轨迹信息
     */
    public void addOrder(String traceId, EstRouteVO estRouteVO) {
        if (traceId == null || estRouteVO == null) {
            throw new IllegalArgumentException("TraceID and RouteVO cannot be null");
        }

        OrderRoute orderRoute = new OrderRoute();

        // 1. 设置主键
        orderRoute.setMongoTraceId(traceId);

        // 2. 属性拷贝 (将 VO 的属性值复制到 Entity)
        // 注意：属性名必须一致 (estRoute, estPolyline, estKm)
        BeanUtils.copyProperties(estRouteVO, orderRoute);

        // 4. 保存到数据库
        mongoTemplate.save(orderRoute);
        log.info("OrderRoute created for TraceID: {}", traceId);
    }

    /**
     * 更新真实轨迹
     *
     * @param traceId 业务唯一ID
     * @param realPolyline 真实轨迹字符串
     */
    public void updateRealPolyline(String traceId, String realPolyline) {
        // 1. 构建查询条件：根据主键 (_id) 查找
        Query query = Query.query(Criteria.where("_id").is(traceId));

        // 2. 构建更新内容：只修改 realPolyline 和 updateTime
        Update update = new Update();
        update.set("realPolyline", realPolyline);

        // 3. 执行更新
        // updateFirst 返回 UpdateResult，可以检查 updateResult.getModifiedCount() 确认是否更新成功
        mongoTemplate.updateFirst(query, update, OrderRoute.class);

        log.info("RealPolyline updated for TraceID: {}", traceId);
    }

    /**
     * 根据 traceId 获取订单轨迹记录
     *
     * @param traceId 业务唯一ID
     * @return 订单轨迹实体；不存在时返回 null
     */
    public OrderRoute getByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("TraceID cannot be blank");
        }

        // 直接按主键查询（对应 MongoDB 的 _id）
        OrderRoute orderRoute = mongoTemplate.findById(traceId, OrderRoute.class);
        if (orderRoute == null) {
            log.info("OrderRoute not found for TraceID: {}", traceId);
        }
        return orderRoute;
    }

    /**
     * 根据 traceId 删除订单轨迹记录
     *
     * @param traceId 业务唯一ID
     * @return 是否删除成功（存在并删除返回 true；不存在返回 false）
     */
    public boolean deleteByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("TraceID cannot be blank");
        }

        Query query = Query.query(Criteria.where("_id").is(traceId));
        DeleteResult result = mongoTemplate.remove(query, OrderRoute.class);

        boolean deleted = result.getDeletedCount() > 0;
        if (deleted) {
            log.info("OrderRoute deleted for TraceID: {}", traceId);
        } else {
            log.info("OrderRoute not found for TraceID: {}", traceId);
        }
        return deleted;
    }
}
