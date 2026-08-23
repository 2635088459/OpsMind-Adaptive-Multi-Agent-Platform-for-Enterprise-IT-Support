package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.util.List;
import java.util.Objects;

/**
 * An evaluable unit inside a {@link PolicyVersion}, expressing condition,
 * effect, risk, approval requirement, and constraints (01-domain-model
 * §PolicyRule). {@code conditions} is empty for an unconditional
 * ("catch-all") rule; when non-empty every {@link RuleCondition} must match
 * for the rule to apply (implicit AND) — see that type's own javadoc for
 * why interpreting them is SPEC-PG-007's job, not this one's.
 */
public record PolicyRule(
    String ruleId,
    List<RuleCondition> conditions,
    DecisionEffect effect,
    RiskLevel riskLevel,
    boolean approvalRequired,
    List<Constraint> constraints
) {

    public PolicyRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(riskLevel, "riskLevel");
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
    }
}
