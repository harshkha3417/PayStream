package com.paygate.payment.service;

import com.paygate.payment.Dto.CreatePaymentRequest;
import com.paygate.payment.Dto.PaymentEventEnvelope;
import com.paygate.payment.Dto.PaymentResponse;
import com.paygate.payment.Entity.Payment;
import com.paygate.payment.Entity.PaymentStatusHistory;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.PaymentNotFoundException;
import com.paygate.payment.outbox.OutboxEvent;
import com.paygate.payment.outbox.OutboxEventRepository;
import com.paygate.payment.repository.PaymentRepository;
import com.paygate.payment.repository.PaymentStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String CACHE_PREFIX = "payment:";

    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;
    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public PaymentService(
            PaymentRepository payments,
            PaymentStatusHistoryRepository history,
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper,
            StringRedisTemplate redis) {
        this.payments = payments;
        this.history = history;
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    /**
     * Payment row + history row + outbox event are written in ONE transaction:
     * both commit or neither does. Eliminates dual-write bugs by staging the event
     * inside PostgreSQL before the outbox poller asynchronously relays to Kafka.
     * Also warms the Redis read cache immediately with the initial CREATED state.
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

        // Warm Redis read cache on creation
        PaymentResponse response = PaymentResponse.from(payment);
        cachePayment(response);
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id, String merchantId) {
        return payments.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /**
     * Fast-path cached read: returns PaymentResponse directly from Redis (<1ms) if cached.
     * On cache miss, queries PostgreSQL, populates Redis, and returns.
     */
    public PaymentResponse getPaymentResponse(UUID id, String merchantId) {
        String cacheKey = cacheKey(id, merchantId);
        String cached = safeRedisGet(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, PaymentResponse.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached payment {}, falling back to database: {}", id, e.getMessage());
            }
        }

        Payment payment = get(id, merchantId);
        PaymentResponse response = PaymentResponse.from(payment);
        cachePayment(response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(String merchantId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return payments.findByMerchantId(merchantId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * Transitions payment status, saves history, emits status event,
     * and performs write-through cache update to keep Redis synchronized.
     */
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

        // Write-through cache update to ensure instantaneous read consistency
        cachePayment(PaymentResponse.from(payment));
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

    private String cacheKey(UUID id, String merchantId) {
        return CACHE_PREFIX + id + ":" + merchantId;
    }

    private void cachePayment(PaymentResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            safeRedisSet(cacheKey(response.id(), response.merchantId()), json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to serialize payment {} for cache: {}", response.id(), e.getMessage());
        }
    }

    private String safeRedisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis unavailable for GET {}, falling back to database: {}", key, e.getMessage());
            return null;
        }
    }

    private void safeRedisSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis unavailable for SET {}, continuing without cache update: {}", key, e.getMessage());
        }
    }
}
