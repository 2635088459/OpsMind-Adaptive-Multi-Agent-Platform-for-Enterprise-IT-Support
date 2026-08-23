package com.opsmind.policygovernance.application.command;

import java.util.Objects;

/**
 * Input to {@code PolicyDecisionService.evaluate} (04-use-cases §UC-PG-001,
 * api-contract §Main Contract). {@code sourceDomain}/{@code sourceRequestId}
 * satisfy the "Input must include sourceDomain/sourceRequestId or
 * equivalent linkage" API contract rule and the SPEC-PG-002 traceability
 * Core Rule; {@code ticketId}/{@code workflowInstanceId} are optional.
 */
public record EvaluateDecisionCommand(
    String decisionKey,
    String inputHash,
    String subjectType,
    String subjectId,
    String actionType,
    String resourceType,
    String resourceId,
    String tenantId,
    String sourceDomain,
    String sourceRequestId,
    String ticketId,
    String workflowInstanceId,
    String policyId,
    String correlationId,
    String causationId
) {
    public EvaluateDecisionCommand {
        Objects.requireNonNull(decisionKey, "decisionKey");
        Objects.requireNonNull(inputHash, "inputHash");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(sourceDomain, "sourceDomain");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
