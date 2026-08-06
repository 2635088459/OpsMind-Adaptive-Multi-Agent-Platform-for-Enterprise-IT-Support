-- SPEC-TW-025 (Resolve Ticket with Verification): verification-evidence
-- columns on the ticket row and its resolution cycle. verification_id here
-- is VARCHAR(64) to match V003's own ticket_resolution_cycles.verification_id
-- column (already present, unused until now) and V028's
-- ticket_verification_attempts.verification_id PK type;
-- verification_evidence_id is VARCHAR(128) to match V029's
-- ticket_verification_attempts.verification_evidence_id column exactly.
-- Neither ck_tickets_resolved_fields (V016) nor ck_resolution_cycle_resolved
-- (V016, already relaxed to not require verification_id/root_cause_code)
-- need to change: both new columns stay optional at the constraint level,
-- since SPEC-TW-010's plain (non-verified) resolve path never sets them.

ALTER TABLE ticket.tickets
    ADD COLUMN IF NOT EXISTS verification_evidence_id VARCHAR(128);

ALTER TABLE ticket.ticket_resolution_cycles
    ADD COLUMN IF NOT EXISTS verification_evidence_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS ix_tickets_verification_evidence_id
    ON ticket.tickets (verification_evidence_id)
    WHERE verification_evidence_id IS NOT NULL;
