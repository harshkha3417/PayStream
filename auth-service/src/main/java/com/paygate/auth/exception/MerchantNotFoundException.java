package com.paygate.auth.exception;

import java.util.UUID;

public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException(UUID id) {
        super("Merchant not found: " + id);
    }
}
