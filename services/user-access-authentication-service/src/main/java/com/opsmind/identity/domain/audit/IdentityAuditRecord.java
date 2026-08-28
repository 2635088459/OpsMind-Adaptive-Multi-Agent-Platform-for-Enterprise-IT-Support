package com.opsmind.identity.domain.audit;

import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * An append-only fact about one identity security action (07-data-model
 * §identity_audit_records). Unlike the other aggregates, this type has no
 * state machine — once recorded it is never transitioned, only read.
 *
 * <p>{@code previousHash}/{@code recordHash} are the tamper-evident chain
 * columns 07-data-model already names; SPEC-UA-001 left them {@code null} —
 * the same "shape present, chain computed later" deferral domain 06's own
 * {@code GovernanceAuditRecord} used until its own dedicated spec
 * (SPEC-PG-017). This class's own earlier javadoc pointed at SPEC-UA-029
 * as that deferral's real owner — checked directly against 06-event-contracts/
 * 12-observability's own footers while scoping that spec and found neither
 * claims it; 07-data-model's own footer (which DOES claim SPEC-UA-031) is
 * what actually names these two columns, so real hash-chaining is
 * SPEC-UA-031's job instead — {@link #withHashes} is what a caller (the
 * real {@code AuditPort} adapter, per that spec's own reasoning) uses to
 * seal a freshly-computed chain link onto an otherwise-built record before
 * persisting it.
 */
public final class IdentityAuditRecord {

    private final String auditId;
    private final TenantId tenantId;
    private final IdentityAuditAction action;
    private final String actorRef;
    private final String subjectRef;
    private final String resourceRef;
    private final AuditOutcome outcome;
    private final String reasonCode;
    private final CorrelationId correlationId;
    private final Instant occurredAt;
    private final String previousHash;
    private final String recordHash;

    private IdentityAuditRecord(
        String auditId, TenantId tenantId, IdentityAuditAction action, String actorRef, String subjectRef, String resourceRef,
        AuditOutcome outcome, String reasonCode, CorrelationId correlationId, Instant occurredAt, String previousHash, String recordHash
    ) {
        this.auditId = Objects.requireNonNull(auditId, "auditId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.action = Objects.requireNonNull(action, "action");
        this.actorRef = actorRef;
        this.subjectRef = subjectRef;
        this.resourceRef = resourceRef;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reasonCode = reasonCode;
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.previousHash = previousHash;
        this.recordHash = recordHash;
    }

    public static IdentityAuditRecord record(
        String auditId, TenantId tenantId, IdentityAuditAction action, String actorRef, String subjectRef, String resourceRef,
        AuditOutcome outcome, String reasonCode, CorrelationId correlationId, Instant occurredAt
    ) {
        return new IdentityAuditRecord(auditId, tenantId, action, actorRef, subjectRef, resourceRef, outcome, reasonCode, correlationId, occurredAt, null, null);
    }

    /** Rehydrates a previously-persisted, already-sealed record — the only caller with real {@code previousHash}/{@code recordHash} values to carry. */
    public static IdentityAuditRecord reconstruct(
        String auditId, TenantId tenantId, IdentityAuditAction action, String actorRef, String subjectRef, String resourceRef,
        AuditOutcome outcome, String reasonCode, CorrelationId correlationId, Instant occurredAt, String previousHash, String recordHash
    ) {
        return new IdentityAuditRecord(auditId, tenantId, action, actorRef, subjectRef, resourceRef, outcome, reasonCode, correlationId, occurredAt, previousHash, recordHash);
    }

    /**
     * SPEC-UA-031: seals this otherwise-fully-built record with its own
     * real chain link — {@code previousHash} is the immediately preceding
     * record's own {@code recordHash} (per tenant), {@code recordHash} is
     * this record's own fingerprint over its fact fields plus that
     * {@code previousHash}. Never called by any {@code record(...)}
     * caller directly (every field but the hashes is already final at that
     * point) — only the real {@code AuditPort} adapter, which alone knows
     * "what was the previous write," calls this right before persisting.
     */
    public IdentityAuditRecord withHashes(String previousHash, String recordHash) {
        return new IdentityAuditRecord(auditId, tenantId, action, actorRef, subjectRef, resourceRef, outcome, reasonCode, correlationId, occurredAt, previousHash, recordHash);
    }

    public String auditId() {
        return auditId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public IdentityAuditAction action() {
        return action;
    }

    public String actorRef() {
        return actorRef;
    }

    public String subjectRef() {
        return subjectRef;
    }

    public String resourceRef() {
        return resourceRef;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String previousHash() {
        return previousHash;
    }

    public String recordHash() {
        return recordHash;
    }
}
