-- Reference migration for SPEC-TW-022.
-- Real service migration should be named V028__start_verification.sql.

CREATE TABLE IF NOT EXISTS ticket.ticket_verification_attempts (
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

CREATE UNIQUE INDEX IF NOT EXISTS uq_verification_one_active_tool_result
    ON ticket.ticket_verification_attempts (tool_result_id)
    WHERE attempt_status = 'ACTIVE';
