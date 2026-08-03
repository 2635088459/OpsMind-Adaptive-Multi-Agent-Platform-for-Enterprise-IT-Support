package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedResult;

public interface ApplyApprovalGrantedUseCase {

    ApplyApprovalGrantedResult applyApprovalGranted(ApplyApprovalGrantedCommand command);
}
