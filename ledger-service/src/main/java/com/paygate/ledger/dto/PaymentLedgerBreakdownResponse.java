package com.paygate.ledger.dto;

import java.util.List;
import java.util.UUID;

public record PaymentLedgerBreakdownResponse(
        UUID paymentId,
        String merchantId,
        String currency,
        long totalDebitsMinor,
        long totalCreditsMinor,
        boolean balanced,
        List<LedgerEntryResponse> entries
) {}
