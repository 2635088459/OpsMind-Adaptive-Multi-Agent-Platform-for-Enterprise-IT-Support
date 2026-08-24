package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Duration;

/**
 * Port for the metrics named in 12-observability §Metrics. Kept as a port
 * (implemented by {@code infrastructure.observability.MicrometerGovernanceMetrics})
 * rather than injecting {@code MeterRegistry} straight into the application
 * services, so application never depends on infrastructure (ArchUnit
 * {@code LayerDependencyTest}). {@code governance_outbox_pending_count} is a
 * gauge self-registered by the adapter against {@code OutboxEventRepository}
 * and has no method here.
 */
public interface GovernanceMetricsPort {

    void recordPolicyDecision(DecisionEffect effect, RiskLevel riskLevel, String sourceDomain, Duration latency);

    void recordPolicyEvaluationFailure();

    /**
     * SPEC-PG-032 (goal: "degraded metrics", 10-failure-handling §Degraded
     * Policy Mode: "audit/metric must mark degraded=true"). Distinct from
     * {@link #recordPolicyEvaluationFailure}: that one fires on every
     * evaluator/version failure regardless of cause ({@code
     * POLICY_VERSION_NOT_FOUND} included), while this one fires only for a
     * decision {@code PolicyDecision#degraded()} actually marks true — so
     * an operator can tell "the evaluator is flaky" apart from "how many
     * decisions degraded mode is actually producing, and with which
     * effect."
     */
    void recordPolicyDegraded(DecisionEffect effect);

    void recordApprovalRequested(ApprovalType approvalType, RiskLevel riskLevel);

    void recordApprovalDecision(ApprovalDecision.Outcome outcome, RiskLevel riskLevel, ApprovalType approvalType, Duration waitTime);

    void recordApprovalExpired();

    void recordPolicyPublished();

    /**
     * SPEC-PG-022: {@code governance_override_total}, deferred by every
     * earlier phase's own note on this port ("no override use case exists
     * yet") until phase-05 built one. {@code action} is {@code "USED"} or
     * {@code "REVOKED"} — {@code domain.approval.ApprovalStatus}'s own
     * override-specific terminal states.
     */
    void recordOverride(String action);
}
