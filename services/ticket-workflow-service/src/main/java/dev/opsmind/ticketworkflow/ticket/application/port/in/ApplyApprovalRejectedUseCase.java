package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedResult;

public interface ApplyApprovalRejectedUseCase {

    ApplyApprovalRejectedResult applyApprovalRejected(ApplyApprovalRejectedCommand command);
}
