CREATE TABLE ticket.audit_records (
    audit_id UUID PRIMARY KEY,
    audit_type VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    client_id VARCHAR(128),
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    display_id VARCHAR(32),
    ticket_status_before VARCHAR(32),
    ticket_status_after VARCHAR(32),
    trace_id VARCHAR(64),
    command_id VARCHAR(128),
    outcome VARCHAR(16) NOT NULL,
    data_classification VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_audit_records_decision CHECK (decision IN ('ALLOWED', 'DENIED')),
    CONSTRAINT ck_audit_records_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_records_data_classification CHECK (data_classification IN ('PUBLIC', 'INTERNAL', 'SENSITIVE'))
);

CREATE INDEX ix_audit_records_resource ON ticket.audit_records (resource_type, resource_id, occurred_at ASC);
CREATE INDEX ix_audit_records_actor ON ticket.audit_records (actor_id, occurred_at DESC);
