package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EvaluateDecisionRequest(
    @NotBlank String decisionKey,
    @NotBlank String inputHash,
    @NotBlank String subjectType,
    @NotBlank String subjectId,
    @NotBlank String actionType,
    String resourceType,
    String resourceId,
    String tenantId,
    @NotBlank String sourceDomain,
    @NotBlank String sourceRequestId,
    String ticketId,
    String workflowInstanceId,
    @NotBlank String policyId
) {
}
