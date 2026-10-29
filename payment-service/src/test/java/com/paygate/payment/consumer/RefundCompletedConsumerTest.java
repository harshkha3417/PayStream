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

class RefundCompletedConsumerTest {

    private PaymentService paymentService;
    private ObjectMapper objectMapper;
    private RefundCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        paymentService = Mockito.mock(PaymentService.class);
        objectMapper = new ObjectMapper();
        consumer = new RefundCompletedConsumer(paymentService, objectMapper);
    }

    @Test
    void appliesRefundedOutcome() {
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                "refund.completed", paymentId, "m_1", Map.of(
                        "paymentStatus", "REFUNDED",
                        "amount", 1000L
                ));
        String payload = assertDoesNotThrow(() -> objectMapper.writeValueAsString(envelope));

        consumer.onRefundCompleted(payload);

        verify(paymentService).applyRefundOutcome(paymentId, PaymentStatus.REFUNDED);
    }

    @Test
    void appliesPartiallyRefundedOutcome() {
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                "refund.completed", paymentId, "m_1", Map.of(
                        "paymentStatus", "PARTIALLY_REFUNDED",
                        "amount", 500L
                ));
        String payload = assertDoesNotThrow(() -> objectMapper.writeValueAsString(envelope));

        consumer.onRefundCompleted(payload);

        verify(paymentService).applyRefundOutcome(paymentId, PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void swallowsInvalidStatusTransitionExceptionAsIdempotentNoOp() {
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                "refund.completed", paymentId, "m_1", Map.of(
                        "paymentStatus", "REFUNDED"
                ));
        String payload = assertDoesNotThrow(() -> objectMapper.writeValueAsString(envelope));

        doThrow(new InvalidStatusTransitionException(PaymentStatus.REFUNDED, PaymentStatus.REFUNDED))
                .when(paymentService).applyRefundOutcome(paymentId, PaymentStatus.REFUNDED);

        assertDoesNotThrow(() -> consumer.onRefundCompleted(payload));
        verify(paymentService).applyRefundOutcome(paymentId, PaymentStatus.REFUNDED);
    }
}
