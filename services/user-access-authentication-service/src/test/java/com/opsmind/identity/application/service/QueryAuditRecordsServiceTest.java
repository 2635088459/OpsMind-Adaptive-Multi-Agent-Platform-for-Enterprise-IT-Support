package com.opsmind.identity.application.service;

import com.opsmind.identity.application.query.QueryAuditRecordsByCorrelationIdQuery;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.support.InMemoryAuditPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-UA-031 (11-security: audit access is itself audited). */
@Tag("unit")
class QueryAuditRecordsServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final FixedClockPort clock = new FixedClockPort(NOW);
    private final InMemoryAuditPort auditPort = new InMemoryAuditPort();
    private final QueryAuditRecordsService service = new QueryAuditRecordsService(auditPort, clock);

    @Test
    void returnsMatchingRecordsAndWritesASelfAuditRecord() {
        String targetCorrelationId = "corr-target";
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), IdentityAuditAction.USER_IDENTITY_LINKED, null,
            "user-1", null, AuditOutcome.SUCCESS, null, new CorrelationId(targetCorrelationId), NOW
        ));

        List<IdentityAuditRecord> found = service.findByCorrelationId(new QueryAuditRecordsByCorrelationIdQuery(
            "tenant-1", "admin-1", targetCorrelationId, "corr-request-1"
        ));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).action()).isEqualTo(IdentityAuditAction.USER_IDENTITY_LINKED);

        List<IdentityAuditRecord> selfAudit = auditPort.findByCorrelationId("corr-request-1");
        assertThat(selfAudit).hasSize(1);
        assertThat(selfAudit.get(0).action()).isEqualTo(IdentityAuditAction.AUDIT_RECORDS_QUERIED);
        assertThat(selfAudit.get(0).actorRef()).isEqualTo("admin-1");
        assertThat(selfAudit.get(0).resourceRef()).isEqualTo(targetCorrelationId);
    }

    @Test
    void selfAuditsEvenWhenNothingMatches() {
        List<IdentityAuditRecord> found = service.findByCorrelationId(new QueryAuditRecordsByCorrelationIdQuery(
            "tenant-1", "admin-1", "corr-missing", "corr-request-2"
        ));

        assertThat(found).isEmpty();
        assertThat(auditPort.findByCorrelationId("corr-request-2")).hasSize(1);
    }
}
