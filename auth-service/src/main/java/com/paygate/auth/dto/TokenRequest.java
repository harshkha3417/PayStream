package com.paygate.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TokenRequest(
        @NotNull UUID merchantId,
        @NotBlank String apiKey
) {
}