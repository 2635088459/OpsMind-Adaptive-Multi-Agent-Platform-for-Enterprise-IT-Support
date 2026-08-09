package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.StepUpAuthenticationDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.StepUpAuthenticationDecisionPort;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.StepUpAuthenticationDecisionJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataStepUpAuthenticationDecisionJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class StepUpAuthenticationDecisionPersistenceAdapter implements StepUpAuthenticationDecisionPort {

    private final SpringDataStepUpAuthenticationDecisionJpaRepository repository;

    public StepUpAuthenticationDecisionPersistenceAdapter(SpringDataStepUpAuthenticationDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(StepUpAuthenticationDecisionEntry entry) {
        repository.save(new StepUpAuthenticationDecisionJpaEntity(
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
