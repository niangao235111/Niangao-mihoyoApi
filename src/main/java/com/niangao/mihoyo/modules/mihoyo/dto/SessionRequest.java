package com.niangao.mihoyo.modules.mihoyo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅使用会话 ID 的状态查询和清理请求。
 *
 * @param sessionId start 返回的本地流程 ID
 */
public record SessionRequest(@NotBlank String sessionId) {
    @Override
    public String toString() {
        return "SessionRequest[redacted]";
    }
}
