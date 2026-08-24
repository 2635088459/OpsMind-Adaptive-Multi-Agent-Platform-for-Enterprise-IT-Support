package com.opsmind.policygovernance.api.dto;

import jakarta.validation.constraints.NotBlank;

/** SPEC-PG-032: {@code readOnly} — see {@code EvaluateDecisionCommand}'s own javadoc; defaults {@code false} when omitted. */
public record EvaluateDecisionRequest(
    @NotBlank String decisionKey,
    @NotBlank String inputHash,
    @NotBlank String subjectType,
    @NotBlank String subjectId,
    @NotBlank String actionType,
    boolean readOnly,
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
