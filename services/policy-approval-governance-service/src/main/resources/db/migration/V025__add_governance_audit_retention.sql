-- SPEC-PG-031 (11-security §Tamper-Resistant Audit: "Ordinary admins cannot
-- delete audit records; they may only be archived by retention policy").
-- Nullable: null until a retention run archives a record. Deliberately not
-- folded into integrity_hash's own computation (see
-- SimpleAuditIntegrityAdapter's own javadoc) — archiving must not
-- retroactively look like tampering against a hash computed before the
-- record was ever archived.
ALTER TABLE governance.governance_audit_records
    ADD COLUMN archived_at TIMESTAMPTZ;

-- Every full-table walk this spec adds (chain verification, compliance
-- report aggregation, the retention cutoff scan) orders or filters by
-- recorded_at; no index on it existed before this migration.
CREATE INDEX ix_governance_audit_records_recorded_at ON governance.governance_audit_records (recorded_at);

-- Check constraints cannot be altered in place, so drop and recreate it
-- (same approach every prior audit-action-adding migration has used).
ALTER TABLE governance.governance_audit_records DROP CONSTRAINT ck_governance_audit_records_action;

ALTER TABLE governance.governance_audit_records ADD CONSTRAINT ck_governance_audit_records_action
    CHECK (action IN (
        'POLICY_DRAFTED', 'POLICY_REVIEWED', 'POLICY_PUBLISHED', 'POLICY_DEPRECATED',
        'POLICY_SUPERSEDED', 'POLICY_ARCHIVED',
        'DECISION_EVALUATED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_DENIED',
        'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED', 'OVERRIDE_APPLIED', 'OVERRIDE_REVOKED',
        'APPROVAL_DECISION_CONFLICT', 'APPROVAL_CANCEL_CONFLICT', 'OVERRIDE_USE_CONFLICT',
        'OVERRIDE_REVOKE_CONFLICT', 'OUTBOX_EVENT_REQUEUED', 'AUDIT_RECORDS_ARCHIVED'
    ));
