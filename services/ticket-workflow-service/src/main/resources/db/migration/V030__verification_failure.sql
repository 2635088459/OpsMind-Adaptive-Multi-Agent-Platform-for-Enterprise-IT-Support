-- SPEC-TW-024 (Verification Failure): failure-classification columns on
-- V028/V029's ticket.ticket_verification_attempts. failed_event_id is
-- VARCHAR(64), not UUID, matching this codebase's established convention
-- for event-id columns (see V025's own comment) — envelope eventIds are
-- arbitrary strings, not necessarily UUIDs.

ALTER TABLE ticket.ticket_verification_attempts
    ADD COLUMN failure_code VARCHAR(128),
    ADD COLUMN failure_class VARCHAR(32),
    ADD COLUMN failed_at TIMESTAMPTZ,
    ADD COLUMN failed_event_id VARCHAR(64),
    ADD COLUMN unsafe_result BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ticket.ticket_verification_attempts
    ADD CONSTRAINT ck_verification_attempt_failure_class CHECK (
        failure_class IS NULL OR failure_class IN ('RETRYABLE', 'PIPELINE_FAILED')
    );

ALTER TABLE ticket.ticket_verification_attempts
    ADD CONSTRAINT ck_verification_attempt_failed_fields CHECK (
        attempt_status <> 'FAILED' OR (
            failure_code IS NOT NULL
            AND failure_class IS NOT NULL
            AND failed_at IS NOT NULL
            AND failed_event_id IS NOT NULL
        )
    );
