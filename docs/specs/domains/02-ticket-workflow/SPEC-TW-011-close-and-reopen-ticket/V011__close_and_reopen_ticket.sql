-- Reference migration for SPEC-TW-011.
-- Real service migration should be named:
-- services/ticket-workflow-service/src/main/resources/db/migration/V017__close_and_reopen_ticket.sql

ALTER TABLE ticket.tickets
    ADD COLUMN IF NOT EXISTS closed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS reopen_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_reopened_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_reopened_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_reopen_reason_code VARCHAR(64);

ALTER TABLE ticket.tickets
    DROP CONSTRAINT IF EXISTS ck_tickets_reopen_count;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_reopen_count CHECK (reopen_count >= 0);

ALTER TABLE ticket.tickets
    DROP CONSTRAINT IF EXISTS ck_tickets_close_reason_code;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_close_reason_code CHECK (
        close_reason_code IS NULL OR close_reason_code IN (
            'REQUESTER_CONFIRMED',
            'SUPPORT_CONFIRMED',
            'AUTO_CLOSE_TIMEOUT',
            'NO_FURTHER_ACTION_REQUIRED',
            'DUPLICATE_CLOSED'
        )
    );

ALTER TABLE ticket.tickets
    DROP CONSTRAINT IF EXISTS ck_tickets_reopen_reason_code;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_reopen_reason_code CHECK (
        last_reopen_reason_code IS NULL OR last_reopen_reason_code IN (
            'ISSUE_RECURRED',
            'RESOLUTION_FAILED',
            'REQUESTER_REPORTED_NOT_FIXED',
            'SUPPORT_REVIEW_REQUIRED',
            'RELATED_FAILURE_DISCOVERED'
        )
    );

ALTER TABLE ticket.tickets
    DROP CONSTRAINT IF EXISTS ck_tickets_closed_fields;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_closed_fields CHECK (
        status <> 'CLOSED'
        OR (
            resolved_at IS NOT NULL
            AND closed_at IS NOT NULL
            AND closed_by IS NOT NULL
            AND close_reason_code IS NOT NULL
            AND active_workflow_id IS NULL
        )
    );

CREATE INDEX IF NOT EXISTS ix_tickets_closed_at
    ON ticket.tickets (closed_at DESC)
    WHERE status = 'CLOSED';

CREATE INDEX IF NOT EXISTS ix_tickets_reopen_count
    ON ticket.tickets (reopen_count)
    WHERE reopen_count > 0;
