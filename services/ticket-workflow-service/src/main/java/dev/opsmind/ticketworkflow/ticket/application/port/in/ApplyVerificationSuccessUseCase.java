package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessResult;

public interface ApplyVerificationSuccessUseCase {

    ApplyVerificationSuccessResult applyVerificationSuccess(ApplyVerificationSuccessCommand command);
}
