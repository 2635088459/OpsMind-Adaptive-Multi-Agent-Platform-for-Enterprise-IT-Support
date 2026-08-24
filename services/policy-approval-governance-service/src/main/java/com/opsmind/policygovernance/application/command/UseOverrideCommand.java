package com.opsmind.policygovernance.application.command;

import java.util.Objects;

/**
 * Input to {@code ApprovalService.use} (04-use-cases §UC-PG-006, SPEC-PG-022).
 * Mirrors {@link CancelApprovalCommand}'s shape: {@code sourceRequestId}/
 * {@code requestHash} re-validate request linkage (INV-PG-005) and {@code
 * commandIdempotencyKey} is this command's own idempotency guard — see
 * {@code domain.approval.ApprovalRequest#usedCommandIdempotencyKey}.
 *
 * <p>{@code causationId} is SPEC-PG-029's own addition — see {@link
 * DecideApprovalCommand#causationId()}'s own javadoc for the same
 * reasoning.
 */
public record UseOverrideCommand(
    String approvalRequestId,
    String sourceRequestId,
    String requestHash,
    String usedBy,
    String reason,
    String correlationId,
    String commandIdempotencyKey,
    String causationId
) {
    public UseOverrideCommand {
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(usedBy, "usedBy");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(commandIdempotencyKey, "commandIdempotencyKey");
    }
}
