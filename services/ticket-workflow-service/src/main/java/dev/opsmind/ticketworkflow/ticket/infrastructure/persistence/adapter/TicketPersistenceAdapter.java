package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdCollisionException;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRepository;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * Inserts via a native {@code INSERT ... ON CONFLICT (display_id) DO
 * NOTHING} rather than a plain JPA save. PostgreSQL aborts the enclosing
 * transaction after a constraint-violation exception (and Hibernate's JPA
 * dialect does not support savepoints, so {@code Propagation.NESTED} cannot
 * be used to recover from one either) — {@code ON CONFLICT DO NOTHING}
 * avoids the problem entirely by reporting zero affected rows instead of
 * throwing, letting the caller regenerate the display id and retry within
 * the same Create Ticket transaction (SPEC-TW-001 §10).
 */
@Component
public class TicketPersistenceAdapter implements TicketRepository {

    private static final String INSERT_SQL = """
        INSERT INTO ticket.tickets
            (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
             priority, status, current_resolution_cycle_id, created_at, updated_at, version,
             created_by_type, created_by_id)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)
        ON CONFLICT (display_id) DO NOTHING
        """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void save(Ticket ticket) {
        int inserted = entityManager.createNativeQuery(INSERT_SQL)
            .setParameter(1, ticket.id().value())
            .setParameter(2, ticket.displayId().value())
            .setParameter(3, ticket.requesterId().value())
            .setParameter(4, ticket.title().value())
            .setParameter(5, ticket.description().value())
            .setParameter(6, ticket.source().name())
            .setParameter(7, ticket.applicationCode().name())
            .setParameter(8, ticket.priority().name())
            .setParameter(9, ticket.status().name())
            .setParameter(10, ticket.currentResolutionCycleId())
            .setParameter(11, Timestamp.from(ticket.createdAt()))
            .setParameter(12, Timestamp.from(ticket.updatedAt()))
            .setParameter(13, ticket.version())
            .setParameter(14, ticket.createdByType())
            .setParameter(15, ticket.createdById())
            .executeUpdate();

        if (inserted == 0) {
            throw new DisplayIdCollisionException(ticket.displayId().value(), null);
        }
    }
}
