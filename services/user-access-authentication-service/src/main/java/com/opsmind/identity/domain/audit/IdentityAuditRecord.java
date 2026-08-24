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
 * columns 07-data-model already names; SPEC-UA-001 leaves them {@code null}
 * — the same "shape present, chain computed later" deferral domain 06's own
 * {@code GovernanceAuditRecord} used until its dedicated spec (SPEC-PG-017)
 * — real hash-chaining here is SPEC-UA-029's job (Identity Security Audit
 * Events).
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
