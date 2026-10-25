package com.paygate.payment.Exception;

/** The first request with this key hasn't finished yet. Maps to 409 — ask the client to retry shortly. */
public class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException(String idempotencyKey) {
        super("A request with Idempotency-Key '" + idempotencyKey + "' is already being processed");
    }
}