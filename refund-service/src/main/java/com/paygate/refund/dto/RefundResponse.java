package com.paygate.refund.dto;

import com.paygate.refund.entity.Refund;
import com.paygate.refund.enums.RefundStatus;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        String merchantId,
        long amount,
        String currency,
        RefundStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundResponse fromEntity(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getMerchantId(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getReason(),
                refund.getCreatedAt(),
                refund.getUpdatedAt()
        );
    }
}
