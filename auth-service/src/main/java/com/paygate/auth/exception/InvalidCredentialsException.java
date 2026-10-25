package com.paygate.auth.exception;

/** Deliberately vague message: never reveal whether the merchantId or the apiKey was wrong. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid merchant id or API key");
    }
}
