-- Reference migration for SPEC-TW-024.
-- Real service migration should be named V030__verification_failure.sql.

ALTER TABLE ticket.ticket_verification_attempts
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(64),
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS failed_event_id UUID,
    ADD COLUMN IF NOT EXISTS unsafe_result BOOLEAN NOT NULL DEFAULT FALSE;
