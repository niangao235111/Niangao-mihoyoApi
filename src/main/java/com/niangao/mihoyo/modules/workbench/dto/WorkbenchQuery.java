package com.niangao.mihoyo.modules.workbench.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** Cookie 会话查询只接收业务选择器，不接受浏览器再次提交米哈游凭证。 */
public final class WorkbenchQuery {
    private WorkbenchQuery() {}

    public record Accounts(@Pattern(regexp = "hk4e_cn|hk4e_bilibili") String gameBiz) {}
    public record Archive(
            @NotBlank @Pattern(regexp = "[0-9]{1,20}") String gameUid,
            @NotBlank @Pattern(regexp = "cn_gf01|cn_qd01") String region,
            @NotBlank @Pattern(regexp = "hk4e_cn|hk4e_bilibili") String gameBiz,
            @Size(min = 1, max = 40) List<@NotBlank @Pattern(regexp = "[0-9]{1,10}") String> characterIds) {}
}
