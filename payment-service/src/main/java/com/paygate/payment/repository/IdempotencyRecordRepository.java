package com.paygate.payment.repository;

import com.paygate.payment.Entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    /**
     * The entire concurrency guarantee for idempotency lives in this one
     * statement. ON CONFLICT DO NOTHING means if two requests race to insert
     * the same (merchant_id, idempotency_key) at the exact same instant,
     * Postgres guarantees exactly one of them actually inserts a row — the
     * other gets 0 rows affected back, with zero chance of both "winning".
     * No @Version, no application-level locking, no retry loop needed.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash, status, created_at, updated_at)
            VALUES (:merchantId, :idempotencyKey, :requestHash, 'PROCESSING', now(), now())
            ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("merchantId") String merchantId,
                 @Param("idempotencyKey") String idempotencyKey,
                 @Param("requestHash") String requestHash);

    @Modifying
    @Transactional
    @Query("""
            UPDATE IdempotencyRecord r
            SET r.status = com.paygate.payment.Enum.IdempotencyStatus.COMPLETED,
                r.responseStatus = :responseStatus,
                r.responseLocation = :responseLocation,
                r.responseBody = :responseBody,
                r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.id.merchantId = :merchantId AND r.id.idempotencyKey = :idempotencyKey
            """)
    void markCompleted(@Param("merchantId") String merchantId,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("responseStatus") int responseStatus,
                       @Param("responseLocation") String responseLocation,
                       @Param("responseBody") String responseBody);

    /**
     * Written as an explicit JPQL query rather than a derived method name
     * (e.g. findByIdMerchantIdAndIdIdempotencyKey) on purpose: a derived
     * name spanning the same @EmbeddedId twice, where one of the nested
     * properties (idempotencyKey) itself starts with "Id", relies on Spring
     * Data's automatic backtracking to resolve correctly — it usually does,
     * but a PropertyReferenceException at context-startup time is a worse
     * failure mode than just writing the three unambiguous lines below.
     */
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.id.merchantId = :merchantId AND r.id.idempotencyKey = :idempotencyKey")
    Optional<IdempotencyRecord> findByMerchantIdAndIdempotencyKey(@Param("merchantId") String merchantId,
                                                                  @Param("idempotencyKey") String idempotencyKey);
}