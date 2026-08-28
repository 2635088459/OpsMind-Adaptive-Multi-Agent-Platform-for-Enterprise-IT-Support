-- SPEC-UA-003 (09-concurrency-and-idempotency: "Events deduplicate by
-- (consumer, eventId)"). Schema now; a real consumer is a later spec's job
-- (see application.port.out.ProcessedEventRepository's own javadoc).
CREATE TABLE identity.processed_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(200) NOT NULL,
    consumer_name VARCHAR(200) NOT NULL,
    event_type VARCHAR(100),
    processed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_processed_events_event_consumer UNIQUE (event_id, consumer_name)
);
