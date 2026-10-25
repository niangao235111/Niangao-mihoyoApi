package com.niangao.mihoyo.modules.mihoyo.util;

import java.security.SecureRandom;

/** 米哈游设备标识生成器，同一登录会话始终使用同一个标识。 */
public final class DeviceIdUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private DeviceIdUtil() {}

    /** 只接受本站生成的登录设备或旧版账号派生 UUID，不重新格式化原设备。 */
    public static String requireValid(String id) {
        if (id == null || !id.matches("(?:[a-z0-9]{53}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})"))
            throw new IllegalArgumentException("Invalid device ID");
        return id;
    }

    /** 生成 53 位随机设备标识。由 start 创建并保存，同一登录流程不随请求重新生成。 */
    public static String generate() {
        StringBuilder value = new StringBuilder(53);
        for (int i = 0; i < 53; i++)
            value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }
}
