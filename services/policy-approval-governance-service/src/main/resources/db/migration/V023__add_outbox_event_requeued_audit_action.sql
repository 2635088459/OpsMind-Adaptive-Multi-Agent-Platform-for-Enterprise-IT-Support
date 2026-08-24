-- SPEC-PG-024: an admin requeuing a dead-lettered outbox event (poison
-- repair) needs its own audit action. Check constraints cannot be altered
-- in place, so drop and recreate it.
ALTER TABLE governance.governance_audit_records DROP CONSTRAINT ck_governance_audit_records_action;

ALTER TABLE governance.governance_audit_records ADD CONSTRAINT ck_governance_audit_records_action
    CHECK (action IN (
        'POLICY_DRAFTED', 'POLICY_REVIEWED', 'POLICY_PUBLISHED', 'POLICY_DEPRECATED',
        'POLICY_SUPERSEDED', 'POLICY_ARCHIVED',
        'DECISION_EVALUATED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_DENIED',
        'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED', 'OVERRIDE_APPLIED', 'OVERRIDE_REVOKED',
        'APPROVAL_DECISION_CONFLICT', 'APPROVAL_CANCEL_CONFLICT', 'OVERRIDE_USE_CONFLICT',
        'OVERRIDE_REVOKE_CONFLICT', 'OUTBOX_EVENT_REQUEUED'
    ));
