package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditDecisionPort;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.SensitiveReadAuditDecisionJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataSensitiveReadAuditDecisionJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class SensitiveReadAuditDecisionPersistenceAdapter implements SensitiveReadAuditDecisionPort {

    private final SpringDataSensitiveReadAuditDecisionJpaRepository repository;

    public SensitiveReadAuditDecisionPersistenceAdapter(SpringDataSensitiveReadAuditDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(SensitiveReadAuditDecisionEntry entry) {
        repository.save(new SensitiveReadAuditDecisionJpaEntity(
            entry.id(),
            entry.ticketId(),
            entry.actorId(),
            entry.actorType(),
            entry.operation(),
            entry.decision(),
            entry.decisionCode(),
            entry.correlationId(),
            entry.traceId(),
            entry.occurredAt()
        ));
    }
}
