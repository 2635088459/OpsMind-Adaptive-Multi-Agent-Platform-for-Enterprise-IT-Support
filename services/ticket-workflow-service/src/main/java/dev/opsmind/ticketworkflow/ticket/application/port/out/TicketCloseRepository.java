package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketCloseRepository {

    TicketCloseUpdateOutcome applyClose(TicketCloseUpdate update);
}
