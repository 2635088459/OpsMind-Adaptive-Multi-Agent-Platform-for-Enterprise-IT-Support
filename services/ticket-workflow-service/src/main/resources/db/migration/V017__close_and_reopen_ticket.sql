-- SPEC-TW-011 (Close and Reopen Ticket): structured close/reopen data on
-- the Ticket row. Renumbered from the spec folder's reference "V011" to the
-- next real slot in this service's Flyway sequence (V001-V016 already
-- exist). `closed_at`/`close_reason_code` already exist since V002; the
-- resolution-cycle table's `closed_at`/`closed_by_type`/`closed_by_id`/
-- `close_reason_code`/`reopened_at`/`reopen_reason_code`/
-- `reopened_by_type`/`reopened_by_id` columns already exist since V003, so
-- no `ticket_resolution_cycles` migration is needed here.

ALTER TABLE ticket.tickets
    ADD COLUMN IF NOT EXISTS closed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS reopen_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_reopened_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_reopened_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_reopen_reason_code VARCHAR(64);

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_reopen_count CHECK (reopen_count >= 0);

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
    ADD CONSTRAINT ck_tickets_reopen_reason_code CHECK (
        last_reopen_reason_code IS NULL OR last_reopen_reason_code IN (
            'ISSUE_RECURRED',
            'RESOLUTION_FAILED',
            'REQUESTER_REPORTED_NOT_FIXED',
            'SUPPORT_REVIEW_REQUIRED',
            'RELATED_FAILURE_DISCOVERED'
        )
    );

-- Replaces V002's ck_tickets_closed_fields (resolved_at/closed_at/
-- close_reason_code/active_workflow_id only): SPEC-TW-011 domain-rules §2
-- additionally requires closed_by.
ALTER TABLE ticket.tickets
    DROP CONSTRAINT ck_tickets_closed_fields;

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
