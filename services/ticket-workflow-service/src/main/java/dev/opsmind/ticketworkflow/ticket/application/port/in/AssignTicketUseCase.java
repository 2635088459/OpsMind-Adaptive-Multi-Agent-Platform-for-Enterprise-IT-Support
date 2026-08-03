package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;

public interface AssignTicketUseCase {

    TicketAssignmentResult assign(AssignTicketCommand command);
}
