package com.niangao.mihoyo.modules.mihoyo.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 仅属于米哈游模块的配置，与以后的网站用户配置分离。 */
@Validated
@ConfigurationProperties(prefix = "mihoyo")
public class MihoyoProperties {
    @NotBlank
    private String passportEndpoint = "https://passport-api.mihoyo.com";

    @NotBlank
    private String bindingEndpoint = "https://api-takumi.mihoyo.com";

    @NotBlank
    private String frontendUrl = "http://127.0.0.1:5173";

    @NotNull
    private Duration sessionTtl = Duration.ofSeconds(600);

    @Min(1)
    private int maxSessions = 20;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(15);

    @NotNull
    private Duration responseTimeout = Duration.ofSeconds(25);

    public String getPassportEndpoint() {
        return passportEndpoint;
    }

    public void setPassportEndpoint(String value) {
        passportEndpoint = value;
    }

    public String getBindingEndpoint() {
        return bindingEndpoint;
    }

    public void setBindingEndpoint(String value) {
        bindingEndpoint = value;
    }

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String value) {
        frontendUrl = value;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration value) {
        sessionTtl = value;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int value) {
        maxSessions = value;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration value) {
        connectTimeout = value;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration value) {
        responseTimeout = value;
    }
}
