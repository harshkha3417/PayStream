CREATE TABLE idempotency_keys (
    merchant_id       VARCHAR(64)  NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    request_hash      VARCHAR(64)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    response_status   SMALLINT,
    response_location VARCHAR(255),
    response_body     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, idempotency_key)
);

-- Old PROCESSING rows whose first request died mid-flight (crash, timeout)
-- would otherwise block that key forever. Not cleaned up automatically in
-- Week 3 — see WEEK3.md's "stretch goals" for a scheduled sweep.
CREATE INDEX idx_idempotency_stale_processing ON idempotency_keys (status, created_at)
    WHERE status = 'PROCESSING';