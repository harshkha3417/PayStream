package com.paygate.payment.service;

import com.paygate.payment.Dto.CreatePaymentRequest;
import com.paygate.payment.Dto.PaymentEventEnvelope;
import com.paygate.payment.Entity.Payment;
import com.paygate.payment.Entity.PaymentStatusHistory;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.PaymentNotFoundException;
import com.paygate.payment.outbox.OutboxEvent;
import com.paygate.payment.outbox.OutboxEventRepository;
import com.paygate.payment.repository.PaymentRepository;
import com.paygate.payment.repository.PaymentStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {
    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;
    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;

    public PaymentService(
            PaymentRepository payments,
            PaymentStatusHistoryRepository history,
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper) {
        this.payments = payments;
        this.history = history;
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
    }

    /**
     * Payment row + history row + outbox event are written in ONE transaction:
     * both commit or neither does. Eliminates dual-write bugs by staging the event
     * inside PostgreSQL before the outbox poller asynchronously relays to Kafka.
     */
    @Transactional
    public Payment create(String merchantId, CreatePaymentRequest req) {
        Payment payment = payments.save(new Payment(
                merchantId, req.amount(), req.currency(), req.method(), req.description()));
        history.save(new PaymentStatusHistory(payment.getId(), null, payment.getStatus()));
        publishEvent(payment.getId(), merchantId, "payment.created", Map.of(
                "amount", payment.getAmountMinor(),
                "currency", payment.getCurrency(),
                "method", payment.getMethod().name()
        ));
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
        publishEvent(payment.getId(), payment.getMerchantId(), "payment.status-changed", Map.of(
                "from", from != null ? from.name() : "",
                "to", next.name(),
                "amount", payment.getAmountMinor(),
                "currency", payment.getCurrency(),
                "method", payment.getMethod().name()
        ));
        return payment;
    }

    /**
     * Applies a refund transition (PARTIALLY_REFUNDED or REFUNDED) triggered by refund-service.
     * If the payment is already in REFUNDED state, idempotent re-deliveries are safely ignored.
     */
    @Transactional
    public Payment applyRefundOutcome(UUID paymentId, PaymentStatus targetStatus) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }
        return transition(paymentId, targetStatus);
    }

    /**
     * Transitions payment to PROCESSING, then to terminal outcome (SUCCEEDED or FAILED).
     *
     * <p>Design rationale: this always goes through PROCESSING first, for both outcomes,
     * because receiving a payment.processed event means the processor genuinely spent time
     * handling it — PROCESSING represents that period accurately regardless of outcome.
     * Both transition() calls join the surrounding transaction (Spring default REQUIRED),
     * so this is atomic: either both sub-transitions commit, or neither does, leaving the
     * payment in CREATED for a clean retry.</p>
     */
    @Transactional
    public Payment applyProcessedOutcome(UUID paymentId, boolean succeeded) {
        transition(paymentId, PaymentStatus.PROCESSING);
        return transition(paymentId, succeeded ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED);
    }

    private void publishEvent(UUID paymentId, String merchantId, String type, Map<String, Object> data) {
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(type, paymentId, merchantId, data);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
        outboxEvents.save(new OutboxEvent(paymentId, type, payload));
    }
}
