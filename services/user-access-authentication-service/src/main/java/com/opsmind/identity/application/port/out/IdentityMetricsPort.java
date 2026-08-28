package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.decision.DecisionEffect;

/**
 * SPEC-UA-030 (Identity Metrics Logs And Traces — 12-observability §Key
 * metrics). {@code io.micrometer}/{@code opentelemetry} have been real
 * dependencies since this project's own baseline pom.xml but had zero real
 * usage anywhere in this codebase before this spec (confirmed via grep).
 * 12-observability's own metrics list names roughly a dozen distinct
 * signals; this port covers the subset that maps directly onto an
 * already-built, already-exercised code path in this domain (deny by
 * default; no invented detection) — authorization decisions, role
 * assignment changes, session lifecycle, and step-up outcomes — plus the
 * outbox-lag gauge (self-registered by the adapter, no method here, same
 * "gauge is a live callback" reasoning policy-approval-governance-service's
 * own {@code GovernanceMetricsPort} already documents). Deliberately
 * excludes metrics with no real underlying detection anywhere in this
 * codebase yet: JWT validation latency/cache-hit (owned entirely by
 * Nimbus's own internal JWKS cache, not this domain's own code — the same
 * "cannot verify without a real Keycloak instance" limit SPEC-UA-004/006
 * already hit), rate-limit hits (no rate-limiting mechanism exists
 * anywhere in this codebase), and audit-chain failure (INV-UA-006 is
 * "always audit," never a cryptographic hash chain — nothing to measure).
 * Kept as a port (implemented by {@code
 * infrastructure.observability.MicrometerIdentityMetrics}) rather than
 * injecting {@code MeterRegistry} straight into application services, so
 * application never depends on infrastructure (ArchUnit
 * {@code LayerDependencyTest}).
 */
public interface IdentityMetricsPort {

    void recordAuthorizationDecision(DecisionEffect effect);

    /** {@code action} is {@code "GRANTED"}, {@code "SCHEDULED"}, {@code "REVOKED"}, {@code "CANCELLED"}, {@code "ACTIVATED"}, or {@code "EXPIRED"} — every real RoleAssignment transition {@code ManageRoleAssignmentService} performs. */
    void recordRoleAssignmentChange(String action);

    /** {@code action} is {@code "STARTED"}, {@code "REVOKED"}, or {@code "EXPIRED"}. */
    void recordSessionLifecycle(String action);

    /** {@code outcome} is {@code "REQUESTED"}, {@code "VERIFIED"}, {@code "REJECTED"} (12-observability's own "replay" line — every rejected-evidence attempt, not only a nonce reuse specifically), {@code "CONSUMED"}, {@code "CANCELLED"}, or {@code "EXPIRED"}. */
    void recordStepUpOutcome(String outcome);

    /**
     * SPEC-UA-032 (12-observability: "Keycloak/JWKS latency/error"; 10-failure-handling:
     * "JWKS endpoint unavailable | Use only keys within max-stale ... |
     * Single-flight refresh/reconcile"). Fired exactly once each time
     * {@code SecurityConfig#jwtDecoder}'s own outage-tolerant JWKS source
     * has to fall back to a previously-cached key set because a live fetch
     * failed — this was previously excluded from this port entirely
     * ("owned entirely by Nimbus's own internal JWKS cache, not this
     * domain's own code," per SPEC-UA-030's own javadoc), but the
     * max-stale fallback this spec adds IS this domain's own code, wired
     * through Nimbus's real {@code OutageTolerantJWKSetSource} event hook.
     */
    void recordJwksDegradedFallback();
}
