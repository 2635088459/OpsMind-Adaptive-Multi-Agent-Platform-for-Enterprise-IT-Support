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
 *
 * <p>{@code previousHash} is SPEC-PG-017's own addition (11-security
 * §Tamper-Resistant Audit: "Audit record should include hash chain or
 * append-only marker"): the {@code integrityHash} of the most recently
 * appended record at write time, folded into this record's own {@code
 * integrityHash} computation by {@code
 * infrastructure.audit.SimpleAuditIntegrityAdapter} — altering or deleting
 * an earlier record breaks every later link, not just that one record's own
 * hash, which a bare per-record hash cannot detect. {@code null} only for
 * the very first record this service ever appends. {@code
 * application.GovernanceAuditService#record} looks the previous link up via
 * {@code application.port.GovernanceAuditRepository#findMostRecentIntegrityHash}
 * — see that method's own javadoc for the ordering guarantee this chain
 * relies on and does not claim beyond.
 *
 * <p>{@code ticketId}/{@code approvalRequestId}/{@code policyDecisionId}
 * are SPEC-PG-030's own addition (goal: "governance audit chain queries by
 * ticket/source/decision/approval/policy") — before this spec, {@code
 * sourceRequestId} was the only per-action business key on an audit
 * record, and it means something DIFFERENT depending on {@code action}
 * (the decision key for {@code DECISION_EVALUATED}, the upstream
 * request id for approval actions), so there was no way to query "every
 * audit record touching this exact ticket/approval request/policy
 * decision" directly. All three are nullable: an action only sets the
 * ones that genuinely apply to it (e.g. a policy action sets none of
 * them; an approval action sets {@code ticketId}/{@code approvalRequestId}
 * when the request itself carries them).
 *
 * <p>{@code archivedAt} is SPEC-PG-031's own addition (11-security
 * §Tamper-Resistant Audit: "Ordinary admins cannot delete audit records;
 * they may only be archived by retention policy") — {@code null} for a
 * record that has never been archived, set to the instant a retention run
 * archived it otherwise. Deliberately excluded from {@code
 * infrastructure.audit.SimpleAuditIntegrityAdapter}'s hash computation (see
 * that class's own javadoc): archiving is a retention-policy action on an
 * already-true fact, not a change to the fact itself, so it must not
 * retroactively look like tampering against a hash computed before the
 * record was ever archived. There is still no way to delete a record or
 * modify any other field once written — {@code
 * application.port.GovernanceAuditRepository} only ever exposes {@code
 * append} and {@code archiveRecordedBefore}, and the latter can only ever
 * set this one field.
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
    Instant recordedAt,
    String previousHash,
    String ticketId,
    String approvalRequestId,
    String policyDecisionId,
    Instant archivedAt
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
            policyId, policyVersion, reason, correlationId, causationId, hash, recordedAt, previousHash,
            ticketId, approvalRequestId, policyDecisionId, archivedAt
        );
    }

    /** SPEC-PG-031: returns a copy with {@code archivedAt} set — the only field a retention run is ever allowed to change on a written record. */
    public GovernanceAuditRecord withArchivedAt(Instant archivedAt) {
        return new GovernanceAuditRecord(
            auditRecordId, action, actorId, sourceDomain, sourceRequestId,
            policyId, policyVersion, reason, correlationId, causationId, integrityHash, recordedAt, previousHash,
            ticketId, approvalRequestId, policyDecisionId, archivedAt
        );
    }

    /** Governance actions that must always leave an audit trace (INV-PG-008). */
    public enum Action {
        POLICY_DRAFTED,
        POLICY_REVIEWED,
        POLICY_PUBLISHED,
        POLICY_DEPRECATED,
        /** SPEC-PG-020: written when publishing a new version automatically supersedes the policy's previously {@code PUBLISHED} version. */
        POLICY_SUPERSEDED,
        /** SPEC-PG-020: the terminal {@code DEPRECATED -> ARCHIVED} transition (03-state-machine). */
        POLICY_ARCHIVED,
        DECISION_EVALUATED,
        APPROVAL_REQUESTED,
        APPROVAL_GRANTED,
        APPROVAL_DENIED,
        APPROVAL_EXPIRED,
        APPROVAL_CANCELLED,
        /** SPEC-PG-022: written when an approved {@code POLICY_OVERRIDE} request is actually exercised ({@code APPROVED -> USED}). */
        OVERRIDE_APPLIED,
        /** SPEC-PG-022: written when an approved {@code POLICY_OVERRIDE} request is revoked before use ({@code APPROVED -> REVOKED}). */
        OVERRIDE_REVOKED,
        /** SPEC-PG-003 (09-concurrency-and-idempotency): "conflicting payload returns conflict and writes audit." */
        APPROVAL_DECISION_CONFLICT,
        /** SPEC-PG-012: the cancel-command analog of {@link #APPROVAL_DECISION_CONFLICT} — a conflicting retry against an already-cancelled request. */
        APPROVAL_CANCEL_CONFLICT,
        /** SPEC-PG-022: the use-command analog of {@link #APPROVAL_DECISION_CONFLICT} — a conflicting retry against an already-used override. */
        OVERRIDE_USE_CONFLICT,
        /** SPEC-PG-022: the revoke-command analog of {@link #APPROVAL_DECISION_CONFLICT} — a conflicting retry against an already-revoked override. */
        OVERRIDE_REVOKE_CONFLICT,
        /** SPEC-PG-024 (10-failure-handling §Recovery: "reschedule poison review"): an admin requeues a dead-lettered outbox event for a fresh retry. */
        OUTBOX_EVENT_REQUEUED,
        /** SPEC-PG-031 (11-security §Tamper-Resistant Audit: "may only be archived by retention policy"): a retention run archived one or more older records. */
        AUDIT_RECORDS_ARCHIVED,
        /** SPEC-PG-034 (goal: "admin-safe repair flow for governance event replay/backfill"): an admin cleared a {@code processed_events} dedup marker so a redelivered event is accepted as new again. */
        PROCESSED_EVENT_BACKFILLED
    }
}
