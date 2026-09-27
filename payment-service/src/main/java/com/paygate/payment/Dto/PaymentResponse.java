package com.paygate.payment.Dto;

import com.paygate.payment.Entity.Payment;
import com.paygate.payment.Enum.PaymentMethod;
import com.paygate.payment.Enum.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String merchantId,
        long amount,
        String currency,
        PaymentMethod method,
        PaymentStatus status,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getMerchantId(), p.getAmountMinor(), p.getCurrency(),
                p.getMethod(), p.getStatus(), p.getDescription(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
