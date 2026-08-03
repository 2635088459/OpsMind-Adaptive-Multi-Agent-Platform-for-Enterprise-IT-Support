-- Reference migration for SPEC-TW-017.
-- Real service migration should be named V023__apply_approval_expired.sql.

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expired_event_id UUID,
    ADD COLUMN IF NOT EXISTS expiration_reason VARCHAR(128);
