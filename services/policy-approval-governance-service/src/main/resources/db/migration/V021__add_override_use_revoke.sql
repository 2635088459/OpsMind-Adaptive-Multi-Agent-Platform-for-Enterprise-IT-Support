-- SPEC-PG-022: high-risk override lifecycle. An approved POLICY_OVERRIDE
-- request can now transition further to USED (actually exercised) or
-- REVOKED (withdrawn by governance before use) — 03-state-machine §Override
-- State Machine. Each new terminal command needs its own idempotency-key
-- column, mirroring cancel_command_idempotency_key (V014) — neither use nor
-- revoke creates a new approval_decisions row.
ALTER TABLE governance.approval_requests
    ADD COLUMN used_command_idempotency_key VARCHAR(128),
    ADD COLUMN revoked_command_idempotency_key VARCHAR(128);

ALTER TABLE governance.approval_requests DROP CONSTRAINT ck_approval_requests_status;

ALTER TABLE governance.approval_requests ADD CONSTRAINT ck_approval_requests_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED', 'USED', 'REVOKED'));

-- OVERRIDE_REVOKED, OVERRIDE_USE_CONFLICT, and OVERRIDE_REVOKE_CONFLICT are
-- new audit actions; OVERRIDE_APPLIED already existed (V007) but had no
-- caller until this spec wires ApprovalService#use to it.
ALTER TABLE governance.governance_audit_records DROP CONSTRAINT ck_governance_audit_records_action;

ALTER TABLE governance.governance_audit_records ADD CONSTRAINT ck_governance_audit_records_action
    CHECK (action IN (
        'POLICY_DRAFTED', 'POLICY_REVIEWED', 'POLICY_PUBLISHED', 'POLICY_DEPRECATED',
        'POLICY_SUPERSEDED', 'POLICY_ARCHIVED',
        'DECISION_EVALUATED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_DENIED',
        'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED', 'OVERRIDE_APPLIED', 'OVERRIDE_REVOKED',
        'APPROVAL_DECISION_CONFLICT', 'APPROVAL_CANCEL_CONFLICT', 'OVERRIDE_USE_CONFLICT',
        'OVERRIDE_REVOKE_CONFLICT'
    ));
