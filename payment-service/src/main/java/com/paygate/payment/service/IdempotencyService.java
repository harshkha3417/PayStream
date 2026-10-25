package com.paygate.payment.service;

import com.paygate.payment.Entity.IdempotencyRecord;
import com.paygate.payment.Exception.IdempotencyInProgressException;
import com.paygate.payment.Exception.IdempotencyKeyReuseException;
import com.paygate.payment.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Wraps any "create something" action with idempotency-key semantics.
 *
 * Flow for a request carrying an Idempotency-Key:
 *  1. Redis fast path — a hit here means a completed response was already
 *     cached; return it without touching Postgres at all.
 *  2. Redis miss: try to claim the key in Postgres with an atomic
 *     INSERT ... ON CONFLICT DO NOTHING (see IdempotencyRecordRepository).
 *     - We claimed it (1 row inserted): we're the winner. Run the action,
 *       store the result in Postgres AND Redis, return it.
 *     - We didn't claim it (0 rows, someone beat us to it): read the
 *       existing row.
 *         - request_hash differs -> 422, the key is being reused for a
 *           genuinely different request.
 *         - status is still PROCESSING -> 409, the first request isn't
 *           done yet; ask the client to retry shortly rather than making
 *           them wait on this thread.
 *         - status is COMPLETED -> replay the stored response exactly
 *           (status code, Location header, body).
 *
 * Why Postgres is the source of truth and Redis is only a cache: Redis can
 * be flushed, evicted under memory pressure, or simply not yet have the
 * key (another instance just wrote it, replication lag, etc). None of that
 * is allowed to cause a double charge. The INSERT ... ON CONFLICT in
 * Postgres is the actual correctness guarantee; Redis only exists to skip
 * a database round-trip on the common case of a client retrying a request
 * it already got a response for.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration REDIS_TTL = Duration.ofHours(24);
    private static final String REDIS_KEY_PREFIX = "idem:";
    /** Field separator used inside the cached Redis value — see cacheKey() below. */
    private static final String FIELD_SEPARATOR = "\u0001";

    private final IdempotencyRecordRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRecordRepository repository, StringRedisTemplate redis, ObjectMapper mapper) {
        this.repository = repository;
        this.redis = redis;
        this.mapper = mapper;
    }

    public ResponseEntity<String> execute(String merchantId, String idempotencyKey, Object requestForHashing,
                                          Supplier<ResponseEntity<?>> action) {
        String requestHash = sha256Hex(toCanonicalJson(requestForHashing));
        String redisKey = REDIS_KEY_PREFIX + merchantId + ":" + idempotencyKey;

        String cached = safeRedisGet(redisKey);
        if (cached != null) {
            return replayFromCacheString(cached);
        }

        int inserted = repository.tryClaim(merchantId, idempotencyKey, requestHash);
        if (inserted == 0) {
            return handleExistingRecord(merchantId, idempotencyKey, requestHash, redisKey);
        }

        // We won the race to claim this key. Run the actual business logic now.
        ResponseEntity<?> result = action.get();
        String bodyJson = writeJson(result.getBody());
        String location = result.getHeaders().getFirst(HttpHeaders.LOCATION);
        int statusCode = result.getStatusCode().value();

        repository.markCompleted(merchantId, idempotencyKey, statusCode, location, bodyJson);
        safeRedisSet(redisKey, encodeForCache(statusCode, location, bodyJson), REDIS_TTL);

        return buildResponse(statusCode, location, bodyJson);
    }

    private ResponseEntity<String> handleExistingRecord(String merchantId, String idempotencyKey,
                                                        String requestHash, String redisKey) {
        IdempotencyRecord existing = repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record vanished between claim attempt and read for key " + idempotencyKey));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        if (existing.getStatus() == com.paygate.payment.Enum.IdempotencyStatus.PROCESSING) {
            throw new IdempotencyInProgressException(idempotencyKey);
        }

        // COMPLETED: replay it, and warm the Redis cache while we're here
        // so the next retry of this same key doesn't need Postgres at all.
        String encoded = encodeForCache(existing.getResponseStatus(), existing.getResponseLocation(), existing.getResponseBody());
        safeRedisSet(redisKey, encoded, REDIS_TTL);
        return buildResponse(existing.getResponseStatus(), existing.getResponseLocation(), existing.getResponseBody());
    }

    private String safeRedisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis unavailable for GET {}, falling back to database: {}", key, e.getMessage());
            return null;
        }
    }

    private void safeRedisSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis unavailable for SET {}, continuing without cache update: {}", key, e.getMessage());
        }
    }

    private ResponseEntity<String> replayFromCacheString(String cached) {
        String[] parts = cached.split(FIELD_SEPARATOR, 3);
        int status = Integer.parseInt(parts[0]);
        String location = parts[1].isEmpty() ? null : parts[1];
        String body = parts.length > 2 ? parts[2] : "";
        return buildResponse(status, location, body);
    }

    private String encodeForCache(int status, String location, String body) {
        return status + FIELD_SEPARATOR + (location == null ? "" : location) + FIELD_SEPARATOR + body;
    }

    private ResponseEntity<String> buildResponse(int status, String location, String body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (location != null) {
            builder.header(HttpHeaders.LOCATION, location);
        }
        return builder.body(body);
    }

    private String toCanonicalJson(Object o) {
        // Re-serializing (rather than hashing the raw request bytes) means two
        // requests that are semantically identical but byte-different (e.g.
        // different key order or whitespace in the original JSON) still hash
        // the same — Jackson serializes a POJO's fields in a consistent order.
        return writeJson(o);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize for idempotency handling", e);
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
