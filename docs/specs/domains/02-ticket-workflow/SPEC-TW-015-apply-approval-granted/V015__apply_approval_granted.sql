-- Reference migration for SPEC-TW-015.
-- Real service migration should be named V021__apply_approval_granted.sql.

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN IF NOT EXISTS approved_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS authorization_reference VARCHAR(128),
    ADD COLUMN IF NOT EXISTS granted_event_id UUID;
