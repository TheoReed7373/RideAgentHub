package com.fancy.rideagenthub.interceptor;

import com.fancy.rideagenthub.security.UserToken;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token 解析拦截器
 * 从请求头提取 Token，查询 Redis 并写入上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthTokenInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";
    private static final String TOKEN_HEADER = "X-Auth-Token";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = extractToken(request);

        if (token != null && !token.isBlank()) {
            UserToken userToken = tokenService.getToken(token);
            if (userToken != null) {
                // 检查是否过期
                if (userToken.getExpiresAtEpochMs() > System.currentTimeMillis()) {
                    UserTokenContext.set(userToken);
                    log.debug("Token validated: userId={}", userToken.getUserId());
                } else {
                    log.debug("Token expired");
                    // 可选：删除过期 token
                    tokenService.deleteToken(token);
                }
            } else {
                log.debug("Token not found in Redis");
            }
        }

        // 始终放行，权限控制由 @RequirePermission 注解处理
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        // 清理上下文，防止线程复用污染
        UserTokenContext.clear();
    }

    /**
     * 从请求头提取 Token
     */
    private String extractToken(HttpServletRequest request) {
        // 优先从 Authorization: Bearer xxx 获取
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        // 其次从 X-Auth-Token 获取
        String tokenHeader = request.getHeader(TOKEN_HEADER);
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return tokenHeader.trim();
        }

        return null;
    }
}
