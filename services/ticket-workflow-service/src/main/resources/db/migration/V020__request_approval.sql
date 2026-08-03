-- SPEC-TW-014 (Request Approval): the open approval-request table for
-- Phase 05's IN_PROGRESS -> WAITING_FOR_APPROVAL slice. Matches the spec
-- folder's suggested real-service migration name (persistence.md).

CREATE TABLE IF NOT EXISTS ticket.ticket_approval_requests (
    approval_request_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    approval_id VARCHAR(128) NOT NULL,
    workflow_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    risk_context JSONB NOT NULL,
    reason TEXT,
    requested_by_type VARCHAR(32) NOT NULL,
    requested_by_id VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    correlation_id VARCHAR(128),
    CONSTRAINT fk_approval_request_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT ck_approval_request_status CHECK (request_status IN ('OPEN', 'GRANTED', 'REJECTED', 'EXPIRED', 'AUTO_APPROVED', 'STALE')),
    CONSTRAINT ck_approval_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_one_open_approval_request
    ON ticket.ticket_approval_requests (ticket_id)
    WHERE request_status = 'OPEN';

CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_request_approval_id
    ON ticket.ticket_approval_requests (approval_id);

CREATE INDEX IF NOT EXISTS ix_approval_request_ticket
    ON ticket.ticket_approval_requests (ticket_id, requested_at DESC);
