package com.opsmind.policygovernance.domain.decision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An immutable snapshot of a governance judgment (01-domain-model
 * §PolicyDecision). Once created it is never modified — a new decision is
 * produced instead (01-domain-model: "Once final, a PolicyDecision must not
 * be silently modified"). This is why the type is a record: there are no
 * setters and no mutable collection escapes past construction.
 *
 * <p>INV-PG-002 ("Policy Decisions Must Be Explainable") requires every
 * decision to persist {@code inputHash}, policy id/version, reason codes,
 * constraints, and {@code evaluatedAt} — the compact constructor rejects a
 * decision missing any of those rather than letting an unexplainable
 * decision exist. {@code sourceDomain}/{@code sourceRequestId}/{@code
 * ticketId}/{@code workflowInstanceId} carry the SPEC-PG-002 Core Rule
 * "Facts produced by this spec must be traceable to source domain, source
 * request, actor, policy version, and correlation id" — {@code
 * sourceDomain}/{@code sourceRequestId} are mandatory (every decision comes
 * from somewhere), {@code ticketId}/{@code workflowInstanceId} are optional
 * (only set when the requesting call is itself ticket- or
 * workflow-scoped).
 *
 * <p>{@code evaluationFailed} is SPEC-PG-005's own addition
 * (03-state-machine §Policy Decision State Machine: {@code EVALUATING ->
 * EVALUATION_FAILED} is a distinct terminal outcome from {@code
 * EVALUATING -> DENIED}; api-contract: "conflict, denied, expired,
 * cancelled, and evaluation failed must remain distinguishable"). It is
 * deliberately a separate boolean rather than a fifth {@link DecisionEffect}
 * value, since 01-domain-model's own Value Objects list freezes {@code
 * DecisionEffect} at exactly ALLOW/DENY/REQUIRE_APPROVAL/
 * ALLOW_WITH_CONSTRAINTS. {@code effect} still carries a safe {@code DENY}
 * when {@code evaluationFailed} is {@code true}, so a caller that only reads
 * {@code effect} and ignores this flag still fails closed — INV-PG-001's
 * "default allow on policy evaluator failure is forbidden" holds either way.
 * The state machine's other non-final state, {@code APPROVAL_REQUIRED ->
 * APPROVAL_LINKED}, is deliberately NOT modeled here — "approval lifecycle
 * is represented by ApprovalRequest" (03-state-machine's own words), so
 * linking a decision to the {@code ApprovalRequest} eventually created for
 * it is SPEC-PG-009's job (Approval Request Aggregate), not this one's.
 *
 * <p>{@code degraded} is SPEC-PG-021's own addition (10-failure-handling
 * §Degraded Policy Mode: "When policy evaluator is unavailable ... audit/
 * metric must mark degraded=true"). It is {@code true} only when an
 * effective policy version existed but the evaluator itself threw while
 * applying it ({@link ReasonCode#EVALUATOR_UNAVAILABLE}) — not when no
 * effective version existed at all ({@link ReasonCode#POLICY_VERSION_NOT_FOUND}),
 * which 10-failure-handling's own "Policy Evaluation Failure" section names
 * as a distinct, harder failure than "degraded mode." {@code effect} still
 * carries {@code DENY} either way, so a caller reading only {@code effect}
 * still fails closed regardless of which flag it ignores.
 */
public record PolicyDecision(
    String policyDecisionId,
    String decisionKey,
    String inputHash,
    String subjectType,
    String subjectId,
    String actionType,
    String resourceType,
    String resourceId,
    String tenantId,
    String sourceDomain,
    String sourceRequestId,
    String ticketId,
    String workflowInstanceId,
    DecisionEffect effect,
    RiskLevel riskLevel,
    boolean approvalRequired,
    boolean evaluationFailed,
    List<Constraint> constraints,
    List<ReasonCode> reasonCodes,
    String policyId,
    String policyVersion,
    Instant evaluatedAt,
    Instant expiresAt,
    boolean degraded
) {

    public PolicyDecision {
        Objects.requireNonNull(policyDecisionId, "policyDecisionId");
        Objects.requireNonNull(decisionKey, "decisionKey");
        Objects.requireNonNull(inputHash, "inputHash");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(sourceDomain, "sourceDomain");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        if (reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("a PolicyDecision must bind at least one reason code");
        }
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
        reasonCodes = List.copyOf(reasonCodes);
    }
}
