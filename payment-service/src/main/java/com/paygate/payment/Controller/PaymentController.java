package com.paygate.payment.Controller;

import com.paygate.payment.Dto.CreatePaymentRequest;
import com.paygate.payment.Dto.PageResponse;
import com.paygate.payment.Dto.PaymentResponse;
import com.paygate.payment.Entity.Payment;
import com.paygate.payment.service.IdempotencyService;
import com.paygate.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService service;
    private final IdempotencyService idempotencyService;

    public PaymentController(PaymentService service, IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("X-Merchant-Id") String merchantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreate(merchantId, request);
        }
        return idempotencyService.execute(merchantId, idempotencyKey, request, () -> doCreate(merchantId, request));
    }

    private ResponseEntity<PaymentResponse> doCreate(String merchantId, CreatePaymentRequest request) {
        Payment payment = service.create(merchantId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/payments/" + payment.getId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(
            @RequestHeader("X-Merchant-Id") String merchantId,
            @PathVariable("id") UUID id) {
        return service.getPaymentResponse(id, merchantId);
    }

    @GetMapping
    public PageResponse<PaymentResponse> list(
            @RequestHeader("X-Merchant-Id") String merchantId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageResponse.from(service.list(merchantId, page, size).map(PaymentResponse::from));
    }
}
