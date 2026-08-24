package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-PG-017 (11-security §Tamper-Resistant Audit: "Audit record should
 * include hash chain or append-only marker"). {@link GovernanceAuditService}
 * previously had no dedicated test class — every prior spec's coverage of
 * it was incidental, exercised only through {@code ApprovalServiceTest}/
 * {@code PolicyAdminServiceTest} calling {@code record()} indirectly.
 */
@Tag("unit")
class GovernanceAuditServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryGovernanceAuditRepository auditRepository = new InMemoryGovernanceAuditRepository();
    private final GovernanceAuditService service = new GovernanceAuditService(
        auditRepository, new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(new InMemoryOutboxEventRepository(), new FakeMessageBrokerPublisher(), clock), clock
    );

    @Test
    void theVeryFirstRecordHasNoPreviousHash() {
        GovernanceAuditRecord first = service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null,
            null, null, null
        );

        assertThat(first.previousHash()).isNull();
        assertThat(first.integrityHash()).isNotBlank();
    }

    /**
     * Each subsequent record links to the previous one's own {@code
     * integrityHash} — the mechanism that turns independent per-record
     * hashes into an actual chain (see {@code SimpleAuditIntegrityAdapter}'s
     * own javadoc for why {@code previousHash} is folded into the hash
     * itself, not just carried as a separate unverified field).
     */
    @Test
    void eachSubsequentRecordChainsToThePreviousRecordsIntegrityHash() {
        GovernanceAuditRecord first = service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null,
            null, null, null
        );
        GovernanceAuditRecord second = service.record(
            GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-2", "tool-gateway",
            "src-req-1", null, null, "approval granted", "corr-1", null,
            null, null, null
        );
        GovernanceAuditRecord third = service.record(
            GovernanceAuditRecord.Action.APPROVAL_EXPIRED, "system", "tool-gateway",
            "src-req-2", null, null, "approval expired", "corr-2", null,
            null, null, null
        );

        assertThat(second.previousHash()).isEqualTo(first.integrityHash());
        assertThat(third.previousHash()).isEqualTo(second.integrityHash());
        assertThat(third.previousHash()).isNotEqualTo(first.integrityHash());
    }

    /** SPEC-PG-030 (goal: "by ticket"): finds only records whose {@code ticketId} matches. */
    @Test
    void findByTicketIdReturnsOnlyRecordsCarryingThatTicket() {
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null,
            "ticket-1", "ar-1", null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-2", "tool-gateway",
            "src-req-2", null, null, "approval requested", "corr-2", null,
            "ticket-2", "ar-2", null
        );

        assertThat(service.findByTicketId("ticket-1"))
            .extracting(GovernanceAuditRecord::approvalRequestId).containsExactly("ar-1");
        assertThat(service.findByTicketId("no-such-ticket")).isEmpty();
    }

    /** SPEC-PG-030 (goal: "by approval"): finds only records for that exact ApprovalRequest. */
    @Test
    void findByApprovalRequestIdReturnsOnlyRecordsForThatApprovalRequest() {
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null,
            "ticket-1", "ar-1", null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-2", "tool-gateway",
            "src-req-2", null, null, "approval granted", "corr-2", null,
            "ticket-2", "ar-2", null
        );

        assertThat(service.findByApprovalRequestId("ar-1"))
            .extracting(GovernanceAuditRecord::action).containsExactly(GovernanceAuditRecord.Action.APPROVAL_REQUESTED);
    }

    /** SPEC-PG-030 (goal: "by decision"): finds only records for that exact PolicyDecision. */
    @Test
    void findByPolicyDecisionIdReturnsOnlyRecordsForThatDecision() {
        service.record(
            GovernanceAuditRecord.Action.DECISION_EVALUATED, "actor-1", "06",
            "decision-key-1", "policy-1", "1", "decision evaluated", "corr-1", null,
            null, null, "pd-1"
        );
        service.record(
            GovernanceAuditRecord.Action.DECISION_EVALUATED, "actor-2", "06",
            "decision-key-2", "policy-1", "1", "decision evaluated", "corr-2", null,
            null, null, "pd-2"
        );

        assertThat(service.findByPolicyDecisionId("pd-1"))
            .extracting(GovernanceAuditRecord::correlationId).containsExactly("corr-1");
    }

    /** SPEC-PG-030 (goal: "by source"): finds every record whose upstream request id matches, across actions. */
    @Test
    void findBySourceRequestIdReturnsOnlyRecordsForThatSourceRequest() {
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-shared", null, null, "approval requested", "corr-1", null,
            null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-2", "tool-gateway",
            "src-req-shared", null, null, "approval granted", "corr-2", null,
            null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-3", "tool-gateway",
            "src-req-other", null, null, "approval requested", "corr-3", null,
            null, null, null
        );

        assertThat(service.findBySourceRequestId("src-req-shared")).hasSize(2);
    }

    /** SPEC-PG-030 (goal: "by policy"): finds every record for that exact policy, across versions. */
    @Test
    void findByPolicyIdReturnsOnlyRecordsForThatPolicy() {
        service.record(
            GovernanceAuditRecord.Action.POLICY_DRAFTED, "actor-1", "06",
            "policy-1", "policy-1", "1", "policy version drafted", "corr-1", null,
            null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.POLICY_PUBLISHED, "actor-2", "06",
            "policy-1", "policy-1", "2", "policy version published", "corr-2", null,
            null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.POLICY_DRAFTED, "actor-3", "06",
            "policy-2", "policy-2", "1", "policy version drafted", "corr-3", null,
            null, null, null
        );

        assertThat(service.findByPolicyId("policy-1")).hasSize(2);
    }

    /** SPEC-PG-031 (goal: "audit hash chain"): an untampered chain built entirely through {@link GovernanceAuditService#record} verifies intact. */
    @Test
    void verifyChainReportsIntactForAnUntamperedChain() {
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null, null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-2", "tool-gateway",
            "src-req-1", null, null, "approval granted", "corr-1", null, null, null, null
        );

        GovernanceAuditService.ChainVerificationResult result = service.verifyChain();

        assertThat(result.intact()).isTrue();
        assertThat(result.recordsChecked()).isEqualTo(2);
        assertThat(result.firstBrokenRecordId()).isNull();
    }

    /**
     * SPEC-PG-031: a record whose stored {@code integrityHash} no longer
     * matches what {@link com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter}
     * would recompute from its own fields — simulating a row edited directly
     * in the database, bypassing {@link GovernanceAuditService#record}
     * entirely — is exactly what the chain must catch.
     */
    @Test
    void verifyChainDetectsATamperedIntegrityHash() {
        GovernanceAuditRecord genuine = service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null, null, null, null
        );
        GovernanceAuditRecord tampered = new GovernanceAuditRecord(
            "tampered-1", GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-2", "tool-gateway",
            "src-req-1", null, null, "approval granted", "corr-1", null, "not-a-real-hash",
            clock.instant(), genuine.integrityHash(), null, null, null, null
        );
        auditRepository.append(tampered);

        GovernanceAuditService.ChainVerificationResult result = service.verifyChain();

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenRecordId()).isEqualTo("tampered-1");
    }

    /** SPEC-PG-031 (goal: "compliance reports"): aggregates counts/date range and folds in the same chain verification {@link GovernanceAuditService#verifyChain} exposes on its own. */
    @Test
    void complianceReportAggregatesCountsAndChainVerification() {
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null, null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_GRANTED, "actor-2", "tool-gateway",
            "src-req-1", null, null, "approval granted", "corr-1", null, null, null, null
        );
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-3", "tool-gateway",
            "src-req-2", null, null, "approval requested", "corr-2", null, null, null, null
        );

        GovernanceAuditService.ComplianceReport report = service.complianceReport();

        assertThat(report.totalRecords()).isEqualTo(3);
        assertThat(report.activeRecords()).isEqualTo(3);
        assertThat(report.archivedRecords()).isZero();
        assertThat(report.countsByAction()).containsEntry(GovernanceAuditRecord.Action.APPROVAL_REQUESTED, 2L);
        assertThat(report.countsByAction()).containsEntry(GovernanceAuditRecord.Action.APPROVAL_GRANTED, 1L);
        assertThat(report.oldestRecordedAt()).isEqualTo(clock.instant());
        assertThat(report.newestRecordedAt()).isEqualTo(clock.instant());
        assertThat(report.chainVerification().intact()).isTrue();
    }

    /** SPEC-PG-031: an empty audit trail reports zero counts, null date range, and a trivially intact chain. */
    @Test
    void complianceReportOnAnEmptyAuditTrail() {
        GovernanceAuditService.ComplianceReport report = service.complianceReport();

        assertThat(report.totalRecords()).isZero();
        assertThat(report.oldestRecordedAt()).isNull();
        assertThat(report.newestRecordedAt()).isNull();
        assertThat(report.chainVerification().intact()).isTrue();
    }

    /**
     * SPEC-PG-031 (11-security §Tamper-Resistant Audit: "may only be
     * archived by retention policy"). Records older than {@code
     * retentionDays} are injected directly into the repository (not through
     * {@link GovernanceAuditService#record}, which always stamps {@code
     * recordedAt} from this test's own {@code Clock.fixed}) so the cutoff
     * actually has something older and something newer to distinguish.
     */
    @Test
    void archiveRecordedBeforeArchivesOnlyRecordsOlderThanTheCutoff() {
        GovernanceAuditRecord old = new GovernanceAuditRecord(
            "old-1", GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-old", null, null, "approval requested", "corr-old", null, "hash-old",
            clock.instant().minus(Duration.ofDays(100)), null, null, null, null, null
        );
        auditRepository.append(old);
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-2", "tool-gateway",
            "src-req-new", null, null, "approval requested", "corr-new", null, null, null, null
        );

        int archivedCount = service.archiveRecordedBefore(30, "admin-1", "quarterly retention sweep", "corr-archive");

        assertThat(archivedCount).isEqualTo(1);
        assertThat(auditRepository.findAllOrderedByRecordedAt())
            .filteredOn(r -> r.auditRecordId().equals("old-1"))
            .extracting(GovernanceAuditRecord::archivedAt)
            .doesNotContainNull();
        assertThat(auditRepository.findAllOrderedByRecordedAt())
            .filteredOn(r -> r.sourceRequestId() != null && r.sourceRequestId().equals("src-req-new"))
            .extracting(GovernanceAuditRecord::archivedAt)
            .containsOnlyNulls();
    }

    /** SPEC-PG-031: a successful archive run writes its own {@code AUDIT_RECORDS_ARCHIVED} audit entry — the archival itself is a governance fact (INV-PG-008). */
    @Test
    void archiveRecordedBeforeWritesItsOwnAuditEntryWhenSomethingWasArchived() {
        GovernanceAuditRecord old = new GovernanceAuditRecord(
            "old-1", GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-old", null, null, "approval requested", "corr-old", null, "hash-old",
            clock.instant().minus(Duration.ofDays(100)), null, null, null, null, null
        );
        auditRepository.append(old);

        service.archiveRecordedBefore(30, "admin-1", "quarterly retention sweep", "corr-archive");

        assertThat(service.findByCorrelationId("corr-archive"))
            .extracting(GovernanceAuditRecord::action)
            .containsExactly(GovernanceAuditRecord.Action.AUDIT_RECORDS_ARCHIVED);
    }

    /** SPEC-PG-031: mirrors {@code ApprovalExpiryService#expireDue}'s own precedent — a no-op retention run stays silent rather than auditing "0 records archived." */
    @Test
    void archiveRecordedBeforeWritesNoAuditEntryWhenNothingWasEligible() {
        service.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-1", null, null, "approval requested", "corr-1", null, null, null, null
        );

        int archivedCount = service.archiveRecordedBefore(30, "admin-1", "quarterly retention sweep", "corr-archive");

        assertThat(archivedCount).isZero();
        assertThat(service.findByCorrelationId("corr-archive")).isEmpty();
    }
}
