package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record DraftPolicyRequest(
    @NotBlank String policyId,
    @NotBlank String policyName,
    @NotBlank String scope,
    List<PolicyRuleDto> rules
) {
}
