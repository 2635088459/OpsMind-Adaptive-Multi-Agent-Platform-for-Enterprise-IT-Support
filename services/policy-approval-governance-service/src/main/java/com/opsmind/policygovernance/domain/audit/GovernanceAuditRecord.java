package com.opsmind.policygovernance.domain.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * An audit fact for policy evaluation, approval lifecycle, override, admin
 * change, or event publication (01-domain-model §GovernanceAudit).
 *
 * <p>INV-PG-008 ("Every Governance Action Must Be Audited") and the
 * SPEC-PG-001 acceptance criterion "Audit records explain who requested,
 * who approved, which policy applied, and why" are why every field below is
 * mandatory rather than optional. Writing the audit record in the same
 * database transaction as the state change it describes is owned by
 * SPEC-PG-003 (08-transaction-and-outbox); this type only defines the
 * immutable fact shape. {@code integrityHash} is computed by {@code
 * infrastructure.audit.AuditIntegrityAdapter} so a record cannot be altered
 * after the fact without detection.
 */
public record GovernanceAuditRecord(
    String auditRecordId,
    Action action,
    String actorId,
    String sourceDomain,
    String sourceRequestId,
    String policyId,
    String policyVersion,
    String reason,
    String correlationId,
    String causationId,
    String integrityHash,
    Instant recordedAt
) {

    public GovernanceAuditRecord {
        Objects.requireNonNull(auditRecordId, "auditRecordId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /**
     * Returns a copy with {@code integrityHash} set. Used after {@code
     * infrastructure.audit.AuditIntegrityAdapter} computes the hash over the
     * record built with a {@code null} hash, so the hash itself never
     * participates in its own input.
     */
    public GovernanceAuditRecord withIntegrityHash(String hash) {
        return new GovernanceAuditRecord(
            auditRecordId, action, actorId, sourceDomain, sourceRequestId,
            policyId, policyVersion, reason, correlationId, causationId, hash, recordedAt
        );
    }

    /** Governance actions that must always leave an audit trace (INV-PG-008). */
    public enum Action {
        POLICY_DRAFTED,
        POLICY_REVIEWED,
        POLICY_PUBLISHED,
        POLICY_DEPRECATED,
        DECISION_EVALUATED,
        APPROVAL_REQUESTED,
        APPROVAL_GRANTED,
        APPROVAL_DENIED,
        APPROVAL_EXPIRED,
        APPROVAL_CANCELLED,
        OVERRIDE_APPLIED,
        /** SPEC-PG-003 (09-concurrency-and-idempotency): "conflicting payload returns conflict and writes audit." */
        APPROVAL_DECISION_CONFLICT,
        /** SPEC-PG-012: the cancel-command analog of {@link #APPROVAL_DECISION_CONFLICT} — a conflicting retry against an already-cancelled request. */
        APPROVAL_CANCEL_CONFLICT
    }
}
