package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketResolveRepository {

    TicketResolveUpdateOutcome applyResolution(TicketResolveUpdate update);
}
