package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationResult;

public interface EvaluateStepUpAuthenticationUseCase {

    EvaluateStepUpAuthenticationResult evaluate(EvaluateStepUpAuthenticationCommand command);
}
