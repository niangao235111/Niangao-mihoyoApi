package com.niangao.mihoyo.modules.genshin.vo;

import com.niangao.mihoyo.modules.genshin.entity.GenshinVerification.Challenge;
import java.time.Instant;

/** 只返回极验组件所需字段；不返回 Cookie、设备指纹和成功后的业务验证标识。 */
public record VerificationView(String verificationId, Instant expiresAt, Challenge challenge) {
    @Override public String toString() { return "VerificationView[redacted]"; }
}
