package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.RequesterReopenTicketCommand;

public interface RequesterReopenTicketUseCase {

    ReopenTicketResult reopen(RequesterReopenTicketCommand command);
}
