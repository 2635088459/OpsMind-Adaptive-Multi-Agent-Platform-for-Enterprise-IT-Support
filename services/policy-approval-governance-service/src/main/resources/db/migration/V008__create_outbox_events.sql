-- Schema only (SPEC-PG-002): OutboxDispatchService still publishes
-- synchronously through LoggingGovernanceEventPublisher, not this table.
-- The durable append-in-transaction write path and the polling dispatch
-- loop that reads/publishes/marks rows here are SPEC-PG-003's job
-- (08-transaction-and-outbox) — mirrors tool-integration-gateway's own
-- TG-002/TG-003 split.
CREATE TABLE governance.outbox_events (
    outbox_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version VARCHAR(10) NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    headers_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(200) NOT NULL,
    causation_id VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_payload_object CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_outbox_events_headers_object CHECK (jsonb_typeof(headers_json) = 'object'),
    CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_events_status_available ON governance.outbox_events (status, available_at);
