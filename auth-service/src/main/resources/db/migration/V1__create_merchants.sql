CREATE TABLE merchants (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    api_key_hash  VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);