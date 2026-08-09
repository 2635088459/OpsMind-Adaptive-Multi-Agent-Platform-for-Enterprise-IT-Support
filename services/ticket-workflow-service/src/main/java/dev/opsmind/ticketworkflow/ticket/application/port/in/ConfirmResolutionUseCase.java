package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionResult;

public interface ConfirmResolutionUseCase {

    ConfirmResolutionResult confirmResolution(ConfirmResolutionCommand command);
}
