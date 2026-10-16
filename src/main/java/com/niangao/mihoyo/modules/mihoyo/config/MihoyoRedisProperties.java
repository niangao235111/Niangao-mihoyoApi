package com.niangao.mihoyo.modules.mihoyo.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Redis 会话专用配置；多个实例必须共用命名空间和手机号 HMAC 密钥。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "mihoyo.redis")
public class MihoyoRedisProperties {
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}")
    private String keyPrefix = "niangao:mihoyo";

    @NotBlank
    private String phoneKeySecret;

    @NotNull
    private Duration operationTimeout = Duration.ofSeconds(90);

    @NotNull
    private Duration smsCooldown = Duration.ofSeconds(60);

    @AssertTrue(message = "Redis 操作超时和短信冷却必须为正值")
    public boolean isTimingValid() {
        return operationTimeout != null
                && operationTimeout.toMillis() > 0
                && smsCooldown != null
                && smsCooldown.toMillis() > 0;
    }
}
