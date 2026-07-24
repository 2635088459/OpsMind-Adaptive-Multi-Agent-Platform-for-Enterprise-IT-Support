package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.TicketStatusHistoryJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataTicketStatusHistoryJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class TicketHistoryPersistenceAdapter implements TicketHistoryWriter {

    private final SpringDataTicketStatusHistoryJpaRepository repository;

    public TicketHistoryPersistenceAdapter(SpringDataTicketStatusHistoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void appendInitial(TicketStatusHistoryEntry entry) {
        repository.save(new TicketStatusHistoryJpaEntity(
            entry.historyId(),
            entry.ticketId().value(),
            entry.fromStatus() == null ? null : entry.fromStatus().name(),
            entry.toStatus().name(),
            entry.transitionId(),
            entry.reasonCode(),
            entry.actorType(),
            entry.actorId(),
            entry.sourceCommandId(),
            entry.sourceEventId(),
            entry.workflowId(),
            entry.aggregateVersion(),
            entry.occurredAt()
        ));
    }
}
