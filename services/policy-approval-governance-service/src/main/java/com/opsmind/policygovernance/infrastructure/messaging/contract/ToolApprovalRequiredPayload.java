package com.opsmind.policygovernance.infrastructure.messaging.contract;

import com.opsmind.policygovernance.domain.decision.Constraint;

import java.time.Instant;
import java.util.List;

/**
 * SPEC-PG-025: {@code tool.approval.required.v1}'s own payload shape
 * (06-event-contracts §Consumed Events: "Key fields: {@code toolRequestId},
 * {@code ticketId}, {@code workflowInstanceId}, {@code riskLevel}, {@code
 * inputHash}, {@code constraints}"). {@code expiresAt} is not one of the
 * named key fields — nullable, optional; when 05 supplies none the
 * resulting {@link com.opsmind.policygovernance.domain.approval.ApprovalRequest}
 * simply has no expiry, the same as any other synchronously-created
 * request that omits it.
 */
public record ToolApprovalRequiredPayload(
    String toolRequestId,
    String ticketId,
    String workflowInstanceId,
    String riskLevel,
    String inputHash,
    List<Constraint> constraints,
    Instant expiresAt
) {
}
