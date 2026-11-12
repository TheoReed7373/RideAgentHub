package com.fancy.rideagenthub.config;

import com.fancy.rideagenthub.util.SnowflakeIdWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.redisson.api.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Configuration
public class IdGeneratorConfig {
    @Bean(destroyMethod = "close")
    public NodeLease idNodeLease(RedissonClient redis, @Value("${id.worker-id:1}") long workerId,
            @Value("${id.datacenter-id:1}") long datacenterId) {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(workerId, datacenterId);
        RLock lock = redis.getLock("id:node:" + datacenterId + ":" + workerId);
        long owner = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        if (!lock.tryLockAsync(0, -1, TimeUnit.SECONDS, owner).toCompletableFuture().join())
            throw new IllegalStateException("雪花ID节点号已被其他实例使用，请配置不同的id.worker-id");
        SnowflakeIdWorker guarded = new SnowflakeIdWorker(workerId, datacenterId) {
            @Override public synchronized long nextId() {
                if (!lock.isHeldByThread(owner)) throw new IllegalStateException("ID节点租约已失效，拒绝继续发号");
                return super.nextId();
            }
        };
        return new NodeLease(guarded, lock, owner);
    }
    @Bean
    public SnowflakeIdWorker snowflakeIdWorker(NodeLease lease) { return lease.worker; }

    public static final class NodeLease implements AutoCloseable {
        private final SnowflakeIdWorker worker;
        private final RLock lock;
        private final long owner;
        NodeLease(SnowflakeIdWorker worker, RLock lock, long owner) { this.worker = worker; this.lock = lock; this.owner = owner; }
        @Override public void close() {
            if (lock.isHeldByThread(owner)) lock.unlockAsync(owner).toCompletableFuture().join();
        }
    }
}
