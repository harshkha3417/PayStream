package com.paygate.audit.consumer;

import com.paygate.audit.dto.PaymentEventEnvelope;
import com.paygate.audit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditEventConsumer(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {
            "payment.created",
            "payment.status-changed",
            "refund.requested",
            "refund.completed"
    }, groupId = "audit-service")
    public void onEvent(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to parse PaymentEventEnvelope in audit-service: {}", payload, e);
            throw new IllegalArgumentException("Malformed event payload", e);
        }

        log.debug("audit-service received event type={} id={}", envelope.type(), envelope.eventId());
        auditService.recordEvent(envelope, payload);
    }
}
