package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalResult;

public interface RequestApprovalUseCase {

    RequestApprovalResult requestApproval(RequestApprovalCommand command);
}
