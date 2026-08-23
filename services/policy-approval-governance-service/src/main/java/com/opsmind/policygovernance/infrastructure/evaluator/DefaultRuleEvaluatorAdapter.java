package com.opsmind.policygovernance.infrastructure.evaluator;

import com.opsmind.policygovernance.application.port.RuleEvaluatorPort;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-PG-007 (Rule Evaluator And Risk Mapping): the real condition-matching
 * engine behind {@link RuleEvaluatorPort} — 04-use-cases §UC-PG-001 step 3
 * ("Rule evaluator computes effect, risk, approvalRequired, and
 * constraints"). Rules are tried in {@link PolicyVersion#rules()} order;
 * the first rule whose {@link com.opsmind.policygovernance.domain.policy.RuleCondition}
 * list fully matches (see {@link RuleConditionMatcher}) wins. Risk mapping
 * is a direct pass-through of the matched rule's own {@code riskLevel} —
 * 01-domain-model already models risk as a per-rule authored value, not a
 * derived one, so there is no separate scoring step here.
 *
 * <p>Stays fail-safe exactly as SPEC-PG-001's domain rule requires ("Default
 * allow on policy evaluator failure" is forbidden): if no rule matches,
 * this returns {@code REQUIRE_APPROVAL}/{@code HIGH}/{@code
 * NO_MATCHING_RULE}, never {@code ALLOW}. 10-failure-handling §Policy
 * Evaluation Failure ("if rule parsing fails ... return EVALUATION_FAILED
 * ... do not default allow") is handled one layer up: {@link
 * RuleConditionMatcher} throws on anything it cannot interpret (unknown
 * attribute, null request field, non-numeric comparison), and {@code
 * PolicyDecisionService#evaluate}'s SPEC-PG-006 try/catch turns that into
 * the fail-safe {@code evaluationFailed=true} snapshot.
 */
@Component
public class DefaultRuleEvaluatorAdapter implements RuleEvaluatorPort {

    @Override
    public Result evaluate(PolicyVersion effectiveVersion, Input input) {
        for (PolicyRule rule : effectiveVersion.rules()) {
            if (RuleConditionMatcher.matches(rule, input)) {
                return resultFor(rule);
            }
        }
        return new Result(
            DecisionEffect.REQUIRE_APPROVAL, RiskLevel.HIGH, true,
            List.of(), List.of(ReasonCode.NO_MATCHING_RULE)
        );
    }

    private static Result resultFor(PolicyRule rule) {
        List<ReasonCode> reasonCodes = new ArrayList<>();
        reasonCodes.add(ReasonCode.POLICY_MATCHED);

        // approvalRequired forces REQUIRE_APPROVAL regardless of the rule's
        // authored effect — an established SPEC-PG-001 convention, unchanged
        // by this spec.
        DecisionEffect effect;
        if (rule.approvalRequired()) {
            effect = DecisionEffect.REQUIRE_APPROVAL;
            reasonCodes.add(ReasonCode.HIGH_RISK_REQUIRES_APPROVAL);
        } else {
            effect = rule.effect();
            if (effect == DecisionEffect.DENY) {
                reasonCodes.add(ReasonCode.RULE_DENIED);
            }
        }

        return new Result(effect, rule.riskLevel(), rule.approvalRequired(), rule.constraints(), reasonCodes);
    }
}
