package com.paygate.webhook.service;

import com.paygate.webhook.dto.CreateEndpointRequest;
import com.paygate.webhook.dto.EndpointResponse;
import com.paygate.webhook.entity.WebhookEndpoint;
import com.paygate.webhook.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookEndpointServiceTest {

    private WebhookEndpointRepository repository;
    private HmacSignatureService signatureService;
    private WebhookEndpointService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(WebhookEndpointRepository.class);
        signatureService = Mockito.mock(HmacSignatureService.class);
        service = new WebhookEndpointService(repository, signatureService);
    }

    @Test
    void createsEndpointWithGeneratedSecretAndEvents() {
        when(signatureService.generateSecret()).thenReturn("whsec_mocksecret123");
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateEndpointRequest request = new CreateEndpointRequest(
                "https://merchant.example.com/webhook",
                List.of("payment.succeeded", "refund.completed")
        );

        EndpointResponse response = service.createEndpoint("m_123", request);

        assertNotNull(response);
        assertEquals("https://merchant.example.com/webhook", response.url());
        assertEquals("whsec_mocksecret123", response.secret());
        assertEquals(List.of("payment.succeeded", "refund.completed"), response.subscribedEvents());

        ArgumentCaptor<WebhookEndpoint> captor = ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(repository).save(captor.capture());
        WebhookEndpoint saved = captor.getValue();
        assertEquals("m_123", saved.getMerchantId());
        assertEquals("payment.succeeded,refund.completed", saved.getSubscribedEvents());
        assertTrue(saved.isSubscribedTo("payment.succeeded"));
        assertTrue(saved.isSubscribedTo("refund.completed"));
        assertFalse(saved.isSubscribedTo("payment.failed"));
    }
}
