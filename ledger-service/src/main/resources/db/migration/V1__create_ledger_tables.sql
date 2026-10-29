CREATE TABLE IF NOT EXISTS ledger_entries (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    merchant_id VARCHAR(100) NOT NULL,
    account VARCHAR(50) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reference_id VARCHAR(100),
    entry_type VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_payment_id ON ledger_entries (payment_id);
CREATE INDEX idx_ledger_merchant_account ON ledger_entries (merchant_id, account);
CREATE INDEX idx_ledger_created_at ON ledger_entries (created_at DESC);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
