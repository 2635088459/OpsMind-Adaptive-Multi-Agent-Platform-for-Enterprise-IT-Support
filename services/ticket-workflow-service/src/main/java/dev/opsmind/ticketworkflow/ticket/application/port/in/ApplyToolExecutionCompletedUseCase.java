package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedResult;

public interface ApplyToolExecutionCompletedUseCase {

    ApplyToolExecutionCompletedResult applyToolExecutionCompleted(ApplyToolExecutionCompletedCommand command);
}
