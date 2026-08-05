-- Reference migration for SPEC-TW-021.
-- Real service migration should be named V027__tool_result_unknown.sql.

ALTER TABLE ticket.ticket_tool_execution_results
    ADD COLUMN IF NOT EXISTS unknown_reason VARCHAR(256),
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS evidence_references JSONB,
    ADD COLUMN IF NOT EXISTS reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS conflict_event_id UUID;
