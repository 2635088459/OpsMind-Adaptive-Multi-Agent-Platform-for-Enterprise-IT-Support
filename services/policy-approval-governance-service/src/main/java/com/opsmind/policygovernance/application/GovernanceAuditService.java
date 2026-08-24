package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.port.AuditIntegrityPort;
import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.shared.DomainEvent;
import com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes governance audit facts (INV-PG-008: every governance action must
 * be audited). Per the SPEC-PG-001 domain rule "every governance state
 * transition must write audit/outbox in the same transaction", {@link
 * #record} both appends the audit record and stages the corresponding
 * governance event in one call, so both happen together once SPEC-PG-002
 * adds a real {@code @Transactional} boundary around this method.
 *
 * <p>SPEC-PG-017 (11-security §Tamper-Resistant Audit): {@link #record}
 * looks up the previously-appended record's own hash via {@link
 * GovernanceAuditRepository#findMostRecentIntegrityHash} and folds it into
 * the new record's {@code previousHash} before computing its own {@code
 * integrityHash} — every write extends the chain, not just leaves an
 * isolated fingerprint.
 *
 * <p>SPEC-PG-030 (goal: "governance audit chain queries by
 * ticket/source/decision/approval/policy"): {@link #record} now also takes
 * {@code ticketId}/{@code approvalRequestId}/{@code policyDecisionId} —
 * see {@code GovernanceAuditRecord}'s own javadoc for why — and {@link
 * #findByTicketId}/{@link #findByApprovalRequestId}/{@link
 * #findByPolicyDecisionId}/{@link #findBySourceRequestId}/{@link
 * #findByPolicyId} give the audit trail's own five named query dimensions
 * real methods; only {@link #findByCorrelationId} existed before this spec.
 *
 * <p>SPEC-PG-031 (goal: "Implement audit hash chain/append-only marker,
 * compliance reports, and audit retention"). The hash chain itself
 * (SPEC-PG-017) and "append-only" (no method on {@link
 * GovernanceAuditRepository} has ever allowed updating or deleting a
 * written record's own fact fields) both already existed; what this spec
 * adds is a way to actually prove the chain is intact and to retire old
 * records without deleting them. {@link #verifyChain} walks every record in
 * write order and recomputes each one's hash — the chain was always
 * write-only-verified-never before this; {@link #complianceReport} builds
 * on it with the aggregate counts/date range 11-security's "compliance
 * report" concept needs; {@link #archiveRecordedBefore} is the "retention
 * policy" 11-security §Tamper-Resistant Audit names as the only way an
 * ordinary admin may retire an old record (never delete).
 */
@Service
public class GovernanceAuditService {

    private final GovernanceAuditRepository auditRepository;
    private final AuditIntegrityPort auditIntegrityPort;
    private final OutboxDispatchService outboxDispatchService;
    private final Clock clock;

    public GovernanceAuditService(
        GovernanceAuditRepository auditRepository,
        AuditIntegrityPort auditIntegrityPort,
        OutboxDispatchService outboxDispatchService,
        Clock clock
    ) {
        this.auditRepository = auditRepository;
        this.auditIntegrityPort = auditIntegrityPort;
        this.outboxDispatchService = outboxDispatchService;
        this.clock = clock;
    }

    /** Auto-generates a placeholder event to stage — see the {@code DomainEvent}-accepting overload for actions with a real, versioned event contract. */
    @Transactional
    public GovernanceAuditRecord record(
        GovernanceAuditRecord.Action action,
        String actorId,
        String sourceDomain,
        String sourceRequestId,
        String policyId,
        String policyVersion,
        String reason,
        String correlationId,
        String causationId,
        String ticketId,
        String approvalRequestId,
        String policyDecisionId
    ) {
        return record(
            action, actorId, sourceDomain, sourceRequestId, policyId, policyVersion, reason, correlationId, causationId,
            ticketId, approvalRequestId, policyDecisionId,
            SimpleGovernanceEvent.of("governance.audit." + action.name().toLowerCase() + ".v1", correlationId, causationId)
        );
    }

    /**
     * SPEC-PG-010: lets a caller stage the real, versioned {@link
     * DomainEvent} 06-event-contracts names for this action (e.g. {@code
     * approval.requested.v1}) instead of the generic placeholder the other
     * overload auto-generates — still in the same transaction as the audit
     * write (SPEC-PG-001 domain rule).
     */
    @Transactional
    public GovernanceAuditRecord record(
        GovernanceAuditRecord.Action action,
        String actorId,
        String sourceDomain,
        String sourceRequestId,
        String policyId,
        String policyVersion,
        String reason,
        String correlationId,
        String causationId,
        String ticketId,
        String approvalRequestId,
        String policyDecisionId,
        DomainEvent eventToPublish
    ) {
        String previousHash = auditRepository.findMostRecentIntegrityHash().orElse(null);
        GovernanceAuditRecord unsealed = new GovernanceAuditRecord(
            UUID.randomUUID().toString(), action, actorId, sourceDomain, sourceRequestId,
            policyId, policyVersion, reason, correlationId, causationId, null, clock.instant(), previousHash,
            ticketId, approvalRequestId, policyDecisionId, null
        );
        String integrityHash = auditIntegrityPort.computeIntegrityHash(unsealed);
        GovernanceAuditRecord saved = auditRepository.append(unsealed.withIntegrityHash(integrityHash));
        outboxDispatchService.stage(eventToPublish);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findByCorrelationId(String correlationId) {
        return auditRepository.findByCorrelationId(correlationId);
    }

    /** SPEC-PG-030: "by ticket" — every audit record whose action touched this exact ticket. */
    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findByTicketId(String ticketId) {
        return auditRepository.findByTicketId(ticketId);
    }

    /** SPEC-PG-030: "by approval" — every audit record for this exact ApprovalRequest. */
    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findByApprovalRequestId(String approvalRequestId) {
        return auditRepository.findByApprovalRequestId(approvalRequestId);
    }

    /** SPEC-PG-030: "by decision" — every audit record for this exact PolicyDecision. */
    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findByPolicyDecisionId(String policyDecisionId) {
        return auditRepository.findByPolicyDecisionId(policyDecisionId);
    }

    /** SPEC-PG-030: "by source" — every audit record whose upstream request id matches, regardless of which domain sent it. */
    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findBySourceRequestId(String sourceRequestId) {
        return auditRepository.findBySourceRequestId(sourceRequestId);
    }

    /** SPEC-PG-030: "by policy" — every audit record for this exact policy (across every version). */
    @Transactional(readOnly = true)
    public List<GovernanceAuditRecord> findByPolicyId(String policyId) {
        return auditRepository.findByPolicyId(policyId);
    }

    /**
     * SPEC-PG-031 (goal: "audit hash chain"): walks every record in {@code
     * recordedAt} write order and confirms each one's stored {@code
     * integrityHash} still matches what {@link AuditIntegrityPort} computes
     * from its own fields, and that its {@code previousHash} still equals
     * the prior record's own stored hash — the two checks {@link #record}'s
     * own chain-building relies on, now actually verified back. Stops at
     * the first broken record rather than continuing past it: everything
     * after an already-broken link is unverifiable by definition (its own
     * {@code previousHash} was computed from a link this walk has already
     * shown cannot be trusted).
     */
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyChain() {
        return verifyChain(auditRepository.findAllOrderedByRecordedAt());
    }

    private ChainVerificationResult verifyChain(List<GovernanceAuditRecord> recordsInOrder) {
        int checked = 0;
        String expectedPreviousHash = null;
        String firstBrokenRecordId = null;
        for (GovernanceAuditRecord record : recordsInOrder) {
            checked++;
            boolean hashMatches = auditIntegrityPort.computeIntegrityHash(record).equals(record.integrityHash());
            boolean linkMatches = Objects.equals(record.previousHash(), expectedPreviousHash);
            if (!hashMatches || !linkMatches) {
                firstBrokenRecordId = record.auditRecordId();
                break;
            }
            expectedPreviousHash = record.integrityHash();
        }
        return new ChainVerificationResult(checked, firstBrokenRecordId == null, firstBrokenRecordId);
    }

    /**
     * SPEC-PG-031 (goal: "compliance reports"): a single, self-contained
     * summary of the whole audit trail's health — total/active/archived
     * record counts, the covered date range, a per-{@link
     * GovernanceAuditRecord.Action} breakdown, and the same hash-chain
     * verification {@link #verifyChain} exposes on its own. Fetches the
     * full record set once and reuses it for both the counts and the chain
     * walk, rather than querying twice.
     */
    @Transactional(readOnly = true)
    public ComplianceReport complianceReport() {
        List<GovernanceAuditRecord> records = auditRepository.findAllOrderedByRecordedAt();
        int archived = 0;
        Map<GovernanceAuditRecord.Action, Long> countsByAction = new EnumMap<>(GovernanceAuditRecord.Action.class);
        for (GovernanceAuditRecord record : records) {
            if (record.archivedAt() != null) {
                archived++;
            }
            countsByAction.merge(record.action(), 1L, Long::sum);
        }
        Instant oldest = records.isEmpty() ? null : records.get(0).recordedAt();
        Instant newest = records.isEmpty() ? null : records.get(records.size() - 1).recordedAt();
        return new ComplianceReport(
            records.size(), records.size() - archived, archived, oldest, newest, countsByAction, verifyChain(records)
        );
    }

    /**
     * SPEC-PG-031 (11-security §Tamper-Resistant Audit: "Ordinary admins
     * cannot delete audit records; they may only be archived by retention
     * policy"). Marks every record older than {@code retentionDays} as
     * archived and, only when at least one record was actually touched,
     * writes its own {@code AUDIT_RECORDS_ARCHIVED} audit entry — mirrors
     * {@code ApprovalExpiryService#expireDue}'s own precedent of staying
     * silent on a no-op run rather than auditing "0 records archived" every
     * time an external scheduler finds nothing to do.
     */
    @Transactional
    public int archiveRecordedBefore(int retentionDays, String actorId, String reason, String correlationId) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        int archivedCount = auditRepository.archiveRecordedBefore(cutoff, now);
        if (archivedCount > 0) {
            record(
                GovernanceAuditRecord.Action.AUDIT_RECORDS_ARCHIVED, actorId, "06", null, null, null,
                reason + " (" + archivedCount + " record(s) recorded before " + cutoff + ")",
                correlationId, null, null, null, null
            );
        }
        return archivedCount;
    }

    /**
     * SPEC-PG-031: {@code firstBrokenRecordId} is {@code null} when {@code
     * intact} is {@code true}; {@code recordsChecked} counts every record
     * examined up to and including the broken one (or every record, when
     * the chain is intact).
     */
    public record ChainVerificationResult(int recordsChecked, boolean intact, String firstBrokenRecordId) {
    }

    /** SPEC-PG-031: {@code oldestRecordedAt}/{@code newestRecordedAt} are {@code null} only when {@code totalRecords} is 0. */
    public record ComplianceReport(
        int totalRecords,
        int activeRecords,
        int archivedRecords,
        Instant oldestRecordedAt,
        Instant newestRecordedAt,
        Map<GovernanceAuditRecord.Action, Long> countsByAction,
        ChainVerificationResult chainVerification
    ) {
    }
}
