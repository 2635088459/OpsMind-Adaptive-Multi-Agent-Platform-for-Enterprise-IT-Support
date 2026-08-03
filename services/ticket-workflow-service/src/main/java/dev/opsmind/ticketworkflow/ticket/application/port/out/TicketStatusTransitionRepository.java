package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketStatusTransitionRepository {

    TicketStatusTransitionUpdateOutcome applyTransition(TicketStatusTransitionUpdate update);
}
