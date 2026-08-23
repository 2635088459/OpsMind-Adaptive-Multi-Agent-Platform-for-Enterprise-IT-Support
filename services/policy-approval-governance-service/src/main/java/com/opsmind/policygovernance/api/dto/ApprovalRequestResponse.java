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
            request.ticketId(), request.workflowInstanceId(), request.toolRequestId(), request.executorId(), request.policyDecisionId(),
            request.requestedBy(), request.approvalType(), request.riskLevel(),
            request.constraints().stream().map(ConstraintDto::from).toList(),
            request.status(), request.expiresAt(), request.createdAt(), request.updatedAt()
        );
    }
}
