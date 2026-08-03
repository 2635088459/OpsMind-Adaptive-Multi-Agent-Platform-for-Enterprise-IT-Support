-- SPEC-TW-008 (Assign Ticket): a local support-agent directory and queue
-- membership catalog, ticket assignment history, and the Ticket columns
-- ownership commands set. Renumbered from the spec folder's reference
-- "V008" to the next real slot in this service's Flyway sequence
-- (V001-V013 already exist); the reference migration's tenant_id columns
-- are dropped throughout (see SPEC-TW-007's V013 migration for the same,
-- earlier documented deviation: no tenant concept exists in this schema).
--
-- The reference migration adds a new `assignee_id` column; this migration
-- instead reuses `ticket.tickets.current_support_user_id`, a column that
-- has existed (and its accompanying CHECK constraint
-- ck_tickets_support_user_requires_team) since V002 but was never
-- populated by any code path until now — the same "activate a dormant
-- column" pattern SPEC-TW-007 used for current_team_id.

-- A local substitute for "identity ownership may live in another
-- service" (persistence_EN.md §1): no external identity/directory
-- service exists anywhere in this codebase, so assignee eligibility is
-- evaluated against a local, minimal agent directory instead.
CREATE TABLE ticket.support_agents (
    agent_id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    role VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_support_agents_role CHECK (role IN ('IT_SUPPORT', 'IT_ADMIN', 'IT_MANAGER'))
);

CREATE TABLE ticket.support_queue_memberships (
    agent_id VARCHAR(128) NOT NULL,
    support_queue_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (agent_id, support_queue_id),
    CONSTRAINT fk_support_queue_memberships_agent
        FOREIGN KEY (agent_id) REFERENCES ticket.support_agents (agent_id),
    CONSTRAINT fk_support_queue_memberships_queue
        FOREIGN KEY (support_queue_id) REFERENCES ticket.support_queues (support_queue_id)
);

CREATE INDEX ix_support_queue_memberships_queue ON ticket.support_queue_memberships (support_queue_id);

CREATE TABLE ticket.ticket_assignment_history (
    assignment_history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL,
    previous_assignee_id VARCHAR(128),
    new_assignee_id VARCHAR(128),
    previous_status VARCHAR(32) NOT NULL,
    new_status VARCHAR(32) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128),
    causation_id VARCHAR(128),
    resulting_version BIGINT NOT NULL,

    CONSTRAINT fk_ticket_assignment_history_ticket
        FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT ck_ticket_assignment_history_action
        CHECK (action IN ('ASSIGNED', 'REASSIGNED', 'UNASSIGNED')),
    CONSTRAINT ck_ticket_assignment_history_version
        CHECK (resulting_version > 0),
    CONSTRAINT ck_ticket_assignment_history_reason
        CHECK (char_length(btrim(reason)) BETWEEN 3 AND 500),
    CONSTRAINT ck_ticket_assignment_history_owner_change
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

CREATE UNIQUE INDEX uq_ticket_assignment_history_version
    ON ticket.ticket_assignment_history (ticket_id, resulting_version);

CREATE INDEX ix_ticket_assignment_history_ticket_time
    ON ticket.ticket_assignment_history (ticket_id, occurred_at DESC, assignment_history_id DESC);

CREATE INDEX ix_ticket_assignment_history_actor_time
    ON ticket.ticket_assignment_history (actor_id, occurred_at DESC);

ALTER TABLE ticket.tickets
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN assigned_by VARCHAR(128);

-- ASSIGNED is new; every legacy value (including WAITING_FOR_USER/
-- WAITING_FOR_APPROVAL, already present from the pre-Phase-03 model and
-- directly reused here per domain-rules §4) is preserved unchanged.
ALTER TABLE ticket.tickets
    DROP CONSTRAINT ck_tickets_status;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_status CHECK (status IN (
        'NEW', 'TRIAGED', 'ASSIGNED', 'TRIAGING', 'INVESTIGATING', 'WAITING_FOR_USER', 'WAITING_FOR_APPROVAL',
        'EXECUTING', 'VERIFYING', 'RESOLVED', 'CLOSED', 'ESCALATED', 'FAILED', 'CANCELLED'));

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_assigned_fields CHECK (
        status <> 'ASSIGNED' OR (
            current_support_user_id IS NOT NULL
            AND assigned_at IS NOT NULL
            AND assigned_by IS NOT NULL
        )
    );

-- Defensive counterpart to ck_tickets_assigned_fields: a TRIAGED ticket
-- (domain-rules §8 "TRIAGED means no assignee") never carries an owner.
-- Reassign/Unassign never leave a ticket in TRIAGED with an owner still
-- set, and no other status currently transitions into TRIAGED.
ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_triaged_no_assignee CHECK (
        status <> 'TRIAGED' OR current_support_user_id IS NULL
    );

CREATE INDEX ix_tickets_queue_assignee_status
    ON ticket.tickets (support_queue_id, current_support_user_id, status);
