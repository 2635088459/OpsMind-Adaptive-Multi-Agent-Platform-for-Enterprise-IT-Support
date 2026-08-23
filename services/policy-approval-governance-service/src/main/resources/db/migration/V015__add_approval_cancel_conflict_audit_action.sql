-- SPEC-PG-012: mirrors V010's APPROVAL_DECISION_CONFLICT addition for
-- grant/deny — a conflicting cancel retry (a different
-- commandIdempotencyKey against a request that is already CANCELLED) must
-- also leave an audit trace. Check constraints cannot be altered in place,
-- so drop and recreate it.
ALTER TABLE governance.governance_audit_records DROP CONSTRAINT ck_governance_audit_records_action;

ALTER TABLE governance.governance_audit_records ADD CONSTRAINT ck_governance_audit_records_action
    CHECK (action IN (
        'POLICY_DRAFTED', 'POLICY_REVIEWED', 'POLICY_PUBLISHED', 'POLICY_DEPRECATED',
        'DECISION_EVALUATED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_DENIED',
        'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED', 'OVERRIDE_APPLIED', 'APPROVAL_DECISION_CONFLICT',
        'APPROVAL_CANCEL_CONFLICT'
    ));
