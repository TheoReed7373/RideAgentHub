package com.fancy.rideagenthub.interceptor;

import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.security.CredentialStamp;
import com.fancy.rideagenthub.util.RedisScripts;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.*;

@Component
@RequiredArgsConstructor
public class RequestRateLimitInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate redis;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) return true;
        String key = "request:rate:" + CredentialStamp.of(request.getRemoteAddr());
        Long remaining = redis.execute(RedisScripts.SLIDING_WINDOW_RATE_LIMIT, List.of(key),
                "60000", "120", UUID.randomUUID().toString());
        if (remaining == null || remaining < 0) throw new BusinessException(429, "请求过于频繁，请稍后重试");
        return true;
    }
}
