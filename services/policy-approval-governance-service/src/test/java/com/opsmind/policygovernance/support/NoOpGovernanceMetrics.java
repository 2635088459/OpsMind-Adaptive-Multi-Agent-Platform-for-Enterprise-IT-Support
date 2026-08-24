package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Duration;

/** No-op test double for {@link GovernanceMetricsPort} — application-layer unit tests don't assert on metrics. */
public class NoOpGovernanceMetrics implements GovernanceMetricsPort {

    @Override
    public void recordPolicyDecision(DecisionEffect effect, RiskLevel riskLevel, String sourceDomain, Duration latency) {
    }

    @Override
    public void recordPolicyEvaluationFailure() {
    }

    @Override
    public void recordPolicyDegraded(DecisionEffect effect) {
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
}
