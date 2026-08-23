package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record RequestApprovalRequest(
    @NotBlank String requestKey,
    @NotBlank String requestHash,
    @NotBlank String sourceDomain,
    @NotBlank String sourceRequestId,
    String ticketId,
    String workflowInstanceId,
    String toolRequestId,
    String policyDecisionId,
    @NotNull ApprovalType approvalType,
    @NotNull RiskLevel riskLevel,
    List<ConstraintDto> constraints,
    Instant expiresAt
) {
}
