package com.paygate.payment.Exception;

import com.paygate.payment.Enum.PaymentStatus;

public class InvalidStatusTransitionException extends RuntimeException{
    public InvalidStatusTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Illegal status transition: " + from + " -> " + to);
    }
}
