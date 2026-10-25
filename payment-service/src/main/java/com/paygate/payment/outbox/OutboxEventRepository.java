package com.paygate.payment.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims an ordered batch of unpublished outbox events using PostgreSQL's
     * SELECT ... FOR UPDATE SKIP LOCKED.
     *
     * Why SKIP LOCKED:
     * When multiple payment-service instances run concurrently, each poller locks
     * only its claimed batch. Other instances skip those locked rows and immediately
     * claim subsequent batches with zero lock contention, zero waiting, and zero
     * distributed lock coordination (like ZooKeeper/Consul/Redis locks).
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublishedBatch(@Param("batchSize") int batchSize);
}
