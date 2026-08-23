package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;

import java.util.List;
import java.util.Objects;

/**
 * Port to the rule evaluation engine that turns a policy version + request
 * facts into a governance effect. The real condition-matching engine and
 * risk mapping belong to SPEC-PG-007 (Rule Evaluator And Risk Mapping);
 * {@code infrastructure.evaluator.DefaultRuleEvaluatorAdapter} is a
 * fail-safe placeholder that never defaults to {@code ALLOW} (SPEC-PG-001
 * domain rule: "Default allow on policy evaluator failure" is forbidden).
 */
public interface RuleEvaluatorPort {

    Result evaluate(PolicyVersion effectiveVersion, Input input);

    record Input(
        String subjectType,
        String subjectId,
        String actionType,
        String resourceType,
        String resourceId,
        String tenantId,
        String inputHash
    ) {
        public Input {
            Objects.requireNonNull(subjectType, "subjectType");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(actionType, "actionType");
            Objects.requireNonNull(inputHash, "inputHash");
        }
    }

    record Result(
        DecisionEffect effect,
        RiskLevel riskLevel,
        boolean approvalRequired,
        List<Constraint> constraints,
        List<ReasonCode> reasonCodes
    ) {
        public Result {
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(riskLevel, "riskLevel");
            constraints = List.copyOf(constraints == null ? List.of() : constraints);
            reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
        }
    }
}
