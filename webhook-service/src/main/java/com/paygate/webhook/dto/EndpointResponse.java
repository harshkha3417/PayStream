package com.paygate.webhook.dto;

import com.paygate.webhook.entity.WebhookEndpoint;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record EndpointResponse(
        UUID id,
        String merchantId,
        String url,
        String secret,
        List<String> subscribedEvents,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static EndpointResponse fromEntity(WebhookEndpoint entity) {
        List<String> events = Arrays.stream(entity.getSubscribedEvents().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new EndpointResponse(
                entity.getId(),
                entity.getMerchantId(),
                entity.getUrl(),
                entity.getSecret(),
                events,
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
