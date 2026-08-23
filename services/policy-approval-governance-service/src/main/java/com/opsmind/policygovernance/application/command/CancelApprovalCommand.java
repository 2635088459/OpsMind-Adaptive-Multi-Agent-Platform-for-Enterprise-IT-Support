package com.opsmind.policygovernance.application.command;

import java.util.Objects;

/**
 * Input to {@code ApprovalService.cancel} (04-use-cases, SPEC-PG-012).
 * Mirrors {@link DecideApprovalCommand}'s shape: {@code sourceRequestId}/
 * {@code requestHash} re-validate request linkage (INV-PG-005) and {@code
 * commandIdempotencyKey} is cancel's own idempotency guard
 * (09-concurrency-and-idempotency, 05-api-contracts "every command must
 * include idempotency key") — see {@code
 * domain.approval.ApprovalRequest#cancelCommandIdempotencyKey} for why it
 * lives on the request itself rather than on a decision row.
 */
public record CancelApprovalCommand(
    String approvalRequestId,
    String sourceRequestId,
    String requestHash,
    String cancelledBy,
    String reason,
    String correlationId,
    String commandIdempotencyKey
) {
    public CancelApprovalCommand {
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(cancelledBy, "cancelledBy");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(commandIdempotencyKey, "commandIdempotencyKey");
    }
}
