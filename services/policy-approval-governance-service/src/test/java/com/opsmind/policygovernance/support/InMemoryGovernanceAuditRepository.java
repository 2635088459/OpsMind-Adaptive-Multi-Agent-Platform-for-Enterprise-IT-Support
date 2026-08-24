package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Fast, in-process test double for {@link GovernanceAuditRepository} — see {@link InMemoryPolicyRepository}. */
public class InMemoryGovernanceAuditRepository implements GovernanceAuditRepository {

    private final Queue<GovernanceAuditRecord> records = new ConcurrentLinkedQueue<>();

    @Override
    public GovernanceAuditRecord append(GovernanceAuditRecord record) {
        records.add(record);
        return record;
    }

    @Override
    public List<GovernanceAuditRecord> findByCorrelationId(String correlationId) {
        return records.stream().filter(r -> r.correlationId().equals(correlationId)).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByTicketId(String ticketId) {
        return records.stream().filter(r -> ticketId.equals(r.ticketId())).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByApprovalRequestId(String approvalRequestId) {
        return records.stream().filter(r -> approvalRequestId.equals(r.approvalRequestId())).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByPolicyDecisionId(String policyDecisionId) {
        return records.stream().filter(r -> policyDecisionId.equals(r.policyDecisionId())).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findBySourceRequestId(String sourceRequestId) {
        return records.stream().filter(r -> sourceRequestId.equals(r.sourceRequestId())).toList();
    }

    @Override
    public List<GovernanceAuditRecord> findByPolicyId(String policyId) {
        return records.stream().filter(r -> policyId.equals(r.policyId())).toList();
    }

    /**
     * Uses insertion order rather than the real adapter's {@code
     * recordedAt}-based ordering: fast unit tests routinely run on a {@code
     * Clock.fixed}, where every record in a test shares the exact same
     * {@code recordedAt} and a timestamp-based "most recent" lookup cannot
     * tell them apart. A {@link ConcurrentLinkedQueue}'s iteration order is
     * already true append order for a single-threaded test, so this is the
     * more correct choice for this double, not just a workaround — see the
     * port's own javadoc for what the real, Postgres-backed adapter
     * guarantees instead.
     */
    @Override
    public Optional<String> findMostRecentIntegrityHash() {
        return records.stream().reduce((first, second) -> second).map(GovernanceAuditRecord::integrityHash);
    }

    /** SPEC-PG-031: same insertion-order reasoning as {@link #findMostRecentIntegrityHash} — a {@code recordedAt}-based sort cannot distinguish records sharing one {@code Clock.fixed} instant. */
    @Override
    public List<GovernanceAuditRecord> findAllOrderedByRecordedAt() {
        return List.copyOf(records);
    }

    /**
     * SPEC-PG-031: {@link GovernanceAuditRecord} is immutable, and {@link
     * Queue} offers no in-place replace, so archiving rebuilds the whole
     * queue with {@code archivedAt}-stamped copies substituted in — a test
     * double's own affordance, not something the real Postgres-backed
     * adapter needs (its own bulk {@code UPDATE} never has this problem).
     */
    @Override
    public int archiveRecordedBefore(Instant cutoff, Instant archivedAt) {
        List<GovernanceAuditRecord> snapshot = new ArrayList<>(records);
        int archivedCount = 0;
        records.clear();
        for (GovernanceAuditRecord record : snapshot) {
            if (record.archivedAt() == null && record.recordedAt().isBefore(cutoff)) {
                records.add(record.withArchivedAt(archivedAt));
                archivedCount++;
            } else {
                records.add(record);
            }
        }
        return archivedCount;
    }
}
