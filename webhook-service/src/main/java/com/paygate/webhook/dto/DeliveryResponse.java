package com.paygate.webhook.dto;

import com.paygate.webhook.entity.WebhookDelivery;
import com.paygate.webhook.enums.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        UUID endpointId,
        String merchantId,
        UUID eventId,
        String eventType,
        String payload,
        DeliveryStatus status,
        int attemptCount,
        Integer statusCode,
        String responseBody,
        String errorMessage,
        Instant nextRetryAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static DeliveryResponse fromEntity(WebhookDelivery entity) {
        return new DeliveryResponse(
                entity.getId(),
                entity.getEndpointId(),
                entity.getMerchantId(),
                entity.getEventId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getStatusCode(),
                entity.getResponseBody(),
                entity.getErrorMessage(),
                entity.getNextRetryAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
