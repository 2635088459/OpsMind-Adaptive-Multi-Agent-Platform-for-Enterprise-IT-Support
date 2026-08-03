package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketAssignmentRepository {

    TicketAssignmentUpdateOutcome applyAssignment(TicketAssignmentUpdate update);
}
