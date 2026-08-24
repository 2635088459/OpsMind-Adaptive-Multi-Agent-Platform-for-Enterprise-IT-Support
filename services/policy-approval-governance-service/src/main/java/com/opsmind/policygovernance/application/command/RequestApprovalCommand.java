package com.opsmind.policygovernance.application.command;

import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@code ApprovalService.request} (04-use-cases §UC-PG-002). When
 * {@code approvalType == POLICY_OVERRIDE}, {@code ApprovalService#request}
 * additionally requires a non-null {@code expiresAt} and at least one
 * {@code constraint} — see {@code InvalidOverrideRequestException} and
 * UC-PG-006 (04-use-cases §High-Risk Override).
 */
public record RequestApprovalCommand(
    String requestKey,
    String requestHash,
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
    List<Constraint> constraints,
    Instant expiresAt,
    String correlationId,
    String causationId
) {
    public RequestApprovalCommand {
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(sourceDomain, "sourceDomain");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(approvalType, "approvalType");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(correlationId, "correlationId");
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
    }
}
