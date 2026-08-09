package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketResult;

public interface EscalateTicketUseCase {

    EscalateTicketResult escalate(EscalateTicketCommand command);
}
