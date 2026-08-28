package com.opsmind.identity.infrastructure.observability;

import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.application.port.out.OutboxEventRepository;
import com.opsmind.identity.domain.decision.DecisionEffect;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Real implementation of {@link IdentityMetricsPort} (SPEC-UA-030;
 * 12-observability §Key metrics). Registers {@code
 * identity_outbox_pending_count} once at construction — a gauge is a live
 * callback, not a value pushed on each event, so it needs no method on the
 * port (mirrors policy-approval-governance-service's own {@code
 * MicrometerGovernanceMetrics} identical reasoning for its own {@code
 * governance_outbox_pending_count}).
 */
@Component
public class MicrometerIdentityMetrics implements IdentityMetricsPort {

    private final MeterRegistry registry;

    public MicrometerIdentityMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository) {
        this.registry = registry;
        Gauge.builder("identity_outbox_pending_count", outboxEventRepository, OutboxEventRepository::countPending)
            .description("Number of identity outbox_events rows still PENDING dispatch")
            .register(registry);
    }

    @Override
    public void recordAuthorizationDecision(DecisionEffect effect) {
        registry.counter("identity_authorization_decision_total", "effect", effect.name()).increment();
    }

    @Override
    public void recordRoleAssignmentChange(String action) {
        registry.counter("identity_role_assignment_total", "action", action).increment();
    }

    @Override
    public void recordSessionLifecycle(String action) {
        registry.counter("identity_session_total", "action", action).increment();
    }

    @Override
    public void recordStepUpOutcome(String outcome) {
        registry.counter("identity_step_up_total", "outcome", outcome).increment();
    }

    @Override
    public void recordJwksDegradedFallback() {
        registry.counter("identity_jwks_degraded_fallback_total").increment();
    }
}
