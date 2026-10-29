# Week 6: Double-Entry Ledger, Transaction History, and Immutable Audit Trail

## Overview

Week 6 adds financial accounting integrity and regulatory compliance to the PayStream payment processing platform with two dedicated microservices:
1. **`ledger-service`** (Port `8086`, Database `ledger`): A strict double-entry accounting engine enforcing the fundamental balance theorem $\sum \text{Debits} = \sum \text{Credits}$ across all financial operations (settlements, fee deductions, full/partial refunds). It maintains real-time merchant settled balances and paginated transaction journals.
2. **`audit-service`** (Port `8087`, Database `audit`): An append-only, tamper-proof audit trail capturing the complete lifecycle of all domain events across the platform (`payment.created`, `payment.status-changed`, `refund.requested`, `refund.completed`). Enforces immutability at the database engine level via PostgreSQL triggers.

Both services adhere to PayStream's core architectural tenets:
- **Database-per-service**: Isolated PostgreSQL databases (`ledger` and `audit`) hosted in `paygate-postgres`.
- **Zero cross-database queries**: Complete isolation (Decisions #4 & #10); state synchronization happens solely through Kafka domain events.
- **Contract autonomy**: Localized event record DTOs (`PaymentEventEnvelope`) preventing binary coupling (Decision #12).
- **At-least-once idempotency**: Deduplication via `processed_events` and database unique constraints (Decision #8 & #24).
- **Zero-trust perimeter**: Internal service ports (8086, 8087) are not exposed to the host; all access is routed through `gateway:8080` with JWT verification, merchant identity injection (`X-Merchant-Id`), and Redis rate limiting.

---

## Architectural Workflow

```
[ Merchant / Client ]
         │
         │ 1. POST /api/v1/payments
         ▼
[ API Gateway ] (Port 8080)
         │
         ▼
[ payment-service ] ──(outbox)──► [ Kafka: payment.created ]
                                               │
                                 ┌─────────────┴─────────────┐
                                 ▼                           ▼
                      [ processor-service ]          [ audit-service ]
                                 │                   (Records PAYMENT_CREATED)
                                 ▼
                      [ Kafka: payment.processed ]
                                 │
                                 ▼
                      [ payment-service ]
                      (Status: SUCCEEDED)
                                 │
                                 ▼
                      [ Kafka: payment.status-changed ]
                                 │
                 ┌───────────────┼───────────────┐
                 ▼               ▼               ▼
        [ refund-service ] [ ledger-service ] [ audit-service ]
        (Replicates payment (Balanced Debits  (Records
         into read model)   & Credits:        PAYMENT_SUCCEEDED)
                             - Customer DR
                             - Merchant CR
                             - Fee CR)
                 │
                 │ 2. POST /api/v1/refunds
                 ▼
        [ refund-service ]
                 │
                 ▼
        [ Kafka: refund.completed ]
                 │
                 ├───────────────────────────────┐
                 ▼                               ▼
        [ ledger-service ]              [ audit-service ]
        (Debits Merchant settlement,    (Records REFUND_COMPLETED)
         Credits Customer refund)
```

---

## Double-Entry Bookkeeping Mechanics

### 1. The Fundamental Accounting Equation
Every financial event recorded by `ledger-service` must generate balanced entries such that:
$$\sum \text{Debits} = \sum \text{Credits}$$

### 2. Payment Settlement Model
When a payment of gross amount $A$ succeeds:
- **Platform Fee Cut**: $F = \lfloor A \times 0.02 \rfloor$ (2% processing fee).
- **Net Merchant Settlement**: $M_{\text{net}} = A - F$.
- **Journal Entries**:
  1. `CUSTOMER` (Debit): $A$ (money pulled from customer source).
  2. `MERCHANT_SETTLEMENT` (Credit): $M_{\text{net}}$ (net funds owed to merchant).
  3. `PLATFORM_FEES` (Credit): $F$ (platform fee revenue).

$$\text{Total Debits} = A$$
$$\text{Total Credits} = M_{\text{net}} + F = (A - F) + F = A$$
$$\therefore \text{Debits} = \text{Credits}$$

### 3. Refund Clearing Model
When a full or partial refund of amount $R$ is completed:
- **Journal Entries**:
  1. `MERCHANT_SETTLEMENT` (Debit): $R$ (withdrawn from merchant settled balance).
  2. `CUSTOMER` (Credit): $R$ (credited back to customer account).

$$\text{Total Debits} = R$$
$$\text{Total Credits} = R$$
$$\therefore \text{Debits} = \text{Credits}$$

### 4. Real-Time Settled Balance Computation
A merchant's available settled balance is computed via SQL aggregation across `MERCHANT_SETTLEMENT` journal entries:
$$\text{Settled Balance} = \sum_{\text{account} = \text{MERCHANT\_SETTLEMENT}} (\text{Credits} - \text{Debits})$$

---

## Immutable Audit Trail & Regulatory Compliance

To satisfy financial compliance requirements (SOC 2, PCI DSS audit standards, SOX):
1. **Append-Only Table**: `audit_logs` records `id`, `event_id`, `topic`, `entity_type`, `entity_id`, `merchant_id`, `actor`, `action`, `payload`, `occurred_at`, and `recorded_at`.
2. **Database Engine Trigger**: PostgreSQL trigger `trg_audit_logs_immutable` executes `prevent_audit_log_modification()` on `BEFORE UPDATE OR DELETE`, throwing a database-level fatal exception:
   ```sql
   CREATE TRIGGER trg_audit_logs_immutable
   BEFORE UPDATE OR DELETE ON audit_logs
   FOR EACH ROW
   EXECUTE FUNCTION prevent_audit_log_modification();
   ```
3. **Event Deduplication**: `UNIQUE(event_id)` ensures duplicate deliveries from Kafka do not generate spurious audit logs.

---

## REST API Specifications

### Ledger Endpoints

| Method | Path | Description | Required Headers |
|---|---|---|---|
| `GET` | `/api/v1/ledger/balance` | Returns settled balance, total credits, total debits, total fees, and entry count. | `Authorization: Bearer <jwt>` |
| `GET` | `/api/v1/ledger/entries/payment/{paymentId}` | Returns journal entries for a payment, debit/credit totals, and double-entry balance verification (`balanced: true`). | `Authorization: Bearer <jwt>` |
| `GET` | `/api/v1/ledger/entries` | Returns paginated journal entries for the authenticated merchant. | `Authorization: Bearer <jwt>` |

#### Sample Response: `GET /api/v1/ledger/balance`
```json
{
  "merchantId": "merchant_alpha_123",
  "currency": "INR",
  "settledBalanceMinor": 6800,
  "totalCreditsMinor": 9800,
  "totalDebitsMinor": 3000,
  "totalFeesMinor": 200,
  "entryCount": 5
}
```

#### Sample Response: `GET /api/v1/ledger/entries/payment/{paymentId}`
```json
{
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "merchantId": "merchant_alpha_123",
  "currency": "INR",
  "totalDebitsMinor": 13000,
  "totalCreditsMinor": 13000,
  "balanced": true,
  "entries": [
    {
      "id": "a1b2c3d4-...",
      "paymentId": "550e8400-...",
      "merchantId": "merchant_alpha_123",
      "account": "CUSTOMER",
      "direction": "DEBIT",
      "amountMinor": 10000,
      "currency": "INR",
      "referenceId": null,
      "entryType": "PAYMENT_SETTLEMENT",
      "description": "Customer charge for payment 550e8400-...",
      "createdAt": "2026-10-29T16:55:00Z"
    },
    {
      "id": "b2c3d4e5-...",
      "paymentId": "550e8400-...",
      "merchantId": "merchant_alpha_123",
      "account": "MERCHANT_SETTLEMENT",
      "direction": "CREDIT",
      "amountMinor": 9800,
      "currency": "INR",
      "referenceId": null,
      "entryType": "PAYMENT_SETTLEMENT",
      "description": "Net settlement for payment 550e8400-...",
      "createdAt": "2026-10-29T16:55:00Z"
    },
    {
      "id": "c3d4e5f6-...",
      "paymentId": "550e8400-...",
      "merchantId": "merchant_alpha_123",
      "account": "PLATFORM_FEES",
      "direction": "CREDIT",
      "amountMinor": 200,
      "currency": "INR",
      "referenceId": null,
      "entryType": "PLATFORM_FEE",
      "description": "Platform processing fee (2%) for payment 550e8400-...",
      "createdAt": "2026-10-29T16:55:00Z"
    },
    {
      "id": "d4e5f6a7-...",
      "paymentId": "550e8400-...",
      "merchantId": "merchant_alpha_123",
      "account": "MERCHANT_SETTLEMENT",
      "direction": "DEBIT",
      "amountMinor": 3000,
      "currency": "INR",
      "referenceId": "ref_998877",
      "entryType": "REFUND",
      "description": "Refund debited from merchant settlement for refund ref_998877",
      "createdAt": "2026-10-29T16:56:00Z"
    },
    {
      "id": "e5f6a7b8-...",
      "paymentId": "550e8400-...",
      "merchantId": "merchant_alpha_123",
      "account": "CUSTOMER",
      "direction": "CREDIT",
      "amountMinor": 3000,
      "currency": "INR",
      "referenceId": "ref_998877",
      "entryType": "REFUND",
      "description": "Refund credited to customer for refund ref_998877",
      "createdAt": "2026-10-29T16:56:00Z"
    }
  ]
}
```

---

### Audit Endpoints

| Method | Path | Description | Required Headers |
|---|---|---|---|
| `GET` | `/api/v1/audit/events?entityId={id}` | Returns chronological timeline of all events for an entity (`paymentId`). | `Authorization: Bearer <jwt>` |
| `GET` | `/api/v1/audit/trail` | Returns paginated audit log for the authenticated merchant, filterable by `entityType`. | `Authorization: Bearer <jwt>` |

#### Sample Response: `GET /api/v1/audit/events?entityId={paymentId}`
```json
[
  {
    "id": "f1e2d3c4-...",
    "eventId": "11111111-...",
    "topic": "payment.created",
    "entityType": "PAYMENT",
    "entityId": "550e8400-...",
    "merchantId": "merchant_alpha_123",
    "actor": "PAYMENT_SERVICE",
    "action": "PAYMENT_CREATED",
    "occurredAt": "2026-10-29T16:55:00Z",
    "recordedAt": "2026-10-29T16:55:01Z"
  },
  {
    "id": "f2e3d4c5-...",
    "eventId": "22222222-...",
    "topic": "payment.status-changed",
    "entityType": "PAYMENT",
    "entityId": "550e8400-...",
    "merchantId": "merchant_alpha_123",
    "actor": "PAYMENT_SERVICE",
    "action": "PAYMENT_SUCCEEDED",
    "occurredAt": "2026-10-29T16:55:02Z",
    "recordedAt": "2026-10-29T16:55:03Z"
  },
  {
    "id": "f3e4d5c6-...",
    "eventId": "33333333-...",
    "topic": "refund.completed",
    "entityType": "REFUND",
    "entityId": "550e8400-...",
    "merchantId": "merchant_alpha_123",
    "actor": "REFUND_SERVICE",
    "action": "REFUND_COMPLETED",
    "occurredAt": "2026-10-29T16:56:00Z",
    "recordedAt": "2026-10-29T16:56:01Z"
  }
]
```

---

## Automated Verification Suite

Run the full end-to-end Week 6 verification suite:
```bash
./scripts/verify-week6.sh
```

### Verification Steps Executed:
1. **Gateway Health**: Confirms Gateway is running and routing.
2. **Merchant Onboarding**: Provisions Merchant Alpha and Merchant Beta with API keys and JWT access tokens.
3. **Payment Processing**: Creates a payment ($100.00 / 10,000 paise) and waits for async Kafka transition to `SUCCEEDED`.
4. **Double-Entry Balance Verification**: Validates $\sum \text{Debits} = \sum \text{Credits} = 10,000$ and `balanced: true`.
5. **Settled Balance Computation**: Validates settled balance is 9,800 paise and fee cut is 200 paise.
6. **Partial Refund Processing**: Submits a 3,000 paise refund.
7. **Post-Refund Balance Verification**: Validates settled balance drops to 6,800 paise and total debits equal 3,000 paise.
8. **Post-Refund Journal Integrity**: Validates the updated 5-entry journal maintains total debits ($13,000$) = total credits ($13,000$).
9. **Audit Trail Chronology**: Validates complete event timeline (`PAYMENT_CREATED` $\rightarrow$ `PAYMENT_SUCCEEDED` $\rightarrow$ `REFUND_COMPLETED`).
10. **Multi-Tenant Isolation**: Confirms Merchant Beta receives `404 Not Found` when requesting Merchant Alpha's ledger entries, and an empty list for audit events.
11. **Database Immutability Enforcement**: Executes a direct SQL `UPDATE` against `audit_logs` in PostgreSQL and asserts failure via trigger exception.
