package com.niangao.mihoyo.modules.mihoyo.client;

import com.niangao.mihoyo.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLPeerUnverifiedException;

/** 只读上游请求的网络重试；同一个网页请求共享 45 秒预算，不重试业务拒绝。 */
@Slf4j
public final class ReadOnlyRetry {
    private static final String DEADLINE = ReadOnlyRetry.class.getName() + ".deadline";
    private ReadOnlyRetry() {}

    public static <T> T execute(String stage, Function<Duration, T> operation) {
        var attributes = RequestContextHolder.getRequestAttributes();
        Long deadline = attributes == null ? null : (Long) attributes.getAttribute(DEADLINE, RequestAttributes.SCOPE_REQUEST);
        if (deadline == null) {
            deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            if (attributes != null) attributes.setAttribute(DEADLINE, deadline, RequestAttributes.SCOPE_REQUEST);
        }
        for (int attempt = 0; ; attempt++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= Duration.ofMillis(2).toNanos()) throw exhausted(stage, attempt);
            try {
                return operation.apply(Duration.ofNanos(remaining));
            } catch (RuntimeException ex) {
                if (!isNetworkFailure(ex) || Thread.currentThread().isInterrupted()) throw ex;
                if (attempt == 2 || deadline - System.nanoTime() <= Duration.ofMillis(750).toNanos())
                    throw exhausted(stage, attempt);
                log.warn("只读上游网络请求重试 stage={}, retry={}, max_retries=2", stage, attempt + 1);
                try { Thread.sleep(250L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(502, "查询已中断，请手动重试", Map.of("stage", stage, "reason", "request_interrupted"));
                }
            }
        }
    }

    private static BusinessException exhausted(String stage, int retries) {
        return new BusinessException(502, "米哈游连接超时或网络中断，有限重试后仍未完成，请稍后手动重试",
                Map.of("stage", stage, "reason", "upstream_network_error", "retry_count", retries));
    }

    public static boolean isNetworkFailure(Throwable ex) {
        boolean network = false;
        for (int depth = 0; ex != null && depth < 24; depth++, ex = ex.getCause()) {
            if (ex instanceof CertificateException || ex instanceof SSLPeerUnverifiedException || ex instanceof InterruptedException)
                return false;
            if (ex instanceof IOException) network = true;
            if (ex instanceof BusinessException business && "upstream_network_error".equals(business.getDetails().get("reason")))
                network = true;
        }
        return network;
    }

    public static BusinessException failure(String stage, RuntimeException ex) {
        if (ex instanceof BusinessException business) {
            var details = new java.util.HashMap<String, Object>(business.getDetails());
            details.put("stage", stage);
            return new BusinessException(business.getStatus(), business.getMessage(), details);
        }
        return new BusinessException(502, isNetworkFailure(ex) ? "米哈游连接超时或网络中断；未自动重试" : "米哈游响应异常；未自动重试",
                Map.of("stage", stage, "reason", isNetworkFailure(ex) ? "upstream_network_error" : "upstream_response_error"));
    }
}
