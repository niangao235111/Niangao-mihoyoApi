package com.niangao.mihoyo.modules.genshin.entity;

/** 验证状态仅以带会话 AAD 的 AES-GCM 密文写入 Redis。 */
public final class GenshinVerification {
    private GenshinVerification() {}
    public record Failure(String uid, String region, String gameBiz, String stage, String trace) {
        @Override public String toString() { return "VerificationFailure[redacted]"; }
    }
    public record Challenge(String gt, String challenge, int newCaptcha, int success, boolean useV4) {
        @Override public String toString() { return "VerificationChallenge[redacted]"; }
    }
    public record Task(Failure failure, String cookie, String deviceId, String fp, Challenge challenge) {
        @Override public String toString() { return "VerificationTask[redacted]"; }
    }
    public record Proof(String uid, String region, String gameBiz, String deviceId, String fp, String challenge) {
        @Override public String toString() { return "VerificationProof[redacted]"; }
    }
}
