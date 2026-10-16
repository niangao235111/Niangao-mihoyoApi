package com.niangao.mihoyo.common.web;

import com.niangao.mihoyo.common.result.Result;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 容器错误分发兜底，不暴露异常堆栈和请求内部信息。 */
@RestController
public class ResultErrorController implements ErrorController {
    /** 处理 Servlet 容器分发到 /error 的兜底错误，避免回退为 Spring Boot 默认的另一套 JSON 结构。 */
    @RequestMapping("/error")
    public ResponseEntity<Result<Void>> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
        String message =
                switch (status) {
                    case 400 -> "请求不合法";
                    case 404 -> "接口不存在";
                    case 405 -> "请求方法不支持";
                    case 413 -> "请求体超过限制";
                    case 415 -> "请求内容类型不支持";
                    default -> "服务器处理异常";
                };
        return ResponseEntity.status(status).body(Result.failure(status, message));
    }
}
