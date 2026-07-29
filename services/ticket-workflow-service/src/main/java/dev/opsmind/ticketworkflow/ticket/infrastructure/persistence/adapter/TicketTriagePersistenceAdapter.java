package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * The version/state-guarded {@code UPDATE} is the authoritative
 * concurrency control (SPEC-TW-007 §8) — the application service's
 * earlier guard-based checks are a fast, friendly pre-check, but only this
 * single-statement, single-row-affecting UPDATE can safely resolve a race
 * against a second concurrent Triage attempt. Zero affected rows triggers
 * one reclassifying {@code SELECT} so the caller can tell a missing ticket
 * apart from a version conflict apart from an invalid state, without a
 * second UPDATE attempt (BI-095-style single-writer-wins semantics).
 */
@Component
public class TicketTriagePersistenceAdapter implements TicketTriageRepository {

    private static final String UPDATE_SQL = """
        UPDATE ticket.tickets
        SET category_id = ?1, subcategory_id = ?2, priority = ?3, support_queue_id = ?4,
            current_team_id = ?5, triaged_by = ?6, triaged_at = ?7, status = 'TRIAGED',
            version = version + 1, updated_at = ?8
        WHERE ticket_id = ?9 AND version = ?10 AND status = 'NEW'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = ?1
        """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public TicketTriageUpdateOutcome applyTriage(TicketTriageUpdate update) {
        int affected = entityManager.createNativeQuery(UPDATE_SQL)
            .setParameter(1, update.categoryId().value())
            .setParameter(2, update.subcategoryId() == null ? null : update.subcategoryId().value())
            .setParameter(3, update.priority().name())
            .setParameter(4, update.supportQueueId().value())
            .setParameter(5, update.teamId())
            .setParameter(6, update.triagedByActorId())
            .setParameter(7, Timestamp.from(update.triagedAt()))
            .setParameter(8, Timestamp.from(update.updatedAt()))
            .setParameter(9, update.ticketId().value())
            .setParameter(10, update.expectedVersion())
            .executeUpdate();

        if (affected == 1) {
            return new TicketTriageUpdateOutcome.Updated(update.expectedVersion() + 1);
        }

        return reclassify(update);
    }

    private TicketTriageUpdateOutcome reclassify(TicketTriageUpdate update) {
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery(RECLASSIFY_SQL)
                .setParameter(1, update.ticketId().value())
                .getSingleResult();
        } catch (NoResultException e) {
            return new TicketTriageUpdateOutcome.TicketMissing();
        }

        TicketStatus currentStatus = TicketStatus.valueOf((String) row[0]);
        long currentVersion = ((Number) row[1]).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketTriageUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketTriageUpdateOutcome.InvalidState(currentStatus);
    }
}
