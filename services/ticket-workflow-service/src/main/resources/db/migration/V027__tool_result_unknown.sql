-- SPEC-TW-021 (Tool Result Unknown): reuses V025/V026's
-- ticket.ticket_tool_execution_results (result_status already accepts
-- 'UNKNOWN'). conflict_event_id is VARCHAR(64), not UUID, matching this
-- codebase's established convention for event-id columns (granted_event_id,
-- rejected_event_id, expired_event_id, auto_approval_event_id, event_id on
-- this same table) — envelope eventIds are arbitrary strings, not
-- necessarily UUIDs.

ALTER TABLE ticket.ticket_tool_execution_results
    ADD COLUMN unknown_reason VARCHAR(256),
    ADD COLUMN observed_at TIMESTAMPTZ,
    ADD COLUMN evidence_references JSONB,
    ADD COLUMN reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN conflict_event_id VARCHAR(64);

ALTER TABLE ticket.ticket_tool_execution_results
    ADD CONSTRAINT ck_tool_execution_result_unknown_fields CHECK (
        result_status <> 'UNKNOWN' OR (unknown_reason IS NOT NULL AND observed_at IS NOT NULL)
    );
