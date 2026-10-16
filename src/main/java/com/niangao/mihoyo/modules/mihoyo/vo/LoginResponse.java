package com.niangao.mihoyo.modules.mihoyo.vo;

import com.niangao.mihoyo.modules.mihoyo.enums.LoginState;

import java.util.Map;

/**
 * 登录流程 VO，放在 Result.data 中。record 省去构造和访问器样板代码；凭证与挑战为可选字段。
 *
 * @param sessionId 本地流程 ID
 * @param state 当前状态，人机验证时不会是 LOGGED_IN
 * @param expiresIn 流程剩余有效秒数，不是凭证有效期
 * @param retryAfter 会话记录的短信等待秒数
 * @param challenge 待完成的上游验证任务
 * @param pendingOperation sms 或 login
 * @param aigisSessionId 上游风险会话 ID
 * @param verificationUrl 前端验证页面 URL
 * @param sentNew 是否新发短信，只在短信成功时返回
 * @param message 流程展示提示
 * @param credentials 仅在取得凭证成功的本次响应返回
 */
public record LoginResponse(
        String sessionId,
        LoginState state,
        long expiresIn,
        long retryAfter,
        Map<String, Object> challenge,
        String pendingOperation,
        String aigisSessionId,
        String verificationUrl,
        Boolean sentNew,
        String message,
        MihoyoCredentials credentials) {
    @Override
    public String toString() {
        return "LoginResponse[state=" + state + "]";
    }
}
