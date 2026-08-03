package dev.opsmind.ticketworkflow.ticket.domain.exception;

/**
 * Raised when {@code Ticket.assign(...)} is invoked against a ticket that
 * already carries an assignee (SPEC-TW-008 domain-rules §3, test-plan
 * UT-03) — defensive: the invariant "TRIAGED means no assignee" should
 * already prevent this, but the aggregate method still guards it
 * explicitly rather than trusting the caller.
 */
public class TicketAlreadyAssignedException extends RuntimeException {

    public TicketAlreadyAssignedException() {
        super("the ticket already has an assignee");
    }
}
