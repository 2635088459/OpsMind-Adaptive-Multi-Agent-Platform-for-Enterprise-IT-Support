package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

/** SPEC-PG-022: mirrors {@link CancelApprovalRequest}'s linkage/idempotency shape. */
public record RevokeOverrideRequest(
    @NotBlank String sourceRequestId,
    @NotBlank String requestHash,
    @NotBlank String reason,
    @NotBlank String commandIdempotencyKey
) {
}
