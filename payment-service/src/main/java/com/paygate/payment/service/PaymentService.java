package com.paygate.payment.service;

import com.paygate.payment.Dto.CreatePaymentRequest;
import com.paygate.payment.Entity.Payment;
import com.paygate.payment.Entity.PaymentStatusHistory;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.PaymentNotFoundException;
import com.paygate.payment.repository.PaymentRepository;
import com.paygate.payment.repository.PaymentStatusHistoryRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentService {
    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;

    public PaymentService(PaymentRepository payments, PaymentStatusHistoryRepository history) {
        this.payments = payments;
        this.history = history;
    }

    /** Payment row + history row are written in ONE transaction: both commit or neither does. */
    @Transactional
    public Payment create(String merchantId, CreatePaymentRequest req) {
        Payment payment = payments.save(new Payment(
                merchantId, req.amount(), req.currency(), req.method(), req.description()));
        history.save(new PaymentStatusHistory(payment.getId(), null, payment.getStatus()));
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id, String merchantId) {
        return payments.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(String merchantId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return payments.findByMerchantId(merchantId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Transactional
    public Payment transition(UUID id, PaymentStatus next) {
        Payment payment = payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
        PaymentStatus from = payment.getStatus();
        payment.transitionTo(next);
        history.save(new PaymentStatusHistory(id, from, next));
        return payment;
    }
}
