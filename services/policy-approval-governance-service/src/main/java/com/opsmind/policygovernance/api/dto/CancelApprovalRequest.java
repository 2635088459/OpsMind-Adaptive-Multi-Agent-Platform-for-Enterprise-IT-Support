package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

/** SPEC-PG-012: mirrors {@link DecideApprovalRequest}'s linkage/idempotency shape (05-api-contracts "every command must include idempotency key"). */
public record CancelApprovalRequest(
    @NotBlank String sourceRequestId,
    @NotBlank String requestHash,
    @NotBlank String reason,
    @NotBlank String commandIdempotencyKey
) {
}
