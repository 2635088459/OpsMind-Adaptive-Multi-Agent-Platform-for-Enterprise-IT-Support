package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ApprovalDecisionJpaEntity;

import java.util.List;

public final class ApprovalDecisionMapper {

    private static final TypeReference<List<Constraint>> CONDITIONS_TYPE = new TypeReference<>() {
    };

    private ApprovalDecisionMapper() {
    }

    public static ApprovalDecisionJpaEntity toEntity(ApprovalDecision decision) {
        return new ApprovalDecisionJpaEntity(
            decision.approvalDecisionId(), decision.approvalRequestId(), decision.decision().name(), null,
            decision.decidedBy(), decision.reason(), JsonSupport.writeList(decision.conditions()),
            decision.separationOfDutiesCheck(), decision.decidedAt(), decision.commandIdempotencyKey()
        );
    }

    public static ApprovalDecision toDomain(ApprovalDecisionJpaEntity entity) {
        return new ApprovalDecision(
            entity.getApprovalDecisionId(), entity.getApprovalRequestId(),
            ApprovalDecision.Outcome.valueOf(entity.getDecision()), entity.getDecidedById(), entity.getDecidedAt(),
            entity.getReason(), JsonSupport.readList(entity.getConditionsJson(), CONDITIONS_TYPE),
            entity.isSeparationOfDutiesResult(), entity.getCommandIdempotencyKey()
        );
    }
}
