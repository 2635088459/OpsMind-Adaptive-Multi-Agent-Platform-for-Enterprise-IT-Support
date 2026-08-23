package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ApprovalRequestJpaEntity;

import java.util.List;

public final class ApprovalRequestMapper {

    private static final TypeReference<List<Constraint>> CONSTRAINTS_TYPE = new TypeReference<>() {
    };

    private ApprovalRequestMapper() {
    }

    public static ApprovalRequestJpaEntity toEntity(ApprovalRequest request) {
        return new ApprovalRequestJpaEntity(
            request.approvalRequestId(), request.requestKey(), request.requestHash(), request.sourceDomain(),
            request.sourceRequestId(), request.ticketId(), request.workflowInstanceId(), request.toolRequestId(), request.executorId(),
            request.policyDecisionId(), null, request.requestedBy(), request.approvalType().name(), request.riskLevel().name(),
            JsonSupport.writeList(request.constraints()), request.status().name(), request.expiresAt(),
            request.createdAt(), request.updatedAt(), request.cancelCommandIdempotencyKey()
        );
    }

    public static ApprovalRequest toDomain(ApprovalRequestJpaEntity entity) {
        return ApprovalRequest.reconstruct(
            entity.getApprovalRequestId(), entity.getRequestKey(), entity.getRequestHash(), entity.getSourceDomain(),
            entity.getSourceRequestId(), entity.getTicketId(), entity.getWorkflowInstanceId(), entity.getToolRequestId(), entity.getExecutorId(),
            entity.getPolicyDecisionId(), entity.getRequestedById(), ApprovalType.valueOf(entity.getApprovalType()),
            RiskLevel.valueOf(entity.getRiskLevel()), JsonSupport.readList(entity.getConstraintsJson(), CONSTRAINTS_TYPE),
            ApprovalStatus.valueOf(entity.getStatus()), entity.getExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt(),
            entity.getCancelCommandIdempotencyKey()
        );
    }
}
