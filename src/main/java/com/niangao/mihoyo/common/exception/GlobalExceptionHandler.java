package com.niangao.mihoyo.common.exception;

import com.niangao.mihoyo.common.result.Result;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一转换 HTTP 错误；不输出 rejectedValue，避免手机号、验证码、Token 泄露。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    /** 保留业务异常的 HTTP 状态及可公开详情，并统一包装为 Result。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> business(BusinessException ex) {
        // 不打印 details：其中可能包含完整 session_id 等流程凭据。
        if (ex.getStatus() >= 500) {
            log.error("API 业务处理失败 status={}, message={}", ex.getStatus(), ex.getMessage());
        } else {
            log.warn("API 业务请求被拒绝 status={}, message={}", ex.getStatus(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(
                        Result.failure(
                                ex.getStatus(),
                                ex.getMessage(),
                                ex.getDetails().isEmpty() ? null : ex.getDetails()));
    }

    /** 处理 DTO 参数校验失败，不输出 rejectedValue，防止手机号或 Token 被回显。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> validation(MethodArgumentNotValidException ex) {
        log.warn("API 参数校验失败 status=400, error_count={}", ex.getBindingResult().getErrorCount());
        return error(400, "请求参数不合法，请检查必填字段和格式");
    }

    /** 处理非法 JSON、字段类型错误及未知字段，统一返回 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> json(HttpMessageNotReadableException ex) {
        // Jackson 异常可能包含原始字段值，不能输出异常消息或堆栈。
        log.warn("API JSON 解析失败 status=400, error_type={}", ex.getClass().getSimpleName());
        return error(400, "请求必须为符合接口定义的 JSON 对象");
    }

    /** 方法不匹配时返回 405，并通过 Allow 响应头告知支持的方法。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> method(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405)
                .header("Allow", String.join(",", ex.getSupportedMethods()))
                .body(Result.failure(405, "请求方法不支持"));
    }

    /** 拒绝非 JSON 请求体，提醒客户端设置 application/json。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> contentType(HttpMediaTypeNotSupportedException ex) {
        return error(415, "请使用 application/json");
    }

    /** 将未知接口统一转换为 404 Result。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<?>> missing(Exception ex) {
        return error(404, "接口不存在");
    }

    /** 未预期的内部异常使用通用说明，避免异常堆栈和内部信息出现在响应中。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unexpected(Exception ex) {
        log.error(
                "API 未预期异常 status=500, error_type={}, location={}",
                ex.getClass().getSimpleName(),
                ex.getStackTrace().length == 0 ? "unknown" : ex.getStackTrace()[0]);
        return error(500, "服务器处理异常");
    }

    /** 构造不带额外详情的失败响应，保持 HTTP 状态码和 Result.code 一致。 */
    private ResponseEntity<Result<?>> error(int status, String message) {
        return ResponseEntity.status(status).body(Result.failure(status, message));
    }
}
