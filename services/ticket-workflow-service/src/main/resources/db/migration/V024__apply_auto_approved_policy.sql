-- SPEC-TW-018 (Apply Auto-Approved Policy): reuses ticket.ticket_approval_requests
-- with request_status = 'AUTO_APPROVED'. authorization_reference already
-- exists (added by V021 for the granted flow) and is reused here; only the
-- policy-decision-specific columns are new.

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN policy_decision_id VARCHAR(128),
    ADD COLUMN policy_id VARCHAR(128),
    ADD COLUMN policy_version VARCHAR(64),
    ADD COLUMN auto_approved_at TIMESTAMPTZ,
    ADD COLUMN auto_approval_event_id VARCHAR(64);

ALTER TABLE ticket.ticket_approval_requests
    ADD CONSTRAINT ck_approval_request_auto_approved CHECK (
        request_status <> 'AUTO_APPROVED' OR (
            policy_id IS NOT NULL
            AND policy_version IS NOT NULL
            AND policy_decision_id IS NOT NULL
            AND auto_approved_at IS NOT NULL
            AND auto_approval_event_id IS NOT NULL
            AND authorization_reference IS NOT NULL
        )
    );
