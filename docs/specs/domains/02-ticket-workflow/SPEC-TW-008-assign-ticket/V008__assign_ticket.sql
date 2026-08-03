-- SPEC-TW-008 reference migration for PostgreSQL/Flyway.
-- Reconcile identifiers and existing columns with Phase 01-007 before applying.

ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS assignee_id UUID,
    ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS assigned_by UUID;

CREATE TABLE IF NOT EXISTS ticket_assignment_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    ticket_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL,
    previous_assignee_id UUID,
    new_assignee_id UUID,
    previous_status VARCHAR(40) NOT NULL,
    new_status VARCHAR(40) NOT NULL,
    actor_id UUID NOT NULL,
    actor_type VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    resulting_version BIGINT NOT NULL,
    CONSTRAINT fk_assignment_history_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT ck_assignment_history_action
        CHECK (action IN ('ASSIGNED', 'REASSIGNED', 'UNASSIGNED')),
    CONSTRAINT ck_assignment_history_version
        CHECK (resulting_version > 0),
    CONSTRAINT ck_assignment_history_reason
        CHECK (char_length(btrim(reason)) BETWEEN 3 AND 500),
    CONSTRAINT ck_assignment_history_owner_change
        CHECK (
            (action = 'ASSIGNED'
                AND previous_assignee_id IS NULL
                AND new_assignee_id IS NOT NULL
                AND previous_status = 'TRIAGED'
                AND new_status = 'ASSIGNED')
            OR
            (action = 'REASSIGNED'
                AND previous_assignee_id IS NOT NULL
                AND new_assignee_id IS NOT NULL
                AND previous_assignee_id <> new_assignee_id
                AND previous_status = new_status)
            OR
            (action = 'UNASSIGNED'
                AND previous_assignee_id IS NOT NULL
                AND new_assignee_id IS NULL
                AND previous_status = 'ASSIGNED'
                AND new_status = 'TRIAGED')
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_assignment_history_version
    ON ticket_assignment_history (tenant_id, ticket_id, resulting_version);

CREATE INDEX IF NOT EXISTS ix_ticket_assignment_history_ticket_time
    ON ticket_assignment_history (tenant_id, ticket_id, occurred_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS ix_ticket_assignment_history_actor_time
    ON ticket_assignment_history (tenant_id, actor_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS ix_tickets_queue_assignee_status
    ON tickets (tenant_id, support_queue_id, assignee_id, status);

-- The application must preserve these aggregate invariants:
-- TRIAGED => assignee_id/assigned_at/assigned_by are NULL.
-- ASSIGNED/IN_PROGRESS/waiting => assignee_id is NOT NULL.
-- Use a database constraint only after reconciling every existing lifecycle state.
