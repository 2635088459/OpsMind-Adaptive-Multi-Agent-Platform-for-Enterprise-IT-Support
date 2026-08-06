-- Reference migration for SPEC-TW-025.
-- Real service migration should be named V031__resolve_ticket_with_verification.sql.

ALTER TABLE ticket.tickets
    ADD COLUMN IF NOT EXISTS verification_evidence_id VARCHAR(128);

ALTER TABLE ticket.ticket_resolution_cycles
    ADD COLUMN IF NOT EXISTS verification_evidence_id VARCHAR(128);
