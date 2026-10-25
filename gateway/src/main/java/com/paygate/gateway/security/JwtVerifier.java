package com.paygate.gateway.security;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * The verify-only counterpart to auth-service's JwtService. Deliberately
 * duplicated rather than shared as a library — each service stays
 * independently deployable, and the Gateway never needs to issue tokens,
 * only check them. Both services must be configured with the SAME
 * JWT_SECRET (see docker-compose.yml) or verification will always fail.
 */
@Component
public class JwtVerifier {

    private static final String ALG = "HmacSHA256";
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secret;

    public JwtVerifier(@Value("${gateway.jwt.secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "gateway.jwt.secret must be at least 32 bytes (set env var JWT_SECRET)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the verified subject (merchantId), or throws JwtInvalidException. */
    public String verifyAndGetSubject(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new JwtInvalidException("Malformed token");
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSig = hmac(signingInput);
            byte[] actualSig = Base64.getUrlDecoder().decode(parts[2]);

            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                throw new JwtInvalidException("Bad signature");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> claims = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);

            long exp = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new JwtInvalidException("Token expired");
            }
            return (String) claims.get("sub");
        } catch (JwtInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtInvalidException("Invalid token");
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance(ALG);
        mac.init(new SecretKeySpec(secret, ALG));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    public static class JwtInvalidException extends RuntimeException {
        public JwtInvalidException(String message) {
            super(message);
        }
    }
}
