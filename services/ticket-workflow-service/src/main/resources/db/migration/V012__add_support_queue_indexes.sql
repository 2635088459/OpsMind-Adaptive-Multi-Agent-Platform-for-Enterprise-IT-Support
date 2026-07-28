-- Support Queue Query (SPEC-TW-005 §20): the default queue predicate is
-- "non-terminal Tickets within an authorized application," and the SLA join
-- key is current_resolution_cycle_id. slaRank/priorityRank are computed
-- expressions (not stored columns), so they cannot be indexed directly;
-- these partial/composite indexes support the WHERE-clause predicates that
-- narrow the row set before that computation and sort happen.
CREATE INDEX ix_tickets_support_queue_open
    ON ticket.tickets (application_code, status)
    WHERE status NOT IN ('CLOSED', 'CANCELLED');

CREATE INDEX ix_tickets_current_support_user
    ON ticket.tickets (current_support_user_id)
    WHERE current_support_user_id IS NOT NULL;
