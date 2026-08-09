package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseResult;

public interface OpenReconciliationCaseUseCase {

    OpenReconciliationCaseResult openCase(OpenReconciliationCaseCommand command);
}
