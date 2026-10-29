package com.paygate.refund.exception;

import java.util.UUID;

public class PaymentNotRefundableException extends RuntimeException {
    public PaymentNotRefundableException(UUID paymentId, String reason) {
        super("Payment " + paymentId + " is not refundable: " + reason);
    }
}
