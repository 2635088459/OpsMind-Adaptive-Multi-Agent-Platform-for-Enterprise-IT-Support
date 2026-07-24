CREATE TABLE ticket.outbox_events (
    outbox_id UUID PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version VARCHAR(16) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    trace_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128),
    data_classification VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_publish_error_code VARCHAR(64),
    last_publish_error_at TIMESTAMPTZ,
    locked_by VARCHAR(128),
    locked_at TIMESTAMPTZ,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT fk_outbox_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT ck_outbox_classification CHECK (data_classification IN ('PUBLIC', 'INTERNAL', 'SENSITIVE')),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_headers_object CHECK (jsonb_typeof(headers) = 'object'),
    CONSTRAINT ck_outbox_publish_attempts CHECK (publish_attempts >= 0)
);

CREATE INDEX ix_outbox_unpublished_available
    ON ticket.outbox_events (available_at, created_at, outbox_id)
    WHERE published_at IS NULL;
CREATE INDEX ix_outbox_locked
    ON ticket.outbox_events (locked_at)
    WHERE published_at IS NULL AND locked_at IS NOT NULL;
CREATE INDEX ix_outbox_ticket_created ON ticket.outbox_events (ticket_id, created_at ASC);
