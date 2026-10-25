package com.paygate.auth.dto;

import java.util.UUID;

public record MerchantCreatedResponse(UUID merchantId, String name, String apiKey) {
}
