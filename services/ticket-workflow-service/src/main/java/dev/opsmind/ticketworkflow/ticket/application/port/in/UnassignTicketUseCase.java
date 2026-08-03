package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;

public interface UnassignTicketUseCase {

    TicketAssignmentResult unassign(UnassignTicketCommand command);
}
