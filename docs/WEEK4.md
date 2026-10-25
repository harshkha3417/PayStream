# Week 4: Asynchronous Processing with Kafka, Transactional Outbox, and Processor Service

## Overview

Week 4 marks the transition from synchronous payment state manipulation to a truly asynchronous, distributed payment gateway architecture.

Prior to Week 4, payment status transitions were driven via a manual `PATCH /internal/payments/{id}/status` endpoint. In Week 4:
1. **Payment Creation is Non-Blocking**: `payment-service` writes a payment row (`CREATED`) and an outbox event (`payment.created`) in a single database transaction, returning `201 Created` immediately.
2. **Transactional Outbox Pattern**: An asynchronous `OutboxRelay` poller claims unpublished outbox rows using `SELECT ... FOR UPDATE SKIP LOCKED` and publishes them to Kafka with broker acknowledgment.
3. **Decoupled Processing**: A new standalone `processor-service` consumes `payment.created`, simulates bank authorization (configurable latency & failure rates), and publishes `payment.processed` outcomes (`SUCCEEDED` or `FAILED`).
4. **Asynchronous State Transition**: `payment-service` consumes `payment.processed`, transitioning the payment atomically through `PROCESSING` to its final outcome.
5. **Idempotency by Design**: At-least-once message delivery is paired with state-machine guards, rejecting duplicate status transitions as benign no-ops.
6. **Internal Endpoint Decommissioned**: The Week 1 `PATCH /internal/payments/{id}/status` testing endpoint has been deleted.

---

## Architectural Workflow

```
[ Client ]
    │
    │ 1. POST /api/v1/payments (JWT + optional Idempotency-Key)
    ▼
[ API Gateway ] (Port 8080)
    │ 2. Validates JWT, applies Redis token-bucket rate limit, strips & injects X-Merchant-Id
    ▼
[ payment-service ]
    │ 3. Atomic DB Transaction (PostgreSQL):
    │    ├── Insert into payments (status = CREATED)
    │    ├── Insert into payment_status_history (null -> CREATED)
    │    └── Insert into outbox_events (type = 'payment.created')
    │
    │ 4. HTTP 201 Created returned to Client
    │
[ OutboxRelay (payment-service) ]
    │ 5. Poller: SELECT ... FOR UPDATE SKIP LOCKED
    │    Publishes synchronously to Kafka topic 'payment.created' (key = paymentId)
    │    Marks outbox_events.published_at = NOW()
    ▼
[ Apache Kafka (KRaft) ] (Topic: payment.created)
    │
    ▼ 6. Consumes 'payment.created'
[ processor-service ]
    │ 7. Simulates network latency (50ms - 500ms) & bank outcome (~10% failure roll)
    │ 8. Publishes to Kafka topic 'payment.processed' (key = paymentId, outcome = SUCCEEDED | FAILED)
    ▼
[ Apache Kafka (KRaft) ] (Topic: payment.processed)
    │
    ▼ 9. Consumes 'payment.processed'
[ payment-service (PaymentProcessedConsumer) ]
    │ 10. Atomic DB Transaction:
    │     ├── Transition: CREATED -> PROCESSING
    │     ├── Transition: PROCESSING -> SUCCEEDED | FAILED
    │     └── Inserts two status history records
    │
    │ * Duplicate/Redelivered message handling:
    │   InvalidStatusTransitionException is caught and logged at INFO as a benign no-op.
```

---

## Core Components Implemented

### 1. `payment-service` Transactional Outbox
- **Migration (`V3__create_outbox_events.sql`)**:
  - `id UUID PRIMARY KEY`, `aggregate_id UUID NOT NULL` (the `payment_id`), `event_type VARCHAR(64) NOT NULL` (also the topic name), `payload TEXT NOT NULL`, `created_at TIMESTAMPTZ`, `published_at TIMESTAMPTZ`.
  - Partial index `idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;` for fast index scans during polling.
- **Repository (`OutboxEventRepository`)**:
  - `claimUnpublishedBatch(int limit)` using PostgreSQL native query:
    ```sql
    SELECT * FROM outbox_events
    WHERE published_at IS NULL
    ORDER BY created_at ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    ```
    `SKIP LOCKED` allows multiple concurrent instances of `payment-service` to poll without locking each other or causing deadlocks.
- **Relay Poller (`OutboxRelay`)**:
  - Scheduled via `@Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")`.
  - Synchronously publishes to Kafka: `kafkaTemplate.send(topic, key, payload).get(5, TimeUnit.SECONDS)`.
  - Marks published upon broker ack. Throws and rolls back transaction on publish failure, ensuring at-least-once delivery.

### 2. `payment-service` Consumer & State Machine
- **`applyProcessedOutcome(UUID paymentId, boolean succeeded)`**:
  - Transitions through `PROCESSING` first, then to terminal `SUCCEEDED` or `FAILED`.
  - Runs in a single transaction with Spring's default `REQUIRED` propagation.
- **`PaymentProcessedConsumer`**:
  - Listens on topic `payment.processed` with groupId `payment-service`.
  - Idempotency guard: catches `InvalidStatusTransitionException` and skips redeliveries gracefully without erroring or crashing.

### 3. `processor-service`
- **Standalone Maven Module**: Java 21, Spring Boot 4.1.1, Spring Kafka, Spring Boot Actuator, Spring Boot Web.
- **Independent Contracts**: `PaymentEventEnvelope` record is duplicated locally (Decision #12) to prevent shared-library coupling.
- **`PaymentSimulator`**:
  - Configurable: `processor.min-latency-ms` (50), `processor.max-latency-ms` (500), `processor.failure-rate` (0.1).
  - Uses `java.security.SecureRandom`.
- **`PaymentCreatedConsumer`**:
  - Listens on topic `payment.created` with groupId `processor-service`.
  - Simulates bank latency and decision, publishing envelope with `outcome: "SUCCEEDED"` or `"FAILED"` to `payment.processed`.

### 4. Infrastructure (`docker-compose.yml`)
- Added single-node Apache Kafka (`apache/kafka:3.8.0`) in KRaft mode.
- Added `processor-service` with container name `paygate-processor-service`.
- Connected `payment-service` and `processor-service` via internal Docker network.

---

## Verification Guide

### 1. Build and Start All Containers
```bash
docker compose up -d --build
```
Ensure all 7 containers are healthy and running:
- `paygate-postgres` (healthy)
- `paygate-postgres-auth` (healthy)
- `paygate-redis` (healthy)
- `paygate-kafka` (healthy)
- `paygate-auth-service` (started)
- `paygate-payment-service` (started)
- `paygate-processor-service` (started)
- `paygate-gateway` (started, port 8080 exposed)

### 2. Test End-to-End Async Flow
1. **Register Merchant**:
   ```bash
   MERCHANT=$(curl -s -X POST http://localhost:8080/api/v1/merchants \
     -H "Content-Type: application/json" \
     -d '{"name": "Async Merchant"}')
   API_KEY=$(echo $MERCHANT | jq -r .apiKey)
   ```

2. **Authenticate for JWT**:
   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
     -H "Content-Type: application/json" \
     -d "{\"apiKey\": \"$API_KEY\"}" | jq -r .token)
   ```

3. **Create Payment**:
   ```bash
   PAYMENT=$(curl -s -X POST http://localhost:8080/api/v1/payments \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"amount": 50000, "currency": "INR", "method": "UPI", "description": "Kafka outbox test"}')
   PAYMENT_ID=$(echo $PAYMENT | jq -r .id)
   INITIAL_STATUS=$(echo $PAYMENT | jq -r .status)
   echo "Created payment: $PAYMENT_ID with initial status: $INITIAL_STATUS"
   ```
   Initial status is `CREATED`.

4. **Verify Asynchronous Transition to Terminal State**:
   Wait 1–2 seconds for outbox relay, Kafka round-trip, and simulator sleep:
   ```bash
   sleep 2
   curl -s http://localhost:8080/api/v1/payments/$PAYMENT_ID \
     -H "Authorization: Bearer $TOKEN" | jq .
   ```
   Status will be either `SUCCEEDED` or `FAILED`.

### 3. Verify Outbox Table Draining
Check Postgres `outbox_events` table:
```bash
docker exec -it paygate-postgres psql -U postgres -d payments -c \
  "SELECT event_type, COUNT(*) as total, COUNT(published_at) as published FROM outbox_events GROUP BY event_type;"
```
All rows should have `published == total` (0 pending unpublished).

### 4. Verify Status History Audit Trail
```bash
docker exec -it paygate-postgres psql -U postgres -d payments -c \
  "SELECT payment_id, from_status, to_status, created_at FROM payment_status_history WHERE payment_id = '$PAYMENT_ID' ORDER BY created_at ASC;"
```
Output demonstrates the strict state sequence:
1. `null -> CREATED`
2. `CREATED -> PROCESSING`
3. `PROCESSING -> SUCCEEDED` (or `FAILED`)

### 5. Verify Consumer Idempotency
Publish a duplicate message directly to Kafka:
```bash
docker exec -i paygate-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic payment.processed \
  --property "parse.key=true" \
  --property "key.separator=:" <<EOF
$PAYMENT_ID:{"eventId":"$(uuidgen)","type":"payment.processed","occurredAt":"$(date -u +"%Y-%m-%dT%H:%M:%SZ")","paymentId":"$PAYMENT_ID","merchantId":"test","data":{"outcome":"SUCCEEDED"}}
EOF
```
Check `payment-service` logs:
```bash
docker logs paygate-payment-service --tail 20
```
Notice log:
`INFO ... Ignoring duplicate or invalid state transition for paymentId=...: Illegal status transition: ...`
The duplicate message is safely skipped without throwing or crashing.

### 6. Verify Decommissioned Endpoint
Attempting to invoke the removed Week 1 internal transition endpoint:
```bash
curl -i -X PATCH http://localhost:8080/internal/payments/$PAYMENT_ID/status \
  -H "Content-Type: application/json" \
  -d '{"status":"SUCCEEDED"}'
```
Returns `404 Not Found` (no route in gateway or payment-service).

---

## Architectural Decisions Added in Week 4

- **#23**: Transactional Outbox pattern implemented via polling relay (`SELECT ... FOR UPDATE SKIP LOCKED` over `outbox_events`). Eliminates dual-write anomalies between Postgres payment transactions and Kafka emissions without 2PC/XA overhead; enables horizontal outbox worker scaling.
- **#24**: At-least-once delivery contract for outbox events. Synchronous broker acknowledgment (`kafkaTemplate.send().get(5s)`) and database transaction rollback on send failure guarantee zero message loss; downstream consumers must be idempotent.
- **#25**: Consumer deduplication enforced via domain state-machine guards rather than a separate `processed_events` dedup table. In `payment-service`, transitions (`PROCESSING -> SUCCEEDED/FAILED`) are rejected by `InvalidStatusTransitionException` on duplicate processing; catching this as a benign no-op guarantees idempotency without extra DB table overhead.
- **#26**: `processor-service` is an asynchronous, headless simulator consuming `payment.created` and publishing `payment.processed` with configurable latency and failure rates; event envelope DTO is duplicated per Decision #12 rather than coupling via shared library jar.
- **#27**: Single-node Apache Kafka in KRaft mode deployed in Docker Compose without ZooKeeper. Eliminates ZooKeeper operational complexity and aligns with contemporary production architectures.
