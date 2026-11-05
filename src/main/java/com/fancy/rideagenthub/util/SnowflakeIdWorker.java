package com.fancy.rideagenthub.util;

/**
 * Snowflake ID Generator
 * 
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 -
 * 000000000000
 * sign bit | 41 bit timestamp | 5 bit datacenterId | 5 bit workerId | 12 bit
 * sequence
 */
public class SnowflakeIdWorker {

    /**
     * Start timestamp (2025-01-01)
     */
    private final long twepoch = 1735689600000L;

    /**
     * Bits for worker ID
     */
    private final long workerIdBits = 5L;

    /**
     * Bits for datacenter ID
     */
    private final long datacenterIdBits = 5L;

    /**
     * Max worker ID
     */
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);

    /**
     * Max datacenter ID
     */
    private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);

    /**
     * Bits for sequence
     */
    private final long sequenceBits = 12L;

    /**
     * Left shift for worker ID
     */
    private final long workerIdShift = sequenceBits;

    /**
     * Left shift for datacenter ID
     */
    private final long datacenterIdShift = sequenceBits + workerIdBits;

    /**
     * Left shift for timestamp
     */
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;

    /**
     * Mask for sequence (4095)
     */
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);

    private long workerId;
    private long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * @param workerId     (0~31)
     * @param datacenterId (0~31)
     */
    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(
                    String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
        }
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException(
                    String.format("datacenter Id can't be greater than %d or less than 0", maxDatacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * Get next ID (thread safe)
     * 
     * @return SnowflakeId
     */
    public synchronized long nextId() {
        long timestamp = timeGen();

        // If current time is less than last timestamp, clock moved backwards
        if (timestamp < lastTimestamp) {
            throw new RuntimeException(
                    String.format("Clock moved backwards. Refusing to generate id for %d milliseconds",
                            lastTimestamp - timestamp));
        }

        // If same millisecond, increase sequence
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            // sequence overflow
            if (sequence == 0) {
                // wait until next millisecond
                timestamp = tilNextMillis(lastTimestamp);
            }
        }
        // Different millisecond, reset sequence
        else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // bitwise OR to construct the ID
        return ((timestamp - twepoch) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;
    }

    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    protected long timeGen() {
        return System.currentTimeMillis();
    }
}
