package com.paygate.webhook.consumer;

import com.paygate.webhook.dto.PaymentEventEnvelope;
import com.paygate.webhook.entity.ProcessedEvent;
import com.paygate.webhook.repository.ProcessedEventRepository;
import com.paygate.webhook.service.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebhookEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventConsumer.class);

    private final WebhookDeliveryService deliveryService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventConsumer(
            WebhookDeliveryService deliveryService,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.created", "payment.status-changed", "refund.completed"}, groupId = "webhook-service")
    @Transactional
    public void onEvent(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to parse PaymentEventEnvelope: {}", payload, e);
            throw new IllegalArgumentException("Malformed event payload", e);
        }

        // Deduplicate using processed_events table per architecture.md §8
        if (processedEventRepository.existsById(envelope.eventId())) {
            log.info("Duplicate event {} ignored by webhook-service for paymentId={}",
                    envelope.eventId(), envelope.paymentId());
            return;
        }

        log.info("Received event type={} id={} for merchantId={}",
                envelope.type(), envelope.eventId(), envelope.merchantId());

        deliveryService.recordDeliveriesForEvent(envelope);
        processedEventRepository.save(new ProcessedEvent(envelope.eventId()));
    }
}
