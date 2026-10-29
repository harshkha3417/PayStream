package com.paygate.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateEndpointRequest(
        @NotBlank(message = "URL is required")
        @Pattern(regexp = "^https?://.+", message = "URL must start with http:// or https://")
        String url,

        List<String> events
) {
}
