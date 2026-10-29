package com.paygate.webhook.service;

import com.paygate.webhook.dto.CreateEndpointRequest;
import com.paygate.webhook.dto.EndpointResponse;
import com.paygate.webhook.entity.WebhookEndpoint;
import com.paygate.webhook.exception.EndpointNotFoundException;
import com.paygate.webhook.repository.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WebhookEndpointService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEndpointService.class);

    private final WebhookEndpointRepository repository;
    private final HmacSignatureService signatureService;

    public WebhookEndpointService(WebhookEndpointRepository repository, HmacSignatureService signatureService) {
        this.repository = repository;
        this.signatureService = signatureService;
    }

    @Transactional
    public EndpointResponse createEndpoint(String merchantId, CreateEndpointRequest request) {
        String secret = signatureService.generateSecret();
        String events = (request.events() == null || request.events().isEmpty())
                ? "*"
                : String.join(",", request.events());

        WebhookEndpoint endpoint = new WebhookEndpoint(
                merchantId,
                request.url().trim(),
                secret,
                events
        );
        WebhookEndpoint saved = repository.save(endpoint);
        log.info("Created webhook endpoint {} for merchantId={}, url={}", saved.getId(), merchantId, saved.getUrl());
        return EndpointResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> listEndpoints(String merchantId) {
        return repository.findByMerchantId(merchantId).stream()
                .map(EndpointResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public EndpointResponse getEndpoint(UUID id, String merchantId) {
        WebhookEndpoint endpoint = repository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new EndpointNotFoundException(id));
        return EndpointResponse.fromEntity(endpoint);
    }

    @Transactional
    public void deleteEndpoint(UUID id, String merchantId) {
        WebhookEndpoint endpoint = repository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new EndpointNotFoundException(id));
        repository.delete(endpoint);
        log.info("Deleted webhook endpoint {} for merchantId={}", id, merchantId);
    }
}
