-- SPEC-TW-020 (Tool Execution Failed): reuses V025's ticket.ticket_tool_execution_results
-- (result_status already accepts 'FAILED'). Adds the failure-classification
-- columns; tool_execution_id remains the table's primary key / unique
-- business dedup key across every SPEC-TW-019..021 outcome.

ALTER TABLE ticket.ticket_tool_execution_results
    ADD COLUMN failure_code VARCHAR(128),
    ADD COLUMN failure_class VARCHAR(32),
    ADD COLUMN failed_at TIMESTAMPTZ,
    ADD COLUMN safe_to_retry BOOLEAN;

ALTER TABLE ticket.ticket_tool_execution_results
    ADD CONSTRAINT ck_tool_execution_failure_class CHECK (
        failure_class IS NULL OR failure_class IN ('KNOWN_SAFE', 'RETRYABLE_SAFE', 'PIPELINE_FAILED')
    );

ALTER TABLE ticket.ticket_tool_execution_results
    ADD CONSTRAINT ck_tool_execution_result_failed_fields CHECK (
        result_status <> 'FAILED' OR (failure_code IS NOT NULL AND failure_class IS NOT NULL AND failed_at IS NOT NULL)
    );
