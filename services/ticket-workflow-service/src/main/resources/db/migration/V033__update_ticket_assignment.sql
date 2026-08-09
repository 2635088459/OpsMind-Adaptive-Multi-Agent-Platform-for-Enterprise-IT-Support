-- SPEC-TW-030 (Assign Ticket / cross-team routing): widens V014's
-- ticket_assignment_history CHECK constraints to admit a new 'ROUTED'
-- action, recorded by UpdateTicketAssignmentApplicationService whenever a
-- support lead or the automated router reassigns a ticket's team/queue/
-- assignee without changing its lifecycle status (unlike ASSIGNED/
-- REASSIGNED/UNASSIGNED, which are always coupled to a TRIAGED<->ASSIGNED
-- transition). No new columns or tables are needed: team_id/support_queue_id
-- live on ticket.tickets already (V007/V014), so ROUTED history rows only
-- need to prove the ticket's lifecycle status did not change.

ALTER TABLE ticket.ticket_assignment_history
    DROP CONSTRAINT ck_ticket_assignment_history_action;

ALTER TABLE ticket.ticket_assignment_history
    ADD CONSTRAINT ck_ticket_assignment_history_action
        CHECK (action IN ('ASSIGNED', 'REASSIGNED', 'UNASSIGNED', 'ROUTED'));

ALTER TABLE ticket.ticket_assignment_history
    DROP CONSTRAINT ck_ticket_assignment_history_owner_change;

ALTER TABLE ticket.ticket_assignment_history
    ADD CONSTRAINT ck_ticket_assignment_history_owner_change
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
            OR
            (action = 'ROUTED'
                AND previous_status = new_status)
        );
