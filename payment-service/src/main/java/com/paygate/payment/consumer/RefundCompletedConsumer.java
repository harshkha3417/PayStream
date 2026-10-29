package com.paygate.payment.consumer;

import com.paygate.payment.Dto.PaymentEventEnvelope;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.InvalidStatusTransitionException;
import com.paygate.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer listening on "refund.completed" topic.
 * Transitions payment to PARTIALLY_REFUNDED or REFUNDED atomically in the state machine.
 */
@Component
public class RefundCompletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundCompletedConsumer.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public RefundCompletedConsumer(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "refund.completed", groupId = "payment-service", concurrency = "${KAFKA_LISTENER_CONCURRENCY:4}")
    public void onRefundCompleted(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to deserialize PaymentEventEnvelope from refund.completed payload: {}", payload, e);
            throw new IllegalArgumentException("Malformed refund.completed event", e);
        }

        Object statusObj = envelope.data() != null ? envelope.data().get("paymentStatus") : null;
        if (statusObj == null) {
            log.warn("refund.completed event missing paymentStatus for paymentId={}", envelope.paymentId());
            return;
        }

        PaymentStatus targetStatus;
        try {
            targetStatus = PaymentStatus.valueOf(statusObj.toString());
        } catch (IllegalArgumentException e) {
            log.error("Unknown paymentStatus '{}' in refund.completed for paymentId={}", statusObj, envelope.paymentId());
            return;
        }

        log.info("Received refund.completed event id={} for paymentId={} with target paymentStatus={}",
                envelope.eventId(), envelope.paymentId(), targetStatus);

        try {
            paymentService.applyRefundOutcome(envelope.paymentId(), targetStatus);
            log.info("Successfully transitioned paymentId={} to {}", envelope.paymentId(), targetStatus);
        } catch (InvalidStatusTransitionException e) {
            // Idempotency: duplicate or invalid transition is a benign no-op
            log.info("Ignoring duplicate or invalid state transition for paymentId={}: {}",
                    envelope.paymentId(), e.getMessage());
        }
    }
}
