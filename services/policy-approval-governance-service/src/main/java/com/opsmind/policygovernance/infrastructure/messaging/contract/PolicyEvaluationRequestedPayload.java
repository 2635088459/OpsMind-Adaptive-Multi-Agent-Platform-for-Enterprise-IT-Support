package com.opsmind.policygovernance.infrastructure.messaging.contract;

/**
 * SPEC-PG-028: {@code policy.evaluation.requested.v1}'s own payload shape —
 * the asynchronous counterpart of {@code POST /api/v1/decisions:evaluate}'s
 * own {@code EvaluateDecisionRequest} body (05-api-contracts §Main
 * Contract), since 06-event-contracts names only this event's purpose
 * ("asynchronous policy evaluation request"), not a distinct "Key fields"
 * list — the natural reading is "the same input the synchronous API
 * already takes, delivered over the bus instead of HTTP."
 * {@code sourceRequestId} is kept a separate field from {@code decisionKey}
 * (unlike the tool/workflow/ticket payloads, where the primary business key
 * doubles as both), matching {@code EvaluateDecisionCommand}'s own field
 * list, where the two are already distinct.
 */
public record PolicyEvaluationRequestedPayload(
    String decisionKey,
    String inputHash,
    String subjectType,
    String subjectId,
    String actionType,
    boolean readOnly,
    String resourceType,
    String resourceId,
    String tenantId,
    String sourceRequestId,
    String ticketId,
    String workflowInstanceId,
    String policyId
) {
}
