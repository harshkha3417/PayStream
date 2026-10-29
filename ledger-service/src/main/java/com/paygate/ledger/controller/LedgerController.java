package com.paygate.ledger.controller;

import com.paygate.ledger.dto.LedgerEntryResponse;
import com.paygate.ledger.dto.MerchantBalanceResponse;
import com.paygate.ledger.dto.PaymentLedgerBreakdownResponse;
import com.paygate.ledger.service.LedgerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * Returns settled balance and accounting breakdown for the calling merchant.
     */
    @GetMapping("/balance")
    public ResponseEntity<MerchantBalanceResponse> getBalance(
            @RequestHeader(name = "X-Merchant-Id") String merchantId,
            @RequestParam(name = "currency", required = false) String currency) {
        MerchantBalanceResponse response = ledgerService.getMerchantBalance(merchantId, currency);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the full double-entry ledger journal for a specific payment,
     * including Debits, Credits, Fee breakdown, and balance identity verification.
     */
    @GetMapping("/entries/payment/{paymentId}")
    public ResponseEntity<PaymentLedgerBreakdownResponse> getPaymentLedger(
            @PathVariable UUID paymentId,
            @RequestHeader(name = "X-Merchant-Id") String merchantId) {
        PaymentLedgerBreakdownResponse response = ledgerService.getPaymentLedger(paymentId, merchantId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns paginated journal entries for the merchant's account.
     */
    @GetMapping("/entries")
    public ResponseEntity<Page<LedgerEntryResponse>> getEntries(
            @RequestHeader(name = "X-Merchant-Id") String merchantId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<LedgerEntryResponse> response = ledgerService.getLedgerEntries(merchantId, pageable);
        return ResponseEntity.ok(response);
    }
}
