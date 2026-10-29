package com.paygate.webhook.service;

import com.paygate.webhook.dto.PaymentEventEnvelope;
import com.paygate.webhook.entity.WebhookDelivery;
import com.paygate.webhook.entity.WebhookEndpoint;
import com.paygate.webhook.enums.DeliveryStatus;
import com.paygate.webhook.repository.WebhookDeliveryRepository;
import com.paygate.webhook.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookDeliveryServiceTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookEndpointRepository endpointRepository;
    private HmacSignatureService signatureService;
    private HttpClient httpClient;
    private KafkaTemplate<String, String> kafkaTemplate;
    private ObjectMapper objectMapper;
    private WebhookDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryRepository = Mockito.mock(WebhookDeliveryRepository.class);
        endpointRepository = Mockito.mock(WebhookEndpointRepository.class);
        signatureService = Mockito.mock(HmacSignatureService.class);
        httpClient = Mockito.mock(HttpClient.class);
        kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper();

        deliveryService = new WebhookDeliveryService(
                deliveryRepository,
                endpointRepository,
                signatureService,
                httpClient,
                kafkaTemplate,
                objectMapper
        );

        ReflectionTestUtils.setField(deliveryService, "maxAttempts", 5);
        ReflectionTestUtils.setField(deliveryService, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(deliveryService, "dlqTopic", "webhooks.dlq");
    }

    @Test
    void marksSuccessWhenHttp200() throws Exception {
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        WebhookEndpoint endpoint = new WebhookEndpoint("m_1", "https://example.com/wh", "whsec_test", "*");
        WebhookDelivery delivery = new WebhookDelivery(endpointId, "m_1", UUID.randomUUID(), "payment.created", "{}");

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(signatureService.sign(any(), any())).thenReturn("sig_123");

        HttpResponse<String> httpResponse = Mockito.mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("OK");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        deliveryService.dispatchDelivery(deliveryId);

        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        assertEquals(200, delivery.getStatusCode());
        verify(deliveryRepository, atLeastOnce()).save(delivery);
    }

    @Test
    void marksRetryingWhenHttpFailsUnderMaxAttempts() throws Exception {
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        WebhookEndpoint endpoint = new WebhookEndpoint("m_1", "https://example.com/wh", "whsec_test", "*");
        WebhookDelivery delivery = new WebhookDelivery(endpointId, "m_1", UUID.randomUUID(), "payment.created", "{}");
        delivery.setAttemptCount(1);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(signatureService.sign(any(), any())).thenReturn("sig_123");

        HttpResponse<String> httpResponse = Mockito.mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        deliveryService.dispatchDelivery(deliveryId);

        assertEquals(DeliveryStatus.RETRYING, delivery.getStatus());
        assertEquals(2, delivery.getAttemptCount());
        assertNotNull(delivery.getNextRetryAt());
        verify(deliveryRepository, atLeastOnce()).save(delivery);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void marksDeadAndPublishesDlqWhenMaxAttemptsExhausted() throws Exception {
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        WebhookEndpoint endpoint = new WebhookEndpoint("m_1", "https://example.com/wh", "whsec_test", "*");
        WebhookDelivery delivery = new WebhookDelivery(endpointId, "m_1", UUID.randomUUID(), "payment.created", "{}");
        delivery.setId(deliveryId);
        delivery.setAttemptCount(4); // this attempt will be attempt 5 (max)

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(signatureService.sign(any(), any())).thenReturn("sig_123");

        HttpResponse<String> httpResponse = Mockito.mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(503);
        when(httpResponse.body()).thenReturn("Service Unavailable");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        deliveryService.dispatchDelivery(deliveryId);

        assertEquals(DeliveryStatus.DEAD, delivery.getStatus());
        assertEquals(5, delivery.getAttemptCount());
        verify(deliveryRepository, atLeastOnce()).save(delivery);

        // Verify DLQ event published to Kafka
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), eq("m_1"), payloadCaptor.capture());
        assertEquals("webhooks.dlq", topicCaptor.getValue());
        assertTrue(payloadCaptor.getValue().contains("webhook.delivery.failed"));
    }
}
