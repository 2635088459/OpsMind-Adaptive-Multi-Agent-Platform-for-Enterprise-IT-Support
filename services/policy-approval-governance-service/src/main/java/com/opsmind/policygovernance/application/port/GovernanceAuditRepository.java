package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for append-only {@link GovernanceAuditRecord} persistence — never
 * updated or deleted (INV-PG-008). SPEC-PG-031: {@link
 * #archiveRecordedBefore} is the one deliberate exception — see its own
 * javadoc for why it does not break the "never updated" guarantee.
 */
public interface GovernanceAuditRepository {

    GovernanceAuditRecord append(GovernanceAuditRecord record);

    List<GovernanceAuditRecord> findByCorrelationId(String correlationId);

    /** SPEC-PG-030 (goal: "queries by ticket/source/decision/approval/policy"). */
    List<GovernanceAuditRecord> findByTicketId(String ticketId);

    List<GovernanceAuditRecord> findByApprovalRequestId(String approvalRequestId);

    List<GovernanceAuditRecord> findByPolicyDecisionId(String policyDecisionId);

    List<GovernanceAuditRecord> findBySourceRequestId(String sourceRequestId);

    List<GovernanceAuditRecord> findByPolicyId(String policyId);

    /**
     * SPEC-PG-017: the {@code integrityHash} of the most recently appended
     * record (by {@code recordedAt}), used as the next record's {@code
     * previousHash} to link the hash chain — see {@code
     * domain.audit.GovernanceAuditRecord}'s own javadoc. Ordered by {@code
     * recordedAt}, not a dedicated sequence: this is a best-effort chain, not
     * a strictly serialized one — two audit writes committing in the same
     * instant could theoretically read the same "most recent" record and
     * both link to it, which a fully gap-free chain (a DB sequence plus a
     * locked chain-head row) would prevent at the cost of serializing every
     * governance write in the service through one lock. That stronger
     * guarantee is not required by this spec's own acceptance criteria and
     * is not implemented here.
     */
    Optional<String> findMostRecentIntegrityHash();

    /**
     * SPEC-PG-031 (goal: "compliance reports"): every record ordered oldest
     * first — the full walk order both {@code
     * application.GovernanceAuditService#verifyChain} (hash-chain
     * verification must walk the chain in the same order it was built) and
     * {@code #complianceReport} (aggregate counts/date range) need.
     */
    List<GovernanceAuditRecord> findAllOrderedByRecordedAt();

    /**
     * SPEC-PG-031 (11-security §Tamper-Resistant Audit: "Ordinary admins
     * cannot delete audit records; they may only be archived by retention
     * policy"). Marks every not-yet-archived record with {@code recordedAt}
     * strictly before {@code cutoff} as archived and returns how many rows
     * were touched. This is the one method on this port that updates an
     * already-appended row — it does not weaken the "never updated"
     * guarantee INV-PG-008 relies on, because it can only ever set {@code
     * archivedAt}: every other column is immutable once written, exactly as
     * {@code SimpleAuditIntegrityAdapter} assumes when it deliberately
     * excludes {@code archivedAt} from the hash computation. {@code
     * archivedAt} — the instant to stamp every touched row with — is passed
     * in rather than computed here, matching {@code
     * OutboxEventRepository#requeue}'s own precedent: the port stays a pure
     * persistence boundary, and the caller (which owns the injected {@code
     * Clock}) supplies "now".
     */
    int archiveRecordedBefore(Instant cutoff, Instant archivedAt);
}
