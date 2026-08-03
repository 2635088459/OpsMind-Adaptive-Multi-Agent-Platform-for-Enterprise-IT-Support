-- Reference migration for SPEC-TW-014.
-- Real service migration should be named V020__request_approval.sql.

CREATE TABLE IF NOT EXISTS ticket.ticket_approval_requests (
    approval_request_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES ticket.tickets (ticket_id),
    approval_id VARCHAR(128) NOT NULL,
    workflow_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    risk_context JSONB NOT NULL,
    requested_by_type VARCHAR(32) NOT NULL,
    requested_by_id VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT ck_approval_request_status CHECK (request_status IN ('OPEN', 'GRANTED', 'REJECTED', 'EXPIRED', 'AUTO_APPROVED', 'STALE')),
    CONSTRAINT ck_approval_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_one_open_approval_request
    ON ticket.ticket_approval_requests (ticket_id)
    WHERE request_status = 'OPEN';
