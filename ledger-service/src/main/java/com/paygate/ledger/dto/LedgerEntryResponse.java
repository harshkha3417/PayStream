package com.paygate.ledger.dto;

import com.paygate.ledger.entity.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID paymentId,
        String merchantId,
        String account,
        String direction,
        long amountMinor,
        String currency,
        String referenceId,
        String entryType,
        String description,
        Instant createdAt
) {
    public static LedgerEntryResponse fromEntity(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getPaymentId(),
                entry.getMerchantId(),
                entry.getAccount().name(),
                entry.getDirection().name(),
                entry.getAmountMinor(),
                entry.getCurrency(),
                entry.getReferenceId(),
                entry.getEntryType().name(),
                entry.getDescription(),
                entry.getCreatedAt()
        );
    }
}
