package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.decision.PolicyDecision;

import java.util.List;
import java.util.Optional;

/** Port for {@link PolicyDecision} snapshot persistence. */
public interface PolicyDecisionRepository {

    PolicyDecision save(PolicyDecision decision);

    Optional<PolicyDecision> findById(String policyDecisionId);

    /** Used for evaluate-request idempotency: a repeated {@code decisionKey} must not produce a conflicting decision. */
    Optional<PolicyDecision> findByDecisionKey(String decisionKey);

    /**
     * SPEC-PG-033 (goal: "poison decision review", 10-failure-handling
     * §Poison Decision: "same request repeatedly crashes evaluator").
     * {@link PolicyDecision#evaluationFailed()} decisions are never
     * modified or retried (decisions are immutable — 01-domain-model:
     * "Once final, a PolicyDecision must not be silently modified"), so
     * this is a review surface, not a repair action: every decision whose
     * evaluator run genuinely failed, for an operator to investigate.
     */
    List<PolicyDecision> findEvaluationFailed();
}
