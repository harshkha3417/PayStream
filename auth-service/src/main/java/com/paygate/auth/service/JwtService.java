package com.paygate.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A hand-rolled, minimal HS256 JWT signer/verifier: plain JDK crypto
 * (HMAC-SHA256) plus Jackson, no extra library version to chase. A JWT is
 * just three base64url parts joined by dots: header.payload.signature —
 * understanding that is the point of writing this by hand.
 *
 * The Gateway module has its own copy of the "verify" half
 * (JwtVerifier). That duplication is deliberate: each service stays
 * independently deployable without a shared internal library.
 */
@Service
public class JwtService {

    private static final String ALG = "HmacSHA256";
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.ttl-seconds:900}") long ttlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "auth.jwt.secret must be at least 32 bytes (set env var JWT_SECRET)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** Issues a token with {@code sub} = merchantId. */
    public String issue(String subject) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", subject);
            claims.put("iat", now);
            claims.put("exp", now + ttlSeconds);

            String headerPart = encode(mapper.writeValueAsBytes(header));
            String claimsPart = encode(mapper.writeValueAsBytes(claims));
            String signingInput = headerPart + "." + claimsPart;
            String signature = encode(hmac(signingInput));

            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue token", e);
        }
    }

    /** Verifies signature and expiry, and returns the subject (merchantId) if valid. */
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

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static class JwtInvalidException extends RuntimeException {
        public JwtInvalidException(String message) {
            super(message);
        }
    }
}
