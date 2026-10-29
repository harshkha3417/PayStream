package com.paygate.audit.service;

import com.paygate.audit.dto.AuditLogResponse;
import com.paygate.audit.dto.PaymentEventEnvelope;
import com.paygate.audit.entity.AuditLog;
import com.paygate.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Appends an immutable audit log record for an observed domain event.
     * Enforces deduplication via eventId.
     */
    @Transactional
    public AuditLog recordEvent(PaymentEventEnvelope envelope, String rawPayload) {
        if (auditLogRepository.existsByEventId(envelope.eventId())) {
            log.info("Duplicate event {} ignored by audit-service", envelope.eventId());
            return null;
        }

        String topic = envelope.type();
        String entityType = resolveEntityType(topic);
        String entityId = resolveEntityId(topic, envelope);
        String actor = resolveActor(topic);
        String action = resolveAction(topic, envelope.data());

        AuditLog auditLog = new AuditLog(
                envelope.eventId(),
                topic,
                entityType,
                entityId,
                envelope.merchantId(),
                actor,
                action,
                rawPayload,
                envelope.occurredAt()
        );

        AuditLog saved = auditLogRepository.save(auditLog);
        log.info("Recorded audit log for eventId={}, topic={}, entityType={}, entityId={}, action={}",
                envelope.eventId(), topic, entityType, entityId, action);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getEventsForEntity(String entityId, String merchantId) {
        return auditLogRepository.findByEntityIdAndMerchantIdOrderByOccurredAtAsc(entityId, merchantId)
                .stream()
                .map(AuditLogResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditTrail(String merchantId, String entityType, Pageable pageable) {
        if (entityType != null && !entityType.isBlank()) {
            return auditLogRepository.findByMerchantIdAndEntityTypeOrderByRecordedAtDesc(
                    merchantId, entityType.toUpperCase(), pageable)
                    .map(AuditLogResponse::fromEntity);
        }
        return auditLogRepository.findByMerchantIdOrderByRecordedAtDesc(merchantId, pageable)
                .map(AuditLogResponse::fromEntity);
    }

    private String resolveEntityType(String topic) {
        if (topic.startsWith("payment.")) {
            return "PAYMENT";
        } else if (topic.startsWith("refund.")) {
            return "REFUND";
        } else if (topic.startsWith("webhook.")) {
            return "WEBHOOK";
        }
        return "DOMAIN_EVENT";
    }

    private String resolveEntityId(String topic, PaymentEventEnvelope envelope) {
        // If it's a refund event, the entity could be the refundId or the paymentId.
        // We use paymentId to ensure querying by paymentId retrieves the complete lifecycle!
        return envelope.paymentId() != null ? envelope.paymentId().toString() : "UNKNOWN";
    }

    private String resolveActor(String topic) {
        if ("payment.created".equals(topic)) {
            return "PAYMENT_SERVICE";
        } else if ("payment.status-changed".equals(topic)) {
            return "PAYMENT_SERVICE";
        } else if (topic.startsWith("refund.")) {
            return "REFUND_SERVICE";
        } else if (topic.startsWith("webhook.")) {
            return "WEBHOOK_SERVICE";
        }
        return "SYSTEM";
    }

    private String resolveAction(String topic, Map<String, Object> data) {
        if ("payment.created".equals(topic)) {
            return "PAYMENT_CREATED";
        } else if ("payment.status-changed".equals(topic)) {
            if (data != null && data.get("to") != null) {
                return "PAYMENT_" + data.get("to").toString().toUpperCase();
            }
            return "PAYMENT_STATUS_CHANGED";
        } else if ("refund.requested".equals(topic)) {
            return "REFUND_REQUESTED";
        } else if ("refund.completed".equals(topic)) {
            return "REFUND_COMPLETED";
        }
        return topic.toUpperCase().replace('.', '_');
    }
}
