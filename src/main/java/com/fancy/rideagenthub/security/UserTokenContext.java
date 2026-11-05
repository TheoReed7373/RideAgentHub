package com.fancy.rideagenthub.security;

import com.fancy.rideagenthub.exception.UnauthorizedException;

import java.util.Arrays;

/**
 * 用户令牌上下文持有器（ThreadLocal）</br>
 * 业务层通过此类获取当前登录用户信息
 */
public final class UserTokenContext {

    private static final ThreadLocal<UserToken> HOLDER = new ThreadLocal<>();

    private UserTokenContext() {
        // 禁止实例化
    }

    /**
     * 设置当前用户令牌
     */
    public static void set(UserToken token) {
        HOLDER.set(token);
    }

    /**
     * 获取当前用户令牌（可能为 null）
     */
    public static UserToken getNullable() {
        return HOLDER.get();
    }

    /**
     * 获取当前用户令牌（若为空则抛出 401 异常）
     */
    public static UserToken getRequired() {
        UserToken token = HOLDER.get();
        if (token == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        return token;
    }

    /**
     * 清除当前用户令牌
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 获取当前用户 ID（Long 类型）
     */
    public static Long getUserIdInLong() {
        UserToken token = getRequired();
        return token.getUserId();
    }

    /**
     * 获取当前用户 ID（String 类型）
     */
    public static String getUserIdInString() {
        return String.valueOf(getUserIdInLong());
    }

    /**
     * 获取当前用户角色
     */
    public static String getRole() {
        UserToken token = getRequired();
        return token.getRole();
    }

    /**
     * 检查当前用户是否拥有指定角色之一
     */
    public static boolean hasAnyRole(String... roles) {
        UserToken token = getNullable();
        if (token == null || token.getRole() == null) {
            return false;
        }
        String currentRole = token.getRole().toUpperCase();
        return Arrays.stream(roles)
                .map(String::toUpperCase)
                .anyMatch(r -> r.equals(currentRole));
    }

    /**
     * 检查当前用户是否已登录
     */
    public static boolean isLoggedIn() {
        return getNullable() != null;
    }
}
