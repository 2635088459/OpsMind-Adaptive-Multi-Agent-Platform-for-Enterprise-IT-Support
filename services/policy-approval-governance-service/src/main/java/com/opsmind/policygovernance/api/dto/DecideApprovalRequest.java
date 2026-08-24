package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** SPEC-PG-016: {@code sessionId}/{@code deviceId}/{@code stepUpVerified} are optional (11-security §Approval Authenticity). */
public record DecideApprovalRequest(
    @NotBlank String sourceRequestId,
    @NotBlank String requestHash,
    @NotBlank String reason,
    List<ConstraintDto> conditions,
    @NotBlank String commandIdempotencyKey,
    String sessionId,
    String deviceId,
    boolean stepUpVerified
) {
}
