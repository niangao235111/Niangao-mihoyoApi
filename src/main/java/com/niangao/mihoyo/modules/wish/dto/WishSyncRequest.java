package com.niangao.mihoyo.modules.wish.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record WishSyncRequest(@NotNull @Valid WishRoleRequest role, @Size(max=12000) String historyUrl) {
        @Override public String toString() { return "WishSync[redacted]"; }
    }
