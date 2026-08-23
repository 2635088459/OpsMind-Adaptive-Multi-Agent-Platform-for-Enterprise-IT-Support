package com.opsmind.policygovernance.domain.decision;

/**
 * Machine-readable governance reason (01-domain-model §Value Objects).
 * Every {@link PolicyDecision} and {@code ApprovalDecision} must bind at
 * least one, per INV-PG-002 ("Policy Decisions Must Be Explainable") and
 * the SPEC-PG-001 domain rule that decisions bind "reason codes".
 *
 * <p>This set covers the walking-skeleton evaluator (see {@code
 * infrastructure.evaluator.DefaultRuleEvaluatorAdapter}); the full rule
 * evaluator owned by SPEC-PG-007 may extend it.
 */
public enum ReasonCode {
    POLICY_MATCHED,
    NO_MATCHING_RULE,
    RULE_DENIED,
    HIGH_RISK_REQUIRES_APPROVAL,
    SEPARATION_OF_DUTIES_VIOLATION,
    POLICY_VERSION_NOT_FOUND,
    INPUT_HASH_MISMATCH,
    EVALUATOR_UNAVAILABLE,
    REQUEST_EXPIRED,
    DUPLICATE_REQUEST,
    OVERRIDE_APPLIED,
    CONSTRAINT_APPLIED
}
