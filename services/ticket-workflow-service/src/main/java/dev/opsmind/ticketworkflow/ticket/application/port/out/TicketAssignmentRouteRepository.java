package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketAssignmentRouteRepository {

    TicketAssignmentRouteUpdateOutcome applyRoute(TicketAssignmentRouteUpdate update);
}
