package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.SecretDetectionDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SecretDetectionDecisionPort;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.SecretDetectionDecisionJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataSecretDetectionDecisionJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class SecretDetectionDecisionPersistenceAdapter implements SecretDetectionDecisionPort {

    private final SpringDataSecretDetectionDecisionJpaRepository repository;

    public SecretDetectionDecisionPersistenceAdapter(SpringDataSecretDetectionDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(SecretDetectionDecisionEntry entry) {
        repository.save(new SecretDetectionDecisionJpaEntity(
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
