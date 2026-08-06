package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationResult;

public interface StartVerificationUseCase {

    StartVerificationResult startVerification(StartVerificationCommand command);
}
