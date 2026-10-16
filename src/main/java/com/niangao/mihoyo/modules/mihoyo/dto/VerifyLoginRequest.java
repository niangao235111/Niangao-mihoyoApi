package com.niangao.mihoyo.modules.mihoyo.dto;

import jakarta.validation.constraints.*;

/**
 * 用短信验证码获取米哈游凭证的请求。
 *
 * @param sessionId 已发送短信的流程 ID
 * @param captcha 4～8 位短信验证码字符串
 * @param geetestResult 登录阶段人机验证结果，无任务时不传
 */
public record VerifyLoginRequest(
        @NotBlank String sessionId,
        @NotBlank @Pattern(regexp = "[0-9]{4,8}") String captcha,
        GeetestResult geetestResult) {
    @Override
    public String toString() {
        return "VerifyLoginRequest[redacted]";
    }
}
