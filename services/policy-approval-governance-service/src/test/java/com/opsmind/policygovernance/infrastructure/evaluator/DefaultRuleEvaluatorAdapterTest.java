package com.opsmind.policygovernance.infrastructure.evaluator;

import com.opsmind.policygovernance.application.port.RuleEvaluatorPort;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.domain.policy.RuleCondition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class DefaultRuleEvaluatorAdapterTest {

    private final DefaultRuleEvaluatorAdapter evaluator = new DefaultRuleEvaluatorAdapter();

    private RuleEvaluatorPort.Input input() {
        return new RuleEvaluatorPort.Input("user", "user-1", "READ", "ticket", "ticket-1", "tenant-1", "hash-1");
    }

    private static PolicyVersion versionOf(PolicyRule... rules) {
        return PolicyVersion.draft("pv-1", "policy-1", 1, List.of(rules), "author-1");
    }

    @Test
    void neverAllowsWhenThereAreNoRules() {
        PolicyVersion version = versionOf();

        RuleEvaluatorPort.Result result = evaluator.evaluate(version, input());

        assertThat(result.effect()).isNotEqualTo(DecisionEffect.ALLOW);
        assertThat(result.reasonCodes()).contains(ReasonCode.NO_MATCHING_RULE);
    }

    @Test
    void anApprovalRequiredRuleAlwaysForcesRequireApproval() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.HIGH, true, List.of());
        PolicyVersion version = versionOf(rule);

        RuleEvaluatorPort.Result result = evaluator.evaluate(version, input());

        assertThat(result.effect()).isEqualTo(DecisionEffect.REQUIRE_APPROVAL);
        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.reasonCodes()).contains(ReasonCode.POLICY_MATCHED, ReasonCode.HIGH_RISK_REQUIRES_APPROVAL);
    }

    @Test
    void honorsANonApprovalRuleEffect() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.DENY, RiskLevel.LOW, false, List.of());
        PolicyVersion version = versionOf(rule);

        RuleEvaluatorPort.Result result = evaluator.evaluate(version, input());

        assertThat(result.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(result.reasonCodes()).contains(ReasonCode.RULE_DENIED);
    }

    @Test
    void aRuleWithAMatchingEqualsConditionApplies() {
        PolicyRule rule = new PolicyRule(
            "rule-1", List.of(new RuleCondition("actionType", RuleCondition.Operator.EQUALS, "READ")),
            DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of()
        );
        PolicyVersion version = versionOf(rule);

        RuleEvaluatorPort.Result result = evaluator.evaluate(version, input());

        assertThat(result.effect()).isEqualTo(DecisionEffect.ALLOW);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void skipsARuleWhoseConditionDoesNotMatchAndFallsThroughToTheNextRule() {
        PolicyRule mismatch = new PolicyRule(
            "rule-1", List.of(new RuleCondition("actionType", RuleCondition.Operator.EQUALS, "WRITE")),
            DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of()
        );
        PolicyRule catchAll = new PolicyRule("rule-2", List.of(), DecisionEffect.DENY, RiskLevel.HIGH, false, List.of());
        PolicyVersion version = versionOf(mismatch, catchAll);

        RuleEvaluatorPort.Result result = evaluator.evaluate(version, input());

        assertThat(result.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void fallsBackToFailSafeWhenNoRuleMatches() {
        PolicyRule rule = new PolicyRule(
            "rule-1", List.of(new RuleCondition("actionType", RuleCondition.Operator.EQUALS, "WRITE")),
            DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of()
        );
        PolicyVersion version = versionOf(rule);

        RuleEvaluatorPort.Result result = evaluator.evaluate(version, input());

        assertThat(result.effect()).isNotEqualTo(DecisionEffect.ALLOW);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.NO_MATCHING_RULE);
    }

    @Test
    void notEqualsInInGreaterAndLessThanAndMatchesOperatorsAllEvaluate() {
        assertMatches(new RuleCondition("actionType", RuleCondition.Operator.NOT_EQUALS, "WRITE"));
        assertMatches(new RuleCondition("actionType", RuleCondition.Operator.IN, "WRITE, READ"));
        assertMatches(new RuleCondition("actionType", RuleCondition.Operator.NOT_IN, "WRITE, DELETE"));
        assertMatches(new RuleCondition("subjectId", RuleCondition.Operator.MATCHES, "user-.*"));
    }

    private void assertMatches(RuleCondition condition) {
        PolicyRule rule = new PolicyRule("rule-1", List.of(condition), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        RuleEvaluatorPort.Result result = evaluator.evaluate(versionOf(rule), input());
        assertThat(result.effect()).as("condition %s should match", condition).isEqualTo(DecisionEffect.ALLOW);
    }

    @Test
    void anUnknownConditionAttributeThrowsRatherThanSilentlyNotMatching() {
        PolicyRule rule = new PolicyRule(
            "rule-1", List.of(new RuleCondition("riskScore", RuleCondition.Operator.GREATER_THAN_OR_EQUAL, "5")),
            DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of()
        );
        PolicyVersion version = versionOf(rule);

        assertThatThrownBy(() -> evaluator.evaluate(version, input()))
            .as("10-failure-handling: rule parsing failure must propagate, not silently become 'no match'")
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aNonNumericComparisonThrowsRatherThanSilentlyNotMatching() {
        PolicyRule rule = new PolicyRule(
            "rule-1", List.of(new RuleCondition("actionType", RuleCondition.Operator.GREATER_THAN_OR_EQUAL, "5")),
            DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of()
        );
        PolicyVersion version = versionOf(rule);

        assertThatThrownBy(() -> evaluator.evaluate(version, input()))
            .isInstanceOf(RuntimeException.class);
    }
}
