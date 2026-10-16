package com.niangao.mihoyo.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.result.Result;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** API 请求大小限制；先缓存有限字节，覆盖分块传输和缺失 Content-Length 的情况。 */
@Component
public class ApiRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private final ObjectMapper objectMapper;

    public ApiRequestFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 仅对 /api/ 请求应用大小限制和响应头，避免影响其他类型资源。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /** 最多读取 16 KB 加一个字节检测超限；未超限时用缓存请求体继续传给 Spring MVC。过滤器提前返回的错误也使用 Result。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json; charset=utf-8");
            objectMapper.writeValue(response.getWriter(), Result.failure(413, "请求体超过 16 KB"));
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        ByteArrayInputStream source = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                return source.read();
                            }

                            @Override
                            public boolean isFinished() {
                                return source.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException(
                                        "Only synchronous JSON requests are supported");
                            }
                        };
                    }

                    @Override
                    public java.io.BufferedReader getReader() {
                        return new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                        getInputStream(), StandardCharsets.UTF_8));
                    }
                },
                response);
    }
}
