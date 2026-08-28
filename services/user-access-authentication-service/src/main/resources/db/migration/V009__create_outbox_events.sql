-- SPEC-UA-003 (08-transaction-and-outbox).
CREATE TABLE identity.outbox_events (
    outbox_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version VARCHAR(10) NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_payload_object CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_events_status_available ON identity.outbox_events (status, available_at);
