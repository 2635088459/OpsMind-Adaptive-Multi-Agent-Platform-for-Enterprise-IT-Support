package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketResolutionConfirmationRepository {

    TicketResolutionConfirmationUpdateOutcome applyConfirmation(TicketResolutionConfirmationUpdate update);
}
