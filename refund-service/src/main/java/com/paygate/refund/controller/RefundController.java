package com.paygate.refund.controller;

import com.paygate.refund.dto.CreateRefundRequest;
import com.paygate.refund.dto.RefundResponse;
import com.paygate.refund.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> createRefund(
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        RefundResponse response = refundService.createRefund(request, merchantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RefundResponse> getRefund(
            @PathVariable UUID id,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        return ResponseEntity.ok(refundService.getRefund(id, merchantId));
    }

    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<List<RefundResponse>> getRefundsByPayment(
            @PathVariable UUID paymentId,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        return ResponseEntity.ok(refundService.getRefundsByPayment(paymentId, merchantId));
    }

    @GetMapping
    public ResponseEntity<List<RefundResponse>> listRefunds(
            @RequestHeader("X-Merchant-Id") String merchantId) {
        return ResponseEntity.ok(refundService.listRefunds(merchantId));
    }
}
