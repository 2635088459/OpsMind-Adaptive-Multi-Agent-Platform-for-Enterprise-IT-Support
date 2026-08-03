package dev.opsmind.ticketworkflow.ticket.domain.exception;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    private final TicketStatus currentStatus;
    private final TicketStatus targetStatus;

    public InvalidStatusTransitionException(TicketStatus currentStatus, TicketStatus targetStatus) {
        super("cannot transition ticket from " + currentStatus + " to " + targetStatus);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public TicketStatus currentStatus() {
        return currentStatus;
    }

    public TicketStatus targetStatus() {
        return targetStatus;
    }
}
