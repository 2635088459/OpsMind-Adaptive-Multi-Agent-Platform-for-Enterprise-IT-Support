-- Reference migration for SPEC-TW-023.
-- Real service migration should be named V029__verification_success.sql.

ALTER TABLE ticket.ticket_verification_attempts
    ADD COLUMN IF NOT EXISTS verification_evidence_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS result_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS evidence_summary TEXT,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_event_id UUID;
