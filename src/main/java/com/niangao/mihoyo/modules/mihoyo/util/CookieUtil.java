package com.niangao.mihoyo.modules.mihoyo.util;

import com.niangao.mihoyo.common.exception.BusinessException;

/** 组装 Cookie 之前验证内容，禁止分隔符和 CRLF 注入。 */
public final class CookieUtil {
    private CookieUtil() {}

    /** 校验即将放入 Cookie 的字符串，拒绝控制字符、分隔符和超长内容。该校验只检查格式，不判断凭证是否有效。 */
    public static String validate(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > 8192
                || !value.matches("[\\x21-\\x7E]+")
                || value.contains(";")
                || value.contains(",")) {
            throw new BusinessException(400, "Invalid credential field: " + name);
        }
        return value;
    }

    /** 按上游兼容字段名组装 SToken Cookie，供凭证转换接口使用。调用前必须校验凭证内容。 */
    public static String stoken(String stoken, String stuid, String mid) {
        return "stoken="
                + stoken
                + "; stoken_v2="
                + stoken
                + "; stuid="
                + stuid
                + "; stuid_v2="
                + stuid
                + "; ltuid="
                + stuid
                + "; ltuid_v2="
                + stuid
                + "; account_id="
                + stuid
                + "; mid="
                + mid
                + "; ltmid_v2="
                + mid;
    }

    /** 组装关联角色查询用的 LToken Cookie；这里不携带原始 SToken。 */
    public static String ltoken(String ltoken, String stuid, String mid) {
        return "ltoken="
                + ltoken
                + "; ltoken_v2="
                + ltoken
                + "; ltuid="
                + stuid
                + "; ltuid_v2="
                + stuid
                + "; account_id="
                + stuid
                + "; account_id_v2="
                + stuid
                + "; mid="
                + mid
                + "; ltmid_v2="
                + mid;
    }
}
