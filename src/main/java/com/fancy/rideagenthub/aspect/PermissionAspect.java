package com.fancy.rideagenthub.aspect;

import com.fancy.rideagenthub.annotation.RequirePermission;
import com.fancy.rideagenthub.exception.ForbiddenException;
import com.fancy.rideagenthub.exception.UnauthorizedException;
import com.fancy.rideagenthub.security.UserToken;
import com.fancy.rideagenthub.security.UserTokenContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 权限控制切面
 * 处理 @RequirePermission 注解
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Around("@annotation(com.fancy.rideagenthub.annotation.RequirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取注解
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequirePermission annotation = signature.getMethod().getAnnotation(RequirePermission.class);

        // 获取当前用户
        UserToken userToken = UserTokenContext.getNullable();

        // 标注了注解就至少要求登录
        if (userToken == null) {
            throw new UnauthorizedException("请先登录");
        }

        // 检查角色（如果指定了角色列表）
        String[] requiredRoles = annotation.value();
        if (requiredRoles.length > 0) {
            String currentRole = userToken.getRole();
            boolean hasPermission = currentRole != null && Arrays.stream(requiredRoles)
                    .map(String::toUpperCase)
                    .anyMatch(role -> role.equals(currentRole.toUpperCase()));

            if (!hasPermission) {
                log.warn("Permission denied: userId={}, role={}, required={}",
                        userToken.getUserId(), currentRole, Arrays.toString(requiredRoles));
                throw new ForbiddenException("无权访问该资源");
            }
        }

        return joinPoint.proceed();
    }
}
