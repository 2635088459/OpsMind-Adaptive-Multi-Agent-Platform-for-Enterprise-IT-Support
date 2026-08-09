package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketAutoCloseRepository {

    TicketAutoCloseUpdateOutcome applyAutoClose(TicketAutoCloseUpdate update);
}
