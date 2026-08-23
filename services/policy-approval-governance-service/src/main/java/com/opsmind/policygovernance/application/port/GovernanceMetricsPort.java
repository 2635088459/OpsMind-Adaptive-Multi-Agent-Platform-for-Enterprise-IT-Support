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
 * and has no method here. {@code governance_override_total} has no method
 * here either — no override use case exists yet (phase-05); adding it now
 * would be a metric with no real caller.
 */
public interface GovernanceMetricsPort {

    void recordPolicyDecision(DecisionEffect effect, RiskLevel riskLevel, String sourceDomain, Duration latency);

    void recordPolicyEvaluationFailure();

    void recordApprovalRequested(ApprovalType approvalType, RiskLevel riskLevel);

    void recordApprovalDecision(ApprovalDecision.Outcome outcome, RiskLevel riskLevel, ApprovalType approvalType, Duration waitTime);

    void recordApprovalExpired();

    void recordPolicyPublished();
}
