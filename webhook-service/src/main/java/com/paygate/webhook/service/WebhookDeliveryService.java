package com.paygate.webhook.service;

import com.paygate.webhook.dto.DeliveryResponse;
import com.paygate.webhook.dto.PaymentEventEnvelope;
import com.paygate.webhook.entity.WebhookDelivery;
import com.paygate.webhook.entity.WebhookEndpoint;
import com.paygate.webhook.enums.DeliveryStatus;
import com.paygate.webhook.exception.EndpointNotFoundException;
import com.paygate.webhook.repository.WebhookDeliveryRepository;
import com.paygate.webhook.repository.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final HmacSignatureService signatureService;
    private final HttpClient httpClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${webhook.max-attempts:5}")
    private int maxAttempts;

    @Value("${webhook.dispatch-timeout-seconds:5}")
    private int timeoutSeconds;

    @Value("${webhook.dlq-topic:webhooks.dlq}")
    private String dlqTopic;

    public WebhookDeliveryService(
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointRepository endpointRepository,
            HmacSignatureService signatureService,
            HttpClient httpClient,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.signatureService = signatureService;
        this.httpClient = httpClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Records pending webhook deliveries for all active endpoints subscribed to this event type.
     */
    @Transactional
    public void recordDeliveriesForEvent(PaymentEventEnvelope envelope) {
        List<WebhookEndpoint> endpoints = endpointRepository.findByMerchantIdAndActiveTrue(envelope.merchantId());
        if (endpoints.isEmpty()) {
            log.debug("No active webhook endpoints configured for merchantId={}", envelope.merchantId());
            return;
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize envelope for event {}", envelope.eventId(), e);
            return;
        }

        for (WebhookEndpoint endpoint : endpoints) {
            if (endpoint.isSubscribedTo(envelope.type())) {
                WebhookDelivery delivery = new WebhookDelivery(
                        endpoint.getId(),
                        envelope.merchantId(),
                        envelope.eventId(),
                        envelope.type(),
                        payloadJson
                );
                WebhookDelivery saved = deliveryRepository.save(delivery);
                log.info("Queued webhook delivery {} for endpoint {} eventId={}",
                        saved.getId(), endpoint.getId(), envelope.eventId());

                // Perform immediate first attempt
                dispatchSingleDelivery(saved, endpoint);
            }
        }
    }

    /**
     * Executes the HTTP delivery attempt for a given delivery record.
     */
    @Transactional
    public void dispatchDelivery(UUID deliveryId) {
        Optional<WebhookDelivery> optDelivery = deliveryRepository.findById(deliveryId);
        if (optDelivery.isEmpty()) {
            log.warn("Delivery {} not found for dispatch", deliveryId);
            return;
        }
        WebhookDelivery delivery = optDelivery.get();
        Optional<WebhookEndpoint> optEndpoint = endpointRepository.findById(delivery.getEndpointId());
        if (optEndpoint.isEmpty() || !optEndpoint.get().isActive()) {
            log.warn("Endpoint {} inactive or deleted; marking delivery {} as DEAD", delivery.getEndpointId(), deliveryId);
            delivery.markDead(delivery.getAttemptCount() + 1, null, "Endpoint inactive or deleted");
            deliveryRepository.save(delivery);
            return;
        }

        dispatchSingleDelivery(delivery, optEndpoint.get());
    }

    private void dispatchSingleDelivery(WebhookDelivery delivery, WebhookEndpoint endpoint) {
        int attempt = delivery.getAttemptCount() + 1;
        long timestamp = Instant.now().getEpochSecond();
        String signature = signatureService.sign(delivery.getPayload(), endpoint.getSecret());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getUrl()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PayStream-Webhook/1.0")
                    .header("X-PayStream-Signature", signature)
                    .header("X-PayStream-Event-Id", delivery.getEventId().toString())
                    .header("X-PayStream-Event-Type", delivery.getEventType())
                    .header("X-PayStream-Timestamp", String.valueOf(timestamp))
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                delivery.markSuccess(statusCode, response.body());
                deliveryRepository.save(delivery);
                log.info("Webhook delivery {} to {} succeeded with status {}", delivery.getId(), endpoint.getUrl(), statusCode);
            } else {
                handleFailure(delivery, attempt, statusCode, "HTTP " + statusCode + ": " + response.body());
            }
        } catch (Exception e) {
            handleFailure(delivery, attempt, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handleFailure(WebhookDelivery delivery, int attempt, Integer statusCode, String errorMsg) {
        if (attempt >= maxAttempts) {
            delivery.markDead(attempt, statusCode, errorMsg);
            deliveryRepository.save(delivery);
            log.warn("Webhook delivery {} exhausted {} attempts. Marked DEAD. Routing to DLQ topic {}",
                    delivery.getId(), attempt, dlqTopic);
            publishToDlq(delivery);
        } else {
            long backoffSeconds = calculateBackoffSeconds(attempt);
            Instant nextRetry = Instant.now().plusSeconds(backoffSeconds);
            delivery.markRetrying(attempt, statusCode, errorMsg, nextRetry);
            deliveryRepository.save(delivery);
            log.info("Webhook delivery {} failed (attempt {}/{}). Scheduled retry in {}s at {}",
                    delivery.getId(), attempt, maxAttempts, backoffSeconds, nextRetry);
        }
    }

    private void publishToDlq(WebhookDelivery delivery) {
        try {
            PaymentEventEnvelope dlqEnvelope = PaymentEventEnvelope.of(
                    "webhook.delivery.failed",
                    delivery.getEventId(),
                    delivery.getMerchantId(),
                    Map.of(
                            "deliveryId", delivery.getId() != null ? delivery.getId().toString() : "",
                            "endpointId", delivery.getEndpointId() != null ? delivery.getEndpointId().toString() : "",
                            "eventType", delivery.getEventType() != null ? delivery.getEventType() : "",
                            "attempts", delivery.getAttemptCount(),
                            "lastStatusCode", delivery.getStatusCode() != null ? delivery.getStatusCode() : -1,
                            "error", delivery.getErrorMessage() != null ? delivery.getErrorMessage() : "",
                            "originalPayload", delivery.getPayload() != null ? delivery.getPayload() : ""
                    )
            );
            String payload = objectMapper.writeValueAsString(dlqEnvelope);
            kafkaTemplate.send(dlqTopic, delivery.getMerchantId(), payload);
            log.info("Published failed webhook delivery {} to DLQ topic {}", delivery.getId(), dlqTopic);
        } catch (Exception e) {
            log.error("Failed to publish webhook delivery {} to DLQ", delivery.getId(), e);
        }
    }

    private long calculateBackoffSeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 5L;
            case 2 -> 30L;
            case 3 -> 120L;
            case 4 -> 300L;
            default -> 600L;
        };
    }

    @Transactional
    public DeliveryResponse triggerTestDelivery(UUID endpointId, String merchantId) {
        WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId));

        PaymentEventEnvelope testEnvelope = PaymentEventEnvelope.of(
                "test.ping",
                UUID.randomUUID(),
                merchantId,
                Map.of("message", "PayStream webhook test ping", "endpointId", endpointId.toString())
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(testEnvelope);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failure", e);
        }

        WebhookDelivery delivery = new WebhookDelivery(
                endpointId,
                merchantId,
                testEnvelope.eventId(),
                testEnvelope.type(),
                payloadJson
        );
        WebhookDelivery saved = deliveryRepository.save(delivery);
        dispatchSingleDelivery(saved, endpoint);
        return DeliveryResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<DeliveryResponse> listDeliveries(String merchantId, UUID endpointId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<WebhookDelivery> results;
        if (endpointId != null) {
            results = deliveryRepository.findByEndpointId(endpointId, pageRequest);
        } else {
            results = deliveryRepository.findByMerchantId(merchantId, pageRequest);
        }
        return results.map(DeliveryResponse::fromEntity);
    }
}
