package com.opsmind.policygovernance.application.command;

import com.opsmind.policygovernance.domain.policy.PolicyRule;

import java.util.List;
import java.util.Objects;

/** Input to {@code PolicyAdminService.draft} (04-use-cases §UC-PG-005 step 1). */
public record DraftPolicyCommand(
    String policyId,
    String policyName,
    String scope,
    List<PolicyRule> rules,
    String createdBy,
    String correlationId
) {
    public DraftPolicyCommand {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(policyName, "policyName");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(correlationId, "correlationId");
        rules = List.copyOf(rules == null ? List.of() : rules);
    }
}
