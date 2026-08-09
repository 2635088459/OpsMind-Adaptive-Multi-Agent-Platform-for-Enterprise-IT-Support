package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationResult;

public interface EvaluateSupportQueueAuthorizationUseCase {

    EvaluateSupportQueueAuthorizationResult evaluate(EvaluateSupportQueueAuthorizationCommand command);
}
