package com.paygate.ledger.exception;

import java.util.UUID;

public class PaymentLedgerNotFoundException extends RuntimeException {
    public PaymentLedgerNotFoundException(UUID paymentId) {
        super("No ledger entries found for paymentId: " + paymentId);
    }
}
