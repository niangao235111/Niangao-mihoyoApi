package com.niangao.mihoyo.modules.mihoyo.dto;

import jakarta.validation.constraints.*;

/**
 * 创建流程的请求参数；请求名由 Jackson 映射为 snake_case。
 *
 * @param mobile 大陆手机号字符串
 * @param areaCode 区号，未指定时由 Service 使用 +86
 */
public record StartLoginRequest(
        @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String mobile,
        @Pattern(regexp = "\\+86") String areaCode) {
    @Override
    public String toString() {
        return "StartLoginRequest[redacted]";
    }
}
