package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputResult;

public interface RequestUserInputUseCase {

    RequestUserInputResult requestUserInput(RequestUserInputCommand command);
}
