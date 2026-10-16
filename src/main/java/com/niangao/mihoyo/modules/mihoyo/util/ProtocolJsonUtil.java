package com.niangao.mihoyo.modules.mihoyo.util;

import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;

/** 仅用于第三方协议解析；Gson 对象不会直接进入公开 VO。 */
public final class ProtocolJsonUtil {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    private ProtocolJsonUtil() {}

    /** 读取可选上游基础类型字段，缺失或 JSON null 时返回 null，数字等基础值按字符串读取。 */
    public static String optional(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive()) throw new BusinessException(502, "上游字段类型异常: " + key);
        return value.getAsString();
    }

    /** 在可选读取的基础上要求非空，用于账号身份等不可缺失的协议字段。 */
    public static String required(JsonObject object, String key) {
        String value = optional(object, key);
        if (value == null || value.isBlank()) throw new BusinessException(502, "上游缺少字段: " + key);
        return value;
    }

    /** 读取上游整数字段，将解析异常转换为脱敏的 502 业务错误。 */
    public static int integer(JsonObject object, String key) {
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new BusinessException(502, "上游整数字段异常: " + key);
        }
    }

    /** 把 true/false 或 1/0 形式统一转换为布尔值，其余值视为上游格式异常。 */
    public static boolean bool(JsonObject object, String key) {
        String value = required(object, key);
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        throw new BusinessException(502, "上游布尔字段异常: " + key);
    }
}
