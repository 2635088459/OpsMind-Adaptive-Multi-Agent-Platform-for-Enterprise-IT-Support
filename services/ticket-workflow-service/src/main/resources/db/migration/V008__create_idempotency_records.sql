CREATE TABLE ticket.idempotency_records (
    idempotency_record_id UUID PRIMARY KEY,
    actor_scope VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_idempotency_scope_key UNIQUE (actor_scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_FINAL')),
    CONSTRAINT ck_idempotency_response_body CHECK (response_body IS NULL OR jsonb_typeof(response_body) = 'object')
);

CREATE INDEX ix_idempotency_expires_at ON ticket.idempotency_records (expires_at) WHERE status <> 'IN_PROGRESS';
