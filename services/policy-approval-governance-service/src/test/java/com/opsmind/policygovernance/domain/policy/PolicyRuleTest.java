package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PolicyRuleTest {

    @Test
    void aRuleWithNoConditionsIsUnconditional() {
        PolicyRule rule = new PolicyRule("rule-1", null, DecisionEffect.ALLOW, RiskLevel.LOW, false, null);

        assertThat(rule.conditions()).isEmpty();
        assertThat(rule.constraints()).isEmpty();
    }

    @Test
    void conditionsAreNotModifiableAfterConstruction() {
        List<RuleCondition> mutable = new ArrayList<>(List.of(
            new RuleCondition("actionType", RuleCondition.Operator.EQUALS, "DELETE")
        ));
        PolicyRule rule = new PolicyRule("rule-1", mutable, DecisionEffect.DENY, RiskLevel.HIGH, false, List.of());
        mutable.add(new RuleCondition("resourceType", RuleCondition.Operator.EQUALS, "PRODUCTION"));

        assertThat(rule.conditions()).hasSize(1);
        assertThatThrownBy(() -> rule.conditions().add(new RuleCondition("x", RuleCondition.Operator.EQUALS, "y")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresRuleIdEffectAndRiskLevel() {
        assertThatThrownBy(() -> new PolicyRule(null, List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyRule("rule-1", List.of(), null, RiskLevel.LOW, false, List.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, null, false, List.of()))
            .isInstanceOf(NullPointerException.class);
    }
}
