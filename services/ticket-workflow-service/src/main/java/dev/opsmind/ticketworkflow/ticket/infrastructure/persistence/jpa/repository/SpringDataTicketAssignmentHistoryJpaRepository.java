package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository;

import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.TicketAssignmentHistoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataTicketAssignmentHistoryJpaRepository extends JpaRepository<TicketAssignmentHistoryJpaEntity, UUID> {
}
