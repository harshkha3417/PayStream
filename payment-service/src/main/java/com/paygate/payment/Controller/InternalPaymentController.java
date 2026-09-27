package com.paygate.payment.Controller;

import com.paygate.payment.Dto.PaymentResponse;
import com.paygate.payment.Dto.UpdateStatusRequest;
import com.paygate.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {
    private final PaymentService service;

    public InternalPaymentController(PaymentService service) {
        this.service = service;
    }

    @PatchMapping("/{id}/status")
    public PaymentResponse updateStatus(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateStatusRequest request) {
        return PaymentResponse.from(service.transition(id, request.status()));
    }
}
