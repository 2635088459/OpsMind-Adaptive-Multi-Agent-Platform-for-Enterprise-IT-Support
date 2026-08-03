package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketResult;

public interface CloseTicketUseCase {

    CloseTicketResult close(CloseTicketCommand command);
}
