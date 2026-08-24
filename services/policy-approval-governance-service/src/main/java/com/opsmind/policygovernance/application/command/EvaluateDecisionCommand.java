package com.opsmind.policygovernance.application.command;

import java.util.Objects;

/**
 * Input to {@code PolicyDecisionService.evaluate} (04-use-cases §UC-PG-001,
 * api-contract §Main Contract). {@code sourceDomain}/{@code sourceRequestId}
 * satisfy the "Input must include sourceDomain/sourceRequestId or
 * equivalent linkage" API contract rule and the SPEC-PG-002 traceability
 * Core Rule; {@code ticketId}/{@code workflowInstanceId} are optional.
 *
 * <p>{@code readOnly} is SPEC-PG-032's own addition (10-failure-handling
 * §Degraded Policy Mode: "high-risk mutation fails closed; low-risk
 * read-only may use latest published policy cache"). Caller-declared
 * (defaults {@code false} — "fails closed" stays the default for any
 * caller that does not explicitly opt in): 06 cannot itself infer
 * read-only-ness or risk from {@code actionType}/{@code resourceType} alone
 * without evaluating the request, and the evaluator is exactly what may be
 * unavailable when this flag matters. Only consulted by {@code
 * PolicyDecisionService#evaluate} in the one scenario 10-failure-handling
 * names for it: a real effective {@code PolicyVersion} was found, but the
 * evaluator itself threw.
 */
public record EvaluateDecisionCommand(
    String decisionKey,
    String inputHash,
    String subjectType,
    String subjectId,
    String actionType,
    boolean readOnly,
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
