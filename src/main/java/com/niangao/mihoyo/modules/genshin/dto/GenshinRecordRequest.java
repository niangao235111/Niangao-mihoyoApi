package com.niangao.mihoyo.modules.genshin.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** 凭证仅用于本次请求；game_uid 还必须由服务端核对通行证关联角色。 */
public record GenshinRecordRequest(
        @NotBlank @Size(max = 8192) String stoken,
        @NotBlank @Pattern(regexp = "[0-9]{1,20}") String stuid,
        @NotBlank @Size(max = 8192) String mid,
        @NotBlank @Pattern(regexp = "[0-9]{1,20}") String gameUid,
        @NotBlank @Pattern(regexp = "cn_gf01|cn_qd01") String region,
        @NotBlank @Pattern(regexp = "hk4e_cn|hk4e_bilibili") String gameBiz,
        @Size(min = 1, max = 40) List<@NotBlank @Pattern(regexp = "[0-9]{1,10}") String> characterIds,
        @com.fasterxml.jackson.annotation.JsonIgnore String deviceId) {
    public GenshinRecordRequest(String stoken, String stuid, String mid, String gameUid,
            String region, String gameBiz, List<String> characterIds) {
        this(stoken, stuid, mid, gameUid, region, gameBiz, characterIds, null);
    }
    @Override
    public String toString() { return "GenshinRecordRequest[redacted]"; }
}
