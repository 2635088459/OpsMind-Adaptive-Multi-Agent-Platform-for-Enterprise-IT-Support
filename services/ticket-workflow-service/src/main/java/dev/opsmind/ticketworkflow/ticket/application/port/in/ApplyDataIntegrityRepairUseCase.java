package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairResult;

public interface ApplyDataIntegrityRepairUseCase {

    ApplyDataIntegrityRepairResult apply(ApplyDataIntegrityRepairCommand command);
}
