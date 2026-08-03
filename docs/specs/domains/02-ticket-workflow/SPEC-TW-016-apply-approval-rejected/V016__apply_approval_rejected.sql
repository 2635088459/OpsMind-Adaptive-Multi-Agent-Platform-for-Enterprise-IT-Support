-- Reference migration for SPEC-TW-016.
-- Real service migration should be named V022__apply_approval_rejected.sql.

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN IF NOT EXISTS rejected_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(256),
    ADD COLUMN IF NOT EXISTS rejected_event_id UUID;
