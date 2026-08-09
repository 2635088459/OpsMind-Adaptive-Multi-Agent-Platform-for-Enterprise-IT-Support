package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketResult;

public interface AutoCloseTicketUseCase {

    AutoCloseTicketResult autoClose(AutoCloseTicketCommand command);
}
