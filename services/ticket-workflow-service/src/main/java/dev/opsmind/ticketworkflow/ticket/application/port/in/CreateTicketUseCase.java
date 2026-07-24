package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;

public interface CreateTicketUseCase {

    CreateTicketResult create(CreateTicketCommand command);
}
