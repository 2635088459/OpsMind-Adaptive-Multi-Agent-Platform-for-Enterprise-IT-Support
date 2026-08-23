package com.opsmind.policygovernance.infrastructure.observability;

import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.application.port.OutboxEventRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Real implementation of {@link GovernanceMetricsPort} (12-observability
 * §Metrics). Registers {@code governance_outbox_pending_count} once at
 * construction — a gauge is a live callback, not a value pushed on each
 * event, so it needs no method on the port.
 */
@Component
public class MicrometerGovernanceMetrics implements GovernanceMetricsPort {

    private final MeterRegistry registry;

    public MicrometerGovernanceMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository) {
        this.registry = registry;
        Gauge.builder("governance_outbox_pending_count", outboxEventRepository, OutboxEventRepository::countPending)
            .description("Number of governance outbox_events rows still PENDING dispatch")
            .register(registry);
    }

    @Override
    public void recordPolicyDecision(DecisionEffect effect, RiskLevel riskLevel, String sourceDomain, Duration latency) {
        registry.counter("policy_decision_total", "effect", effect.name(), "riskLevel", riskLevel.name(), "sourceDomain", nullToUnknown(sourceDomain))
            .increment();
        registry.timer("policy_decision_latency_seconds").record(latency);
    }

    @Override
    public void recordPolicyEvaluationFailure() {
        registry.counter("policy_evaluation_failure_total").increment();
    }

    @Override
    public void recordApprovalRequested(ApprovalType approvalType, RiskLevel riskLevel) {
        registry.counter("approval_request_created_total", "approvalType", approvalType.name(), "riskLevel", riskLevel.name()).increment();
    }

    @Override
    public void recordApprovalDecision(ApprovalDecision.Outcome outcome, RiskLevel riskLevel, ApprovalType approvalType, Duration waitTime) {
        registry.counter("approval_decision_total", "decision", outcome.name(), "riskLevel", riskLevel.name()).increment();
        registry.timer("approval_wait_seconds", "approvalType", approvalType.name(), "riskLevel", riskLevel.name()).record(waitTime);
    }

    @Override
    public void recordApprovalExpired() {
        registry.counter("approval_expired_total").increment();
    }

    @Override
    public void recordPolicyPublished() {
        registry.counter("policy_publish_total").increment();
    }

    private static String nullToUnknown(String value) {
        return value == null ? "unknown" : value;
    }
}
