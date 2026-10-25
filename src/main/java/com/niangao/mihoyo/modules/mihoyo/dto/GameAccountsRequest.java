package com.niangao.mihoyo.modules.mihoyo.dto;

import jakarta.validation.constraints.*;

/**
 * 查询通行证关联原神账号的请求，凭证只用于当前调用。
 *
 * @param stoken 米哈游 SToken
 * @param stuid 通行证账号 ID，与游戏 UID 不同
 * @param mid 米哈游账号标识
 * @param gameBiz 可选原神渠道筛选
 * @param ds 可选上游签名调试参数
 */
public record GameAccountsRequest(
        @NotBlank @Size(max = 8192) String stoken,
        @NotBlank @Pattern(regexp = "[0-9]{1,20}") String stuid,
        @NotBlank @Size(max = 8192) String mid,
        String gameBiz,
        String ds,
        @com.fasterxml.jackson.annotation.JsonIgnore String deviceId) {
    public GameAccountsRequest(String stoken, String stuid, String mid, String gameBiz, String ds) {
        this(stoken, stuid, mid, gameBiz, ds, null);
    }
    @Override
    public String toString() {
        return "GameAccountsRequest[redacted]";
    }
}
