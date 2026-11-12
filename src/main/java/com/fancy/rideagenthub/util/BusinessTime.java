package com.fancy.rideagenthub.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 业务 DATETIME 统一使用中国标准时间，避免随宿主机默认时区变化。 */
public final class BusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private BusinessTime() {}
    public static LocalDateTime now() { return LocalDateTime.now(ZONE); }
}
