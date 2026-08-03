package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * SPEC-TW-011 persistence §4/§5: the ticket-row update, the old
 * resolution-cycle's {@code REOPENED} archival update, and the new active
 * cycle's insert — three statements against the same Spring-managed
 * transaction.
 */
@Component
public class TicketReopenPersistenceAdapter implements TicketReopenRepository {

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'IN_PROGRESS',
            current_resolution_cycle_id = :newResolutionCycleId,
            resolved_at = NULL,
            resolved_by = NULL,
            resolution_code = NULL,
            resolution_summary = NULL,
            auto_close_due_at = NULL,
            closed_at = NULL,
            closed_by = NULL,
            close_reason_code = NULL,
            last_reopened_at = :reopenedAt,
            last_reopened_by = :reopenedById,
            last_reopen_reason_code = :reopenReasonCode,
            reopen_count = :newReopenCount,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = :expectedStatus
        """;

    private static final String ARCHIVE_OLD_CYCLE_SQL = """
        UPDATE ticket.ticket_resolution_cycles
        SET cycle_status = 'REOPENED',
            reopened_at = :reopenedAt,
            reopened_by_type = :reopenedByType,
            reopened_by_id = :reopenedById,
            reopen_reason_code = :reopenReasonCode
        WHERE resolution_cycle_id = :previousResolutionCycleId
          AND ticket_id = :ticketId
          AND cycle_status = :expectedCycleStatus
        """;

    private static final String INSERT_NEW_CYCLE_SQL = """
        INSERT INTO ticket.ticket_resolution_cycles (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
        VALUES (:newResolutionCycleId, :ticketId, :newResolutionCycleNumber, 'ACTIVE', :reopenedAt)
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketReopenPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketReopenUpdateOutcome applyReopen(TicketReopenUpdate update) {
        int newResolutionCycleNumber = update.previousResolutionCycleNumber() + 1;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("newResolutionCycleId", update.newResolutionCycleId())
            .addValue("newResolutionCycleNumber", newResolutionCycleNumber)
            .addValue("reopenedAt", Timestamp.from(update.reopenedAt()))
            .addValue("reopenedById", update.reopenedById())
            .addValue("reopenedByType", update.reopenedByType())
            .addValue("reopenReasonCode", update.reopenReasonCode().name())
            .addValue("newReopenCount", update.newReopenCount())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("expectedStatus", update.expectedStatus().name())
            .addValue("previousResolutionCycleId", update.previousResolutionCycleId())
            .addValue("expectedCycleStatus", update.expectedStatus().name());

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        int oldCycleRowsAffected = jdbcTemplate.update(ARCHIVE_OLD_CYCLE_SQL, params);
        if (oldCycleRowsAffected != 1) {
            return new TicketReopenUpdateOutcome.ResolutionCycleConflict();
        }

        jdbcTemplate.update(INSERT_NEW_CYCLE_SQL, params);

        return new TicketReopenUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketReopenUpdateOutcome reclassify(TicketReopenUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketReopenUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketReopenUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketReopenUpdateOutcome.InvalidState(currentStatus);
    }
}
