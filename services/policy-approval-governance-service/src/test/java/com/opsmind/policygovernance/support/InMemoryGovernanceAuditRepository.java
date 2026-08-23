package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

import java.util.List;
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
}
