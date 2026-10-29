package com.paygate.processor.consumer;

import com.paygate.processor.dto.PaymentEventEnvelope;
import com.paygate.processor.service.PaymentSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Consumes "payment.created" events from Kafka, executes simulated bank authorization,
 * and publishes "payment.processed" events back to Kafka.
 */
@Component
public class PaymentCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentCreatedConsumer.class);
    private static final String TOPIC_PAYMENT_PROCESSED = "payment.processed";

    private final PaymentSimulator simulator;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentCreatedConsumer(
            PaymentSimulator simulator,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.simulator = simulator;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.created", groupId = "${spring.kafka.consumer.group-id:processor-service-v2}", concurrency = "${KAFKA_LISTENER_CONCURRENCY:4}")
    public void onPaymentCreated(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to deserialize PaymentEventEnvelope from payment.created payload: {}", payload, e);
            throw new IllegalArgumentException("Malformed payment.created event", e);
        }

        log.info("Processing payment.created event id={} for paymentId={}, merchantId={}",
                envelope.eventId(), envelope.paymentId(), envelope.merchantId());

        boolean succeeded = simulator.process(envelope.paymentId());
        String outcome = succeeded ? "SUCCEEDED" : "FAILED";

        PaymentEventEnvelope processedEnvelope = PaymentEventEnvelope.of(
                TOPIC_PAYMENT_PROCESSED,
                envelope.paymentId(),
                envelope.merchantId(),
                Map.of("outcome", outcome)
        );

        String outPayload;
        try {
            outPayload = objectMapper.writeValueAsString(processedEnvelope);
        } catch (Exception e) {
            log.error("Failed to serialize PaymentEventEnvelope for paymentId={}: {}", envelope.paymentId(), e.getMessage(), e);
            throw new IllegalStateException("Serialization error for processed event", e);
        }

        String partitionKey = envelope.paymentId().toString();
        log.info("Publishing {} event with outcome={} for paymentId={}", TOPIC_PAYMENT_PROCESSED, outcome, partitionKey);
        kafkaTemplate.send(TOPIC_PAYMENT_PROCESSED, partitionKey, outPayload);
    }
}
