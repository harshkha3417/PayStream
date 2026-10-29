package com.paygate.refund.exception;

import java.util.UUID;

public class RefundNotFoundException extends RuntimeException {
    public RefundNotFoundException(UUID id) {
        super("Refund not found: " + id);
    }
}
