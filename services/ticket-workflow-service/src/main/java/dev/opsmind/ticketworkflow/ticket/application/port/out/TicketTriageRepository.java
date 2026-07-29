package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketTriageRepository {

    TicketTriageUpdateOutcome applyTriage(TicketTriageUpdate update);
}
