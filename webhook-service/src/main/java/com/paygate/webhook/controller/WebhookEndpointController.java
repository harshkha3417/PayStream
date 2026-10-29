package com.paygate.webhook.controller;

import com.paygate.webhook.dto.CreateEndpointRequest;
import com.paygate.webhook.dto.DeliveryResponse;
import com.paygate.webhook.dto.EndpointResponse;
import com.paygate.webhook.service.WebhookDeliveryService;
import com.paygate.webhook.service.WebhookEndpointService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;
    private final WebhookDeliveryService deliveryService;

    public WebhookEndpointController(
            WebhookEndpointService endpointService,
            WebhookDeliveryService deliveryService) {
        this.endpointService = endpointService;
        this.deliveryService = deliveryService;
    }

    @PostMapping("/endpoints")
    public ResponseEntity<EndpointResponse> createEndpoint(
            @Valid @RequestBody CreateEndpointRequest request,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        EndpointResponse response = endpointService.createEndpoint(merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/endpoints")
    public ResponseEntity<List<EndpointResponse>> listEndpoints(
            @RequestHeader("X-Merchant-Id") String merchantId) {
        return ResponseEntity.ok(endpointService.listEndpoints(merchantId));
    }

    @GetMapping("/endpoints/{id}")
    public ResponseEntity<EndpointResponse> getEndpoint(
            @PathVariable UUID id,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        return ResponseEntity.ok(endpointService.getEndpoint(id, merchantId));
    }

    @DeleteMapping("/endpoints/{id}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable UUID id,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        endpointService.deleteEndpoint(id, merchantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/endpoints/{id}/test")
    public ResponseEntity<DeliveryResponse> triggerTest(
            @PathVariable UUID id,
            @RequestHeader("X-Merchant-Id") String merchantId) {
        DeliveryResponse response = deliveryService.triggerTestDelivery(id, merchantId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/deliveries")
    public ResponseEntity<Page<DeliveryResponse>> listDeliveries(
            @RequestHeader("X-Merchant-Id") String merchantId,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(deliveryService.listDeliveries(merchantId, endpointId, page, size));
    }
}
