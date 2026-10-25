package com.paygate.processor.consumer;

import com.paygate.processor.dto.PaymentEventEnvelope;
import com.paygate.processor.service.PaymentSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentCreatedConsumerTest {

    private PaymentSimulator simulator;
    private KafkaTemplate<String, String> kafkaTemplate;
    private ObjectMapper objectMapper;
    private PaymentCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        simulator = mock(PaymentSimulator.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> mockTemplate = mock(KafkaTemplate.class);
        kafkaTemplate = mockTemplate;
        objectMapper = new ObjectMapper();
        consumer = new PaymentCreatedConsumer(simulator, kafkaTemplate, objectMapper);
    }

    @Test
    void consumesPaymentCreatedAndPublishesProcessedSucceeded() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(simulator.process(paymentId)).thenReturn(true);

        PaymentEventEnvelope inEnvelope = PaymentEventEnvelope.of(
                "payment.created", paymentId, "m_1", Map.of("amount", 1000L));
        String inPayload = objectMapper.writeValueAsString(inEnvelope);

        consumer.onPaymentCreated(inPayload);

        verify(simulator).process(paymentId);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("payment.processed");
        assertThat(keyCaptor.getValue()).isEqualTo(paymentId.toString());

        PaymentEventEnvelope outEnvelope = objectMapper.readValue(payloadCaptor.getValue(), PaymentEventEnvelope.class);
        assertThat(outEnvelope.type()).isEqualTo("payment.processed");
        assertThat(outEnvelope.paymentId()).isEqualTo(paymentId);
        assertThat(outEnvelope.merchantId()).isEqualTo("m_1");
        assertThat(outEnvelope.data().get("outcome")).isEqualTo("SUCCEEDED");
    }

    @Test
    void consumesPaymentCreatedAndPublishesProcessedFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(simulator.process(paymentId)).thenReturn(false);

        PaymentEventEnvelope inEnvelope = PaymentEventEnvelope.of(
                "payment.created", paymentId, "m_1", Map.of("amount", 1000L));
        String inPayload = objectMapper.writeValueAsString(inEnvelope);

        consumer.onPaymentCreated(inPayload);

        verify(simulator).process(paymentId);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.processed"), eq(paymentId.toString()), payloadCaptor.capture());

        PaymentEventEnvelope outEnvelope = objectMapper.readValue(payloadCaptor.getValue(), PaymentEventEnvelope.class);
        assertThat(outEnvelope.data().get("outcome")).isEqualTo("FAILED");
    }
}
