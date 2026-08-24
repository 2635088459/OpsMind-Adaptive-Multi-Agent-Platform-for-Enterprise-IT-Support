package com.opsmind.identity.infrastructure.audit;

import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC-UA-001-scoped placeholder — see {@code infrastructure.persistence.adapter.InMemoryUserIdentityRepository}'s
 * own javadoc for the deferral this mirrors. Real durable, tamper-evident
 * audit persistence is SPEC-UA-002/SPEC-UA-003/SPEC-UA-029's job.
 */
@Component
public class InMemoryAuditPort implements AuditPort {

    private final Map<String, IdentityAuditRecord> byId = new ConcurrentHashMap<>();

    @Override
    public IdentityAuditRecord record(IdentityAuditRecord record) {
        byId.put(record.auditId(), record);
        return record;
    }

    @Override
    public List<IdentityAuditRecord> findByCorrelationId(String correlationId) {
        return byId.values().stream()
            .filter(r -> r.correlationId().value().equals(correlationId))
            .toList();
    }
}
