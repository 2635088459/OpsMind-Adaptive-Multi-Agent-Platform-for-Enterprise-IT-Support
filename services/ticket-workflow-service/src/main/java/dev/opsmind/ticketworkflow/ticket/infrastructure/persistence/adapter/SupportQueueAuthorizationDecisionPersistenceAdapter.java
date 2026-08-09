package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.SupportQueueAuthorizationDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueAuthorizationDecisionPort;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.SupportQueueAuthorizationDecisionJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataSupportQueueAuthorizationDecisionJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class SupportQueueAuthorizationDecisionPersistenceAdapter implements SupportQueueAuthorizationDecisionPort {

    private final SpringDataSupportQueueAuthorizationDecisionJpaRepository repository;

    public SupportQueueAuthorizationDecisionPersistenceAdapter(SpringDataSupportQueueAuthorizationDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(SupportQueueAuthorizationDecisionEntry entry) {
        repository.save(new SupportQueueAuthorizationDecisionJpaEntity(
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
