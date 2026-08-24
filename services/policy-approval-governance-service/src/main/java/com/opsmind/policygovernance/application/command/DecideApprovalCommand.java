package com.opsmind.policygovernance.application.command;

import com.opsmind.policygovernance.domain.decision.Constraint;

import java.util.List;
import java.util.Objects;

/**
 * Input to {@code ApprovalService.grant}/{@code deny} (04-use-cases
 * §UC-PG-003/004). {@code commandIdempotencyKey} is SPEC-PG-011's own
 * addition — see {@code domain.approval.ApprovalDecision}'s own javadoc for
 * why it is a distinct idempotency key from {@code sourceRequestId}/{@code
 * requestHash}. {@code sessionId}/{@code deviceId}/{@code stepUpVerified}
 * are SPEC-PG-016's own addition (11-security §Approval Authenticity) —
 * nullable/defaulted the same way their {@link
 * com.opsmind.policygovernance.domain.approval.ApprovalDecision} counterparts
 * are, since this codebase has no session store of its own.
 *
 * <p>{@code causationId} is SPEC-PG-029's own addition (12-observability
 * §Logs names it a required structured-log field; 06-event-contracts
 * §Envelope names it a required field on every published event) — nullable,
 * mirroring {@code RequestApprovalCommand#causationId}'s own reasoning:
 * an actor grant/deny call may not be reacting to any prior event.
 */
public record DecideApprovalCommand(
    String approvalRequestId,
    String sourceRequestId,
    String requestHash,
    String decidedBy,
    String reason,
    List<Constraint> conditions,
    String correlationId,
    String commandIdempotencyKey,
    String sessionId,
    String deviceId,
    boolean stepUpVerified,
    String causationId
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
