CREATE TABLE payments (
    id             UUID         PRIMARY KEY,
    merchant_id    VARCHAR(64)  NOT NULL,
    amount_minor   BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency       VARCHAR(3)   NOT NULL,
    payment_method VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    description    VARCHAR(255),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

-- Serves "list this merchant's payments, newest first"
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at DESC);

CREATE TABLE payment_status_history (
    id          BIGSERIAL    PRIMARY KEY,
    payment_id  UUID         NOT NULL REFERENCES payments (id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_history_payment ON payment_status_history (payment_id, created_at);