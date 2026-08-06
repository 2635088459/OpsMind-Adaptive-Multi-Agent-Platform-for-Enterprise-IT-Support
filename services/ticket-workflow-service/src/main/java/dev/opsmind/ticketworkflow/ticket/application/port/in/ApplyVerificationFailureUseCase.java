package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureResult;

public interface ApplyVerificationFailureUseCase {

    ApplyVerificationFailureResult applyVerificationFailure(ApplyVerificationFailureCommand command);
}
