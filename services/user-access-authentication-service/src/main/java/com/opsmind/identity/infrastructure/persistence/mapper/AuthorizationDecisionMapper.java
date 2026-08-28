package com.opsmind.identity.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.decision.ReasonCode;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.AuthorizationDecisionJpaEntity;

import java.util.List;

public final class AuthorizationDecisionMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ReasonCode>> REASON_CODE_LIST = new TypeReference<>() {
    };

    private AuthorizationDecisionMapper() {
    }

    public static AuthorizationDecisionJpaEntity toEntity(AuthorizationDecision decision) {
        return new AuthorizationDecisionJpaEntity(
            decision.decisionId(), decision.decisionKey(), decision.inputHash(), decision.tenantId().value(), decision.actorId(),
            decision.subjectId(), decision.sessionId(), decision.target().action(), decision.target().resourceType(),
            decision.target().resourceId(), decision.effect().name(), JsonSupport.writeList(decision.evaluatedRoles()),
            JsonSupport.writeList(decision.evaluatedScopes()), decision.ownershipSatisfied(), decision.assuranceLevel(),
            JsonSupport.writeList(decision.reasonCodes()), JsonSupport.writeList(decision.constraints()), decision.createdAt(),
            decision.expiresAt(), decision.correlationId().value()
        );
    }

    public static AuthorizationDecision toDomain(AuthorizationDecisionJpaEntity entity) {
        AuthorizationTarget target = new AuthorizationTarget(entity.getAction(), entity.getResourceType(), entity.getResourceId());
        return AuthorizationDecision.of(
            entity.getDecisionId(), entity.getDecisionKey(), entity.getInputHash(), new TenantId(entity.getTenantId()), entity.getActorId(),
            entity.getSubjectId(), entity.getSessionId(), target, DecisionEffect.valueOf(entity.getEffect()),
            JsonSupport.readList(entity.getEvaluatedRolesJson(), STRING_LIST), JsonSupport.readList(entity.getEvaluatedScopesJson(), STRING_LIST),
            entity.isOwnershipSatisfied(), entity.getAssuranceLevel(), JsonSupport.readList(entity.getReasonCodesJson(), REASON_CODE_LIST),
            JsonSupport.readList(entity.getConstraintsJson(), STRING_LIST), entity.getCreatedAt(), entity.getExpiresAt(),
            new CorrelationId(entity.getCorrelationId())
        );
    }
}
