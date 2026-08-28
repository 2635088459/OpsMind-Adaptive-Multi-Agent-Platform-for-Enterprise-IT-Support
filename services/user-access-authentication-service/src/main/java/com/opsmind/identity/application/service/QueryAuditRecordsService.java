package com.opsmind.identity.application.service;

import com.opsmind.identity.application.port.in.QueryAuditRecordsUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.query.QueryAuditRecordsByCorrelationIdQuery;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * SPEC-UA-031 (11-security: audit access is itself audited). Wraps the
 * already-built-but-previously-uncalled {@link AuditPort#findByCorrelationId}
 * with a self-audit write — every real read through this use case leaves
 * its own {@code AUDIT_RECORDS_QUERIED} trail, chained onto the same
 * per-tenant hash chain as the records it reads (SPEC-UA-031's own real
 * {@code IdentityAuditPersistenceAdapter} sealing).
 */
@Service
public class QueryAuditRecordsService implements QueryAuditRecordsUseCase {

    private final AuditPort auditPort;
    private final ClockPort clock;

    public QueryAuditRecordsService(AuditPort auditPort, ClockPort clock) {
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    public List<IdentityAuditRecord> findByCorrelationId(QueryAuditRecordsByCorrelationIdQuery query) {
        List<IdentityAuditRecord> found = auditPort.findByCorrelationId(query.correlationId());
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId(query.tenantId()), IdentityAuditAction.AUDIT_RECORDS_QUERIED,
            query.actorRef(), null, query.correlationId(), AuditOutcome.SUCCESS,
            "queried " + found.size() + " record(s)", new CorrelationId(query.requestCorrelationId()), clock.now()
        ));
        return found;
    }
}
