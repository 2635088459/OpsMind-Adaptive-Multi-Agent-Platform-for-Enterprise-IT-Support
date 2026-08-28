-- SPEC-UA-003 (07-data-model §identity_audit_records; append-only).
CREATE TABLE identity.identity_audit_records (
    audit_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    action VARCHAR(48) NOT NULL,
    actor_ref VARCHAR(128),
    subject_ref VARCHAR(128),
    resource_ref VARCHAR(128),
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(255),
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    previous_hash VARCHAR(128),
    record_hash VARCHAR(128)
);

CREATE INDEX ix_identity_audit_records_correlation ON identity.identity_audit_records (correlation_id);
CREATE INDEX ix_identity_audit_records_tenant_time ON identity.identity_audit_records (tenant_id, occurred_at);
CREATE INDEX ix_identity_audit_records_action ON identity.identity_audit_records (action);
