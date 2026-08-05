package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredResult;

/** SPEC-TW-017: consumed from {@code approval.expired.v1}; also callable directly by a future internal scheduler evaluating local expiration. */
public interface ApplyApprovalExpiredUseCase {

    ApplyApprovalExpiredResult applyApprovalExpired(ApplyApprovalExpiredCommand command);
}
