package com.paygate.payment.Exception;

/** Same (merchant, key) pair, but the request body hashes differently. Maps to 422. */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
    }
}
