-- SPEC-TW-022 (Start Verification): the verification-attempt table for
-- Phase 07's VERIFYING -> VERIFYING slice. attempt_status stays generic
-- (ACTIVE/SUCCEEDED/FAILED/STALE/CONFLICT) so SPEC-TW-023 (Verification
-- Success) and SPEC-TW-024 (Verification Failure) can transition this same
-- row instead of creating their own. Only one ACTIVE attempt may exist per
-- tool result at a time; attempt_number is monotonic per tool result
-- (enforced in the Application layer, not by a DB sequence, since it must
-- restart cleanly per tool_result_id).

CREATE TABLE ticket.ticket_verification_attempts (
    verification_id VARCHAR(128) PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES ticket.tickets (ticket_id),
    resolution_cycle_id UUID NOT NULL,
    workflow_id VARCHAR(128) NOT NULL,
    tool_result_id VARCHAR(128) NOT NULL,
    attempt_number INTEGER NOT NULL,
    attempt_status VARCHAR(32) NOT NULL,
    verification_type VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    event_id UUID,
    CONSTRAINT ck_verification_attempt_status CHECK (
        attempt_status IN ('ACTIVE', 'SUCCEEDED', 'FAILED', 'STALE', 'CONFLICT')
    ),
    CONSTRAINT ck_verification_attempt_number CHECK (attempt_number >= 1)
);

CREATE UNIQUE INDEX uq_verification_one_active_tool_result
    ON ticket.ticket_verification_attempts (tool_result_id)
    WHERE attempt_status = 'ACTIVE';

CREATE INDEX ix_verification_attempts_ticket
    ON ticket.ticket_verification_attempts (ticket_id, started_at DESC);

CREATE INDEX ix_verification_attempts_tool_result
    ON ticket.ticket_verification_attempts (tool_result_id, attempt_number DESC);
