package com.niangao.mihoyo.modules.wish.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record WishRecordsRequest(
        @NotNull @Valid WishRoleRequest role,
        @Pattern(regexp = "100|200|301|302|500") String pool,
        @Min(3) @Max(5) Integer rank,
        @Pattern(regexp = "[0-9]{1,20}") String beforeId,
        @Min(1) @Max(100) Integer limit,
        @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String fromDate,
        @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String toDate) {}
