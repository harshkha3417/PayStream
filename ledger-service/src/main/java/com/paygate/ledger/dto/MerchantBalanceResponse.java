package com.paygate.ledger.dto;

public record MerchantBalanceResponse(
        String merchantId,
        String currency,
        long settledBalanceMinor,
        long totalCreditsMinor,
        long totalDebitsMinor,
        long totalFeesMinor,
        long entryCount
) {}
