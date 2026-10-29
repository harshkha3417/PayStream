package com.paygate.payment.consumer;

import com.paygate.payment.Dto.PaymentEventEnvelope;
import com.paygate.payment.Exception.InvalidStatusTransitionException;
import com.paygate.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer listening on "payment.processed" topic for processor-service simulation outcomes.
 *
 * <p><strong>Idempotency Contract & State Machine Guards:</strong></p>
 * <p>
 * Kafka delivers messages with at-least-once semantics. If a message is redelivered or replayed,
 * calling {@link PaymentService#applyProcessedOutcome} will attempt to transition the payment
 * (e.g. PROCESSING -&gt; SUCCEEDED/FAILED). If the payment is already in a terminal state (or already
 * transitioned), {@link InvalidStatusTransitionException} is thrown by the domain model.
 * </p>
 * <p>
 * We specifically catch {@link InvalidStatusTransitionException} and log it at INFO level as a
 * benign no-op without rethrowing. The state machine's built-in transition validation IS the
 * deduplication / idempotency mechanism for this consumer: "already in target/terminal state == already processed, skip".
 * </p>
 * <p>
 * Note on dedup tables: A separate {@code processed_events} deduplication table is not needed here
 * because state transitions in PostgreSQL are inherently guarded and idempotent. A dedicated dedup table
 * would become necessary for consumers with non-idempotent external side-effects (such as dispatching
 * merchant webhooks or sending customer email notifications in future weeks).
 * </p>
 * <p>
 * Any other unexpected exception (e.g. DB connection dropped, serialization failure) is allowed to propagate
 * so Kafka's error handling and retry mechanism can handle genuine transient failures.
 * </p>
 */
@Component
public class PaymentProcessedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessedConsumer.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentProcessedConsumer(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.processed", groupId = "payment-service", concurrency = "${KAFKA_LISTENER_CONCURRENCY:4}")
    public void onPaymentProcessed(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to deserialize PaymentEventEnvelope from payment.processed payload: {}", payload, e);
            throw new IllegalArgumentException("Malformed payment.processed event", e);
        }

        Object outcomeObj = envelope.data() != null ? envelope.data().get("outcome") : null;
        String outcome = outcomeObj != null ? outcomeObj.toString() : "";
        boolean succeeded = "SUCCEEDED".equalsIgnoreCase(outcome);

        log.info("Received payment.processed event id={} for paymentId={} with outcome={}",
                envelope.eventId(), envelope.paymentId(), outcome);

        try {
            paymentService.applyProcessedOutcome(envelope.paymentId(), succeeded);
            log.info("Successfully transitioned paymentId={} to outcome={}", envelope.paymentId(), outcome);
        } catch (InvalidStatusTransitionException e) {
            // Idempotency: duplicate or replayed message hitting invalid transition is a benign no-op
            log.info("Ignoring duplicate or invalid state transition for paymentId={}: {}",
                    envelope.paymentId(), e.getMessage());
        }
    }
}
