package com.niangao.mihoyo.modules.wish.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;

public record WishImportRequest(@NotNull @Valid WishRoleRequest role, @NotNull JsonNode document) {}
