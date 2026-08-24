package com.opsmind.policygovernance.infrastructure.messaging.contract;

import com.opsmind.policygovernance.domain.decision.Constraint;

import java.time.Instant;
import java.util.List;

/**
 * SPEC-PG-026: {@code workflow.approval.required.v1}'s own payload shape.
 * 06-event-contracts §Consumed Events names only this event's purpose ("03
 * requests approval for workflow override, resume, or automation risk"),
 * not a "Key fields" list the way {@code tool.approval.required.v1} got —
 * this shape is the direct structural analog of {@link
 * ToolApprovalRequiredPayload}'s own fields with {@code workflowInstanceId}
 * standing in for {@code toolRequestId} as the primary business key (both
 * events are structurally the same shape: an upstream domain requesting
 * approval for one risky action it wants to take), since nothing more
 * specific is named for this one.
 */
public record WorkflowApprovalRequiredPayload(
    String workflowInstanceId,
    String ticketId,
    String riskLevel,
    String inputHash,
    List<Constraint> constraints,
    Instant expiresAt
) {
}
