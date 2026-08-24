package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-PG-032: unlike {@link NoOpGovernanceMetrics}, records every {@link
 * #recordPolicyDegraded} call so a test can assert degraded-mode metrics
 * actually fired (and how many times, with which effect) — mirrors {@link
 * FakeMessageBrokerPublisher}'s own "records what happened" shape.
 */
public class RecordingGovernanceMetrics implements GovernanceMetricsPort {

    private final List<DecisionEffect> degradedEffects = new ArrayList<>();

    @Override
    public void recordPolicyDecision(DecisionEffect effect, RiskLevel riskLevel, String sourceDomain, Duration latency) {
    }

    @Override
    public void recordPolicyEvaluationFailure() {
    }

    @Override
    public void recordPolicyDegraded(DecisionEffect effect) {
        degradedEffects.add(effect);
    }

    @Override
    public void recordApprovalRequested(ApprovalType approvalType, RiskLevel riskLevel) {
    }

    @Override
    public void recordApprovalDecision(ApprovalDecision.Outcome outcome, RiskLevel riskLevel, ApprovalType approvalType, Duration waitTime) {
    }

    @Override
    public void recordApprovalExpired() {
    }

    @Override
    public void recordPolicyPublished() {
    }

    @Override
    public void recordOverride(String action) {
    }

    public List<DecisionEffect> degradedEffects() {
        return List.copyOf(degradedEffects);
    }
}
