package com.paygate.webhook.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class HmacSignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Computes HMAC-SHA256 hex string over the payload using the provided secret key.
     */
    public String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    /**
     * Verifies HMAC signature using constant-time equality check to prevent timing attacks.
     */
    public boolean verify(String payload, String secret, String expectedSignature) {
        if (expectedSignature == null || payload == null || secret == null) {
            return false;
        }
        String calculated = sign(payload, secret);
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Generates a cryptographically strong secret prefixed with 'whsec_'.
     */
    public String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }
}
