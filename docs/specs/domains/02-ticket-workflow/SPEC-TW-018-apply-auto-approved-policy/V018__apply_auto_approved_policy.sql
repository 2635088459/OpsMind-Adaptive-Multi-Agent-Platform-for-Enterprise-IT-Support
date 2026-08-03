-- Reference migration for SPEC-TW-018.
-- Real service migration should be named V024__apply_auto_approved_policy.sql.

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN IF NOT EXISTS policy_decision_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS policy_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS auto_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS auto_approval_event_id UUID;
