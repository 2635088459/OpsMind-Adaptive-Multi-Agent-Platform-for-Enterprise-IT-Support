-- SPEC-TW-023 (Verification Success): success-evidence columns on
-- V028's ticket.ticket_verification_attempts. The reference migration's
-- own "result_status" column is deliberately not added here — V028's
-- attempt_status already carries exactly that information (it is set to
-- 'SUCCEEDED' by this spec's happy path), and a second column recording
-- the same fact would just be a redundant, driftable copy. completed_event_id
-- is VARCHAR(64), not UUID, matching this codebase's established
-- convention for event-id columns (see V025's own comment) — envelope
-- eventIds are arbitrary strings, not necessarily UUIDs. conflict_event_id
-- is new (beyond the reference draft): SPEC-TW-023's own acceptance
-- criteria ("Conflicting failure result enters reconciliation") needs a
-- queryable trace of which event triggered the conflict flag, mirroring
-- V027's identical addition for SPEC-TW-021.

ALTER TABLE ticket.ticket_verification_attempts
    ADD COLUMN verification_evidence_id VARCHAR(128),
    ADD COLUMN evidence_summary TEXT,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN completed_event_id VARCHAR(64),
    ADD COLUMN conflict_event_id VARCHAR(64);

ALTER TABLE ticket.ticket_verification_attempts
    ADD CONSTRAINT ck_verification_attempt_succeeded_fields CHECK (
        attempt_status <> 'SUCCEEDED' OR (
            verification_evidence_id IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_event_id IS NOT NULL
        )
    );
