package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.EventSchemaValidator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.OutboxEventJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataOutboxEventJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OutboxPersistenceAdapter implements OutboxEventRepository {

    private final SpringDataOutboxEventJpaRepository repository;
    private final EventSchemaValidator eventSchemaValidator;

    public OutboxPersistenceAdapter(SpringDataOutboxEventJpaRepository repository, EventSchemaValidator eventSchemaValidator) {
        this.repository = repository;
        this.eventSchemaValidator = eventSchemaValidator;
    }

    @Override
    public void append(OutboxEventEntry entry) {
        eventSchemaValidator.validate(entry.eventType(), entry.eventVersion(), entry.payload());

        repository.save(new OutboxEventJpaEntity(
            entry.outboxId(),
            entry.eventId(),
            entry.eventType(),
            entry.eventVersion(),
            entry.routingKey(),
            entry.aggregateType(),
            entry.aggregateId(),
            entry.aggregateVersion(),
            entry.ticketId().value(),
            entry.workflowId(),
            entry.traceId(),
            entry.correlationId(),
            entry.causationId(),
            entry.dataClassification(),
            entry.payload(),
            Map.of(),
            entry.createdAt(),
            entry.availableAt()
        ));
    }
}
