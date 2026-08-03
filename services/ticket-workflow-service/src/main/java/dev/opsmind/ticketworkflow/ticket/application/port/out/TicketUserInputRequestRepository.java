package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketUserInputRequestRepository {

    TicketUserInputRequestUpdateOutcome applyRequestUserInput(TicketUserInputRequestUpdate update);
}
