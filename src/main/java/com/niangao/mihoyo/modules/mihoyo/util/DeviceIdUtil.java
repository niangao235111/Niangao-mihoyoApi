package com.niangao.mihoyo.modules.mihoyo.util;

import java.security.SecureRandom;

/** 米哈游设备标识生成器，同一登录会话始终使用同一个标识。 */
public final class DeviceIdUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private DeviceIdUtil() {}

    /** 生成 53 位随机设备标识。由 start 创建并保存，同一登录流程不随请求重新生成。 */
    public static String generate() {
        StringBuilder value = new StringBuilder(53);
        for (int i = 0; i < 53; i++)
            value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }
}
