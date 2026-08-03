package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusResult;

public interface TransitionTicketStatusUseCase {

    TransitionTicketStatusResult transition(TransitionTicketStatusCommand command);
}
