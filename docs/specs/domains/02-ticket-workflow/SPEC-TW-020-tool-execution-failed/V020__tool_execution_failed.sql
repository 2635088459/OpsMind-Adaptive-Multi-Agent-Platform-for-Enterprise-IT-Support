-- Reference migration for SPEC-TW-020.
-- Real service migration should be named V026__tool_execution_failed.sql.

ALTER TABLE ticket.ticket_tool_execution_results
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(64),
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS safe_to_retry BOOLEAN;

ALTER TABLE ticket.ticket_tool_execution_results
    DROP CONSTRAINT IF EXISTS ck_tool_execution_failure_class;

ALTER TABLE ticket.ticket_tool_execution_results
    ADD CONSTRAINT ck_tool_execution_failure_class CHECK (
        failure_class IS NULL OR failure_class IN ('KNOWN_SAFE', 'RETRYABLE_SAFE', 'PIPELINE_FAILED')
    );
