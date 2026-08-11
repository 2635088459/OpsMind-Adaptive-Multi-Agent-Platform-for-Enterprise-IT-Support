package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ExecuteCompensationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ExecuteCompensationResult;

public interface ExecuteCompensationUseCase {

    ExecuteCompensationResult execute(ExecuteCompensationCommand command);
}
