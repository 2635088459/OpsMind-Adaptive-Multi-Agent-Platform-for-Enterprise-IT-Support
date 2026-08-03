package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.TicketAssignmentHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.TicketAssignmentHistoryJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataTicketAssignmentHistoryJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class TicketAssignmentHistoryPersistenceAdapter implements TicketAssignmentHistoryWriter {

    private final SpringDataTicketAssignmentHistoryJpaRepository repository;

    public TicketAssignmentHistoryPersistenceAdapter(SpringDataTicketAssignmentHistoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(TicketAssignmentHistoryEntry entry) {
        repository.save(new TicketAssignmentHistoryJpaEntity(
            entry.assignmentHistoryId(),
            entry.ticketId().value(),
            entry.action(),
            entry.previousAssigneeId(),
            entry.newAssigneeId(),
            entry.previousStatus().name(),
            entry.newStatus().name(),
            entry.actorType(),
            entry.actorId(),
            entry.reason(),
            entry.occurredAt(),
            entry.correlationId(),
            entry.causationId(),
            entry.resultingVersion()
        ));
    }
}
