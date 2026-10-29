package com.paygate.audit.dto;

import com.paygate.audit.entity.AuditLog;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID eventId,
        String topic,
        String entityType,
        String entityId,
        String merchantId,
        String actor,
        String action,
        String payload,
        Instant occurredAt,
        Instant recordedAt
) {
    public static AuditLogResponse fromEntity(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getEventId(),
                log.getTopic(),
                log.getEntityType(),
                log.getEntityId(),
                log.getMerchantId(),
                log.getActor(),
                log.getAction(),
                log.getPayload(),
                log.getOccurredAt(),
                log.getRecordedAt()
        );
    }
}
