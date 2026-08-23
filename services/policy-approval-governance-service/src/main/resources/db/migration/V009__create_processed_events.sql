-- Schema only (SPEC-PG-002): consumer-side dedup writes and enforcement are
-- SPEC-PG-003's job (09-concurrency-and-idempotency) — this table exists so
-- that spec adds behavior, not schema.
CREATE TABLE governance.processed_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(200) NOT NULL,
    consumer_name VARCHAR(200) NOT NULL,
    event_type VARCHAR(100),
    processed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_processed_events_event_consumer UNIQUE (event_id, consumer_name)
);
