package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventResult;

public interface PublishCorrectionEventUseCase {

    PublishCorrectionEventResult publish(PublishCorrectionEventCommand command);
}
