-- SPEC-PG-020: publishing a new version automatically supersedes the
-- policy's previously PUBLISHED version (POLICY_SUPERSEDED), and the
-- terminal DEPRECATED -> ARCHIVED transition (POLICY_ARCHIVED) both need
-- their own audit action. Check constraints cannot be altered in place, so
-- drop and recreate it.
ALTER TABLE governance.governance_audit_records DROP CONSTRAINT ck_governance_audit_records_action;

ALTER TABLE governance.governance_audit_records ADD CONSTRAINT ck_governance_audit_records_action
    CHECK (action IN (
        'POLICY_DRAFTED', 'POLICY_REVIEWED', 'POLICY_PUBLISHED', 'POLICY_DEPRECATED',
        'POLICY_SUPERSEDED', 'POLICY_ARCHIVED',
        'DECISION_EVALUATED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_DENIED',
        'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED', 'OVERRIDE_APPLIED', 'APPROVAL_DECISION_CONFLICT',
        'APPROVAL_CANCEL_CONFLICT'
    ));
