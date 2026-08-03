package dev.opsmind.ticketworkflow.ticket.domain.exception;

/**
 * Raised when {@code Ticket.reassign(...)}/{@code Ticket.unassign(...)}
 * is invoked against a ticket with no current assignee (SPEC-TW-008
 * domain-rules §4/§5) — defensive, mirrors {@link TicketAlreadyAssignedException}.
 */
public class TicketNotAssignedException extends RuntimeException {

    public TicketNotAssignedException() {
        super("the ticket has no current assignee");
    }
}
