package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.domain.decision.DecisionEffect;

import java.util.ArrayList;
import java.util.List;

/** Fast, dependency-free application-service unit-test double for {@link IdentityMetricsPort}. Real metrics are {@code MicrometerIdentityMetrics} (SPEC-UA-030). */
public class InMemoryIdentityMetricsPort implements IdentityMetricsPort {

    private final List<String> authorizationDecisions = new ArrayList<>();
    private final List<String> roleAssignmentChanges = new ArrayList<>();
    private final List<String> sessionLifecycleEvents = new ArrayList<>();
    private final List<String> stepUpOutcomes = new ArrayList<>();
    private int jwksDegradedFallbackCount;

    @Override
    public void recordAuthorizationDecision(DecisionEffect effect) {
        authorizationDecisions.add(effect.name());
    }

    @Override
    public void recordRoleAssignmentChange(String action) {
        roleAssignmentChanges.add(action);
    }

    @Override
    public void recordSessionLifecycle(String action) {
        sessionLifecycleEvents.add(action);
    }

    @Override
    public void recordStepUpOutcome(String outcome) {
        stepUpOutcomes.add(outcome);
    }

    @Override
    public void recordJwksDegradedFallback() {
        jwksDegradedFallbackCount++;
    }

    public int jwksDegradedFallbackCount() {
        return jwksDegradedFallbackCount;
    }

    public List<String> authorizationDecisions() {
        return authorizationDecisions;
    }

    public List<String> roleAssignmentChanges() {
        return roleAssignmentChanges;
    }

    public List<String> sessionLifecycleEvents() {
        return sessionLifecycleEvents;
    }

    public List<String> stepUpOutcomes() {
        return stepUpOutcomes;
    }
}
