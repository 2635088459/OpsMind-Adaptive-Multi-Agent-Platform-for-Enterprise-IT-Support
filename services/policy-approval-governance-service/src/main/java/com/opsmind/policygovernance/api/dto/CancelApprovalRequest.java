package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelApprovalRequest(
    @NotBlank String reason
) {
}
