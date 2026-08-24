package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.domain.decision.DecisionEffect;

import java.time.Instant;
import java.util.List;

public record AuthorizationDecisionView(
    String decisionId,
    String subjectId,
    String action,
    String resourceType,
    String resourceId,
    DecisionEffect effect,
    List<String> evaluatedRoles,
    List<String> reasonCodes,
    Instant createdAt
) {
    public static AuthorizationDecisionView from(AuthorizationDecision d) {
        return new AuthorizationDecisionView(
            d.decisionId(), d.subjectId(), d.target().action(), d.target().resourceType(), d.target().resourceId(),
            d.effect(), d.evaluatedRoles(), d.reasonCodes().stream().map(r -> r.value()).toList(), d.createdAt()
        );
    }
}
