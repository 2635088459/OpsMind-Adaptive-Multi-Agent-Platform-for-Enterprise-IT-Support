package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.decision.PolicyDecision;

import java.util.Optional;

/** Port for {@link PolicyDecision} snapshot persistence. */
public interface PolicyDecisionRepository {

    PolicyDecision save(PolicyDecision decision);

    Optional<PolicyDecision> findById(String policyDecisionId);

    /** Used for evaluate-request idempotency: a repeated {@code decisionKey} must not produce a conflicting decision. */
    Optional<PolicyDecision> findByDecisionKey(String decisionKey);
}
