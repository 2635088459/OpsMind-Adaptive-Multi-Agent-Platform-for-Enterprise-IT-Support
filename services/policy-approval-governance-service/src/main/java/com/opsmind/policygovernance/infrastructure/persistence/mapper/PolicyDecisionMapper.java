package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyDecisionJpaEntity;

import java.util.List;

public final class PolicyDecisionMapper {

    private static final TypeReference<List<Constraint>> CONSTRAINTS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ReasonCode>> REASON_CODES_TYPE = new TypeReference<>() {
    };

    private PolicyDecisionMapper() {
    }

    public static PolicyDecisionJpaEntity toEntity(PolicyDecision decision) {
        return new PolicyDecisionJpaEntity(
            decision.policyDecisionId(), decision.decisionKey(), decision.inputHash(), decision.subjectType(),
            decision.subjectId(), decision.actionType(), decision.resourceType(), decision.resourceId(),
            decision.tenantId(), decision.sourceDomain(), decision.sourceRequestId(), decision.ticketId(),
            decision.workflowInstanceId(), decision.effect().name(), decision.riskLevel().name(),
            decision.approvalRequired(), decision.evaluationFailed(), JsonSupport.writeList(decision.constraints()),
            JsonSupport.writeList(decision.reasonCodes()), decision.policyId(), decision.policyVersion(),
            decision.evaluatedAt(), decision.expiresAt(), decision.degraded()
        );
    }

    public static PolicyDecision toDomain(PolicyDecisionJpaEntity entity) {
        return new PolicyDecision(
            entity.getPolicyDecisionId(), entity.getDecisionKey(), entity.getInputHash(), entity.getSubjectType(),
            entity.getSubjectId(), entity.getActionType(), entity.getResourceType(), entity.getResourceId(),
            entity.getTenantId(), entity.getSourceDomain(), entity.getSourceRequestId(), entity.getTicketId(),
            entity.getWorkflowInstanceId(), DecisionEffect.valueOf(entity.getEffect()), RiskLevel.valueOf(entity.getRiskLevel()),
            entity.isApprovalRequired(), entity.isEvaluationFailed(), JsonSupport.readList(entity.getConstraintsJson(), CONSTRAINTS_TYPE),
            JsonSupport.readList(entity.getReasonCodesJson(), REASON_CODES_TYPE), entity.getPolicyId(),
            entity.getPolicyVersion(), entity.getCreatedAt(), entity.getExpiresAt(), entity.isDegraded()
        );
    }
}
