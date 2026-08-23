package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.policy.RuleCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RuleConditionDto(
    @NotBlank String attribute,
    @NotNull RuleCondition.Operator operator,
    @NotBlank String value
) {

    public static RuleConditionDto from(RuleCondition condition) {
        return new RuleConditionDto(condition.attribute(), condition.operator(), condition.value());
    }

    public RuleCondition toDomain() {
        return new RuleCondition(attribute, operator, value);
    }
}
