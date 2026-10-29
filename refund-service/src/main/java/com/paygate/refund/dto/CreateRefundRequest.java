package com.paygate.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CreateRefundRequest(
        @NotNull(message = "paymentId is required")
        UUID paymentId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        Long amount,

        @NotBlank(message = "currency is required")
        String currency,

        String reason
) {}
