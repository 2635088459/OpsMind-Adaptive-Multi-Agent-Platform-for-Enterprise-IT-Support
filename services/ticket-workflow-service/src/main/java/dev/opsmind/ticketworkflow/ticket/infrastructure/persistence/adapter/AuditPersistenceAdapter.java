package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.AuditRecordJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataAuditRecordJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class AuditPersistenceAdapter implements AuditRecordPort {

    private final SpringDataAuditRecordJpaRepository repository;

    public AuditPersistenceAdapter(SpringDataAuditRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(AuditRecordEntry entry) {
        repository.save(new AuditRecordJpaEntity(
            entry.auditId(),
            entry.auditType(),
            entry.action(),
            entry.decision(),
            entry.actorType(),
            entry.actorId(),
            entry.clientId(),
            entry.resourceType(),
            entry.resourceId(),
            entry.displayId(),
            entry.ticketStatusBefore(),
            entry.ticketStatusAfter(),
            entry.traceId(),
            entry.commandId(),
            entry.outcome(),
            entry.dataClassification(),
            entry.occurredAt()
        ));
    }
}
