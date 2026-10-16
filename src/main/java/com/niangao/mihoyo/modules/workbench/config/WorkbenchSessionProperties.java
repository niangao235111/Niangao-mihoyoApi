package com.niangao.mihoyo.modules.workbench.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.List;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "workbench.session")
public class WorkbenchSessionProperties {
    @NotNull
    private Duration idleTtl = Duration.ofDays(7);
    @NotNull
    private Duration absoluteTtl = Duration.ofDays(30);
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}")
    private String keyPrefix = "niangao:workbench";
    private String encryptionKey = "";
    private boolean cookieSecure = false;
    private List<String> allowedOrigins = List.of("http://127.0.0.1:5173", "http://localhost:5173",
            "http://127.0.0.1:4321", "http://localhost:4321");

    @AssertTrue(message = "工作台会话期限必须为正数，且空闲期限不能大于绝对期限")
    public boolean isTimingValid() {
        return idleTtl != null && absoluteTtl != null && idleTtl.toMillis() > 0
                && absoluteTtl.compareTo(idleTtl) >= 0;
    }

    @Override
    public String toString() { return "WorkbenchSessionProperties[redacted]"; }
}
