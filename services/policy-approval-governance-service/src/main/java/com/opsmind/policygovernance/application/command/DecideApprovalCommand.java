package com.opsmind.policygovernance.application.command;

import com.opsmind.policygovernance.domain.decision.Constraint;

import java.util.List;
import java.util.Objects;

/**
 * Input to {@code ApprovalService.grant}/{@code deny} (04-use-cases
 * §UC-PG-003/004). {@code commandIdempotencyKey} is SPEC-PG-011's own
 * addition — see {@code domain.approval.ApprovalDecision}'s own javadoc for
 * why it is a distinct idempotency key from {@code sourceRequestId}/{@code
 * requestHash}.
 */
public record DecideApprovalCommand(
    String approvalRequestId,
    String sourceRequestId,
    String requestHash,
    String decidedBy,
    String reason,
    List<Constraint> conditions,
    String correlationId,
    String commandIdempotencyKey
) {
    public DecideApprovalCommand {
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(commandIdempotencyKey, "commandIdempotencyKey");
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }
}
