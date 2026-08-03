package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;

public interface ReassignTicketUseCase {

    TicketAssignmentResult reassign(ReassignTicketCommand command);
}
