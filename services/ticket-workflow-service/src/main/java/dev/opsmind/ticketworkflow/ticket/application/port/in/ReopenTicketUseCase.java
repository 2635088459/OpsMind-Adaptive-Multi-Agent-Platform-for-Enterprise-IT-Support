package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketResult;

public interface ReopenTicketUseCase {

    ReopenTicketResult reopen(ReopenTicketCommand command);
}
