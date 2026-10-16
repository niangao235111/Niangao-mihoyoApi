package com.niangao.mihoyo.modules.mihoyo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发送短信或继续短信阶段人机验证的请求。
 *
 * @param sessionId 有效流程 ID
 * @param geetestResult 首次发送不传，出现验证任务后提交对应结果
 */
public record SendSmsRequest(@NotBlank String sessionId, GeetestResult geetestResult) {
    @Override
    public String toString() {
        return "SendSmsRequest[redacted]";
    }
}
