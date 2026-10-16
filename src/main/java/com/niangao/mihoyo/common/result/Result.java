package com.niangao.mihoyo.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一 API 响应。code 与 HTTP 状态码一致，业务对象放入 data；空 data 也保留字段。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Result<T>(int code, String message, T data) {
    /** 把业务 VO 包装为成功结果，code=200；短信流程是否完成仍由 data.state 表达。 */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "成功", data);
    }

    /** 创建失败结果，code 与 HTTP 状态码一致；无额外公开详情时 data 为 null。 */
    public static <T> Result<T> failure(int code, String message, T data) {
        return new Result<>(code, message, data);
    }

    /** 创建失败结果，code 与 HTTP 状态码一致；无额外公开详情时 data 为 null。 */
    public static Result<Void> failure(int code, String message) {
        return new Result<>(code, message, null);
    }

    // data 可能包含第三方凭证，日志中不打印其内容。
    @Override
    public String toString() {
        return "Result[code=" + code + "]";
    }
}
