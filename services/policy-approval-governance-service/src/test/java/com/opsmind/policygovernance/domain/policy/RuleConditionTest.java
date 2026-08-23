package com.opsmind.policygovernance.domain.policy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class RuleConditionTest {

    @Test
    void requiresAttributeOperatorAndValue() {
        assertThatThrownBy(() -> new RuleCondition(null, RuleCondition.Operator.EQUALS, "READ"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RuleCondition("actionType", null, "READ"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RuleCondition("actionType", RuleCondition.Operator.EQUALS, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void carriesItsDeclaredShape() {
        RuleCondition condition = new RuleCondition("riskLevel", RuleCondition.Operator.GREATER_THAN_OR_EQUAL, "HIGH");

        assertThat(condition.attribute()).isEqualTo("riskLevel");
        assertThat(condition.operator()).isEqualTo(RuleCondition.Operator.GREATER_THAN_OR_EQUAL);
        assertThat(condition.value()).isEqualTo("HIGH");
    }
}
