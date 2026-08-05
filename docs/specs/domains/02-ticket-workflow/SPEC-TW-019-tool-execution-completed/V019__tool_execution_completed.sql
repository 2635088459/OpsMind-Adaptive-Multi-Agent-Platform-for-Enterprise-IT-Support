-- Reference migration for SPEC-TW-019.
-- Real service migration should be named V025__tool_execution_completed.sql.

CREATE TABLE IF NOT EXISTS ticket.ticket_tool_execution_results (
    tool_execution_id VARCHAR(128) PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES ticket.tickets (ticket_id),
    workflow_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    authorization_reference VARCHAR(128) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    tool_result_id VARCHAR(128),
    completed_at TIMESTAMPTZ,
    result_summary TEXT,
    event_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_tool_execution_result_status CHECK (
        result_status IN ('COMPLETED', 'FAILED', 'UNKNOWN')
    )
);

CREATE INDEX IF NOT EXISTS ix_tool_execution_ticket
    ON ticket.ticket_tool_execution_results (ticket_id, recorded_at DESC);
