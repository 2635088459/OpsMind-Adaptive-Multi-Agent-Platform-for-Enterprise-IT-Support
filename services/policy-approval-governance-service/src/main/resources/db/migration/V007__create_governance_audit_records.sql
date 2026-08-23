CREATE TABLE governance.governance_audit_records (
    audit_record_id VARCHAR(64) PRIMARY KEY,
    action VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    source_domain VARCHAR(64),
    source_request_id VARCHAR(128),
    policy_id VARCHAR(64),
    policy_version VARCHAR(32),
    reason TEXT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128),
    integrity_hash VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_governance_audit_records_action CHECK (action IN (
        'POLICY_DRAFTED', 'POLICY_REVIEWED', 'POLICY_PUBLISHED', 'POLICY_DEPRECATED',
        'DECISION_EVALUATED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_DENIED',
        'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED', 'OVERRIDE_APPLIED'
    ))
);

-- Audit lookups are always by correlation id (GovernanceAuditService.findByCorrelationId).
CREATE INDEX ix_governance_audit_records_correlation ON governance.governance_audit_records (correlation_id);
