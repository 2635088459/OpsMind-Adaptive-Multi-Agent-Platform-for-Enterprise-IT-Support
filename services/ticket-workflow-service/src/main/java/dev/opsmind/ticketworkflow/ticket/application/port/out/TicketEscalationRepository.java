package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketEscalationRepository {

    TicketEscalationUpdateOutcome applyEscalation(TicketEscalationUpdate update);
}
