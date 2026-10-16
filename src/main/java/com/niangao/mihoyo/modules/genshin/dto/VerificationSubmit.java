package com.niangao.mihoyo.modules.genshin.dto;

import jakarta.validation.constraints.*;

public record VerificationSubmit(
        @NotBlank @Pattern(regexp = "[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") String verificationId,
        @NotBlank @Size(max = 2048) String geetestChallenge,
        @NotBlank @Size(max = 2048) String geetestValidate,
        @NotBlank @Size(max = 2048) String geetestSeccode) {
    @Override public String toString() { return "VerificationSubmit[redacted]"; }
}
