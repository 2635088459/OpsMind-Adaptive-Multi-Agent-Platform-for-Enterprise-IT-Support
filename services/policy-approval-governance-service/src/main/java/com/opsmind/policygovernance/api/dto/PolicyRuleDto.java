package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PolicyRuleDto(
    @NotBlank String ruleId,
    List<RuleConditionDto> conditions,
    @NotNull DecisionEffect effect,
    @NotNull RiskLevel riskLevel,
    boolean approvalRequired,
    List<ConstraintDto> constraints
) {

    public static PolicyRuleDto from(PolicyRule rule) {
        return new PolicyRuleDto(
            rule.ruleId(), rule.conditions().stream().map(RuleConditionDto::from).toList(),
            rule.effect(), rule.riskLevel(), rule.approvalRequired(),
            rule.constraints().stream().map(ConstraintDto::from).toList()
        );
    }

    public PolicyRule toDomain() {
        List<RuleConditionDto> safeConditions = conditions == null ? List.of() : conditions;
        List<ConstraintDto> safeConstraints = constraints == null ? List.of() : constraints;
        return new PolicyRule(
            ruleId, safeConditions.stream().map(RuleConditionDto::toDomain).toList(), effect, riskLevel,
            approvalRequired, safeConstraints.stream().map(ConstraintDto::toDomain).toList()
        );
    }
}
