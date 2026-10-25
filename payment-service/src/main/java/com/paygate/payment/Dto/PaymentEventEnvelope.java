package com.paygate.payment.Dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Standard envelope format shared across all Kafka event topics in the gateway ecosystem.
 *
 * Each event topic (e.g. "payment.created", "payment.processed", "payment.status-changed")
 * wraps its specific payload inside this envelope to provide consistent metadata:
 * - eventId: globally unique identifier for this event emission (used for deduplication if needed)
 * - type: the event type name (doubles as topic name)
 * - occurredAt: ISO-8601 timestamp of when the event took place
 * - paymentId: the aggregate ID (used as Kafka partition key for per-payment ordering)
 * - merchantId: tenant/merchant scope
 * - data: domain-specific event payload attributes
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
