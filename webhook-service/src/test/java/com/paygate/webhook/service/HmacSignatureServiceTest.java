package com.paygate.webhook.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacSignatureServiceTest {

    private HmacSignatureService service;

    @BeforeEach
    void setUp() {
        service = new HmacSignatureService();
    }

    @Test
    void generatesValidSecret() {
        String secret = service.generateSecret();
        assertNotNull(secret);
        assertTrue(secret.startsWith("whsec_"));
        assertEquals(70, secret.length()); // 6 + 64 hex chars
    }

    @Test
    void signsAndVerifiesPayloadCorrectly() {
        String payload = "{\"event\":\"payment.succeeded\",\"amount\":1000}";
        String secret = service.generateSecret();

        String signature = service.sign(payload, secret);
        assertNotNull(signature);
        assertFalse(signature.isBlank());

        assertTrue(service.verify(payload, secret, signature));
        assertFalse(service.verify(payload, secret, "invalid_signature"));
        assertFalse(service.verify("{\"tampered\":true}", secret, signature));
    }

    @Test
    void producesDeterministicSignatureForSameInput() {
        String payload = "{\"test\":123}";
        String secret = "fixed_secret_key_12345";

        String sig1 = service.sign(payload, secret);
        String sig2 = service.sign(payload, secret);

        assertEquals(sig1, sig2);
    }
}
