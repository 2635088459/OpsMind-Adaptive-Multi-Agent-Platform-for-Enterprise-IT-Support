package com.opsmind.policygovernance.domain.decision;

/**
 * The governance effect of a {@link PolicyDecision} (01-domain-model
 * §Value Objects, 03-state-machine §Policy Decision State Machine).
 */
public enum DecisionEffect {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL,
    ALLOW_WITH_CONSTRAINTS
}
