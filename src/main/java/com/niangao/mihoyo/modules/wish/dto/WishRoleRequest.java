package com.niangao.mihoyo.modules.wish.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record WishRoleRequest(
        @NotBlank @Pattern(regexp = "[0-9]{1,20}") String gameUid,
        @NotBlank @Pattern(regexp = "cn_gf01|cn_qd01") String region,
        @NotBlank @Pattern(regexp = "hk4e_cn|hk4e_bilibili") String gameBiz) {}
