-- SPEC-TW-019 (Tool Execution Completed): idempotency/result table for the
-- EXECUTING -> VERIFYING slice. tool_execution_id is the business dedup
-- key (unique across every tool-result outcome ever recorded for it), and
-- also doubles as the table's own primary key so a duplicate delivery can
-- never insert a second row for the same execution attempt. result_status
-- is left generic (COMPLETED/FAILED/UNKNOWN) so SPEC-TW-020 (tool.execution.failed)
-- and SPEC-TW-021 (tool.execution.result_unknown) can share this same table
-- instead of creating their own.

CREATE TABLE ticket.ticket_tool_execution_results (
    tool_execution_id VARCHAR(128) PRIMARY KEY,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    authorization_reference VARCHAR(128) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    tool_result_id VARCHAR(128),
    completed_at TIMESTAMPTZ,
    result_summary TEXT,
    event_id VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_tool_execution_result_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT ck_tool_execution_result_status CHECK (
        result_status IN ('COMPLETED', 'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT ck_tool_execution_result_completed_fields CHECK (
        result_status <> 'COMPLETED' OR (tool_result_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_tool_execution_ticket
    ON ticket.ticket_tool_execution_results (ticket_id, recorded_at DESC);
