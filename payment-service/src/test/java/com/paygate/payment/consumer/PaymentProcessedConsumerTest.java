package com.paygate.payment.consumer;

import com.paygate.payment.Dto.PaymentEventEnvelope;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.InvalidStatusTransitionException;
import com.paygate.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class PaymentProcessedConsumerTest {

    private PaymentService paymentService;
    private ObjectMapper objectMapper;
    private PaymentProcessedConsumer consumer;

    @BeforeEach
    void setUp() {
        paymentService = Mockito.mock(PaymentService.class);
        objectMapper = new ObjectMapper();
        consumer = new PaymentProcessedConsumer(paymentService, objectMapper);
    }

    @Test
    void appliesSuccessfulOutcome() {
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                "payment.processed", paymentId, "m_1", Map.of("outcome", "SUCCEEDED"));
        String payload = assertDoesNotThrow(() -> objectMapper.writeValueAsString(envelope));

        consumer.onPaymentProcessed(payload);

        verify(paymentService).applyProcessedOutcome(paymentId, true);
    }

    @Test
    void appliesFailedOutcome() {
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                "payment.processed", paymentId, "m_1", Map.of("outcome", "FAILED"));
        String payload = assertDoesNotThrow(() -> objectMapper.writeValueAsString(envelope));

        consumer.onPaymentProcessed(payload);

        verify(paymentService).applyProcessedOutcome(paymentId, false);
    }

    @Test
    void swallowsInvalidStatusTransitionExceptionAsIdempotentNoOp() {
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                "payment.processed", paymentId, "m_1", Map.of("outcome", "SUCCEEDED"));
        String payload = assertDoesNotThrow(() -> objectMapper.writeValueAsString(envelope));

        doThrow(new InvalidStatusTransitionException(PaymentStatus.SUCCEEDED, PaymentStatus.PROCESSING))
                .when(paymentService).applyProcessedOutcome(paymentId, true);

        // Crucial test for consumer idempotency contract: redelivery must NOT fail or throw
        assertDoesNotThrow(() -> consumer.onPaymentProcessed(payload));
        verify(paymentService).applyProcessedOutcome(paymentId, true);
    }
}
