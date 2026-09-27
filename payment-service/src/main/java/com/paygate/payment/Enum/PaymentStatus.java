package com.paygate.payment.Enum;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED;
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(CREATED, Set.of(PROCESSING, FAILED));
        ALLOWED.put(PROCESSING, Set.of(SUCCEEDED, FAILED));
        ALLOWED.put(SUCCEEDED, Set.of(PARTIALLY_REFUNDED, REFUNDED));
        ALLOWED.put(PARTIALLY_REFUNDED, Set.of(PARTIALLY_REFUNDED, REFUNDED));
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
