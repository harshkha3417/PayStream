package com.paygate.processor.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Standard event envelope for Kafka events consumed and produced by processor-service.
 *
 * <p><strong>Architectural Decision Note (Decision #12 in architecture.md):</strong></p>
 * <p>
 * This record is deliberately duplicated across services rather than pulled into a shared internal
 * "common-dto" library. In accordance with microservice independence principles established in
 * Decision #12 (originally applied to JWT verification across auth-service and gateway), duplicating
 * lightweight contracts avoids coupling service build lifecycles and release schedules to a common JAR.
 * </p>
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
