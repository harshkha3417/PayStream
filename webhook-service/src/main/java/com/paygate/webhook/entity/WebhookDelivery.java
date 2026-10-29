package com.paygate.webhook.entity;

import com.paygate.webhook.enums.DeliveryStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_body", length = 1024)
    private String responseBody;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookDelivery() {
        // JPA requirement
    }

    public WebhookDelivery(UUID endpointId, String merchantId, UUID eventId, String eventType, String payload) {
        this.endpointId = endpointId;
        this.merchantId = merchantId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.nextRetryAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        if (this.nextRetryAt == null) {
            this.nextRetryAt = this.createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markSuccess(int statusCode, String responseBody) {
        this.status = DeliveryStatus.SUCCESS;
        this.statusCode = statusCode;
        this.responseBody = responseBody != null && responseBody.length() > 1000
                ? responseBody.substring(0, 1000) : responseBody;
        this.errorMessage = null;
    }

    public void markRetrying(int attemptCount, Integer statusCode, String errorMessage, Instant nextRetryAt) {
        this.status = DeliveryStatus.RETRYING;
        this.attemptCount = attemptCount;
        this.statusCode = statusCode;
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage;
        this.nextRetryAt = nextRetryAt;
    }

    public void markDead(int attemptCount, Integer statusCode, String errorMessage) {
        this.status = DeliveryStatus.DEAD;
        this.attemptCount = attemptCount;
        this.statusCode = statusCode;
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEndpointId() { return endpointId; }
    public String getMerchantId() { return merchantId; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Integer getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
