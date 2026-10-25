package com.paygate.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transactional poller relay that drains unpublished rows from outbox_events and publishes them to Kafka.
 *
 * <p><strong>Delivery Guarantee: At-Least-Once Delivery by Design</strong></p>
 * <p>
 * Dual-write problems are eliminated by having business methods write payment records and outbox rows
 * inside a single local relational transaction. The outbox relay is the second phase of the Transactional
 * Outbox pattern:
 * <ol>
 *   <li>It claims an unpublished batch of rows using {@code SELECT ... FOR UPDATE SKIP LOCKED}.</li>
 *   <li>It publishes each message to Kafka <em>synchronously</em>, awaiting broker acknowledgment
 *       ({@code kafkaTemplate.send(...).get(5, TimeUnit.SECONDS)}).</li>
 *   <li>Upon successful broker ack, {@link OutboxEvent#markPublished()} marks the row published.</li>
 *   <li>The transaction commits, updating the {@code published_at} timestamps in PostgreSQL.</li>
 * </ol>
 * </p>
 *
 * <p><strong>Failure Modes and Rollback:</strong></p>
 * <p>
 * If Kafka is unreachable, times out, or rejects a publish, this method throws a {@link RuntimeException}.
 * Because this method is {@link Transactional}, any thrown exception immediately rolls back the current
 * database transaction. All rows in the batch remain {@code published_at IS NULL} and will be claimed again
 * on the subsequent scheduled poll.
 * </p>
 * <p>
 * However, if a message was successfully accepted by Kafka but the process crashes before the database
 * transaction commits (or if network partition occurs during commit), the next poll will re-publish that
 * message. Therefore, this pattern delivers <strong>at-least-once</strong> messages.
 * <strong>Every downstream Kafka consumer in this system must be idempotent.</strong>
 * </p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxRelay(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${outbox.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.claimUnpublishedBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Outbox relay claimed {} unpublished event(s)", batch.size());

        for (OutboxEvent event : batch) {
            String topic = event.getEventType();
            String key = event.getAggregateId().toString();
            String payload = event.getPayload();

            try {
                // Synchronous send with 5-second timeout: we do NOT mark the row published
                // unless Kafka has confirmed persistence to the broker leader/ISR.
                kafkaTemplate.send(topic, key, payload).get(5, TimeUnit.SECONDS);
                event.markPublished();
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} (type={}, aggregateId={}) to Kafka: {}",
                        event.getId(), topic, key, e.getMessage(), e);
                // Throwing rolls back the @Transactional boundary so unpublished rows remain pending
                throw new RuntimeException("Outbox relay publish failed for event " + event.getId() + ", rolling back batch", e);
            }
        }

        log.info("Outbox relay successfully published {} event(s)", batch.size());
    }
}
