package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketReopenRepository {

    TicketReopenUpdateOutcome applyReopen(TicketReopenUpdate update);
}
