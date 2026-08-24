package com.opsmind.identity.domain.decision;

import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An immutable authorization fact (01-domain-model §Immutable Facts;
 * 02-business-invariants: "Deny by default. Allow requires the intersection
 * of trusted principal, active role assignment, resource scope, ownership
 * rule, and assurance requirement"). Once created, never modified — a new
 * decision is produced instead, the same discipline domain 06's own {@code
 * PolicyDecision} uses.
 *
 * <p>This is the immutable-snapshot shape only. The actual evaluation
 * algorithm (role/scope/ownership/assurance intersection) is SPEC-UA-014's
 * job (Authorization Context And Decision API); {@link #denyByDefault} here
 * is the one piece of real behavior SPEC-UA-001 itself supplies — a decision
 * that structurally cannot default to {@code ALLOW} without an explicit,
 * populated evaluation (INV-UA-002).
 */
public final class AuthorizationDecision {

    private final String decisionId;
    private final String decisionKey;
    private final String inputHash;
    private final TenantId tenantId;
    private final String actorId;
    private final String subjectId;
    private final String sessionId;
    private final AuthorizationTarget target;
    private final DecisionEffect effect;
    private final List<String> evaluatedRoles;
    private final List<String> evaluatedScopes;
    private final boolean ownershipSatisfied;
    private final String assuranceLevel;
    private final List<ReasonCode> reasonCodes;
    private final List<String> constraints;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final CorrelationId correlationId;

    private AuthorizationDecision(
        String decisionId, String decisionKey, String inputHash, TenantId tenantId, String actorId, String subjectId,
        String sessionId, AuthorizationTarget target, DecisionEffect effect, List<String> evaluatedRoles,
        List<String> evaluatedScopes, boolean ownershipSatisfied, String assuranceLevel, List<ReasonCode> reasonCodes,
        List<String> constraints, Instant createdAt, Instant expiresAt, CorrelationId correlationId
    ) {
        this.decisionId = Objects.requireNonNull(decisionId, "decisionId");
        this.decisionKey = Objects.requireNonNull(decisionKey, "decisionKey");
        this.inputHash = Objects.requireNonNull(inputHash, "inputHash");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.sessionId = sessionId;
        this.target = Objects.requireNonNull(target, "target");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.evaluatedRoles = List.copyOf(evaluatedRoles == null ? List.of() : evaluatedRoles);
        this.evaluatedScopes = List.copyOf(evaluatedScopes == null ? List.of() : evaluatedScopes);
        this.ownershipSatisfied = ownershipSatisfied;
        this.assuranceLevel = assuranceLevel;
        this.reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
        if (effect != DecisionEffect.ALLOW && this.reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("a non-ALLOW decision must carry at least one reason code");
        }
        this.constraints = List.copyOf(constraints == null ? List.of() : constraints);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    public static AuthorizationDecision of(
        String decisionId, String decisionKey, String inputHash, TenantId tenantId, String actorId, String subjectId,
        String sessionId, AuthorizationTarget target, DecisionEffect effect, List<String> evaluatedRoles,
        List<String> evaluatedScopes, boolean ownershipSatisfied, String assuranceLevel, List<ReasonCode> reasonCodes,
        List<String> constraints, Instant now, Instant expiresAt, CorrelationId correlationId
    ) {
        return new AuthorizationDecision(
            decisionId, decisionKey, inputHash, tenantId, actorId, subjectId, sessionId, target, effect, evaluatedRoles,
            evaluatedScopes, ownershipSatisfied, assuranceLevel, reasonCodes, constraints, now, expiresAt, correlationId
        );
    }

    /**
     * INV-UA-002: the fail-closed decision for "missing context" or "no
     * evaluation performed yet" (02-business-invariants: "Missing context
     * defaults to DENY"). Always {@code DENY} with the given reason.
     */
    public static AuthorizationDecision denyByDefault(
        String decisionId, String decisionKey, String inputHash, TenantId tenantId, String actorId, String subjectId,
        String sessionId, AuthorizationTarget target, ReasonCode reasonCode, Instant now, CorrelationId correlationId
    ) {
        return new AuthorizationDecision(
            decisionId, decisionKey, inputHash, tenantId, actorId, subjectId, sessionId, target, DecisionEffect.DENY,
            List.of(), List.of(), false, null, List.of(reasonCode), List.of(), now, null, correlationId
        );
    }

    public String decisionId() {
        return decisionId;
    }

    public String decisionKey() {
        return decisionKey;
    }

    public String inputHash() {
        return inputHash;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String actorId() {
        return actorId;
    }

    public String subjectId() {
        return subjectId;
    }

    public String sessionId() {
        return sessionId;
    }

    public AuthorizationTarget target() {
        return target;
    }

    public DecisionEffect effect() {
        return effect;
    }

    public List<String> evaluatedRoles() {
        return evaluatedRoles;
    }

    public List<String> evaluatedScopes() {
        return evaluatedScopes;
    }

    public boolean ownershipSatisfied() {
        return ownershipSatisfied;
    }

    public String assuranceLevel() {
        return assuranceLevel;
    }

    public List<ReasonCode> reasonCodes() {
        return reasonCodes;
    }

    public List<String> constraints() {
        return constraints;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }
}
