-- SPEC-TW-009 reference migration. The real service migration should be
-- renumbered to V015__transition_ticket_status.sql because this codebase
-- already uses V013 for triage and V014 for assignment.

ALTER TABLE ticket.tickets
    ADD COLUMN waiting_for_requester_since TIMESTAMPTZ,
    ADD COLUMN approval_reference VARCHAR(128);

ALTER TABLE ticket.tickets
    DROP CONSTRAINT ck_tickets_status;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_status CHECK (status IN (
        'NEW', 'TRIAGED', 'ASSIGNED', 'IN_PROGRESS', 'TRIAGING', 'INVESTIGATING',
        'WAITING_FOR_USER', 'WAITING_FOR_APPROVAL', 'EXECUTING', 'VERIFYING',
        'RESOLVED', 'CLOSED', 'ESCALATED', 'FAILED', 'CANCELLED'
    ));

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_work_states_have_assignee CHECK (
        status NOT IN ('IN_PROGRESS', 'WAITING_FOR_USER', 'WAITING_FOR_APPROVAL')
        OR current_support_user_id IS NOT NULL
    );

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_waiting_for_user_metadata CHECK (
        status <> 'WAITING_FOR_USER'
        OR (
            waiting_for_requester_since IS NOT NULL
            AND approval_reference IS NULL
        )
    );

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_waiting_for_approval_metadata CHECK (
        status <> 'WAITING_FOR_APPROVAL'
        OR (
            approval_reference IS NOT NULL
            AND char_length(btrim(approval_reference)) BETWEEN 3 AND 128
            AND waiting_for_requester_since IS NULL
        )
    );

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_in_progress_no_waiting_metadata CHECK (
        status <> 'IN_PROGRESS'
        OR (
            waiting_for_requester_since IS NULL
            AND approval_reference IS NULL
        )
    );

CREATE INDEX ix_tickets_status_updated
    ON ticket.tickets (status, updated_at DESC);

CREATE INDEX ix_tickets_waiting_user
    ON ticket.tickets (waiting_for_requester_since)
    WHERE status = 'WAITING_FOR_USER';

CREATE INDEX ix_tickets_waiting_approval
    ON ticket.tickets (approval_reference)
    WHERE status = 'WAITING_FOR_APPROVAL';
