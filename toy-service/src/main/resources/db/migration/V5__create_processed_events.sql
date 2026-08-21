-- Idempotency ledger for Kafka consumers (per CLAUDE.md: "check eventId before
-- processing any Kafka event; if already processed, log and skip").
CREATE TABLE processed_events (
    event_id      VARCHAR(36)   PRIMARY KEY,
    event_type    VARCHAR(100)  NOT NULL,
    processed_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);
