package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionResult;

public interface EvaluateSecretDetectionUseCase {

    EvaluateSecretDetectionResult evaluate(EvaluateSecretDetectionCommand command);
}
