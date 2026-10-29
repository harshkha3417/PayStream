# Week 5: Webhooks and Refunds Microservices

## Overview

Week 5 introduces two dedicated, event-driven microservices into the PayStream payment platform:
1. **`refund-service`**: Handles full and partial refunds on settled payments, validates business rules, maintains an event-driven localized read model, and broadcasts refund outcomes via Kafka.
2. **`webhook-service`**: Listens to core payment and refund domain events, signs HTTP payloads with HMAC-SHA256, dispatches asynchronous webhooks to merchant endpoints, manages exponential backoff retries, logs delivery audit records, and routes dead letters to a dedicated DLQ (`webhooks.dlq`).

Both microservices follow PayStream's fundamental architectural principles:
- **Database-per-service** (PostgreSQL databases `refunds` and `webhooks`).
- **No direct cross-service database queries** (Decision #4 & #10).
- **Independent schema evolution** managed via Flyway.
- **Contract autonomy** through localized event record DTOs (Decision #12).
- **Uniform API Gateway routing** with JWT token validation, merchant identity injection (`X-Merchant-Id`), and Redis token-bucket rate limiting.

---

## Architectural Workflow

```
[ Client / Merchant ]
    │
    │ 1. POST /api/v1/payments
    ▼
[ API Gateway ] (Port 8080)
    │
    ▼
[ payment-service ] ───(outbox)───► [ Kafka: payment.created ]
                                             │
                       ┌─────────────────────┴────────────────────┐
                       ▼                                          ▼
             [ processor-service ]                       [ webhook-service ]
                       │                                          │ Signs HMAC-SHA256
                       ▼                                          ▼
             [ Kafka: payment.processed ]                [ Merchant Webhook URL ]
                       │                                 (POST https://merchant/webhook)
                       ▼
             [ payment-service ]
             (Status: SUCCEEDED)
                       │
                       ▼
             [ Kafka: payment.status-changed ]
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
  [ refund-service ]          [ webhook-service ]
  (Replicates payment         (Dispatches webhook:
   into read model)            payment.status-changed)
         │
         │ Merchant calls POST /api/v1/refunds
         ▼
  [ refund-service ]
  (Validates balance & state)
         │
         ▼
  [ Kafka: refund.completed ]
         │
         ├───────────────────────────────────────────┐
         ▼                                           ▼
  [ payment-service ]                         [ webhook-service ]
  (Atomically transitions                     (Dispatches webhook:
   payment state to                            refund.completed)
   PARTIALLY_REFUNDED or REFUNDED)
```

---

## Core Components & Microservices

### 1. `refund-service` (Port 8084, DB: `refunds`)

#### Localized Read Model (`RefundablePayment`)
To strictly adhere to microservice boundaries and avoid cross-service database coupling, `refund-service` maintains its own localized read model table `refundable_payments`.
- It consumes `payment.created` and `payment.status-changed` events from Kafka.
- Replicates essential fields required for refund validation: `payment_id`, `merchant_id`, `amount`, `currency`, `status`, and `refunded_amount`.
- Deduplicates incoming Kafka messages using a consumer deduplication table `processed_events`.

#### Refund Validation Rules
When a merchant submits `POST /api/v1/refunds`, the service enforces strict validations:
1. **Ownership**: The payment must exist in the local read model and belong to the authenticated merchant (`X-Merchant-Id`).
2. **State Validity**: Only payments with status `SUCCEEDED` or `PARTIALLY_REFUNDED` can be refunded. Attempting to refund a `CREATED`, `PROCESSING`, `FAILED`, or `REFUNDED` payment yields an RFC 7807 `422 Unprocessable Entity` response.
3. **Amount Limits**:
   - `amount > 0`
   - `amount <= (payment.amount - payment.refundedAmount)`
   Attempting to refund more than the remaining balance yields an RFC 7807 `400 Bad Request` (`Invalid Refund Amount`).
4. **Currency Matching**: Refund currency must match payment currency.

#### State Transition & Event Emission
- Upon successful validation, a `Refund` record is persisted with status `COMPLETED`.
- The local read model's `refunded_amount` is incremented.
- A `refund.completed` event is published to Kafka topic `refund.completed` containing:
  ```json
  {
    "eventId": "UUID",
    "type": "refund.completed",
    "occurredAt": "2026-10-29T02:02:37Z",
    "paymentId": "UUID",
    "merchantId": "UUID",
    "data": {
      "refundId": "UUID",
      "amount": 4000,
      "currency": "INR",
      "remainingAmount": 6000,
      "reason": "Customer return"
    }
  }
  ```

---

### 2. `payment-service` Refund Integration

- **`RefundCompletedConsumer`**: Listens to topic `refund.completed` with consumer group `payment-service`.
- **Atomic State Transition**:
  - If `remainingAmount == 0`: Transitions payment status to `REFUNDED`.
  - If `remainingAmount > 0`: Transitions payment status to `PARTIALLY_REFUNDED`.
  - Appends an audit record to `payment_status_history`.
- **Event Enrichment**: Updated `PaymentService.java` to include `amount`, `currency`, and `method` in `payment.status-changed` events so downstream consumers have complete context without back-channel queries.

---

### 3. `webhook-service` (Port 8085, DB: `webhooks`)

The webhook service handles reliable, cryptographically secure event notifications to merchants.

#### Cryptographic Payload Signing (HMAC-SHA256)
Every outgoing webhook request is signed using standard HMAC-SHA256:
- When a merchant creates an endpoint, the service generates a high-entropy secret (`whsec_` + 64 hex characters from `SecureRandom`).
- Signatures are computed over the raw UTF-8 JSON payload: `HMAC-SHA256(payload, secret)`.
- Dispatched with standard headers:
  - `X-Paystream-Signature`: Hex-encoded HMAC-SHA256 digest.
  - `X-Paystream-Event-Id`: Unique event UUID for merchant deduplication.
  - `X-Paystream-Event-Type`: Domain event type (e.g., `payment.created`, `payment.status-changed`, `refund.completed`).
  - `X-Paystream-Timestamp`: UNIX epoch timestamp in seconds.
  - `User-Agent`: `PayStream-Webhook/1.0`.

#### Event Dispatch & Audit Trail
- Listens to Kafka topics: `payment.created`, `payment.status-changed`, and `refund.completed`.
- Resolves all active `WebhookEndpoint` entities matching the `merchantId` and subscribed to the event type.
- Records a `WebhookDelivery` row in PostgreSQL with initial status `PENDING`.
- Dispatches HTTP POST requests with a 5-second connection/request timeout via Java 21's asynchronous `HttpClient`.
- On HTTP $2xx$, marks delivery as `SUCCESS` and records response latency.
- On HTTP non-$2xx$ or network timeouts, marks status as `FAILED`, increments `attemptCount`, and computes `nextRetryAt`.

#### Retry Strategy & Dead Letter Queue (DLQ)
- **Exponential Backoff**:
  - Attempt 1: 5 seconds
  - Attempt 2: 30 seconds
  - Attempt 3: 2 minutes (120 seconds)
  - Attempt 4: 5 minutes (300 seconds)
  - Attempt 5: 10 minutes (600 seconds)
- **Retry Scheduler**: `@Scheduled(fixedDelay = 5000)` poller queries `SELECT ... WHERE status = 'FAILED' AND attempt_count < 5 AND next_retry_at <= NOW()`.
- **Dead Letter Queue (`webhooks.dlq`)**: If a webhook fails after 5 delivery attempts, the delivery is marked permanently failed, and the full event payload along with failure diagnostics is published to Kafka topic `webhooks.dlq` for alerting and manual intervention.

---

## API Endpoints Reference

All endpoints require JWT Bearer Authentication and are accessible via the API Gateway at `http://localhost:8080`.

### Refunds API (`/api/v1/refunds`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/refunds` | Issue a full or partial refund for a payment |
| `GET` | `/api/v1/refunds/{id}` | Retrieve a refund by its ID |
| `GET` | `/api/v1/refunds/payment/{paymentId}` | Retrieve all refunds for a specific payment |

#### Refund Request Body
```json
{
  "paymentId": "d46a15f5-e1ba-4ec8-8320-54b5c40be2de",
  "amount": 4000,
  "currency": "INR",
  "reason": "Customer requested partial return"
}
```

#### Refund Response (`201 Created`)
```json
{
  "id": "27fb78b4-45ee-4b80-81e7-edcec51750d8",
  "paymentId": "d46a15f5-e1ba-4ec8-8320-54b5c40be2de",
  "merchantId": "46d3e379-e83b-4fb6-a420-30687e2d1a2d",
  "amount": 4000,
  "currency": "INR",
  "status": "COMPLETED",
  "reason": "Customer requested partial return",
  "createdAt": "2026-10-29T02:02:37Z",
  "updatedAt": "2026-10-29T02:02:37Z"
}
```

---

### Webhook Endpoints API (`/api/v1/webhooks`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/webhooks/endpoints` | Register a merchant webhook URL |
| `GET` | `/api/v1/webhooks/endpoints` | List registered endpoints for the merchant |
| `DELETE` | `/api/v1/webhooks/endpoints/{id}` | Deactivate or delete a webhook endpoint |
| `POST` | `/api/v1/webhooks/endpoints/{id}/test` | Send a test ping webhook with HMAC signature |
| `GET` | `/api/v1/webhooks/deliveries` | Query paginated webhook delivery audit logs |

#### Register Webhook Endpoint Request
```json
{
  "url": "https://merchant.example.com/webhooks",
  "events": ["payment.created", "payment.status-changed", "refund.completed"]
}
```

#### Register Webhook Endpoint Response (`201 Created`)
```json
{
  "id": "6b248906-fa48-4d34-be77-c8bf3e2a4f48",
  "merchantId": "46d3e379-e83b-4fb6-a420-30687e2d1a2d",
  "url": "https://merchant.example.com/webhooks",
  "secret": "whsec_0e0c9b091aead945f8d5cfacb29e411314a87a806e93467668b2522026601e20",
  "subscribedEvents": ["payment.created", "payment.status-changed", "refund.completed"],
  "active": true,
  "createdAt": "2026-10-29T02:02:29Z",
  "updatedAt": "2026-10-29T02:02:29Z"
}
```

---

## Local Engineering & Runtime Considerations

### 1. Spring Data JPA with UUID Keys
When entities use `@GeneratedValue(strategy = GenerationType.UUID)`, assigning `this.id = UUID.randomUUID()` manually in the entity constructor causes Spring Data JPA's `SimpleJpaRepository.save()` to treat the instance as detached (`entityInformation.isNew()` returns false). Hibernate executes an SQL `UPDATE` instead of `INSERT`, affecting 0 rows and raising `StaleObjectStateException` ("Row was already updated or deleted by another transaction"). Leaving the `id` field null allows JPA to treat the entity as new and execute `em.persist()`.

### 2. Spring Cloud Gateway DNS Resolution
Spring Cloud Gateway's Netty HTTP client caches Docker DNS host resolution. When a backend container (`refund-service` or `webhook-service`) is rebuilt, it receives a new IP address on the Docker bridge network. If the Gateway container is not restarted, requests to that microservice fail with `Connection refused`. Restarting the Gateway refreshes the DNS resolution pool.

### 3. Dynamic Consumer Group IDs
To avoid processing backlogged messages from earlier stress testing runs (such as the 50k+ messages generated during Week 4 benchmarks), `@KafkaListener` consumer group IDs use dynamic configuration properties (`${spring.kafka.consumer.group-id}`) with `auto-offset-reset: latest`.

### 4. Memory Footprint on 8 GB RAM (MacBook M2)
With 10 running containers (Postgres, Postgres Auth, Redis, Kafka KRaft, Gateway, Auth Service, Payment Service, Processor Service, Refund Service, Webhook Service), connection pool sizing is critical:
- `DB_POOL_SIZE` is bounded to 10 connections per service via HikariCP.
- Memory limits and JVM options ensure total container footprint stays comfortably within ~4 GB, leaving ample headroom for macOS system services.

---

## Verification & Automated Test Suite

The automated test script `scripts/verify-week5.sh` exercises the full lifecycle:
1. Validates Gateway Actuator health (`{"status":"UP"}`).
2. Registers a new merchant and retrieves a JWT access token.
3. Registers a merchant webhook endpoint listening to all event types.
4. Sends a test webhook ping to `https://httpbin.org/post` and verifies HMAC-SHA256 signature headers.
5. Creates a payment of 10,000 paise (100.00 INR), waiting for `processor-service` to transition it to `SUCCEEDED`.
6. Issues a partial refund of 4,000 paise; verifies Kafka event emission and `payment-service` transition to `PARTIALLY_REFUNDED`.
7. Tests balance validation: attempts to refund 7,000 paise (remaining balance is only 6,000) and asserts HTTP 400 `Invalid Refund Amount` RFC 7807 error.
8. Issues a full refund of remaining 6,000 paise; verifies `payment-service` transition to `REFUNDED`.
9. Asserts boundary validation: attempting any refund on a `REFUNDED` payment is rejected with HTTP 422.
10. Retrieves payment refund history verifying both refund records.
11. Inspects `webhook_deliveries` audit logs confirming dispatches and HTTP 200 delivery receipts.

Run the verification suite:
```bash
./scripts/verify-week5.sh
```
All assertions pass with exit code `0`.
