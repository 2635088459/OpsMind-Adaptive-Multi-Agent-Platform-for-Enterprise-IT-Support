-- SPEC-TW-010 (Resolve Ticket): structured resolution data on the Ticket
-- row, and completion of the ticket's current resolution cycle. Renumbered
-- from the spec folder's reference "V010" to the next real slot in this
-- service's Flyway sequence (V001-V015 already exist).

ALTER TABLE ticket.tickets
    ADD COLUMN IF NOT EXISTS resolution_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS resolution_summary TEXT,
    ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(128);

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_resolution_code CHECK (
        resolution_code IS NULL OR resolution_code IN (
            'FIXED',
            'WORKAROUND_PROVIDED',
            'DUPLICATE',
            'REQUEST_FULFILLED',
            'NOT_REPRODUCIBLE',
            'USER_ERROR',
            'NO_ACTION_REQUIRED'
        )
    );

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_resolution_summary CHECK (
        resolution_summary IS NULL
        OR char_length(btrim(resolution_summary)) BETWEEN 10 AND 5000
    );

-- Replaces V002's ck_tickets_resolved_fields (resolved_at + auto_close_due_at
-- only): SPEC-TW-010 domain-rules §8/persistence §2 additionally require
-- resolved_by, resolution_code, resolution_summary, a retained assignee, and
-- no leftover SPEC-TW-009 waiting metadata on a RESOLVED ticket.
ALTER TABLE ticket.tickets
    DROP CONSTRAINT ck_tickets_resolved_fields;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_resolved_fields CHECK (
        status <> 'RESOLVED'
        OR (
            resolved_at IS NOT NULL
            AND auto_close_due_at IS NOT NULL
            AND resolved_by IS NOT NULL
            AND resolution_code IS NOT NULL
            AND resolution_summary IS NOT NULL
            AND current_support_user_id IS NOT NULL
            AND waiting_for_requester_since IS NULL
            AND approval_reference IS NULL
        )
    );

-- persistence_EN.md §4 "defaults to the first path": relax V003's
-- ck_resolution_cycle_resolved so completing a cycle no longer requires
-- root_cause_code/verification_id (SPEC-TW-010 does not collect either).
ALTER TABLE ticket.ticket_resolution_cycles
    DROP CONSTRAINT ck_resolution_cycle_resolved;

ALTER TABLE ticket.ticket_resolution_cycles
    ADD CONSTRAINT ck_resolution_cycle_resolved CHECK (
        cycle_status NOT IN ('RESOLVED', 'CLOSED', 'REOPENED')
        OR (
            resolved_at IS NOT NULL
            AND resolution_code IS NOT NULL
            AND resolution_summary IS NOT NULL
            AND resolved_by_id IS NOT NULL
        )
    );

CREATE INDEX IF NOT EXISTS ix_tickets_resolved_at
    ON ticket.tickets (resolved_at DESC)
    WHERE status = 'RESOLVED';

CREATE INDEX IF NOT EXISTS ix_tickets_resolution_code
    ON ticket.tickets (resolution_code)
    WHERE resolution_code IS NOT NULL;
