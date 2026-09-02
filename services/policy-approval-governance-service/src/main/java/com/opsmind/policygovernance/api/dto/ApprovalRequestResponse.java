package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Instant;
import java.util.List;

public record ApprovalRequestResponse(
    String approvalRequestId,
    String requestKey,
    String sourceDomain,
    String sourceRequestId,
    /**
     * SPEC-SC-009: a real bug found live during that spec's own backend
     * grounding — {@code grant}/{@code deny}/{@code cancel}/{@code use}/
     * {@code revoke} all require the caller to supply {@code requestHash}
     * (checked via {@code ApprovalRequest#matches}), but until this field
     * existed, NO response ever exposed it — structurally impossible for
     * any caller that did not itself originate the request (e.g. a human
     * support-console user deciding a request `agent-runtime-service`
     * created) to ever produce the correct value. Not a new precedent:
     * this controller's own class javadoc already documents "Audit API
     * returns metadata/hash by default, not sensitive raw input" for the
     * sibling governance-audit endpoint — a correlation hash, not a secret.
     */
    String requestHash,
    String ticketId,
    String workflowInstanceId,
    String toolRequestId,
    String executorId,
    String policyDecisionId,
    String requestedBy,
    ApprovalType approvalType,
    RiskLevel riskLevel,
    List<ConstraintDto> constraints,
    ApprovalStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt
) {

    public static ApprovalRequestResponse from(ApprovalRequest request) {
        return new ApprovalRequestResponse(
            request.approvalRequestId(), request.requestKey(), request.sourceDomain(), request.sourceRequestId(),
            request.requestHash(),
            request.ticketId(), request.workflowInstanceId(), request.toolRequestId(), request.executorId(), request.policyDecisionId(),
            request.requestedBy(), request.approvalType(), request.riskLevel(),
            request.constraints().stream().map(ConstraintDto::from).toList(),
            request.status(), request.expiresAt(), request.createdAt(), request.updatedAt()
        );
    }
}
