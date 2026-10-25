package com.paygate.payment.Entity;

import com.paygate.payment.Enum.IdempotencyStatus;
import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per (merchantId, idempotencyKey) pair. The composite primary key
 * IS the concurrency control here — there is no @Version/optimistic lock on
 * this entity, because that's the wrong tool for this job. Two requests
 * racing to claim the same key are resolved by a native INSERT ... ON
 * CONFLICT DO NOTHING (see IdempotencyRecordRepository): the database
 * guarantees exactly one of them inserts a row, with no window for both to
 * "win". That's a stronger, simpler guarantee than optimistic locking gives
 * you for a brand-new row that doesn't exist yet to version.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @EmbeddedId
    private Key id;

    /** SHA-256 hex of the canonical (re-serialized) request body. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    /** Null until the action completes. */
    @Column(name = "response_status")
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.SMALLINT)
    private Integer responseStatus;

    /** The original Location header, if the replayed response should carry one. */
    @Column(name = "response_location", length = 255)
    private String responseLocation;

    /** The exact JSON body to replay, byte-for-byte. */
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String merchantId, String idempotencyKey, String requestHash) {
        this.id = new Key(merchantId, idempotencyKey);
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.PROCESSING;
    }

    public Key getId() { return id; }
    public String getRequestHash() { return requestHash; }
    public IdempotencyStatus getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseLocation() { return responseLocation; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "merchant_id", nullable = false, length = 64)
        private String merchantId;

        @Column(name = "idempotency_key", nullable = false, length = 255)
        private String idempotencyKey;

        protected Key() {
        }

        public Key(String merchantId, String idempotencyKey) {
            this.merchantId = merchantId;
            this.idempotencyKey = idempotencyKey;
        }

        public String getMerchantId() { return merchantId; }
        public String getIdempotencyKey() { return idempotencyKey; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(merchantId, key.merchantId) && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(merchantId, idempotencyKey);
        }
    }
}
