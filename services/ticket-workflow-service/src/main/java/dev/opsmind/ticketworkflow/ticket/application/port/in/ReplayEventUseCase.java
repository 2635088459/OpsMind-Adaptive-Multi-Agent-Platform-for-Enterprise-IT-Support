package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ReplayEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReplayEventResult;

public interface ReplayEventUseCase {

    ReplayEventResult replay(ReplayEventCommand command);
}
