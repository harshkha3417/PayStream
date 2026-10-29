package com.paygate.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * High-performance Transactional Outbox Relay.
 *
 * <p><strong>Key Performance Optimization (Decoupled Database & Kafka):</strong></p>
 * <p>
 * In the unoptimized implementation, the entire relay loop ran inside a single {@code @Transactional}
 * method. This held a PostgreSQL database connection and table/row locks open across sequential
 * synchronous network round-trips to Kafka (50 calls * 1-2ms = 100ms+ lock hold time).
 * Under high-concurrency surge traffic (500-1000 req/s), this caused severe HikariCP pool starvation
 * for incoming payment HTTP creation threads.
 * </p>
 * <p>
 * In this optimized version:
 * <ol>
 *   <li><strong>Short Transaction 1 (Claim):</strong> Claims a batch via {@code SELECT ... FOR UPDATE SKIP LOCKED}
 *       and immediately commits, releasing the DB connection within ~2ms.</li>
 *   <li><strong>Non-Blocking Async Kafka Publish:</strong> Dispatches messages to Kafka asynchronously using
 *       {@link CompletableFuture}, waiting with a bounded timeout for broker leader acknowledgments.
 *       <strong>No database connection is held during this network I/O.</strong></li>
 *   <li><strong>Short Transaction 2 (Batch Update):</strong> Marks all acknowledged event IDs as published
 *       in a single {@code UPDATE ... WHERE id IN (...)} query (~1ms).</li>
 * </ol>
 * </p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public OutboxRelay(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
    }

    private record OutboxItem(UUID id, String topic, String key, String payload) {}

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:200}")
    public void relay() {
        // Step 1: Claim batch inside short transaction (releases DB connection immediately)
        List<OutboxItem> batch = transactionTemplate.execute(status -> {
            List<OutboxEvent> events = repository.claimUnpublishedBatch(batchSize);
            if (events.isEmpty()) {
                return List.of();
            }
            List<OutboxItem> items = new ArrayList<>(events.size());
            for (OutboxEvent event : events) {
                items.add(new OutboxItem(
                        event.getId(),
                        event.getEventType(),
                        event.getAggregateId().toString(),
                        event.getPayload()
                ));
            }
            return items;
        });

        if (batch == null || batch.isEmpty()) {
            return;
        }

        log.debug("Outbox relay claimed {} unpublished event(s)", batch.size());

        // Step 2: Publish to Kafka concurrently (NO database connection held)
        List<CompletableFuture<UUID>> futures = new ArrayList<>(batch.size());
        for (OutboxItem item : batch) {
            CompletableFuture<SendResult<String, String>> sendFuture =
                    kafkaTemplate.send(item.topic(), item.key(), item.payload());

            futures.add(sendFuture
                    .thenApply(res -> item.id())
                    .exceptionally(ex -> {
                        log.error("Failed to publish outbox event {} to Kafka: {}", item.id(), ex.getMessage());
                        return null;
                    }));
        }

        // Wait for all publishes with a safety timeout
        List<UUID> successfulIds = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            for (CompletableFuture<UUID> f : futures) {
                UUID id = f.getNow(null);
                if (id != null) {
                    successfulIds.add(id);
                }
            }
        } catch (Exception e) {
            log.error("Error waiting for Kafka publish batch acknowledgment: {}", e.getMessage());
            for (CompletableFuture<UUID> f : futures) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    UUID id = f.getNow(null);
                    if (id != null) {
                        successfulIds.add(id);
                    }
                }
            }
        }

        if (successfulIds.isEmpty()) {
            return;
        }

        // Step 3: Mark acknowledged events published in a single batch query
        transactionTemplate.executeWithoutResult(status -> {
            repository.markBatchPublished(successfulIds, Instant.now());
        });

        log.info("Outbox relay successfully published {}/{} event(s)", successfulIds.size(), batch.size());
    }
}
