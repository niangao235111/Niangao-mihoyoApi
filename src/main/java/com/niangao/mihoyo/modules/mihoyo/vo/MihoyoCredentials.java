package com.niangao.mihoyo.modules.mihoyo.vo;

/**
 * 米哈游凭证响应，只随 verify 成功返回。record 的字段引用不可变，自动访问方法例如 stoken()；不是网站登录令牌。
 *
 * @param credentialType 固定为 stoken
 * @param stoken 调用米哈游所需的 SToken
 * @param stuid 通行证账号 ID
 * @param mid 米哈游账号标识
 * @param loginTicket 可选上游票据
 */
public record MihoyoCredentials(
        String credentialType, String stoken, String stuid, String mid, String loginTicket) {
    @Override
    public String toString() {
        return "MihoyoCredentials[redacted]";
    }
}
