package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedResult;

public interface ApplyToolExecutionFailedUseCase {

    ApplyToolExecutionFailedResult applyToolExecutionFailed(ApplyToolExecutionFailedCommand command);
}
