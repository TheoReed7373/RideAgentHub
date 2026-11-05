package com.fancy.rideagenthub.domain.enums;

/**
 * 系统角色枚举
 */
public enum UserRole {
    USER,
    ADMIN,
    SUPPORT,
    DRIVER;

    /**
     * 从字符串解析角色（大小写不敏感）
     */
    public static UserRole fromString(String role) {
        if (role == null || role.isBlank()) {
            return USER;
        }
        try {
            return valueOf(role.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }
    }

    /**
     * 检查是否为后台角色（非USER）
     */
    public boolean isBackendRole() {
        return this != USER;
    }
}
