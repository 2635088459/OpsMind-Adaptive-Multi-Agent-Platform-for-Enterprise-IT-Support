package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketResult;

public interface CancelTicketUseCase {

    CancelTicketResult cancel(CancelTicketCommand command);
}
