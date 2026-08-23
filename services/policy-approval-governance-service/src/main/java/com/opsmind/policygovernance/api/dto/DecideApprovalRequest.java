package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record DecideApprovalRequest(
    @NotBlank String sourceRequestId,
    @NotBlank String requestHash,
    @NotBlank String reason,
    List<ConstraintDto> conditions,
    @NotBlank String commandIdempotencyKey
) {
}
