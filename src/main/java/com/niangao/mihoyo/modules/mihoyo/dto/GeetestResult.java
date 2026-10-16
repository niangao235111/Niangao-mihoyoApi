package com.niangao.mihoyo.modules.mihoyo.dto;

/** GT3/GT4 共用请求模型，Service 根据当前验证任务选择对应版本的必填字段。 */
public record GeetestResult(
        String geetestChallenge,
        String geetestValidate,
        String geetestSeccode,
        String captchaId,
        String lotNumber,
        String passToken,
        String genTime,
        String captchaOutput) {
    @Override
    public String toString() {
        return "GeetestResult[redacted]";
    }
}
