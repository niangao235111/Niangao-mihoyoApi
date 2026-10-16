package com.niangao.mihoyo.common.exception;

import java.util.Map;

/** 只携带可公开的错误说明与详情，不保留第三方原始响应或账号凭证。 */
public class BusinessException extends RuntimeException {
    private final int status;
    private final Map<String, Object> details;

    public BusinessException(int status, String message) {
        this(status, message, Map.of());
    }

    public BusinessException(int status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = Map.copyOf(details);
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
