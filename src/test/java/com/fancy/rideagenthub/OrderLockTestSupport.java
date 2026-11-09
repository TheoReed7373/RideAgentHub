package com.fancy.rideagenthub;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

final class OrderLockTestSupport {

    private OrderLockTestSupport() {
    }

    static RedissonClient availableClient() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(3L, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return client;
    }
}
