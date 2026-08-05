package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyResult;

public interface ApplyAutoApprovedPolicyUseCase {

    ApplyAutoApprovedPolicyResult applyAutoApprovedPolicy(ApplyAutoApprovedPolicyCommand command);
}
