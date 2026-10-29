package com.paygate.audit.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 100)
    private String entityId;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AuditLog() {
        // JPA requirement
    }

    public AuditLog(
            UUID eventId,
            String topic,
            String entityType,
            String entityId,
            String merchantId,
            String actor,
            String action,
            String payload,
            Instant occurredAt) {
        this.eventId = eventId;
        this.topic = topic;
        this.entityType = entityType;
        this.entityId = entityId;
        this.merchantId = merchantId;
        this.actor = actor;
        this.action = action;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.recordedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getMerchantId() { return merchantId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
