package com.paygate.webhook.exception;

import java.util.UUID;

public class EndpointNotFoundException extends RuntimeException {
    public EndpointNotFoundException(UUID id) {
        super("Webhook endpoint not found: " + id);
    }
}
