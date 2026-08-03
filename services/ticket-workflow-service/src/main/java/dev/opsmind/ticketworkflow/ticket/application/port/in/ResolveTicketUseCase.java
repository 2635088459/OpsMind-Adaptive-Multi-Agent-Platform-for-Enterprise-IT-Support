package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketResult;

public interface ResolveTicketUseCase {

    ResolveTicketResult resolve(ResolveTicketCommand command);
}
