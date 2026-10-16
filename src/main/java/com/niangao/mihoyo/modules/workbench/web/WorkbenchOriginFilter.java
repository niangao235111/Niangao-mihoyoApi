package com.niangao.mihoyo.modules.workbench.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.result.Result;
import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Cookie 会话仅用于配置的同源工作台，不对任意站点放行。 */
@Component
public class WorkbenchOriginFilter extends OncePerRequestFilter {
    private final WorkbenchSessionProperties settings;
    private final ObjectMapper json;

    public WorkbenchOriginFilter(WorkbenchSessionProperties settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/workbench/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        String origin = request.getHeader("Origin");
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                || origin != null && !settings.getAllowedOrigins().contains(origin)) {
            response.setStatus(403);
            response.setContentType("application/json; charset=utf-8");
            json.writeValue(response.getWriter(), Result.failure(403, "工作台请求来源不允许"));
            return;
        }
        chain.doFilter(request, response);
    }
}
