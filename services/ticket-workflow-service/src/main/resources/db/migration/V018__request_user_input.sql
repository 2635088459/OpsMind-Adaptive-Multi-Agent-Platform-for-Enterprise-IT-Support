-- SPEC-TW-012 (Request User Input): the open user-input-request table for
-- Phase 04's IN_PROGRESS -> WAITING_FOR_USER slice. Renumbered from the
-- spec folder's reference "V012" to the next real slot in this service's
-- Flyway sequence (V001-V017 already exist).

CREATE TABLE IF NOT EXISTS ticket.ticket_user_input_requests (
    request_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    prompt TEXT NOT NULL,
    requested_fields JSONB,
    requested_by_type VARCHAR(32) NOT NULL,
    requested_by_id VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    resume_status VARCHAR(32) NOT NULL,
    answered_message_id UUID,
    answered_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    correlation_id VARCHAR(128),
    CONSTRAINT fk_user_input_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT ck_user_input_status CHECK (request_status IN ('OPEN', 'ANSWERED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_user_input_prompt CHECK (char_length(btrim(prompt)) BETWEEN 10 AND 2000),
    CONSTRAINT ck_user_input_resume_status CHECK (resume_status IN ('IN_PROGRESS')),
    CONSTRAINT ck_user_input_answered CHECK (
        request_status <> 'ANSWERED' OR (answered_message_id IS NOT NULL AND answered_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_one_open_user_input_request
    ON ticket.ticket_user_input_requests (ticket_id)
    WHERE request_status = 'OPEN';

CREATE INDEX IF NOT EXISTS ix_user_input_ticket
    ON ticket.ticket_user_input_requests (ticket_id, requested_at DESC);
