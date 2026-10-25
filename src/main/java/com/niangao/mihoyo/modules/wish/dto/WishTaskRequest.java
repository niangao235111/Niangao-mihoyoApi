package com.niangao.mihoyo.modules.wish.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record WishTaskRequest(
        @NotNull @Valid WishRoleRequest role,
        @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String taskId) {}
