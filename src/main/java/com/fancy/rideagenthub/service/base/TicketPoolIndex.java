package com.fancy.rideagenthub.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.Ticket;
import com.fancy.rideagenthub.domain.enums.TicketStatus;
import com.fancy.rideagenthub.mapper.TicketMapper;
import com.fancy.rideagenthub.util.RedisScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 按状态分池的工单排序缓存，按优先级、更新时间、主键倒序分页。
 * 写入提交后失效所有状态池，下次读取全量重建；不把单条写入当成完整索引。
 * 重建时校验版本与锁令牌，防止旧数据库快照或过期锁持有者覆盖新索引。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketPoolIndex {

    private static final double PRIORITY_WEIGHT = 1e13;
    private static final Duration INDEX_TTL = Duration.ofHours(2);
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(10);

    public record PoolSlice(String version, long total, List<Long> ids) {
    }

    private final StringRedisTemplate redisTemplate;
    private final TicketMapper ticketMapper;
    private final RedisLock redisLock;

    /**
     * 在工单事务提交后失效索引。跨状态变更和删除都统一处理，避免并发回表同步乱序。
     * 工单管理写入频率较低，选择重建完整状态池，换取分页总数和成员集合的一致性。
     */
    public void syncByTicketId(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            keys.add(RedisKeyConstants.TICKET_POOL_VERSION_KEY);
            for (TicketStatus status : TicketStatus.values()) {
                keys.add(RedisKeyConstants.ticketPoolReadyKey(status.getCode()));
                keys.add(RedisKeyConstants.ticketPoolKey(status.getCode()));
            }
            redisTemplate.execute(RedisScripts.INVALIDATE_TICKET_POOLS, keys, UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            // 索引固定到期，不因写入续期；即使失效失败，漂移也不会无限延长。
            log.warn("工单池索引失效失败: ticketId={}", ticketId, e);
        }
    }

    /** 读取完整索引的一页；不可用时交由调用方查询数据库。 */
    public Optional<PoolSlice> slice(int status, long offset, int size) {
        if (size <= 0 || offset < 0 || offset > Long.MAX_VALUE - size
                || TransactionSynchronizationManager.isActualTransactionActive()) {
            // 事务中的旧快照或未提交修改不能发布为公共索引。
            return Optional.empty();
        }
        try {
            TicketStatus.fromCode(status);
            Optional<PoolSlice> existing = readSlice(status, offset, size);
            if (existing.isPresent()) {
                return existing;
            }
            String lockKey = RedisKeyConstants.ticketPoolRebuildLockKey(status);
            String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
            if (token == null) {
                return Optional.empty();
            }
            try {
                existing = readSlice(status, offset, size);
                if (existing.isPresent()) {
                    return existing;
                }
                redisTemplate.opsForValue().setIfAbsent(RedisKeyConstants.TICKET_POOL_VERSION_KEY,
                        UUID.randomUUID().toString());
                String version = redisTemplate.opsForValue().get(RedisKeyConstants.TICKET_POOL_VERSION_KEY);
                if (version == null) {
                    return Optional.empty();
                }
                List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getTicketStatus, status));
                List<String> args = new ArrayList<>();
                args.add(version);
                args.add(token);
                args.add(String.valueOf(INDEX_TTL.toMillis()));
                for (Ticket ticket : tickets) {
                    if (ticket.getId() == null || ticket.getId() <= 0
                            || !Integer.valueOf(status).equals(ticket.getTicketStatus())) {
                        return Optional.empty();
                    }
                    args.add(Double.toString(score(ticket)));
                    // 等分时 Redis 按成员字典序排序；补零使其与数据库主键数值顺序一致。
                    args.add(String.format(Locale.ROOT, "%019d", ticket.getId()));
                }
                Long published = redisTemplate.execute(RedisScripts.PUBLISH_TICKET_POOL,
                        List.of(RedisKeyConstants.TICKET_POOL_VERSION_KEY, lockKey,
                                RedisKeyConstants.ticketPoolReadyKey(status), RedisKeyConstants.ticketPoolKey(status)),
                        args.toArray());
                return Long.valueOf(1L).equals(published) ? readSlice(status, offset, size) : Optional.empty();
            } finally {
                redisLock.unlock(lockKey, token);
            }
        } catch (RuntimeException e) {
            log.warn("读取工单池索引失败，降级为数据库查询: status={}", status, e);
            return Optional.empty();
        }
    }

    /** 回表期间发生变更时整页退回数据库，不能返回过时的总数或过滤后的残页。 */
    public boolean isCurrent(PoolSlice slice) {
        try {
            return slice.version().equals(redisTemplate.opsForValue().get(RedisKeyConstants.TICKET_POOL_VERSION_KEY));
        } catch (RuntimeException e) {
            log.warn("工单池索引版本校验失败，降级为数据库查询", e);
            return false;
        }
    }

    private Optional<PoolSlice> readSlice(int status, long offset, int size) {
        List<?> result = redisTemplate.execute(RedisScripts.READ_TICKET_POOL,
                List.of(RedisKeyConstants.TICKET_POOL_VERSION_KEY, RedisKeyConstants.ticketPoolReadyKey(status),
                        RedisKeyConstants.ticketPoolKey(status)),
                String.valueOf(offset), String.valueOf(offset + size - 1));
        if (result == null || result.size() < 2) {
            return Optional.empty();
        }
        long total = Long.parseLong((String) result.get(1));
        List<Long> ids = new ArrayList<>();
        for (int i = 2; i < result.size(); i++) {
            ids.add(Long.valueOf((String) result.get(i)));
        }
        if (total < 0 || ids.size() != Math.min(size, Math.max(0L, total - offset))
                || ids.stream().anyMatch(id -> id <= 0) || ids.stream().distinct().count() != ids.size()) {
            return Optional.empty();
        }
        return Optional.of(new PoolSlice((String) result.getFirst(), total, List.copyOf(ids)));
    }

    private double score(Ticket ticket) {
        // 数据库时间不带时区；用固定 UTC 映射，避免实例时区和夏令时改变排序。
        long millis = ticket.getUpdatedAt() == null ? 0L
                : ticket.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        return (ticket.getPriority() == null ? 0 : ticket.getPriority()) * PRIORITY_WEIGHT + millis;
    }
}
