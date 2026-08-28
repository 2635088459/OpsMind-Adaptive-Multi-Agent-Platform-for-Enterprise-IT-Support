package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.TenantId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fast, dependency-free application-service unit-test double for {@link AuditPort}. Real persistence (incl. real SPEC-UA-031 hash-chaining) is {@code IdentityAuditPersistenceAdapter} (SPEC-UA-003/SPEC-UA-031) — this fake tracks insertion order well enough for {@link #findMostRecentRecordHash} but does not itself compute hashes. */
public class InMemoryAuditPort implements AuditPort {

    private final Map<String, IdentityAuditRecord> byId = new ConcurrentHashMap<>();
    private final List<IdentityAuditRecord> inOrder = new CopyOnWriteArrayList<>();

    @Override
    public IdentityAuditRecord record(IdentityAuditRecord record) {
        byId.put(record.auditId(), record);
        inOrder.add(record);
        return record;
    }

    @Override
    public List<IdentityAuditRecord> findByCorrelationId(String correlationId) {
        return byId.values().stream()
            .filter(r -> r.correlationId().value().equals(correlationId))
            .toList();
    }

    @Override
    public Optional<String> findMostRecentRecordHash(TenantId tenantId) {
        for (int i = inOrder.size() - 1; i >= 0; i--) {
            IdentityAuditRecord candidate = inOrder.get(i);
            if (candidate.tenantId().equals(tenantId)) {
                return Optional.ofNullable(candidate.recordHash());
            }
        }
        return Optional.empty();
    }

    /** Test-only introspection: every record ever written, in insertion order — for asserting on records whose correlation id is not known ahead of time (e.g. a reconciliation's own fresh one). */
    public List<IdentityAuditRecord> all() {
        return List.copyOf(inOrder);
    }
}
