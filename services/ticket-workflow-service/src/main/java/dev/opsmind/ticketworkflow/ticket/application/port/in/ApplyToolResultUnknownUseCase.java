package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownResult;

public interface ApplyToolResultUnknownUseCase {

    ApplyToolResultUnknownResult applyToolResultUnknown(ApplyToolResultUnknownCommand command);
}
