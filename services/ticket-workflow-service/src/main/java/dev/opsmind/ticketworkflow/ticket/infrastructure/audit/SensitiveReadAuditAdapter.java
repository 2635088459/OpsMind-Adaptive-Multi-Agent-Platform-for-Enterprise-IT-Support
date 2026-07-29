package dev.opsmind.ticketworkflow.ticket.infrastructure.audit;

import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Records a Get Ticket sensitive-read audit entry (SPEC-TW-002 §16) through
 * the shared {@link AuditRecordPort}, so both Create Ticket business audit
 * and Get Ticket sensitive-read audit land in the same append-only
 * {@code ticket.audit_records} table.
 */
@Component
public class SensitiveReadAuditAdapter implements SensitiveReadAuditPort {

    private static final String AUDIT_TYPE = "SENSITIVE_READ";
    private static final String ACTION = "TICKET_VIEWED";
    private static final String RESOURCE_TYPE = "TICKET";

    private final AuditRecordPort auditRecordPort;

    public SensitiveReadAuditAdapter(AuditRecordPort auditRecordPort) {
        this.auditRecordPort = auditRecordPort;
    }

    @Override
    public void recordSensitiveRead(SensitiveReadAuditEntry entry) {
        auditRecordPort.append(new AuditRecordEntry(
            UUID.randomUUID(),
            AUDIT_TYPE,
            ACTION,
            "ALLOWED",
            entry.actorType(),
            entry.actorId(),
            entry.clientId(),
            RESOURCE_TYPE,
            entry.resourceId(),
            null,
            null,
            null,
            entry.traceId(),
            null,
            entry.outcome(),
            "SENSITIVE",
            entry.occurredAt(),
            entry.viewType(),
            entry.fieldsPolicyVersion()
        ));
    }
}
