package com.fancy.rideagenthub.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限控制注解
 * 标注在方法上，用于方法级访问控制
 *
 * 使用示例：
 * - @RequirePermission({"ADMIN"}) - 仅管理员可访问
 * - @RequirePermission({"ADMIN", "SUPPORT"}) - 管理员或客服可访问
 * - @RequirePermission - 仅要求登录，不限制角色
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 允许访问的角色列表（大小写不敏感）
     * 为空表示仅要求登录，不限制角色
     */
    String[] value() default {};
}
