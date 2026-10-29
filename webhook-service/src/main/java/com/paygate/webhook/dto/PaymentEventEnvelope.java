package com.paygate.webhook.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Standard envelope format shared across all Kafka event topics in the gateway ecosystem.
 * Duplicated locally per Architecture Decision #12 to maintain service decoupling.
 */
public record PaymentEventEnvelope(
        UUID eventId,
        String type,
        Instant occurredAt,
        UUID paymentId,
        String merchantId,
        Map<String, Object> data
) {
    public static PaymentEventEnvelope of(String type, UUID paymentId, String merchantId, Map<String, Object> data) {
        return new PaymentEventEnvelope(
                UUID.randomUUID(),
                type,
                Instant.now(),
                paymentId,
                merchantId,
                data != null ? data : Map.of()
        );
    }
}
