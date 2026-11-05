package com.fancy.rideagenthub.domain.enums;

/**
 * 邮件验证码场景枚举
 */
public enum EmailScene {
    REGISTER,
    LOGIN,
    RESET_PASSWORD;

    /**
     * 从字符串解析场景（大小写不敏感）
     */
    public static EmailScene fromString(String scene) {
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("Scene cannot be empty");
        }
        try {
            return valueOf(scene.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid scene: " + scene);
        }
    }
}
